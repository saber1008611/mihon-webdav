package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.enhanceKey
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * View of the ViewPager that contains a page of a chapter.
 *
 * SY 2026-10-09 双页移植：[extraPage] 非空时这一屏要同时显示两页（左右拼成一张图）。
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private var extraPage: ReaderPage? = null,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page to extraPage

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    init {
        // SY --> 翻页模式下，一屏只有一页是「当前页」。每个 Holder 都调用
        // ReaderPageImageView.setNonAnimatedImage 时会尝试 reprioritizeAround，
        // 若允许 Holder 自行上报目标页，ViewPager 的预载页会在绑定顺序中最后一个，
        // 把目标页霸占成预载页，导致用户真正在读的那一页被挤到队尾甚至放弃（显示"原图"）。
        // 因此把目标页控制权交给 PagerViewer.onReaderPageSelected。
        controlsCurrentPageSelection = false
        // SY <--
        loadJob = scope.launch { loadPageAndProcessStatus() }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return
        val extra = extraPage

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            // SY --> 双页移植：第二页也要走 loader 才会真正开始下载
            if (extra != null) {
                launchIO {
                    (extra.chapter.pageLoader ?: loader).loadPage(extra)
                }
            }
            // SY <--

            val statusFlow = if (extra == null) {
                page.statusFlow.map { it to Page.State.Ready }
            } else {
                combine(page.statusFlow, extra.statusFlow) { first, second -> first to second }
            }

            statusFlow.collectLatest { (first, second) ->
                when {
                    first is Page.State.Error -> setError(first.error)
                    second is Page.State.Error -> setError(second.error)
                    first == Page.State.Queue || second == Page.State.Queue -> setQueued()
                    first == Page.State.LoadPage || second == Page.State.LoadPage -> setLoading()
                    first == Page.State.DownloadImage || second == Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    first == Page.State.Ready && second == Page.State.Ready -> setImage()
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        val streamFn = page.stream ?: return
        val extra = extraPage

        try {
            val (source, isAnimated, background) = withIOContext {
                if (extra == null) {
                    val source = streamFn().use { process(page, Buffer().readFrom(it)) }
                    val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                    val background = if (!isAnimated && viewer.config.automaticBackground) {
                        ImageUtil.chooseBackground(context, source.peek().inputStream())
                    } else {
                        null
                    }
                    Triple(source, isAnimated, background)
                } else {
                    // SY --> 双页移植：两页合成一张图
                    val primary = streamFn().use { process(page, Buffer().readFrom(it)) }
                    val secondary = extra.stream?.invoke()?.use { process(extra, Buffer().readFrom(it)) }
                    if (secondary == null) {
                        Triple(primary, false, null)
                    } else {
                        Triple(mergeOrSplitPages(primary, secondary), false, null)
                    }
                    // SY <--
                }
            }
            withUIContext {
                // SY --> 合成/变换后的图与增强缓存对不上，这些模式退回原图显示
                val enhancedDisplayAvailable = !usesTransformedEnhancedDisplay()
                // SY <--
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                    // SY --> 超分移植：开启增强时把这一页在缓存里的定位信息传下去
                    enhanceKey = if (enhancedDisplayAvailable) {
                        page.enhanceKey(viewer.config.enhanceEnabled)
                    } else {
                        null
                    },
                    // 数据源必须一并传下去：enqueueEnhancement 取数据的优先级是
                    // streamFn → readerPage → 否则直接 return。翻页模式此前只传了
                    // enhanceKey，streamFn/readerPage 都是 null，于是「单点画面增强」
                    // 入队即被丢弃，只有 loader 里的「整话超分」按钮能触发增强。
                    // 对齐 WebtoonPageHolder 的做法，把本页的流与 ReaderPage 都透传。
                    streamFn = if (enhancedDisplayAvailable) page.stream else null,
                    readerPage = if (enhancedDisplayAvailable) page else null,
                    // SY <--
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    // SY --> 超分移植：与上游 usesTransformedEnhancedDisplay 对齐。
    //
    // 双页拆分 / 旋转时，显示的是「变换之后的半页」，而增强缓存的键与内容是
    // 「未变换的整页」——直接换图会导致几何/内容对不上。这些模式下退回原图路径，
    // 只影响开了增强 + 同时开了双页模式的用户，且是「不增强」而非「错增强」。
    private fun usesTransformedEnhancedDisplay(): Boolean {
        // 双页合成后显示的是「两页拼出来的图」，与原图不是同一张，增强缓存对不上
        return extraPage != null || viewer.config.dualPageSplit || viewer.config.dualPageRotateToFit
    }
    // SY <--

    /**
     * SY 2026-10-09 双页移植：把左右两页拼成一张图。
     *
     * 若其中一页本身就是跨页大图（宽页），双页模式下它应独占一屏 —— 上报给 adapter
     * 重新配对，本屏先按单页显示。
     */
    private fun mergeOrSplitPages(imageSource: BufferedSource, imageSource2: BufferedSource): BufferedSource {
        if (viewer.config.doublePages) {
            if (ImageUtil.isWideImage(imageSource)) {
                viewer.onWidePageDetected(page)
                return imageSource
            }
            if (ImageUtil.isWideImage(imageSource2)) {
                extraPage?.let { viewer.onWidePageDetected(it) }
                return imageSource2
            }
        }

        val bitmap1 = BitmapFactory.decodeStream(imageSource.inputStream())
        val bitmap2 = BitmapFactory.decodeStream(imageSource2.inputStream())
        if (bitmap1 == null || bitmap2 == null) {
            bitmap1?.recycle()
            bitmap2?.recycle()
            return imageSource
        }

        val isLTR = viewer !is R2LPagerViewer
        val grayBackground = Color.rgb(0x20, 0x21, 0x25)
        val background = when (viewer.config.theme) {
            0 -> Color.WHITE
            2 -> grayBackground
            3 -> if (context.isNightMode()) grayBackground else Color.WHITE
            else -> Color.BLACK
        }

        return try {
            ImageUtil.mergeBitmaps(bitmap1, bitmap2, isLTR, background)
        } finally {
            bitmap1.recycle()
            bitmap2.recycle()
        }
    }

    /**
     * SY 2026-10-09：只有在「单页模式 + 开了拆分宽页」时才拆宽页。
     * 双页模式下宽页应当独占一屏（由 adapter 的 fullPage 逻辑处理）。
     */
    private fun shouldSplitWidePagesInSinglePageMode(): Boolean {
        return !viewer.config.doublePages && viewer.config.autoSplitPages
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!shouldSplitWidePagesInSinglePageMode()) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
