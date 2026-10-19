package eu.kanade.tachiyomi.source.webdav

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import eu.kanade.tachiyomi.data.download.CloudSyncConfig
import eu.kanade.tachiyomi.data.download.CloudSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File

private val COVER_EXTENSIONS = listOf("jpg", "webp", "png", "jpeg")

/** 磁盘缓存命中检查（与 WebDavCoverStore / ensureCover 同口径：非空 + 非空白页）。 */
private fun cachedCoverFile(coverDir: File, base: String): File? =
    COVER_EXTENSIONS.firstNotNullOfOrNull { ext ->
        File(coverDir, "$base.$ext")
            .takeIf { it.exists() && it.length() > 0 && !CoverQuality.isBlank(it) }
    }

/**
 * SY --> 路线 A 方案 1 的封面按需下载通道。
 *
 * 网格项封面由 Mihon 的 MangaCoverFetcher 按 scheme 分发：content:// 走 fileUriLoader，
 * 最终通过 UniFile.fromUri(...).openInputStream() 调到这里。我们在这里用与上传同源的
 * CloudSyncService（带 WebDAV 鉴权、复用 uploadClient 的信任配置）把 cover.jpg 下载到
 * filesDir 并返回文件描述符，从而绕开 Mihon 全局图片加载器对「URL 内嵌 Basic Auth /
 * 自签证书 / 明文流量」等限制。
 *
 * 列表不预拉封面，Coil 只在网格项可见时才各自触发一次下载（天然懒加载、不阻塞列表）。
 * SY <--
 */
object WebDavCoverBridge {
    const val COVER_AUTHORITY = "app.mihonW.webdav.cover"
    /** SY --> 文件夹视图封面 URI 的第一段：content://<authority>/folder/<encoded path> */
    const val FOLDER_SEGMENT = "folder"

    // SY 2026-10-05：网盘源（夸克 / 阿里云盘）复用同一个 Provider 类，按 authority 路由。
    // SY 2026-10-08：115 Cookie 模式（CLOUD115_AUTHORITY）已随 Cookie 源移除。
    const val QUARK_AUTHORITY = "app.mihonW.quark.cover"
    const val ALIYUN_AUTHORITY = "app.mihonW.aliyun.cover"

    /** SY 2026-10-08：115 开放平台（OAuth）源——id 体系与 Cookie 模式不同，authority/缓存独立。 */
    const val CLOUD115OPEN_AUTHORITY = "app.mihonW.cloud115open.cover"

    lateinit var cloudSyncService: CloudSyncService
    lateinit var downloadPreferences: DownloadPreferences
    /** 封面回退（无 cover 时取第一话首页）需要源侧的下载/解析能力，由 WebDavSource.init 注入。 */
    var webDavSource: WebDavSource? = null
        private set

    /** SY 2026-10-05：网盘源实例，由各自 init 注入（Provider 按 authority 取用）。 */
    var quarkSource: eu.kanade.tachiyomi.source.netdisk.quark.QuarkSource? = null
        private set
    var aliyunPanSource: eu.kanade.tachiyomi.source.netdisk.aliyun.AliyunPanSource? = null
        private set

    /** SY 2026-10-08：115 开放平台源实例，由其 init 注入。 */
    var cloud115OpenSource: eu.kanade.tachiyomi.source.netdisk.cloud115open.Cloud115OpenSource? = null
        private set

    fun bindWebDavSource(source: WebDavSource) {
        webDavSource = source
    }

    fun bindQuarkSource(source: eu.kanade.tachiyomi.source.netdisk.quark.QuarkSource) {
        quarkSource = source
    }

    fun bindAliyunPanSource(source: eu.kanade.tachiyomi.source.netdisk.aliyun.AliyunPanSource) {
        aliyunPanSource = source
    }

    /** SY 2026-10-08：绑定 115 开放平台源。 */
    fun bindCloud115OpenSource(source: eu.kanade.tachiyomi.source.netdisk.cloud115open.Cloud115OpenSource) {
        cloud115OpenSource = source
    }

    fun resolveConfig(): Pair<CloudSyncConfig, String>? {
        if (!downloadPreferences.cloudSyncEnabled.get()) return null
        val config = CloudSyncConfig(
            url = downloadPreferences.cloudSyncUrl.get(),
            username = downloadPreferences.cloudSyncUsername.get(),
            password = downloadPreferences.cloudSyncPassword.get(),
        )
        if (!config.isValid) return null
        return config to downloadPreferences.cloudSyncDestination.get()
    }

    /** SY 2026-10-05：按 authority 解析封面目标（源 + 后端 + 缓存目录名）。 */
    data class CoverTarget(
        val source: BaseWebDavSource,
        val backend: WebDavCoverStore.Backend,
        val coverDirName: String,
    )

    fun resolveTarget(authority: String?): CoverTarget? = when (authority) {
        COVER_AUTHORITY -> webDavSource?.let { CoverTarget(it, WebDavCoverStore.Backend.WEBDAV, "webdav-covers") }
        QUARK_AUTHORITY -> quarkSource?.let { CoverTarget(it, WebDavCoverStore.Backend.QUARK, "quark-covers") }
        ALIYUN_AUTHORITY -> aliyunPanSource?.let { CoverTarget(it, WebDavCoverStore.Backend.ALIYUNPAN, "aliyun-covers") }
        CLOUD115OPEN_AUTHORITY -> cloud115OpenSource?.let {
            CoverTarget(it, WebDavCoverStore.Backend.CLOUD115OPEN, "cloud115open-covers")
        }
        else -> null
    }
}

class WebDavCoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        // SY 2026-10-05：同一 Provider 类按 authority 服务 WebDAV + 115 + 夸克（manifest 各注册一条）。
        val target = WebDavCoverBridge.resolveTarget(uri.authority) ?: return null
        val source = target.source
        val segments = uri.pathSegments
        val isFolder = segments.size >= 2 && segments[0] == WebDavCoverBridge.FOLDER_SEGMENT
        val targetPath = Uri.decode(if (isFolder) segments[1] else uri.lastPathSegment ?: return null)
        // 未配置该网盘时快速返回，避免为每本漫画都走一次解析。
        if (!source.isConfigured()) return null

        val coverDir = File(ctx.filesDir, target.coverDirName).apply { mkdirs() }
        // SY --> 本地已扫出的封面直接返回，零流量：此前每次 openFile 都走完整 resolveCover
        // 网络链（远端 cover → listFiles → 抽首页 → 失败回落整本下载），扫描期间网格刷新时
        // 每个可见项都触发一次网络解析，带宽被扫描占满后全部卡成占位符。
        val base = if (isFolder) {
            "${targetPath.trimStart('/').replace('/', '_')}__folder"
        } else {
            targetPath.trimStart('/').replace('/', '_')
        }
        // SY --> 空白封面（坏版本时期写入的纯白图）视为未命中：往下走重新抽取，实现自愈。
        cachedCoverFile(coverDir, base)?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        // SY 2026-10-06 修复：占位封面（深链 PDF 等，落盘在 fallback 目录）已存在即视为命中，
        // 直接返回 fd——否则每次渲染/重绘/冷启动都把它当「没封面」重跑 resolveCover，
        // 而深链 PDF 的 resolveCover 必走一次 115 downlink（受全局 0.67 r/s 闸管控），
        // 于是「打开书架 / 滚动网格」源源不断砸 115，把用户真实操作（翻页取直链）全堵在闸后。
        // 占位图渲染出来与真封面无差异，且用户读过该卷后本地缓存生效会自动换成真封面。
        WebDavCoverStore.fallbackCoverFile(ctx, base)
            .takeIf { it.exists() && it.length() > 0L }
            ?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        // SY <--
        // 走源的 resolveCover：先试远端 cover.*，没有则回退到第一话的首页图片。
        // 文件夹封面则取该目录下第一个漫画的封面。
        // SY --> 2026-09-28 封面保存策略：抽取段按 key 串行（`resolveCover` 的 .tmp 中间文件
        // 路径按漫画确定，并发 openFile 会互相覆盖出损坏封面），并在锁内二次确认磁盘，
        // 让「打开章节顺手生成」与网格按需生成共用同一份产物、不重复抽取。
        val coverFile = runBlocking(Dispatchers.IO) {
            WebDavCoverStore.withCoverLock(WebDavCoverStore.lockKey(target.backend, base)) {
                cachedCoverFile(coverDir, base) ?: run {
                    if (isFolder) {
                        source.resolveFolderCover(targetPath, coverDir)
                    } else {
                        source.resolveCover(targetPath, coverDir)
                    }
                }
            }
        }
        // SY <--
        coverFile?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        // SY --> B1：深链 PDF 的占位封面。真实封面目录**故意保持为空**（占位另存于 fallback 目录），
        // 这样等用户读过该卷（整本进章节缓存）后，resolveCover 会改从本地抽真封面、自动替换。
        return WebDavCoverStore.fallbackCoverFile(ctx, base)
            .takeIf { it.exists() && it.length() > 0L }
            ?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        // SY <--
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val name = Uri.decode(uri.lastPathSegment ?: "cover")
        val size = runCatching {
            val coverDir = File(context!!.filesDir, WebDavCoverBridge.resolveTarget(uri.authority)?.coverDirName ?: "webdav-covers")
            val base = name.replace('/', '_')
            val coverFile = cachedCoverFile(coverDir, base) ?: return@runCatching -1L
            coverFile.length()
        }.getOrDefault(-1L)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        cursor.addRow(arrayOf(name, size))
        return cursor
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
