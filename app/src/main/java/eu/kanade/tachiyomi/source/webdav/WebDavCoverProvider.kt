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
    const val COVER_AUTHORITY = "eu.kanade.tachiyomi.webdav.cover"

    lateinit var cloudSyncService: CloudSyncService
    lateinit var downloadPreferences: DownloadPreferences

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
}

class WebDavCoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val mangaUrl = Uri.decode(uri.lastPathSegment ?: return null)
        val (config, _) = WebDavCoverBridge.resolveConfig() ?: return null

        val coverDir = File(ctx.filesDir, "webdav-covers").apply { mkdirs() }
        val coverFile = runBlocking(Dispatchers.IO) {
            WebDavCoverBridge.cloudSyncService.downloadCover(config, mangaUrl, coverDir)
        } ?: return null
        return ParcelFileDescriptor.open(coverFile, ParcelFileDescriptor.MODE_READ_ONLY)
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
            val coverDir = File(context!!.filesDir, "webdav-covers")
            val base = name.replace('/', '_')
            val coverFile = COVER_EXTENSIONS.firstNotNullOfOrNull { ext ->
                File(coverDir, "$base.$ext").takeIf { it.exists() }
            } ?: return@runCatching -1L
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
