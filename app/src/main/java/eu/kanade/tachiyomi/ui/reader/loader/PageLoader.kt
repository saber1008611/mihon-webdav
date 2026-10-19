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
     * SY --> 2026-09-28：该加载器的「额外预绑定页数」建议。
     *
     * 条漫（[WebtoonViewer]）的 RecyclerView 预绑定窗口决定「离屏多少页就开始 bind → loadPage」。
     * 对**本地**加载器（本地文件拷贝，几 ms 就绪）很小的窗口就够；
     * 对**网络**加载器（WebDAV 逐张 HTTP 下载，单张 50~200ms+）必须把窗口拉得足够远，
     * 让下载跑在用户滑到之前 —— 否则就是「未缓存 WebDAV 滑动掉帧 + 停下半秒才出图」。
     *
     * 默认 0 = 不额外放大（本地/归档等加载器保持现状，避免无谓的离屏贴图）。
     * 网络加载器可覆盖为 >0 的值（单位：页）。
     * SY <--
     */
    open val preloadDistanceExtra: Int = 0

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
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
    }
}
