package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import mihon.core.archive.ArchiveReader
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
internal class ArchivePageLoader(private val reader: ArchiveReader) : PageLoader() {
    private val context: Context by injectLazy()

    // SY --> 超分移植：与 DownloadPageLoader 一致，读一次偏好判断整话超分开关。
    private val readerPreferencesOrNull: ReaderPreferences?
        get() = (context.applicationContext as? App)?.graph?.readerPreferences
    // SY <--

    // SY --> 整话超分：getPages() 在 loadChapter 里先于 enqueueAllPagesForEnhancement 执行，
    // 把已加载、且已被 ChapterLoader 附上 `.chapter` 的页缓存下来，供整话批量增强遍历。
    private var loadedPages: List<ReaderPage>? = null
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
    }.also { loadedPages = it }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    // SY --> 整话超分：归档章节（本地 / WebDAV）的所有页都是 Ready 状态（无需联网拉取），
    // 直接逐页触发 enhance() 即可；enhance() 内部的 isCached/pendingRequests 闸门会去重，
    // 已算过 / 正在算的页不会白算。
    //
    // 此前 ArchivePageLoader 没有重写 enqueueAllPagesForEnhancement —— 基类 PageLoader 是空实现
    // `{}`，于是「整话超分」开关对走归档阅读器的 WebDAV / 本地 CBZ 漫画完全不生效，
    // 只剩视图绑定驱动的那几页（当前 + 预载）被单页增强，表现就是「整话超分不生效，只有预超分几页」。
    // 这里的实现与 DownloadPageLoader.enqueueAllPagesForEnhancement 保持逐字一致。
    override fun enqueueAllPagesForEnhancement() {
        if (readerPreferencesOrNull?.realCuganWholeChapter()?.get() != true) return
        val pages = loadedPages ?: return
        pages.forEach { page ->
            runCatching { ImageEnhancer.enhance(context, page, highPriority = false) }
        }
        logcat(LogPriority.DEBUG) {
            "ArchivePageLoader: whole-chapter enhancement enqueued ${pages.size} pages"
        }
    }
    // SY <--

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}
