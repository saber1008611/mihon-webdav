package eu.kanade.tachiyomi.source.netdisk

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.download.CloudSyncDirectory
import eu.kanade.tachiyomi.data.download.CloudSyncEntry
import eu.kanade.tachiyomi.source.FolderSource
import eu.kanade.tachiyomi.source.HeavyChapterLoadSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFolder
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.webdav.BaseWebDavSource
import eu.kanade.tachiyomi.source.webdav.CoverQuality
import eu.kanade.tachiyomi.source.webdav.WebDavCoverStore
import eu.kanade.tachiyomi.source.webdav.WebDavRandomAccessSource
import eu.kanade.tachiyomi.source.webdav.enforceLruCacheBudget
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.pdf.PdfiumRenderer
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import mihon.core.archive.RandomAccessSource
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SY --> 网盘源（115 / 夸克 / 未来更多）的公共基座：继承 [BaseWebDavSource]，把
 * 「浏览 / 章节 / 封面 / 文件夹视图 / 排序」的**全部现成逻辑**原样复用（WebDAV 文件夹样式），
 * 后端差异收敛到 [NetdiskClient] 五个动词（list / downlink / resolveName / invalidateDownlink /
 * downloadHeaders）。
 *
 * 与 WebDAV 的关键差异 —— **逻辑路径模型**：
 *  - WebDAV 的章节 url 是云端文件路径（本身就是文件名，带扩展名）；
 *  - 网盘接口按 **id**（115 的 cid/pickcode、夸克的 fid）列目录与取直链，且文件可重名，
 *    故本基座把源内相对路径定义为 **id 段拼接**：`/<根id>/<子目录id>/<文件id>`。
 *    真实文件名由 [namesById] 缓存解析（[fileNameOf]），供 ChapterLoader 按扩展名分流。
 *
 * 随机读（Range 流式 + 块缓存 + 不支持 Range 的整本回退）复用 [WebDavRandomAccessSource]：
 * 直链短时效 → 每次请求经 [NetdiskClient.downlink] 现取（内部有 TTL 缓存）；
 * 直链校验头（Cookie/UA/Referer）→ [NetdiskClient.downloadHeaders] 每次现取（夸克 __puus 会续期）；
 * 缓存身份 → `stableKey = id:路径:真实文件名`（直链轮换不影响块缓存命中，且带扩展名可判格式）。
 * SY <--
 */
abstract class BaseNetdiskSource protected constructor(
    context: Context,
    /** 整本下载 / 散图下载用的客户端（与 API 取链同一个实现，保证头完全一致）。 */
    private val downloadClient: OkHttpClient,
) : BaseWebDavSource(context), Source, UnmeteredSource, FolderSource, HeavyChapterLoadSource {

    /** 具体网盘的客户端接缝。 */
    protected abstract val netdiskClient: NetdiskClient

    /**
     * SY 2026-10-06：路由键 —— 「云盘」源把挂载目录（`@<routeKey>:<id>` 前缀路径）委托回
     * 对应网盘后端时用。115 = "115"，夸克 = "quark"，阿里云盘 = "aliyun"。
     */
    abstract val routeKey: String

    // ===================== id → 名字缓存 =====================

    /** 文件 id → 真实文件名（listFiles / downloadImageToCache / 取链时填充）。 */
    protected val namesById = ConcurrentHashMap<String, String>()

    /** 目录 id → 目录名（文件夹视图显示名用）。 */
    protected val dirNames = ConcurrentHashMap<String, String>()

    /** 根目录 id（"0" = 网盘根）。 */
    protected open fun rootId(): String = "0"

    /** 根目录显示名（文件夹视图；网盘根没有「名字」，给个人盘一个固定标题）。 */
    protected abstract fun rootDisplayName(): String

    // ===================== 路径模型 =====================

    /** 逻辑路径的最后一段 = 目标 id。 */
    protected fun lastId(path: String): String =
        path.trimStart('/').split('/').lastOrNull().orEmpty().ifBlank { rootId() }

    /** 章节逻辑路径（id 段）→ 真实文件名（带扩展名），供 ChapterLoader 分流。 */
    override suspend fun fileNameOf(url: String): String {
        val id = lastId(url)
        namesById[id]?.let { return it }
        return runCatching { netdiskClient.resolveName(id) }.getOrNull()
            ?.also { namesById[id] = it }
            ?: url.trimStart('/').substringAfterLast('/')
    }

    // ===================== 后端接缝实现 =====================

    override val isRemoteStreamingBackend: Boolean = true

    override suspend fun listDirectories(path: String): List<CloudSyncDirectory> {
        if (!isConfigured()) return emptyList()
        return netdiskClient.list(lastId(path))
            .filter { it.isDir }
            .map {
                dirNames[it.id] = it.name
                CloudSyncDirectory(name = it.name, path = it.id, lastModified = it.mtime)
            }
    }

    override suspend fun listFiles(path: String): List<CloudSyncEntry> {
        if (!isConfigured()) return emptyList()
        return netdiskClient.list(lastId(path))
            .filterNot { it.isDir }
            .map {
                namesById[it.id] = it.name
                CloudSyncEntry(name = it.name, path = it.id, isCollection = false, lastModified = it.mtime)
            }
    }

    override fun openRangeSource(remotePath: String): RandomAccessSource? {
        if (!isConfigured()) return null
        val id = lastId(remotePath)
        return WebDavRandomAccessSource(
            url = remotePath,
            // 直链短时效：每次请求现取（内部 TTL 缓存 + 并发合并，同一文件不会反复打取链接口）
            // SY --> 不再把异常吞成 null（否则上层只能弹"登录态可能已失效"误导用户）：
            // 透传真实异常（405 风控 / 403 失效 / 缺 data），让用户看到真正的失败原因。
            dynamicUrlProvider = {
                try {
                    runBlocking { netdiskClient.downlink(id) }.url
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "$name: 取直链失败 $id：${e.message}" }
                    throw e
                }
            },
            // 夸克按 Cookie/UA/Referer 校验直链签名；__puus 续期后每次现读
            extraHeadersProvider = { netdiskClient.downloadHeaders() },
            // 稳定缓存身份：直链轮换不影响块缓存命中；带真实文件名让适配器能判扩展名（rar/7z 强制整本）
            stableKey = "$id:$remotePath:${namesById[id].orEmpty()}",
            fallbackCacheDir = File(context.cacheDir, FALLBACK_SUBDIR),
            cacheMaxBytes = FALLBACK_CACHE_BYTES,
        )
    }

    override suspend fun downloadImageToCache(folderPath: String, fileName: String): File {
        if (!isConfigured()) throw IllegalStateException("$name 未配置，无法读取章节")
        val dirId = lastId(folderPath)
        // 散图页只给「文件名」没有 id：查一次目录（API 侧有 20s 缓存 + DirListingCache 兜底）
        val entry = netdiskClient.list(dirId).firstOrNull { !it.isDir && it.name == fileName }
            ?: throw IllegalStateException("$name: 目录里找不到图片 $fileName")
        namesById[entry.id] = entry.name
        val info = netdiskClient.downlink(entry.id)
        val cacheDir = File(context.cacheDir, IMG_CACHE_SUBDIR).apply { mkdirs() }
        val cacheFile = File(cacheDir, Hash.sha256("$folderPath/$fileName"))
        if (!cacheFile.exists()) {
            NetdiskHttp.downloadToFile(downloadClient, info.url, netdiskClient.downloadHeaders(), cacheFile)
            enforceLruCacheBudget(cacheDir, IMG_CACHE_BYTES, setOf(cacheFile.name))
                .forEach { (name, len) ->
                    logcat(LogPriority.INFO) { "$name: 散图缓存超限，淘汰 $name ($len B)" }
                }
        } else {
            cacheFile.setLastModified(System.currentTimeMillis())
        }
        return cacheFile
    }

    override suspend fun downloadChapterToCache(remotePath: String): File {
        if (!isConfigured()) throw IllegalStateException("$name 未配置，无法读取章节")
        val id = lastId(remotePath)
        val info = netdiskClient.downlink(id)
        val cacheDir = File(context.cacheDir, archiveCacheSubdir).apply { mkdirs() }
        val cacheFile = File(cacheDir, remotePath.trimStart('/').replace('/', '_'))
        if (!cacheFile.exists()) {
            NetdiskHttp.downloadToFile(downloadClient, info.url, netdiskClient.downloadHeaders(), cacheFile)
            enforceLruCacheBudget(cacheDir, ARCHIVE_CACHE_BYTES, setOf(cacheFile.name))
                .forEach { (name, len) ->
                    logcat(LogPriority.INFO) { "$name: 章节缓存超限，淘汰 $name ($len B)" }
                }
        } else {
            cacheFile.setLastModified(System.currentTimeMillis())
        }
        return cacheFile
    }

    override suspend fun downloadCoverEntry(
        actual: CloudSyncEntry,
        mangaUrl: String,
        coverDir: File,
    ): File? {
        val info = runCatching { netdiskClient.downlink(actual.path) }.getOrNull() ?: return null
        namesById[actual.path] = actual.name
        val ext = actual.name.substringAfterLast('.', "jpg").ifBlank { "jpg" }
        val dst = File(coverDir, "${mangaUrl.replace('/', '_')}.$ext")
        runCatching {
            NetdiskHttp.downloadToFile(downloadClient, info.url, netdiskClient.downloadHeaders(), dst)
        }.onFailure { e ->
            dst.delete()
            logcat(LogPriority.WARN, e) { "$name: 封面文件下载失败 ${actual.name}" }
        }
        return dst.takeIf { it.exists() && it.length() > 0L && !CoverQuality.isBlank(it) }
    }

    override fun openPdfRenderer(ras: RandomAccessSource): PdfiumRenderer =
        PdfiumRenderer.openProxy(context, ras)

    // ===================== 深链 PDF 占位封面（与 WebDAV 同策略：网盘更怕风控） =====================

    override suspend fun tryHandlePdfCoverLocally(
        targetPath: String,
        baseName: String,
        title: String,
        coverFile: File,
    ): Boolean = handlePdfDeepChainCover(targetPath, baseName, title, coverFile)

    override fun hasPlaceholderCover(base: String): Boolean =
        WebDavCoverStore.fallbackCoverFile(context, base).exists()

    override suspend fun isDeepChainPdfOrPlaceholder(remotePath: String): Boolean =
        runCatching { isRemotePdfDeepChain(remotePath) }.getOrDefault(false)

    // ===================== 文件夹视图（id 路径需要单独解析显示名） =====================

    override suspend fun getFolders(): List<SourceFolder> = withIOContext {
        if (!isConfigured()) return@withIOContext emptyList()
        getFoldersPaths().map { id ->
            SourceFolder(
                url = id,
                name = folderDisplayName(id),
                coverUrl = "content://$coverAuthority/$folderCoverSegment/${Uri.encode(id)}",
            )
        }
    }

    /** 文件夹显示名：目录名缓存 → 根目录固定标题 → 兜底显示 id。 */
    protected open fun folderDisplayName(id: String): String =
        dirNames[id] ?: if (id == rootId()) rootDisplayName() else id

    override suspend fun addFolder(identifier: String): Boolean {
        val id = identifier.trim().trimStart('/')
        if (id.isBlank()) return false
        if (id in getFoldersPaths()) return false
        // 校验目录存在（列一次即可；失败 = 无效 id）
        val exists = runCatching { netdiskClient.list(id) }.isSuccess
        if (!exists) return false
        onFolderAdded(id)
        return true
    }

    /** 校验通过后把目录 id 写进偏好（子类实现）。 */
    protected abstract fun onFolderAdded(id: String)

    /** 重新登录 / 更换 Cookie 后清空「id → 名字」缓存（下次浏览重新填充）。 */
    fun clearNameCaches() {
        namesById.clear()
        dirNames.clear()
    }

    /** SY 2026-10-05：可视化选择器逐层进入时登记目录名（文件夹视图显示名用）。 */
    fun rememberDirName(id: String, name: String) {
        dirNames[id] = name
    }

    // ===================== 供「云盘」门面委托的 public 包装 =====================
    // listDirectories/listFiles/openRangeSource/downloadChapterToCache/downloadCoverEntry/
    // tryHandlePdfCoverLocally 等在 BaseWebDavSource 里是 protected，兄弟类不能直接调，
    // 这里统一开 public 委托（2026-10-06：网盘挂载目录并入「云盘」文件夹视图，共用其规则）。

    suspend fun delegateListDirectories(path: String): List<CloudSyncDirectory> = listDirectories(path)

    suspend fun delegateListFiles(path: String): List<CloudSyncEntry> = listFiles(path)

    fun delegateOpenRangeSource(path: String): RandomAccessSource? = openRangeSource(path)

    suspend fun delegateDownloadChapterToCache(remotePath: String): File = downloadChapterToCache(remotePath)

    suspend fun delegateDownloadCoverEntry(
        actual: CloudSyncEntry,
        mangaUrl: String,
        coverDir: File,
    ): File? = downloadCoverEntry(actual, mangaUrl, coverDir)

    suspend fun delegateTryHandlePdfCoverLocally(
        targetPath: String,
        baseName: String,
        title: String,
        coverFile: File,
    ): Boolean = tryHandlePdfCoverLocally(targetPath, baseName, title, coverFile)

    suspend fun delegateIsDeepChainPdfOrPlaceholder(remotePath: String): Boolean =
        isDeepChainPdfOrPlaceholder(remotePath)

    /**
     * SY 2026-10-08 修复：网盘源的章节/漫画 url 是**裸 id**（单段、无 `/`），基类实现的
     * `lastIndexOf('/') <= 0` 守卫把它们全部判 null → 深链占位图的「读后零请求升级」
     * （ensureCover gate + handlePdfDeepChainCover step①）对独立网盘源永远失效
     * （占位图永远不换成真封面）。覆写去掉守卫：命名与 [downloadChapterToCache] 逐字一致。
     */
    override fun cachedArchiveFile(remotePath: String): File? =
        File(File(context.cacheDir, archiveCacheSubdir), remotePath.trimStart('/').replace('/', '_'))
            .takeIf { it.exists() && it.length() > 0L }

    /** SY 2026-10-08：云盘门面复用同一实现（原为重复代码，现统一走覆写，行为一致）。 */
    fun delegateCachedArchiveFile(remotePath: String): File? = cachedArchiveFile(remotePath)

    /**
     * SY 2026-10-05：「添加网盘文件夹」可视化浏览用 —— 列出 [path] 下的子目录。
     * [path] 传空串表示根目录（内部转 rootId），返回 (目录id, 目录名) 列表；
     * 失败返回空列表（由调用方提示）。
     */
    suspend fun browseFolders(path: String): List<Pair<String, String>> = withIOContext {
        if (!isConfigured()) return@withIOContext emptyList()
        runCatching { listDirectories(path.ifBlank { rootId() }) }
            .onFailure { e -> logcat(LogPriority.WARN, e) { "$name: 浏览目录失败 $path" } }
            .getOrDefault(emptyList())
            .map { it.path to it.name }
    }

    companion object {
        /**
         * 网盘下载共用客户端（进程级单例）：与 API 取链同款「直连、无全局 UA 拦截器」的构建方式，
         * 保证取链与下载的头完全一致（115/夸克都按调用方头校验直链签名）。
         */
        val sharedDownloadClient: OkHttpClient by lazy { NetdiskHttp.newClient() }

        /** 整本下载缓存预算：1GB LRU（与 WebDAV 同口径）。 */
        const val ARCHIVE_CACHE_BYTES = 1L * 1024 * 1024 * 1024

        /** 不支持 Range / rar·7z 强制整本回退的磁盘缓存预算：4GB LRU。 */
        const val FALLBACK_CACHE_BYTES = 4L * 1024 * 1024 * 1024

        /** 散图缓存预算：256MB LRU（与 WebDAV 同口径）。 */
        const val IMG_CACHE_BYTES = 256L * 1024 * 1024

        /** 流式整本回退缓存目录名（两网盘共用）。 */
        const val FALLBACK_SUBDIR = "netdisk-stream"
    }
}
