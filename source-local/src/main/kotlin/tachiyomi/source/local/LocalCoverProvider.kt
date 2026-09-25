package tachiyomi.source.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import tachiyomi.source.local.image.LocalCoverManager
import java.io.File

/**
 * SY --> 本地图源封面懒加载通道，与 WebDavCoverProvider 同构。
 *
 * 列表阶段不再实时查封面（LocalSource 原本会在列表加载时对每个漫画目录调用
 * LocalCoverManager.find 列目录找 cover.*，44G 库下很慢、阻塞进源）；改为填
 * content:// 直链，Coil 在网格项滑到可见时才触发一次 find，天然懒加载、不阻塞列表。
 * 若某漫画没有封面文件，find 返回 null → 这里返回 null → 网格显示占位图。
 */
object LocalCoverBridge {
    const val COVER_AUTHORITY = "eu.kanade.tachiyomi.local.cover"

    lateinit var coverManager: LocalCoverManager
}

class LocalCoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val mangaName = Uri.decode(uri.lastPathSegment ?: return null)
        val cover = runCatching { LocalCoverBridge.coverManager.find(mangaName) }.getOrNull() ?: return null
        val coverUri = cover.uri
        return when (coverUri.scheme) {
            "file" -> runCatching {
                ParcelFileDescriptor.open(File(coverUri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrNull()
            else -> runCatching { ctx.contentResolver.openFileDescriptor(coverUri, "r") }.getOrNull()
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val name = Uri.decode(uri.lastPathSegment ?: "cover")
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        cursor.addRow(arrayOf(name, -1L))
        return cursor
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
