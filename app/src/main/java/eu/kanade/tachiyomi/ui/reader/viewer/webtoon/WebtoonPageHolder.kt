package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.PagePreviewCache
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.enhanceKey
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
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
 * Holder of the webtoon reader for a single page of a chapter.
 *
 * @param frame the root view for this holder.
 * @param viewer the webtoon viewer.
 * @constructor creates a new webtoon holder.
 */
class WebtoonPageHolder(
    private val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
) : WebtoonBaseHolder(frame, viewer) {

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressIndicator = createProgressIndicator()

    /**
     * Progress bar container. Needed to keep a minimum height size of the holder, otherwise the
     * adapter would create more views to fill the screen, which is not wanted.
     */
    private lateinit var progressContainer: ViewGroup

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    /**
     * Getter to retrieve the height of the recycler view.
     */
    private val parentHeight
        get() = viewer.recycler.height

    /**
     * Page of a chapter.
     */
    private var page: ReaderPage? = null

    private val scope = MainScope()

    /**
     * Job for loading the page.
     */
    private var loadJob: Job? = null

    init {
        refreshLayoutParams()

        // SY --> 条漫模式下禁止单个页面视图决定「当前页」。
        //
        // 条漫一屏会同时绑定好几页（含 RecyclerView 预载页），若让每个视图各自上报
        // 目标页并抢占队列，最后绑定的那个预载页就会霸占目标页、把正在推理的页顶掉，
        // 表现就是「队列明显有问题、同一页反复超分、进度从零开始」。
        // 目标页改由 WebtoonViewer.onPageSelected 统一上报（对应上游同名做法）。
        frame.controlsCurrentPageSelection = false
        // SY <--

        frame.onImageLoaded = { onImageDecoded() }
        frame.onImageLoadError = { error -> setError(error) }
        frame.onScaleChanged = { viewer.activity.hideMenu() }
    }

    /**
     * Binds the given [page] with this view holder, subscribing to its state.
     */
    fun bind(page: ReaderPage) {
        this.page = page
        // SY --> 2026-10-18：页面还在 Queue/LoadPage/DownloadImage 时，frame 里只有一个转圈
        // （progressContainer 本身是透明 FrameLayout），没有预览就会透出阅读器底色 = 黑闪。
        // 这里先垫上「这一页自己的低清预览」；等真实图片画出来（onImageLoaded）会自动撤掉，
        // 既不留常驻 overdraw，也不会有纯黑/纯白突然出现。
        // 用 bindPagePreview（先清后垫）：RecyclerView view cache 命中时 holder 不经 recycle
        // 就直接绑到另一页，不清就会残留上一页的模糊图。
        frame.bindPagePreview(previewKey(page))
        // SY <--
        loadJob?.cancel()
        loadJob = scope.launch { loadPageAndProcessStatus() }
        // SY 2026-10-01：合并成**一次** layoutParams 赋值（原先是 refreshLayoutParams 新建一套、
        // applyKnownHeight 再改高度 ⇒ 每次绑定两次 requestLayout）；且与现值相同则完全不写。
        // 条漫滑动期 holder 反复绑定，减少无谓的布局请求对 144Hz 下的帧预算有实际意义。
        applyLayoutParams(page)
    }

    // SY --> 2026-10-18：低清预览（blur-up 占位）用的键值工具。
    //
    // previewKey：本页在 PagePreviewCache 里的定位信息。章节/漫画 id 缺失（未入库的本地页）
    //   时返回 null —— 预览只是占位，取不到就退回历史上的白底，不影响任何显示逻辑。
    //   变体名统一走 [pageTransformVariant]（与超分缓存键、WebtoonViewer 的预取共用一份判断）。
    private fun previewKey(page: ReaderPage): PagePreviewCache.Key? {
        val chapterId = page.chapter.chapter.id ?: return null
        val mangaId = page.chapter.chapter.manga_id ?: return null
        return PagePreviewCache.Key(mangaId, chapterId, page.index, viewer.config.pageTransformVariant())
    }
    // SY <--

    /**
     * SY --> 条漫 item 的布局参数（一次算好、按需写入）。
     *
     * 「按已知高宽比定高」的由来：条漫每页高度本要等解码完成才知道 —— **加载中**靠
     * `progressContainer` 铺满 1 屏，**解码完成**才变成 `宽×高宽比`，这个「1 屏 → 自然高」的突变
     * 若发生在视口上方就会把可见内容顶动（往回滑时离屏 holder 重绑会反复发生）。
     * 这里在**绑定时**就用首次解码缓存的比例把「帧高度」定成最终值，使加载中/加载完成高度一致；
     * 未知比例（首次加载）仍为 WRAP_CONTENT，一次性占位→自然高，可接受。
     * SY <--
     */
    private fun applyLayoutParams(page: ReaderPage) {
        val widthPx = Resources.getSystem().displayMetrics.widthPixels
        val margin = widthPx * (viewer.config.sidePadding / 100f)
        val contentWidth = (widthPx - 2 * margin).toInt()
        // ⚠️ 2026-10-02 修复「条漫图片显示不完整」：
        // 曾经在比例未知时用固定比例（1.4）兜底定高，但**兜底值与真实比例不符**时，
        // frame 的固定高度会**小于**图片自然高度，而条漫的 SSIV 是按宽度铺满的 ⇒ 图片**下部被截断**。
        // 现在改为：**只有拿到真实比例（解码后回填）才定高**，未知时用 WRAP_CONTENT，
        // 由内容自然撑开 —— 宁可接受一次「占位→自然高」的过渡，也绝不裁掉图片像素。
        //
        // SY --> 2026-10-18：比例未知时，若这一页的低清预览已经有了，就用预览的真实比例定高。
        // 预览是从「这一页自己」解出来的（超分缓存文件 / 增强位图），比例与最终显示的图完全一致
        // （含拆分/旋转变体），所以不会重蹈「兜底比例与真实比例不符 ⇒ 裁掉图片下部」的覆辙；
        // 附带好处是「还没加载出来的页」从一开始就占住正确高度，滚动位置不再跳动。
        val ratio = page.knownAspectRatio.takeIf { it > 0f }
            ?: PagePreviewCache.aspectRatio(previewKey(page))
            ?: 0f
        // SY <--
        val height = if (ratio > 0f && contentWidth > 0) {
            (contentWidth * ratio).toInt().coerceAtLeast(1)
        } else {
            WRAP_CONTENT
        }
        val bottom = if (!viewer.isContinuous) 15.dpToPx else 0
        val startEnd = margin.toInt()
        // ⚠️ 2026-10-01 实机崩溃修复：item 已经挂进 RecyclerView 之后，它的 LayoutParams 必须是
        // RecyclerView.LayoutParams —— 直接换成 FrameLayout.LayoutParams 会让
        // RecyclerView.getChildViewHolderInt 强转失败（ClassCastException，崩在 dispatchLayoutStep1）。
        // 同步在 bind() 里设 FrameLayout 版是安全的（紧接着的 addView 会重新生成），但从异步
        // 回调（读头完成）里再设就不安全了。所以：能复用就**就地改字段**，绝不换类型。
        val current = frame.layoutParams
        if (current is RecyclerView.LayoutParams) {
            if (current.height == height && current.bottomMargin == bottom &&
                current.marginStart == startEnd && current.marginEnd == startEnd
            ) {
                return // 与现值一致：不写 layoutParams ⇒ 不触发 requestLayout
            }
            current.height = height
            current.bottomMargin = bottom
            current.marginStart = startEnd
            current.marginEnd = startEnd
            frame.layoutParams = current
            return
        }
        val frameLp = current as? FrameLayout.LayoutParams
        if (frameLp != null && frameLp.height == height && frameLp.bottomMargin == bottom &&
            frameLp.marginStart == startEnd && frameLp.marginEnd == startEnd
        ) {
            return
        }
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, height).apply {
            bottomMargin = bottom
            marginStart = startEnd
            marginEnd = startEnd
        }
    }

    private fun refreshLayoutParams() {
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!viewer.isContinuous) {
                bottomMargin = 15.dpToPx
            }

            val margin = Resources.getSystem().displayMetrics.widthPixels * (viewer.config.sidePadding / 100f)
            marginEnd = margin.toInt()
            marginStart = margin.toInt()
        }
    }

    /**
     * Called when the view is recycled and added to the view pool.
     */
    override fun recycle() {
        loadJob?.cancel()
        loadJob = null

        removeErrorLayout()
        frame.recycle()
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if there is no page or the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading
     */
    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator.setProgress(0)

        val currentPage = page ?: return
        val streamFn = currentPage.stream ?: return

        try {
            val (source, isAnimated) = withIOContext {
                val source = streamFn().use { process(Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                // SY --> 2026-10-18：这一页的数据此刻就在内存里（Buffer），顺手解一张低清预览。
                // 这条路径覆盖的是「超分还没算过这一页」（冷章节 / 超分关闭）：没有它，
                // SSIV 分块解码的窗口里垫的就是纯白兜底（用户明确不接受白底），
                // 而「换图视图」那种没有底色的情况则是纯黑。
                // 用 peek()：不消费原 buffer，交给 SSIV 的字节一个都不会少（也没有第二次网络请求）。
                // 位图缩放是 IO 线程做的，不占主线程；previewKey 带变体，与显示的是同一张变换图。
                // 目标宽度 = 显示宽度（2026-10-18）：占位图要与最终清晰图视觉一致，否则滑动时
                // 会看到明显的「糊 → 清晰」切换（用户反馈「伤眼睛」）。
                val previewTargetWidth = PagePreviewCache.previewTargetWidthFor(
                    Resources.getSystem().displayMetrics.widthPixels,
                )
                previewKey(currentPage)?.let { key ->
                    if (PagePreviewCache.get(key) == null) {
                        PagePreviewCache.decodePreview({ source.peek().inputStream() }, previewTargetWidth)
                            ?.let { PagePreviewCache.put(key, it) }
                    }
                }
                // SY <--
                Pair(source, isAnimated)
            }
            withUIContext {
                // SY --> 超分移植：开启增强时把这一页在缓存里的定位信息传下去。
                // 2026-09-30：拆分双页/旋转适配**也支持超分** —— 增强与缓存对象改为
                // 「实际显示的变换图」，用 pageVariant 区分键（dpsL/dpsR=拆分合并、r90/r270=旋转），
                // worker 的数据源同样取变换后的图 ⇒ 状态条轮询与缓存命中的交换显示无需再做任何变换。
                // 2026-10-18：变体名统一走 pageTransformVariant()（WebtoonConfig.kt 里的共享实现），
                // 预览键（previewKey）、超分缓存键、WebtoonViewer 的开口页预取都复用同一份逻辑，
                // 保证「预览」与「超分缓存」永远指向同一个变体，不会出现比例对不上的模糊图。
                frame.setImage(
                    source,
                    isAnimated,
                    ReaderPageImageView.Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                        cropBorders = viewer.config.imageCropBorders,
                    ),
                    enhanceKey = currentPage.enhanceKey(
                        viewer.config.enhanceEnabled,
                        viewer.config.pageTransformVariant(),
                    ),
                    streamFn = currentPage.stream?.let { raw ->
                        val transformed: () -> java.io.InputStream = {
                            raw.invoke().use { src -> process(Buffer().readFrom(src)).inputStream() }
                        }
                        transformed
                    },
                    readerPage = currentPage,
                    previewKey = previewKey(currentPage),
                    // SY <--
                )
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (viewer.config.dualPageSplit) {
            val isDoublePage = ImageUtil.isWideImage(imageSource)
            if (isDoublePage) {
                val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
                return ImageUtil.splitAndMerge(imageSource, upperSide)
            }
        }

        return imageSource
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

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressContainer.isVisible = false
        initErrorLayout(error)
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        // SY --> 解码完成后记下本页高宽比，供回收/重绑时提前占位定高（消除条漫往回滑跳动）。
        frame.readyAspectRatio?.let { ratio -> page?.knownAspectRatio = ratio }
        // SY <--
        progressContainer.isVisible = false
        removeErrorLayout()
    }

    /**
     * Creates a new progress bar.
     */
    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight)

        val progress = ReaderProgressIndicator(context).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    /**
     * Initializes a button to retry pages.
     */
    private fun initErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), frame, true)
            errorLayout?.root?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt())
            errorLayout?.actionRetry?.setOnClickListener {
                page?.let { it.chapter.pageLoader?.retryPage(it) }
            }
        }

        val imageUrl = page?.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}
