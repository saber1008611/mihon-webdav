package eu.kanade.tachiyomi.source.webdav

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

private val COVER_EXTENSIONS = listOf("jpg", "webp", "png", "jpeg")

/** 磁盘缓存命中检查（与 WebDavCoverStore / ensureCover 同口径：非空 + 非空白页）。 */
private fun cachedCoverFile(coverDir: File, base: String): File? =
    COVER_EXTENSIONS.firstNotNullOfOrNull { ext ->
        File(coverDir, "$base.$ext")
            .takeIf { it.exists() && it.length() > 0 && !CoverQuality.isBlank(it) }
    }

/**
 * SY --> 本地文件夹版 WebDAV 图源的封面按需下载通道（LocalWebDavSource 的 content:// 后端）。
 *
 * 与 WebDavCoverBridge 同机制：网格项封面由 MangaCoverFetcher 按 scheme 分发到 content://，
 * 这里调用本地 LocalWebDavSource 的 resolveCover / resolveFolderCover（本地文件抽取首页、
 * 复用与 WebDAV 源同一套策略）把封面写进 filesDir 并返回文件描述符。
 * 列表不预拉封面，Coil 只在可见项触发一次（天然懒加载、不阻塞列表）。
 * SY <--
 */
object LocalWebDavCoverBridge {
    const val COVER_AUTHORITY = "app.mihonW.localwebdav.cover"
    /** 文件夹视图封面 URI 的第一段：content://<authority>/folder/<encoded path> */
    const val FOLDER_SEGMENT = "folder"

    /** 封面回退（无 cover 时取第一话首页）需要源侧的本地文件解析能力，由 LocalWebDavSource.init 注入。 */
    var localWebDavSource: LocalWebDavSource? = null
        private set

    fun bindLocalWebDavSource(source: LocalWebDavSource) {
        localWebDavSource = source
    }
}

class LocalWebDavCoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val segments = uri.pathSegments
        val isFolder = segments.size >= 2 && segments[0] == LocalWebDavCoverBridge.FOLDER_SEGMENT
        val target = Uri.decode(if (isFolder) segments[1] else uri.lastPathSegment ?: return null)
        // 未选择本地文件夹（源未注入）时快速返回，避免每个网格项都空转。
        val source = LocalWebDavCoverBridge.localWebDavSource ?: return null

        val coverDir = File(ctx.filesDir, "localwebdav-covers").apply { mkdirs() }
        // SY --> 本地已扫出的封面直接返回，零磁盘重读：此前每次 openFile 都走完整 resolveCover
        val base = if (isFolder) {
            "${target.trimStart('/').replace('/', '_')}__folder"
        } else {
            target.trimStart('/').replace('/', '_')
        }
        // SY --> 空白封面（坏版本时期写入的纯白图）视为未命中：往下走重新抽取，实现自愈。
        cachedCoverFile(coverDir, base)?.let { return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        // SY <--
        // 走 LocalWebDavSource.resolveCover：本地封面（cover.* 优先，没有则回退第一话首页）。
        // 文件夹封面则取该目录下第一个漫画的封面。
        // SY --> 2026-09-28 封面保存策略：抽取段按 key 串行（.tmp 中间文件路径按漫画确定，
        // 并发 openFile 会互相覆盖出损坏封面），并在锁内二次确认磁盘，
        // 让「打开章节顺手生成」与网格按需生成共用同一份产物、不重复抽取。
        val coverFile = runBlocking(Dispatchers.IO) {
            WebDavCoverStore.withCoverLock(WebDavCoverStore.lockKey(WebDavCoverStore.Backend.LOCAL, base)) {
                cachedCoverFile(coverDir, base) ?: run {
                    if (isFolder) source.resolveFolderCover(target, coverDir) else source.resolveCover(target, coverDir)
                }
            }
        }
        // SY <--
        return coverFile?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
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
            val coverDir = File(context!!.filesDir, "localwebdav-covers")
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
