package eu.kanade.tachiyomi.ui.reader.loader

import androidx.annotation.CallSuper
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Whether this loader has been already recycled.
     */
    var isRecycled = false
        private set

    abstract var isLocal: Boolean

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    open fun retryPage(page: ReaderPage) {}

    /**
     * SY --> 整话超分（从 mihon_img_upscale 移植）：把本章**尚未就绪**的页全部送入
     * 加载 + 后台超分队列，使整章每一页都被超分并落盘缓存，而不只是用户滑到的几页。
     *
     * 默认空实现：只有需要「整章批量」触发的子类才重写 —— 网络源（HttpPageLoader）、
     * 已下载源（DownloadPageLoader）、归档源（ArchivePageLoader，服务本地 CBZ 与 WebDAV）。
     * 调用方（ReaderViewModel）负责判断开关是否打开、是否在阅读器内生效。
     * SY <--
     */
    open fun enqueueAllPagesForEnhancement() {}

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
    }
}
