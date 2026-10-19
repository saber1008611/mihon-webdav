package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import mihon.core.archive.ArchiveHandle
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from an archive file.
 *
 * SY --> 同时服务本地归档源（LocalSource）与 WebDAV 归档源（ChapterLoader 的
 * `source is WebDavSource` 分支）：两者都把 CBZ 当本地文件读页，页都是 Ready 状态。
 * SY <--
 */
internal class ArchivePageLoader(private val reader: ArchiveHandle) : PageLoader() {
    private val context: Context by injectLazy()

    // SY --> 超分移植：与 DownloadPageLoader 一致，读一次偏好判断整话超分开关。
    private val readerPreferencesOrNull: ReaderPreferences?
        get() = (context.applicationContext as? App)?.graph?.readerPreferences
    // SY <--

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> = reader.useEntries { entries ->
        entries
            .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
            .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            .mapIndexed { i, entry ->
                ReaderPage(i).apply {
                    stream = { reader.getInputStream(entry.name)!! }
                    status = Page.State.Ready
                }
            }
            .toList()
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}
