package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.data.coil.chapterId
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.data.coil.mangaId
import eu.kanade.tachiyomi.data.coil.pageIndex
import eu.kanade.tachiyomi.data.coil.pageVariant
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.enhancementConfigHash
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.InputStream

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private var pageView: View? = null

    /**
     * SY --> 当前视图**已就绪**的图片高宽比 (height / width)；未就绪返回 null。
     * 供持有人在解码完成（onImageLoaded）后回填 [eu.kanade.tachiyomi.ui.reader.model.ReaderPage.knownAspectRatio]，
     * 下次（含回收重绑）绑定时据此提前把 item 高度定到最终值，消除条漫往回滑的高度突变跳动。
     * SY <--
     */
    val readyAspectRatio: Float?
        get() = (pageView as? SubsamplingScaleImageView)
            ?.takeIf { it.sWidth > 0 && it.sHeight > 0 }
            ?.let { it.sHeight.toFloat() / it.sWidth.toFloat() }

    private var config: Config? = null

    // SY --> 超分移植：本视图有没有资格决定「用户当前在读哪一页」。
    //
    // 翻页模式一屏只有一页，视图被绑定即当前页，所以默认 true（沿用上游语义）。
    // 条漫模式一屏会同时绑定多页（含预载页），此时必须由 WebtoonViewer.onPageSelected
    // 统一上报当前页；否则最后绑定的那个预载页会霸占目标页并把正在推理的页顶掉，
    // 表现就是「队列明显有问题 / 同一页反复超分」。
    // 对应上游 WebtoonPageHolder 里的 `frame.controlsCurrentPageSelection = false`。
    var controlsCurrentPageSelection: Boolean = true
    // SY <--

    // SY --> mihon_img_upscale 移植：增强显示所需的字段
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var processingJob: Job? = null

    // SY --> 图片解码任务句柄 + 代次。
    //
    // 历史上这段还兼管「超大图降采样」，现已移除（见 setImageCapped 上方注释）；
    // 现在只用于把 `setImage` 从主线程挪到 IO，并用代次丢弃过期结果。
    //
    // 保留的历史教训（2026-09-26 实测，结论依然有效）：降采样曾是**主线程同步**做的，
    // 本机常见「双页 8K 扫描」3309×2500 / 5620×2500（PNG）一页解码 100ms+，
    // framestats 显示 HandleInputStart − IntendedVsync = 141ms、Vsync 迟到 133ms
    // ⇒ 绘制极轻但**主线程在开帧前就被占死**，表现为「任何格式滑动都掉帧」。
    // 所以：**解码绝不能在主线程做**。
    private var capDecodeJob: Job? = null
    private var capDecodeGeneration = 0
    // SY <--

    /** 正在交叉淡入的「增强图」视图；ready 后接管显示。 */
    private var processedSwapView: SubsamplingScaleImageView? = null

    /** 被替换掉的旧视图，淡入结束后回收。 */
    private var outgoingProcessedView: SubsamplingScaleImageView? = null

    // SY --> 2026-10-18：本页低清预览（blur-up 占位）的状态。
    //
    // 条漫每次重绑都要让 SSIV 重新分块解码，解码窗口里 SSIV 画不出任何像素，看的只是
    // 「View 自己的 background」。纯白底 = 白闪、没底 = 黑闪，两者用户都不接受，
    // 所以改成垫这一页自己的低清缩略图（见 PagePreviewCache）。
    //
    // previewKey  当前绑定的这一页在预览缓存里的定位信息；
    // previewJob  正在后台生成预览的任务（读超分缓存文件，本地 I/O）；
    // previewGeneration  代次，保证迟到的预览不会贴到已经换了页的视图上。
    private var previewKey: PagePreviewCache.Key? = null
    private var previewJob: Job? = null
    private var previewGeneration = 0

    /** 这一页的图片是否已经画出来了（决定 frame 层的预览要不要撤掉）。 */
    private var imageReady = false
    // SY <--

    /** 当前这一页（用于取不到 stream 时兜底）。 */
    var readerPage: ReaderPage? = null

    private val readerPreferences: ReaderPreferences?
        get() = (context.applicationContext as? App)?.graph?.readerPreferences

    // SY --> 逐页状态文案（排队中 / 收尾中… / 已增强 / 原图）**已整体移除**，
    // 改由 ReaderActivity 的左下角**全局**状态显示（ReaderProcessingStatusView）统一负责。
    //
    // 原因：只有一条 worker、且严格按页码顺序消费，所以「这一页」的状态必然会在
    // 「增强中 → 排队中 → 排队中 → 已增强」之间来回跳（worker 先跑去算别的页，
    // 本页就退回「排队中」）。一个视图一份逐页状态，天然会闪；改成与 TachiyomiX
    // 一致的「单一全局状态源」后，文案是单调的，且空闲时整条隐藏。
    // 本类只保留「发现缓存出现 → 把增强图换进来」这件事。
    // SY <--

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        // SY --> 修「底部白图一闪而过」（2026-09-27）：
        //
        // 原先这里**无条件** `background = pageBackground`。条漫模式下 pageBackground 恒为
        // null ⇒ 图片一就绪就把帧背景清空成透明，于是透出阅读器主题色——浅色主题下就是
        // 一片白，表现为「出图瞬间底部白图一闪而过」。
        //
        // 页面图像本身是**不透明且铺满**帧的，背景色在图显示后本就看不到；真正需要背景的
        // 是「图还没画出来」的那一瞬。所以这里只在「自动背景特性真的给了颜色」
        // （pageBackground != null，翻页模式的 automaticBackground）时才去设它；
        // 其余情况（条漫 / 自动背景关闭）保留既有占位底色（页面自身边缘色），
        // 既不闪黑也不闪白。
        // SY <--
        if (pageBackground != null) {
            background = pageBackground
        } else if (previewKey != null) {
            // SY --> 2026-10-18：预览占位只在「图还没画出来」时有价值 —— 图一就绪立刻撤掉，
            // 否则每一页都会留一层全屏 overdraw（历史实测：frame 常驻背景会让条漫滚动掉帧）。
            // 撤掉之后解码窗口外侧露出的仍是 SSIV 自己的预览背景，所以不会闪。
            background = null
            // SY <--
        }
        imageReady = true
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        // SY --> 修上游遗留的崩溃点（真机 FATAL：ReaderPageImageView$landscapeZoom
        //   $$inlined$postDelayed$default$1.run，Dimensity 9400+ / Android 16 / 本 APK）。
        //
        //   上游写的是 `animateScaleAndCenter(...)!!`，而这个 500ms 延时任务执行时，
        //   本页很可能已经被回收或换图重建（快速翻页、滚出预载窗口、增强图替换后的
        //   清理都会 recycle 视图）。SubsamplingScaleImageView.recycle() 会 reset()
        //   把 readySent 复位，于是 animateScaleAndCenter() 返回 null
        //   （其字节码：if (!isReady()) return null;）→ `!!` 在主线程延时任务里抛
        //   NullPointerException → **直接崩进程**（阅读器还在，属于确定性崩溃）。
        //
        //   改法（只做空安全，不改任何正常路径的行为）：
        //     · 视图已失效 → 这次放大动画安静放弃（与 animateScaleAndCenter 内部
        //       本来就会返回 null 的效果一致）；
        //     · config 在投递前取成局部量，避免 500ms 后再读一次可空字段。
        val cfg = config ?: return
        if (
            cfg.landscapeZoom &&
            cfg.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                if (!isReady()) return@postDelayed
                val point = when (cfg.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)
                    ?.withDuration(500)
                    ?.withEasing(EASE_IN_OUT_QUAD)
                    ?.withInterruptible(true)
                    ?.start()
            }
        }
        // SY <--
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(
        source: BufferedSource,
        isAnimated: Boolean,
        config: Config,
        enhanceKey: EnhanceKey? = null,
        streamFn: (() -> InputStream)? = null,
        readerPage: ReaderPage? = null,
        previewKey: PagePreviewCache.Key? = null,
    ) {
        this.config = config
        // SY --> 超分移植：逐页状态 TextView 已移除（改由 ReaderActivity 的全局状态视图负责），
        // 所以这里不再需要在绑新页时清理残留状态文案。
        // SY <--
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config, enhanceKey, streamFn, readerPage, previewKey)
        }
    }

    fun recycle() {
        // SY --> 2026-10-18：取消未完成的预览生成，并撤掉 frame 上的预览占位
        // （holder 会被复用去显示别的页，留着上一页的模糊图会「串页」）。
        clearPagePreview()
        // SY <--
        pageView?.let {
            processingJob?.cancel()
            processingJob = null
            // SY --> 作废还没跑完的降采样解码，避免把解码结果贴到已回收的视图上
            capDecodeJob?.cancel()
            capDecodeJob = null
            capDecodeGeneration++
            // SY <--
            clearProcessedSwapView()
            when (it) {
                is SubsamplingScaleImageView -> it.recycle()
                is AppCompatImageView -> it.dispose()
            }
            it.isVisible = false
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        processingJob?.cancel()
        processingJob = null
        // SY --> 同上：离开窗口后取消未完成的降采样解码
        capDecodeJob?.cancel()
        capDecodeJob = null
        capDecodeGeneration++
        // SY <--
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            // SY --> 同 landscapeZoom：animateCenter 未就绪时也返回 null，上游的 `!!`
            //   同样会在主线程抛 NPE 崩进程，这里一并做空安全。
            view.animateCenter(target)
                ?.withEasing(EASE_OUT_QUAD)
                ?.withDuration(250)
                ?.withInterruptible(true)
                ?.start()
            // SY <--
        }
    }

    private fun newSubsamplingView(): SubsamplingScaleImageView {
        return if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        // SY --> 条漫占位底色：白色放在「SSIV 自身背景」上，而不是外层 frame。
        //
        // 之前把 frame 设为不透明白背景，等于给每一页都垫了一层全屏白 → 大面积 overdraw，
        // 平板高分辨率下 GPU 填充压力上升 → 滚动卡顿；且白色在深色主题背景上刺眼 → 白闪。
        //
        // 2026-10-18 更新：**纯白已经不再是最终形态**。这里设的白只是「预览还没生成好」的
        // 兜底（通常只存在几十毫秒）；真正的占位是这一页自己的低清预览，
        // 由 applyPagePreview() 在这之后立刻换上，见 PagePreviewCache 的说明。
        // SY <--
        pageView = newSubsamplingView().also {
            it.setBackgroundColor(Color.WHITE)
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    // SY --> 2026-10-18：低清预览（blur-up）占位。
    //
    // 条漫场景下「解码窗口」有三处会露出底色，这里一次解决：
    //   1) SSIV 已经 setImage、但分块解码还没出像素 → SSIV 自身背景；
    //   2) 超分换图后新建的 swap 视图（随后成为常驻 pageView）→ 同样垫预览，绝不留透明；
    //   3) 页面数据还没下载下来（Queue/LoadPage/DownloadImage），frame 里只有转圈 →
    //      frame 自身背景（只有这一种情况才允许垫 frame，且图一就绪立刻撤掉，避免 overdraw）。
    fun applyPagePreview(key: PagePreviewCache.Key?) {
        if (previewKey != key) {
            previewGeneration++
            previewJob?.cancel()
            previewJob = null
        }
        previewKey = key

        val cached = PagePreviewCache.get(key)
        val drawable = cached?.let { BitmapDrawable(resources, it) }

        // 没有预览时**留透明**，不再兜白色：透明会露出阅读器底下的封面底图（ReaderActivity 的
        // loadingBackdrop），是内容而不是纯色。2026-10-18 用户明确「黑底白底都不能接受」。
        (pageView as? SubsamplingScaleImageView)?.background = drawable

        // frame 层：只有「图还没画出来」时才垫。没有预览就保持透明（历史行为，不额外引入纯色）。
        if (!imageReady) background = drawable

        if (cached == null && previewJob?.isActive != true) {
            buildPagePreview(key)
        }
    }

    /**
     * 预览按**显示宽度**解码（再留 15% 余量，见 [PagePreviewCache.previewTargetWidthFor]）。
     *
     * 2026-10-18 修正：最初按 240px 宽解码（约屏宽 1/4），放大 4.5 倍后滑动中肉眼可见地糊，
     * 用户反馈「滑动的距离稍微长一点，过程中图片就会变得很模糊…伤眼睛」。改成显示宽度后，
     * 占位图与 SSIV 随后画出的清晰图在 fit-width 下视觉一致，「模糊→清晰」的切换不再可见。
     */
    private fun previewTargetWidth(): Int =
        PagePreviewCache.previewTargetWidthFor(resources.displayMetrics.widthPixels)

    /** 后台生成预览（读超分缓存文件，纯本地 I/O）。没有超分缓存时什么都做不了。 */
    private fun buildPagePreview(key: PagePreviewCache.Key?) {
        if (key == null) return
        val preferences = readerPreferences ?: return
        val generation = previewGeneration
        val targetWidth = previewTargetWidth()
        previewJob = viewScope.launchIO {
            val configHash = runCatching { enhancementConfigHash(preferences) }.getOrNull() ?: return@launchIO
            val preview = PagePreviewCache.loadFromEnhancedCache(context, key, configHash, targetWidth)
                ?: return@launchIO
            PagePreviewCache.put(key, preview)
            withUIContext {
                if (generation != previewGeneration || previewKey != key) return@withUIContext
                val drawable = BitmapDrawable(resources, preview)
                (pageView as? SubsamplingScaleImageView)?.background = drawable
                if (!imageReady) background = drawable
            }
        }
    }

    /** 回收/换页时清掉预览状态（holder 会被复用去显示别的页，留着会串页）。 */
    private fun clearPagePreview() {
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        previewKey = null
        imageReady = false
        background = null
    }

    /**
     * SY 2026-10-18：持有人重新绑定一页时调用 —— 先清掉上一页的预览状态，再垫这一页的预览。
     *
     * 必须「先清」的原因：RecyclerView 的 view cache 命中时**不会**走 onViewRecycled，
     * 即 holder 没被 recycle 就直接绑到了另一页，此时 frame/SSIV 上还留着上一页的模糊图，
     * 而 imageReady 也还停在 true（→ 新预览不会垫上、加载态会露出阅读器底色）。
     */
    fun bindPagePreview(key: PagePreviewCache.Key?) {
        clearPagePreview()
        applyPagePreview(key)
    }
    // SY <--

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
        enhanceKey: EnhanceKey? = null,
        streamFn: (() -> InputStream)? = null,
        readerPage: ReaderPage? = null,
        previewKey: PagePreviewCache.Key? = null,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        // SY --> 2026-10-18：先垫「这一页自己的低清预览」，再让 SSIV 分块解码。
        // 解码窗口里看到的是同一页的模糊版（既不黑也不白），SSIV 出像素后自然变清晰。
        imageReady = false
        applyPagePreview(previewKey)
        // SY <--
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                // SY --> mihon_img_upscale 移植：超分的显示路径。
                //
                // 上游的做法是「显示」与「增强」完全解耦：
                //   1) 缓存里已经有这一页的增强结果 → 直接把增强图当原图显示，零推理；
                //   2) 没有 → **立刻**显示原图（解码不阻塞、不烧 GPU），
                //      同时把这一页丢进后台 ImageEnhancer，并轮询缓存是否已出现；
                //      一旦出现就把图换掉（左下角全程显示进度/状态）。
                // 之前那版是在 Coil 解码里同步跑推理，用户要盯着空白等好几秒，
                // 既没有进度提示，也永远不会把已经显示的图换成增强图。
                // SY <--
                val prefs = readerPreferences
                if (enhanceKey == null || prefs == null || !prefs.realCuganEnabled().get()) {
                    // SY --> 超大图先降采样（见 setImageCapped 注释），防 subscale 全分辨率拷贝 OOM
                    setImageCapped(data)
                    // SY <--
                    isVisible = true
                    return@apply
                }

                this@ReaderPageImageView.readerPage = readerPage
                val cachedFile = currentEnhancedFile(enhanceKey, prefs)
                if (cachedFile != null) {
                    setImageCapped(cachedFile)
                    isVisible = true
                    return@apply
                }

                setImageCapped(data)
                isVisible = true

                if (readerPage != null && controlsCurrentPageSelection) {
                    currentGlobalPageIndex = readerPage.index
                }
                // SY --> 只有「有资格决定当前页」的视图才更新目标页。条漫模式下这一步由
                // WebtoonViewer.onPageSelected 做（见 controlsCurrentPageSelection 的说明）。
                if (controlsCurrentPageSelection) {
                    ImageEnhancer.reprioritizeAround(enhanceKey.pageIndex, enhanceKey.pageVariant)
                    // SY 2026-09-29：恢复基线「预载页数」的超分窗口裁剪（原功能，重构时丢失）：
                    // 只保留 [当前页, 当前页+预载页数] 的增强请求，窗口外取消（前面已看过、
                    // 后面太远的不浪费算力）；被取消的页滑回窗口时由绑定/轮询重新入队。
                    val preloadSize = prefs.realCuganPreloadSize().get().coerceIn(1, 8)
                    ImageEnhancer.cancelRequestsLessThan(
                        enhanceKey.mangaId,
                        enhanceKey.chapterId,
                        enhanceKey.pageIndex,
                    )
                    ImageEnhancer.cancelRequestsGreaterThan(
                        enhanceKey.mangaId,
                        enhanceKey.chapterId,
                        enhanceKey.pageIndex + preloadSize,
                    )
                }
                // 优先级不再无脑 highPriority=true：只有「这一页就是当前目标页」才插队，
                // 其余（预载页）按普通优先级排队。条漫一屏多页时，若每页都按高优先级
                // 插队，它们会互相抢占、把目标页顶下去 —— 这就是队列乱掉的直接原因。
                enqueueEnhancement(
                    enhanceKey,
                    streamFn,
                    highPriority = ImageEnhancer.isTargetPage(enhanceKey.pageIndex, enhanceKey.pageVariant),
                )
                // SY <--
                startEnhancementPolling(enhanceKey, streamFn)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    // SY --> 2026-10-02：**已移除「按像素上限降采样」**（用户要求保分辨率）。
    // 历史上曾因 SSIV 的 Decoder 会把全分辨率结果整幅拷进 Java 堆（8K 跨页 ~24MP ≈ 97MB）
    // 而设过 8MP / 屏幕级上限，超限则降采样成 Bitmap 再显示；代价是放大的页发虚，
    // 且条漫下容器高度与图片不符会裁切。现统一走 `ImageSource.inputStream` 让 SSIV
    // 分块解码（只解可见区域 tile），全分辨率与低内存兼得。
    // ⚠️ 若将来真的出现超大跨页 OOM（栈顶为 `subscaleview.decoder.*TilesInitTask`），
    //    请针对**单个畸形页**加保护，不要再做全局降采样。
    private fun SubsamplingScaleImageView.setImageCapped(source: BufferedSource) {
        setImageCapped(source::inputStream)
    }

    private fun SubsamplingScaleImageView.setImageCapped(file: File) {
        setImageCapped(file::inputStream)
    }

    /**
     * SY --> 2026-10-02（用户要求）：**不再按像素上限降采样，统一交给 SSIV 分块解码**。
     *
     * 旧实现先用 `probe()` 读图片头，像素数超过 `MAX_INPUT_PIXELS` 就整页解码 + 2 的幂
     * `inSampleSize` 降采样再交给 SSIV —— 省了合成带宽，但**放大会明显发虚**（用户实测反馈
     * "条漫模式下图片不能完整显示 / 分辨率被切掉"）。
     *
     * 现在直接走 `ImageSource.inputStream(display())`：SSIV 用 BitmapRegionDecoder
     * **只解码可见区域的 tile**，内存占用与图片总像素无关 ⇒ 全分辨率与低内存可以兼得；
     * 同时**省掉"读图片头"这一步**（少开一次流，对未缓存的 WebDAV 页少一次网络往返）。
     *
     * 代次（generation）保证：视图被 recycle / 重新 setImage 后，迟到的结果直接丢弃，
     * 不会贴到错页或已回收的视图上。
     * SY <--
     */
    private fun SubsamplingScaleImageView.setImageCapped(display: () -> InputStream) {
        val generation = ++capDecodeGeneration
        val target = this
        capDecodeJob?.cancel()
        capDecodeJob = viewScope.launchIO {
            withUIContext {
                if (generation == capDecodeGeneration && pageView === target) {
                    setImage(ImageSource.inputStream(display()))
                }
            }
        }
    }
    // SY <--

    // SY --> mihon_img_upscale 移植：一页在增强缓存里的定位信息
    data class EnhanceKey(
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val pageVariant: String = "",
    )
    // SY <--

    // SY --> mihon_img_upscale 移植：增强结果轮询 + 换图
    // （逐页状态文案已移除，统一由 ReaderProcessingStatusView 显示全局状态）
    // region Image enhancement

    /**
     * SY 2026-10-18：超分缓存键 → 预览缓存键。
     * 两者同构；变体必须带上（拆分合并 / 旋转后的显示比例与原页不同，预览也不能混用）。
     */
    private fun previewCacheKey(key: EnhanceKey): PagePreviewCache.Key =
        PagePreviewCache.Key(key.mangaId, key.chapterId, key.pageIndex, key.pageVariant)

    /** 这一页当前配置下的增强缓存文件；没有则 null。 */
    private fun currentEnhancedFile(key: EnhanceKey, preferences: ReaderPreferences): File? {
        ImageEnhancementCache.init(context)
        val configHash = enhancementConfigHash(preferences)
        return ImageEnhancementCache.getCachedImage(
            key.mangaId,
            key.chapterId,
            key.pageIndex,
            configHash,
            key.pageVariant,
        )
    }

    private fun decodeEnhancedBitmap(file: File): Bitmap? {
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            if (ImageEnhancementCache.isDisplayable(bitmap)) {
                bitmap
            } else {
                logcat(LogPriority.WARN) { "ReaderPageImageView: ignoring invalid enhanced cache ${file.absolutePath}" }
                bitmap.recycle()
                null
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ReaderPageImageView: failed to decode enhanced cache" }
            null
        }
    }

    private fun enqueueEnhancement(
        key: EnhanceKey,
        streamFn: (() -> InputStream)?,
        highPriority: Boolean,
    ) {
        val stream = streamFn
        val page = readerPage
        val provider: () -> Any? = when {
            stream != null -> {
                { runCatching { stream().use { Buffer().readFrom(it) } }.getOrNull() }
            }
            page != null -> {
                { runCatching { page.stream?.invoke()?.use { Buffer().readFrom(it) } }.getOrNull() }
            }
            else -> return
        }

        ImageEnhancer.enhanceLazy(
            context = context.applicationContext,
            mangaId = key.mangaId,
            chapterId = key.chapterId,
            pageIndex = key.pageIndex,
            highPriority = highPriority,
            pageVariant = key.pageVariant,
            dataProvider = provider,
        )
    }

    /**
     * 轮询增强结果 + 刷新左下角状态。任务随视图脱离窗口而取消。
     */
    private fun startEnhancementPolling(key: EnhanceKey, streamFn: (() -> InputStream)?) {
        val preferences = readerPreferences ?: return
        processingJob?.cancel()
        processingJob = viewScope.launchIO {
            var requeued = 0
            var attemptsSinceRequeue = 0
            var gaveUp = false
            var wasTarget = false
            // SY --> 本页「已经按哪个配置指纹定稿」（换好图 / 被判定不用算 / 解码失败）。
            // 与当前 configHash 相同就转入省钱守候：不再查缓存、不再查队列，
            // 只低频比对配置指纹，等着用户换引擎。
            // SY <--
            var settledHash: String? = null
            var lastConfigHash = enhancementConfigHash(preferences)
            // SY --> 从「最多轮询 300 次就退出」改成「只要视图还挂着就一直守候」。
            //
            // 原因：旧实现一旦换图成功就 `return`，于是**用户切换超分引擎时这一页
            // 根本没有轮询在跑**，没有任何人会察觉配置指纹变了 —— 画面纹丝不动，
            // 看起来就是「切了引擎没反应」。
            //
            // 生命周期安全：视图脱离窗口时 onDetachedFromWindow 会
            // cancel(processingJob)，所以 while(isActive) 正好被视图挂载状态兜住，
            // 不会泄漏；重新绑定页面时 startEnhancementPolling 也会先 cancel 掉旧循环。
            // 已经定稿或已判定放弃的页会走 1s 的低频守候，常态开销可以忽略。
            // SY <--
            while (isActive) {
                val configHash = enhancementConfigHash(preferences)

                // 已按当前配置定稿：只等配置变更，别的什么都不做。
                if (settledHash == configHash) {
                    delay(ENHANCEMENT_IDLE_POLL_INTERVAL_MS)
                    continue
                }
                // SY --> 配置（模型/精度/尺寸上限…）一变，缓存键就变了，上一轮攒的补投
                // 额度也随之作废，必须重新给足。否则用户在盯着这一页的时候改了设置，
                // 队列又被 cancelAll 清空，这一页就再也没机会被重新超分。
                if (configHash != lastConfigHash) {
                    lastConfigHash = configHash
                    requeued = 0
                    attemptsSinceRequeue = 0
                    gaveUp = false
                    // SY --> 换了超分引擎/精度之后，**立刻**重新排队。
                    //
                    // 旧实现只把计数清零，真正的重投入队要等下面 requeue 分支
                    //   「攒够 ENHANCEMENT_REQUEUE_INTERVAL_ATTEMPTS 次轮询」才发生，
                    //   而且当时还会被 ImageEnhancer 的会话去重表（旧版不含配置指纹）
                    //   直接挡住 —— 用户改完设置就一直是旧引擎的画面，等于没生效。
                    //
                    // 现在：指纹一变就在这里主动入队。
                    //   · 入队不会再被挡：会话去重表的键已带上配置指纹；
                    //   · 去重仍然安全：enhanceLazy 里 isCached/isSkipped/pendingRequests
                    //     三道闸门都还在，重复调用不会真的重复跑模型。
                    //   · 旧引擎的增强图先原样留在屏幕上，等新结果落盘后，
                    //     下一轮缓存命中会走 setProcessedSource 自然换图，所以不会闪。
                    //
                    // 只有「用户当前正在读的页」给高优先级立刻插队；其余可见页
                    //   按普通优先级排后面（与 loader 预载入队的方式一致）。
                    // SY <--
                    val isCurrentPage = ImageEnhancer.isTargetPage(key.pageIndex, key.pageVariant)
                    enqueueEnhancement(key, streamFn, highPriority = isCurrentPage)
                }
                // SY <--

                if (ImageEnhancementCache.isSkipped(key.mangaId, key.chapterId, key.pageIndex, configHash, key.pageVariant)) {
                    // 当前配置下本就该跳过（超出尺寸上限等）：定稿成原图，
                    // 但继续守候 —— 用户换了引擎/放宽了尺寸上限后要能自动恢复。
                    settledHash = configHash
                    continue
                }

                val file = ImageEnhancementCache.getCachedImage(
                    key.mangaId,
                    key.chapterId,
                    key.pageIndex,
                    configHash,
                    key.pageVariant,
                )
                if (file != null) {
                    val bitmap = decodeEnhancedBitmap(file)
                    if (bitmap != null) {
                        // SY --> 2026-10-18：这一页的全分辨率增强图此刻就在手上，顺手生成低清预览
                        // （零额外 I/O）。它既是当前换图的兜底，也是**滑回去重绑时**的 blur-up 占位，
                        // 让「SSIV 重新分块解码」的窗口里看到的是这一页的模糊版而不是纯色。
                        // 这里在 launchIO 里，缩放不会卡主线程；seed 只读不写传入的位图。
                        PagePreviewCache.seed(previewCacheKey(key), bitmap)
                        // SY <--
                        withUIContext { setProcessedSource(file, bitmap) }
                        // 换图成功：记下是按哪个配置换的，转入守候态等下一次配置变更。
                        // 注意这里在 IO 线程上写、守候态在下一轮 IO 迭代上读，不跨线程。
                        settledHash = configHash
                    } else {
                        // 文件在但解不出来（损坏/过大）：与旧实现一样放弃这一页，
                        // 但同样要定稿，否则会每 400ms 空转重试。
                        settledHash = configHash
                    }
                    continue
                }

                // SY --> 逐页状态文案（排队中 / 收尾中… / 已增强 / 原图）已整体移除，
                // 左下角的显示改由 ReaderActivity 的 ReaderProcessingStatusView 从
                // ImageEnhancer.status 这一个全局数据源统一渲染。
                //
                // 这里只保留**功能性**的那一半：这一页还没算出来时，要不要补投。
                // （状态机剩下的计数仍然有用 —— 它们决定轮询是走 400ms 还是 1s 的守候节奏。）
                // SY <--
                if (ImageEnhancer.hasRequest(key.mangaId, key.chapterId, key.pageIndex, key.pageVariant)) {
                    // SY --> 请求还挂在队列里 / 正在落盘：正常路径，什么都不做。
                    //
                    // 这里**刻意不主动 cancel**：hasRequest 为真也可能是完全正常的
                    //   「正在排队」或「正在跑但进度 ID 尚未对上本页」。此时撤销重排会
                    //   把一个健康的任务反复打断重来，退化成本项目历史上出现过的
                    //   「重复超分 / 进度从零开始」。真正卡死的任务由
                    //   ImageEnhancer 侧的超时（ENHANCEMENT_JOB_TIMEOUT_MS）负责收尾。
                    // SY <--
                    attemptsSinceRequeue++
                } else {
                    // SY --> 队列里没有、缓存里也没有。成因有三种：
                    //   1) 被抢占时任务被丢弃（现在不再 requeue，靠这里兜底）；
                    //   2) 改设置触发 cancelAll，整个队列被清空；
                    //   3) 上一次推理直接失败（native 返回 null 时不写缓存）。
                    //
                    // 关键约束：**只有「用户当前正在读的这一页」才补投**。
                    // 预载页被抢占/被裁剪掉是正常的调度结果，无脑补投会把它重新塞回队列
                    // 并从头再算一遍 —— 条漫快速滚动时几乎每页都在补投、重启，这正是
                    // 「重复超分 + 进度从零开始」的观感来源。非当前页安静地继续轮询，
                    // 等它重新成为当前页时再补投（读者回头读也照样能恢复）。
                    val isCurrentTarget = ImageEnhancer.isTargetPage(key.pageIndex, key.pageVariant)
                    if (isCurrentTarget && !wasTarget) {
                        // 刚成为当前页：作废上一轮（作为预载页时）攒下的额度，重新给足机会
                        wasTarget = true
                        requeued = 0
                        attemptsSinceRequeue = 0
                        gaveUp = false
                    } else if (!isCurrentTarget) {
                        wasTarget = false
                    }

                    attemptsSinceRequeue++
                    if (!isCurrentTarget) {
                        gaveUp = true
                    } else if (
                        requeued < ENHANCEMENT_MAX_REQUEUE &&
                        attemptsSinceRequeue >= ENHANCEMENT_REQUEUE_INTERVAL_ATTEMPTS
                    ) {
                        requeued++
                        attemptsSinceRequeue = 0
                        enqueueEnhancement(key, streamFn, highPriority = true)
                    } else if (requeued >= ENHANCEMENT_MAX_REQUEUE) {
                        gaveUp = true
                    }
                    // SY <--
                }

                // 已判定放弃的页没必要再按 400ms 高频轮询，降到 1s 继续守候即可。
                delay(
                    if (gaveUp) ENHANCEMENT_IDLE_POLL_INTERVAL_MS else ENHANCEMENT_POLL_INTERVAL_MS,
                )
            }
            // while (isActive) 只在视图被回收（processingJob 被 cancel）时结束，
            // 取消会直接从 delay 抛出 CancellationException，所以这里不需要（也到不了）
            // 任何状态复位 —— 各分支已经各自负责把状态写到终态。
        }
    }

    /**
     * 把已经算好的增强图「偷偷」换进视图。
     *
     * 与旧版（增强图叠在最上面淡入）不同，这里把增强图放在**原图下面**那一层慢慢加载，
     * 原图全程盖在上面、对读者完全可见也完全不动；等增强图就绪后，再把原图这一层淡出
     * （露出底下的增强图），最后悄悄 recycle 掉原图。中间永远有图挡着，不会出现
     * 「黑屏一闪再替换」的观感。
     *
     * 视口继承：只有用户**手动放大过**时才做映射（相对 fit 的倍数 + 归一化中心），
     * 否则新图按它自己的 fit 显示。两种情况换图前后的取景都完全一致。
     */
    private fun setProcessedSource(file: File, bitmap: Bitmap? = null) {
        val activeView = pageView as? SubsamplingScaleImageView
        val enhanced = bitmap ?: decodeEnhancedBitmap(file)
        if (activeView == null || enhanced == null) {
            // 边界（动图/解码失败）：没法做分层，保留原图。
            // 绝不把视图重新 setImage —— 那会让 SubsamplingScaleImageView 丢弃当前位图、
            // 露出背景黑屏，正是「黑屏再替换」的成因之一。
            enhanced?.recycle()
            isVisible = true
            return
        }

        val oldScale = activeView.scale
        val oldCenter = activeView.center
        val oldSourceWidth = activeView.sWidth
        val oldSourceHeight = activeView.sHeight
        val oldMinScale = activeView.minScale

        clearProcessedSwapView()

        val swapView = newSubsamplingView()
        // SY --> 2026-10-18：换图视图绝不能被留成透明的 —— 淡出结束后它就是这个 holder 的
        // 常驻 pageView，下次重绑分块解码时透明的它就会露出阅读器底色（黑闪）。
        // 垫上本页预览；没有预览时留透明（露出阅读器底下的封面底图，不是纯色兜底）。
        val swapPreview = PagePreviewCache.get(previewKey)
        swapView.background = swapPreview?.let { BitmapDrawable(resources, it) }
        // SY <--
        // 新视图必须与正在显示的视图用同一套显示参数，否则换图瞬间会有裁边/缩放方式的跳变
        config?.let { cfg ->
            swapView.setMinimumScaleType(cfg.minimumScaleType)
            swapView.setCropBorders(cfg.cropBorders)
            swapView.setMinimumDpi(1)
            swapView.setDoubleTapZoomDuration(cfg.zoomDuration.getSystemScaledDuration())
        }
        // 关键：把增强图加在**最底层**（index 0），被原图完全盖住。
        // 它在底下偷偷加载，原图全程盖在上面、对读者完全可见也完全不动。
        addView(swapView, 0, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        processedSwapView = swapView
        outgoingProcessedView = activeView

        swapView.setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    // SY --> 与上游 animateProcessedSwap 对齐的视口继承逻辑：
                    //   · 用户**手动放大过**才做映射 —— 用「相对 fit 的放大倍数」
                    //     + 归一化中心，把同一块内容映射到新图上；
                    //   · 没放大过就什么都不动，让新图用自己的 fit 显示。
                    swapView.setupZoom(config)
                    val wasZoomed = oldScale > 0f &&
                        oldCenter != null &&
                        oldMinScale > 0f &&
                        oldSourceWidth > 0 &&
                        oldSourceHeight > 0 &&
                        oldScale > oldMinScale + 0.01f

                    if (wasZoomed && swapView.sWidth > 0 && swapView.sHeight > 0) {
                        val zoomFactor = oldScale / oldMinScale
                        val mappedCenter = PointF(
                            (oldCenter.x / oldSourceWidth) * swapView.sWidth,
                            (oldCenter.y / oldSourceHeight) * swapView.sHeight,
                        )
                        val mappedScale = (swapView.minScale * zoomFactor)
                            .coerceIn(swapView.minScale, swapView.maxScale)
                        swapView.setScaleAndCenter(mappedScale, mappedCenter)
                    } else if (isVisibleOnScreen()) {
                        swapView.landscapeZoom(true)
                    }
                    // SY <--

                    // 增强图已就绪（藏在原图下面）。把原图淡出露出增强图，露完再销毁原图 ——
                    // 中间永远有图挡着，不会出现「黑屏一闪再替换」的观感。
                    revealProcessedSwap()
                }

                override fun onImageLoadError(e: Exception) {
                    logcat(LogPriority.WARN, e) { "ReaderPageImageView: failed to load enhanced behind original" }
                    clearProcessedSwapView()
                }
            },
        )
        swapView.setImage(ImageSource.bitmap(enhanced))
        isVisible = true
    }


    /**
     * 原图（上层）淡出，露出底层已就绪的增强图；淡出结束后悄悄回收原图层。
     * 全程原图都在，换图瞬间没有任何空帧 / 黑屏。
     */
    private fun revealProcessedSwap() {
        val swapView = processedSwapView ?: return
        val outgoing = outgoingProcessedView ?: run {
            // 没有原图层可隐藏（理论上不会到这）：直接接管显示
            swapView.alpha = 1f
            pageView = swapView
            processedSwapView = null
            onImageLoaded()
            return
        }

        // 原图淡出，露出底层的增强图；淡出过程中增强图早已画好，绝无黑帧
        outgoing.animate()
            .alpha(0f)
            .setDuration(ENHANCEMENT_SWAP_FADE_MS)
            .withEndAction {
                // 隐藏原图图层
                outgoing.alpha = 0f
                pageView = swapView
                swapView.alpha = 1f
                // 原图已被增强图完全盖住，悄悄摘掉并回收
                removeView(outgoing)
                runCatching { outgoing.recycle() }
                processedSwapView = null
                outgoingProcessedView = null
                onImageLoaded()
            }
            .start()
    }

    private fun clearProcessedSwapView() {
        val swapView = processedSwapView
        val outgoing = outgoingProcessedView
        if (swapView != null) {
            if (pageView === swapView) {
                pageView = outgoing
            }
            // 取消可能还在进行的淡出动画：否则动画的 withEndAction 会在视图已被回收后
            // 才触发，把 pageView 指向一个已被 recycle 的视图。
            swapView.animate().cancel()
            runCatching { swapView.recycle() }
            removeView(swapView)
        }
        outgoing?.animate()?.cancel()
        outgoing?.alpha = 1f
        processedSwapView = null
        outgoingProcessedView = null
    }
    // endregion
    // SY <--

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            // SY --> 动图走这套视图，同样给白色占位背景（与 SSIV 路径一致），
            // 仅在创建时设一次，复用 holder 时保留，不落滑动热路径。
            setBackgroundColor(Color.WHITE)
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }

    companion object {
        /** 用户当前可见的主页 index；用于给后台增强排序（越靠近越先算）。 */
        @Volatile
        var currentGlobalPageIndex: Int = -1
    }
}

private const val MAX_ZOOM_SCALE = 5F

// SY --> 2026-10-02：`MAX_INPUT_PIXELS`（页面像素上限）已**删除**。
// 它曾用于「超过上限的页先降采样成 Bitmap 再交给 SSIV」，代价是放大发虚、条漫下裁切。
// 现统一走 SSIV 分块解码（BitmapRegionDecoder 只解可见 tile），内存与总像素无关，
// 因此不再需要任何像素上限。历史 OOM 结论与注意事项见 setImageCapped 上方注释。
// SY <--

// SY --> mihon_img_upscale 移植：增强轮询与过渡参数
private const val ENHANCEMENT_POLL_INTERVAL_MS = 400L
// SY --> 定稿/放弃之后的守候间隔。
//
// 轮询循环改成了「只要视图还挂着就一直守候」，所以不能在未出结果时按 400ms 无限空转。
// 已换好图、已被判定跳过、或已放弃（gaveUp）的页降到 1s 一次，
// 只做一次配置指纹比对 —— 用户换引擎/改参数时依然能在 1s 内被察觉并自动重算。
// SY <--
private const val ENHANCEMENT_IDLE_POLL_INTERVAL_MS = 1000L
// 补投额度：只对「用户当前正在读的这一页」生效，每 ENHANCEMENT_REQUEUE_INTERVAL_ATTEMPTS
// 次轮询才补投一次（约 1.2 秒一次），最多补投 3 次。
// 上限不放高是有意的：每次补投都是一次完整的重新推理，对一页注定失败的图反复重算，
// 本身就是用户看到的「重复超分」。额度用尽后仍然继续轮询，缓存一旦出现就换图。
private const val ENHANCEMENT_MAX_REQUEUE = 3
private const val ENHANCEMENT_REQUEUE_INTERVAL_ATTEMPTS = 3
private const val ENHANCEMENT_SWAP_FADE_MS = 120L
// SY <--

// SY --> 超分移植：把「这一页在增强缓存里的定位信息」交给调用方。
//
// 只要 enabled=false 就返回 null，调用方随即回到原生的「直接喂流」路径，
// 对没开增强的用户做到零行为变更。
// 章节 id 为空（尚未入库的本地/未保存章节）时不增强——缓存键需要稳定的 chapterId。
internal fun ReaderPage.enhanceKey(
    enabled: Boolean,
    variant: String = "",
): ReaderPageImageView.EnhanceKey? {
    if (!enabled) return null
    val chapterId = chapter.chapter.id ?: return null
    val mangaId = chapter.chapter.manga_id ?: return null
    return ReaderPageImageView.EnhanceKey(
        mangaId = mangaId,
        chapterId = chapterId,
        pageIndex = index,
        pageVariant = variant,
    )
}
// SY <--
