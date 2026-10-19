package tachiyomi.source.local.image

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.storage.DiskUtil
import mihon.core.archive.archiveReader
import tachiyomi.core.common.storage.nameWithoutExtension
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.LocalSourceFileSystem
import java.io.File
import java.io.InputStream

private const val DEFAULT_COVER_NAME = "cover.jpg"

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

@Inject
@SingleIn(AppScope::class)
class LocalCoverManager(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
) {

    fun find(mangaUrl: String): UniFile? {
        // 1) 现有：manga 目录里自带的 cover.* 文件
        fileSystem.getFilesInMangaDirectory(mangaUrl)
            .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
            .firstOrNull { ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.let { return it }
        // 2) 兜底：从归档/目录抽首图并缓存到 App 私有目录（只读挂载盘上也能命中，对齐 WebDAV 懒加载）
        return renderAndCache(mangaUrl)
    }

    private fun renderAndCache(mangaUrl: String): UniFile? {
        val mangaDir = fileSystem.getMangaDirectory(mangaUrl) ?: return null
        val cacheDir = File(context.filesDir, "local_covers").apply { mkdirs() }
        val cacheFile = File(cacheDir, mangaUrl.hashCode().toString() + ".jpg")
        if (cacheFile.exists()) return UniFile.fromFile(cacheFile)

        // 确定要抽首图的归档：单归档 manga 取自身；目录型 manga 取首个子归档
        val target = if (Archive.isSupported(mangaDir)) {
            mangaDir
        } else if (mangaDir.isDirectory) {
            mangaDir.listFiles()?.firstOrNull { Archive.isSupported(it) } ?: return null
        } else {
            return null
        }

        // useEntries 内部只取 entry 名（完全消费 archive），关闭后再重新开流写缓存，避免惰性 Sequence 闪退
        val coverName = target.archiveReader(context).use { reader ->
            reader.useEntries { entries ->
                entries
                    .sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
                    .firstOrNull { it.isFile && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
                    ?.name
            } ?: return null
        }
        target.archiveReader(context).use { reader ->
            reader.getInputStream(coverName)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return UniFile.fromFile(cacheFile)
    }

    fun update(
        manga: SManga,
        inputStream: InputStream,
    ): UniFile? {
        val directory = fileSystem.getMangaDirectory(manga.url)
        if (directory == null) {
            inputStream.close()
            return null
        }

        val targetFile = find(manga.url) ?: directory.createFile(DEFAULT_COVER_NAME)!!

        inputStream.use { input ->
            targetFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (directory.isDirectory) {
            DiskUtil.createNoMediaFile(directory, context)
        }

        manga.thumbnail_url = targetFile.uri.toString()
        return targetFile
    }
}
