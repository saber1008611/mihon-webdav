package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    /** 正在交叉淡入的「增强图」视图；ready 后接管显示。 */
    private var processedSwapView: SubsamplingScaleImageView? = null

    /** 被替换掉的旧视图，淡入结束后回收。 */
    private var outgoingProcessedView: SubsamplingScaleImageView? = null

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
        background = pageBackground
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
            setNonAnimatedImage(source, config, enhanceKey, streamFn, readerPage)
        }
    }

    fun recycle() = pageView?.let {
        processingJob?.cancel()
        processingJob = null
        clearProcessedSwapView()
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        processingJob?.cancel()
        processingJob = null
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

        pageView = newSubsamplingView()
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

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
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
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
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                this@ReaderPageImageView.readerPage = readerPage
                val cachedFile = currentEnhancedFile(enhanceKey, prefs)
                if (cachedFile != null) {
                    setImage(ImageSource.inputStream(cachedFile.inputStream()))
                    isVisible = true
                    return@apply
                }

                setImage(ImageSource.inputStream(data.inputStream()))
                isVisible = true

                if (readerPage != null && controlsCurrentPageSelection) {
                    currentGlobalPageIndex = readerPage.index
                }
                // SY --> 只有「有资格决定当前页」的视图才更新目标页。条漫模式下这一步由
                // WebtoonViewer.onPageSelected 做（见 controlsCurrentPageSelection 的说明）。
                if (controlsCurrentPageSelection) {
                    ImageEnhancer.reprioritizeAround(enhanceKey.pageIndex, enhanceKey.pageVariant)
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
internal fun ReaderPage.enhanceKey(enabled: Boolean): ReaderPageImageView.EnhanceKey? {
    if (!enabled) return null
    val chapterId = chapter.chapter.id ?: return null
    val mangaId = chapter.chapter.manga_id ?: return null
    return ReaderPageImageView.EnhanceKey(
        mangaId = mangaId,
        chapterId = chapterId,
        pageIndex = index,
    )
}
// SY <--
