package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.AndroidUiDispatcher
import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max

class WebGpuViewerContinuous(activity: ReaderActivity, val useGap: Boolean = false) :
    WebGpuViewer(activity, isReversed = false, isVertical = true, pager = ImageViewContinuous(activity)) {

    override val isContinuous: Boolean = true

    // How many pages the viewport shows depends on the zoom, and a page on screen has to be
    // decoded rather than merely reserved - so the window follows what the last frame reached.
    override val preloadAhead get() = max(3, state.pagesBelow)
    override val preloadBehind get() = max(2, state.pagesAbove)

    override val cacheSize get() = 2 + 2 * max(4, state.pagesAbove + state.pagesBelow)

    private val state get() = (pager as ImageViewContinuous).state

    init {
        state.backgroundColor = readerBackgroundColor()

        // MIHON 2026-09-28：条漫关闭库的双击缩放判定，让点击「瞬时触发」。
        // 库默认 doubleTapZoomEnabled=true，tap 要等双击超时（~300ms）才派发，点击明显不跟手；
        // 条漫以竖滚动为主、双击缩放使用极少，这里用双击缩放换跟手度。
        state.doubleTapZoomEnabled = false

        state.onViewport = { readThrough ->
            val pageChanged = readThrough !== lastReadThrough
            if (pageChanged) {
                lastReadThrough = readThrough
                readThrough?.let { select(it) }
            }

            val page0 = state.getPage(0)
            val edge = (page0 is TransitionPage && page0.prevChapter == null) ||
                (readThrough is TransitionPage && readThrough.nextChapter == null)

            val first = wasAtEdge == null
            val edgeChanged = edge != wasAtEdge
            wasAtEdge = edge
            if (edge) {
                if (edgeChanged) scope.launch { activity.showMenu() }
            } else if (!first && (edgeChanged || pageChanged) && !activity.isScrollingThroughPages) {
                scope.launch { activity.hideMenu() }
            }
        }
    }

    /** Null until the first frame, so opening mid-document neither shows nor hides. */
    @Volatile
    private var wasAtEdge: Boolean? = null

    @Volatile
    private var lastReadThrough: ImagePage? = null

    /**
     * Mark [imagePage] read - its bottom has cleared the viewport, which is later than
     * onPageChange's "top arrived" and the reason this mode selects from here: scrolling up to
     * reveal the page above would otherwise walk progress back past pages still on screen.
     */
    private fun select(imagePage: ImagePage) {
        val page = readerPageFor(imagePage) ?: return
        scope.launch { activity.onPageSelected(page) }
    }

    private fun readerPageFor(imagePage: ImagePage): ReaderPage? {
        // Off the page itself: a transition page is never currentPage (the scroll walk stops
        // before it), so it is the first thing a preload evicts.
        if (imagePage is TransitionPage) return imagePage.prevChapter?.pages?.lastOrNull()

        // Only an image page needs the lookup - nothing on it names its ReaderPage. Evicted
        // means scrolled well clear of, so there is no progress left to report.
        return (viewerPageFor(imagePage) as? ViewerReaderPage)?.page
    }

    override fun destroy() {
        scrollJob?.cancel()
        scrollJob = null
        state.onViewport = null
        lastReadThrough = null
        wasAtEdge = null
        super.destroy()
    }

    private var scrollJob: Job? = null

    /**
     * MIHON 2026-09-28：点击滚动改为「缓出（ease-out）+ 可打断」。
     * 库自带的 animateScroll 曲线/时长写死、且不可中断（连点会排队，感觉不跟手）；
     * 这里用 Compose 动画逐帧 scrollTo：点击立即响应（打断上一次动画）、缓出收尾更顺滑。
     *
     * ⚠️ 2026-09-28 崩溃修复：Compose 的 `animate()` 要求 CoroutineContext 里有
     * **MonotonicFrameClock**，而 Viewer 的 `scope` 是 `MainScope()`（只带 `Dispatchers.Main`）。
     * 直接在它上面跑动画会抛 `IllegalStateException: A MonotonicFrameClock is not available...`，
     * 且 `launch` 的未捕获异常会直接杀掉进程（用户实测：条漫模式点一下滚动就崩）。
     * 修法：用 `AndroidUiDispatcher.Main` 启动——它同时是 Dispatcher 与 MonotonicFrameClock，
     * 正是 Compose 动画需要的上下文；另加兜底，任何失败都退回库自带 `animateScroll`。
     */
    private fun scrollByHalfPage(direction: Int) {
        val delta = direction * state.height / 2f
        val start = state.documentY
        scrollJob?.cancel()
        scrollJob = scope.launch(AndroidUiDispatcher.Main) {
            try {
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = SCROLL_DURATION_MS, easing = LinearOutSlowInEasing),
                ) { t, _ ->
                    state.scrollTo(start + delta * t)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 兜底：动画上下文仍不可用时退回库动画，绝不让异常逃到 scope（那会直接崩）。
                logcat(LogPriority.WARN, t) { "WebGpuViewerContinuous: 缓出动画不可用，回退库动画" }
                runCatching { state.animateScroll(delta) }
            }
        }
    }

    override fun moveRight() = scrollByHalfPage(1)

    override fun moveLeft() = scrollByHalfPage(-1)

    override fun moveToPage(page: ReaderPage) {
        super.moveToPage(page)
        wasAtEdge = null
        lastReadThrough = null
        // Also for a jump to the page already showing, which turns nothing to slide in.
        state.resetScroll()
    }

    override fun animateTurn(direction: Int, fromSpread: ImagePage) {
        state.animateSlideIn(direction)
    }

    private companion object {
        /** 点击滚动的缓出动画时长（ms）。 */
        const val SCROLL_DURATION_MS = 250
    }
}
