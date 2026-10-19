package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.platform.MaterialContainerTransform
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import kotlin.time.Duration.Companion.seconds

class ReaderActivity : BaseActivity() {

    private val graph: AppGraph by lazy { metroGraph() }

    companion object {
        /**
         * SY --> 离散模式（翻页 / 缩放）触摸后让超分「全停让路」的时长，1000ms。
         *
         * 原生侧在 ui_busy 为真时**完全停住**（waifu2x.cpp 里 while(ui_busy) sleep(8ms)，
         * 一块 tile 都不提交），从而把 GPU 让给 UI 渲染线程，消除翻页 / 缩放时的卡顿。
         * dispatchTouchEvent 每收到一个事件都会 setUiBusy(true) 并重置这个定时器。
         *
         * 注意（2026-09-22 修正 / Z4）：连续（条漫）模式**不再**走这套全停 —— 连续滚动时
         * 全停会让超分整段冻住（滑动时不工作、停下才工作）。连续模式改为「滑动期间持续超分
         * + 原生 tile 间 sleep 交错让出 GPU」，仅在抬起 / 取消时短暂全停（见
         * CONTINUOUS_FLING_COOLDOWN_MS）。此 1000ms 仅用于离散模式。
         * SY <--
         */
        private const val UI_BUSY_COOLDOWN_MS = 1000L

        /**
         * SY --> 连续模式「抬起 / 取消」后让超分全停的时长（ms），用于盖住惯性滑动尾巴。
         *
         * ⚠️ 2026-09-28 修复「滑动时超分不让路」：原值 **350ms 盖不住惯性滑动**。
         * 真机（Lenovo TB371FC / 骁龙870）实测一次 fling 常持续 1s 以上，350ms 后超分
         * 就恢复跑 —— 正好落在肉眼可见的惯性滚动里，抢 CPU 与内存带宽，
         * 直接表现为条漫严重掉帧（Janky(legacy) 33.42%、P50 10ms、绘制指令慢帧 183）。
         * 现提到 **900ms**，并由 [notifyViewerScrolling] 在视图仍在滚动时持续**续期**，
         * 直到真正停止滚动才恢复超分。
         * SY <--
         */
        private const val CONTINUOUS_FLING_COOLDOWN_MS = 900L

        /**
         * SY --> 视图滚动回调为「超分让路」续期的节流间隔（ms）。
         * 滚动每帧都会回调 [notifyViewerScrolling]，没必要每帧都 cancel + 重建协程。
         * SY <--
         */
        private const val SCROLL_YIELD_REARM_THROTTLE_MS = 120L

        /**
         * SY --> 2026-09-29：「打开 / 切换章节」后让超分**全停让路**的时长（ms）。
         *
         * 为什么需要：用户报「本地图源打开压缩包好慢」。真机取证（`su -c am start … ReaderActivity`）
         * 发现同一批书连开三次的耗时是 868ms / 695ms / **8452ms**，而那 8.4 秒的窗口里同时出现：
         *   · `thermal: CPU7temp 95.7 severity 3` ⇒ **CPU 重度限频**（采样到大核只有 960MHz，上限 3187MHz）；
         *   · `ImageEnhancementCache: Saved page 57/ 58/…` ⇒ **上一本书的「整话超分」还在满负荷跑**；
         *   · `Waifu2xNative: Processing complete … elapsed=585ms` ⇒ 每页约 0.6s GPU 满载。
         * 即：整话超分长时间满载 ⇒ 发热 ⇒ 降频 ⇒ 此时「开归档」这种要 CPU 解压 + FUSE/binder
         * 往返的操作全面变慢（数秒级）。
         *
         * 原先的让路只覆盖「滑动」（[onUiInteracted] / [notifyViewerScrolling]），**不覆盖「开章」**。
         * 现在每章页面就绪（[setChapters]）以及原地换书（[onNewIntent]）都补一段全停，
         * 把开章/首屏解码这几秒的 CPU/GPU 完整让给 UI；超分随后自动恢复继续追帧。
         * SY <--
         */
        private const val CHAPTER_OPEN_YIELD_MS = 2500L

        /**
         * SY --> 左下角超分状态的刷新间隔，200ms。
         *
         * 数值直接对齐 TachiyomiX 的实现：它的 `produceState` 内部就是
         * `while(true) { value = Waifu2x.getProgressPercent(); delay(200) }`。
         * 百分比只能靠轮询拿（原生侧只暴露一个 getter），200ms 是观感与开销的平衡点。
         * SY <--
         */
        private const val ENHANCEMENT_STATUS_POLL_INTERVAL_MS = 200L

        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    @Inject private lateinit var readerPreferences: ReaderPreferences

    @Inject private lateinit var preferences: BasePreferences

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel> { graph.viewModelFactory }
    private var assistUrl: String? = null

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private var readingModeToast: Toast? = null
    private val displayRefreshHost = DisplayRefreshHost()

    /** SY --> 超分「让路」定时任务（移植自 mihon_img_upscale）：触摸后延迟复位 ui_busy。 */
    private var uiBusyJob: Job? = null
    // SY <--

    // SY --> 2026-09-28 修复「滑动时超分不让路」新增：
    /** ui_busy 当前是否已置位（避免滚动每帧重复下发 JNI）。 */
    private var uiBusyFlagSet = false
    /** 上次（重新）预约让路复位的时刻，用于节流。 */
    private var lastYieldArmMs = 0L
    // SY <--

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    private var loadingIndicator: ReaderProgressIndicator? = null

    // SY --> 2026-10-18：打开章节加载期背景。
    //
    // 「打开漫画/章节黑屏」发生在章节加载期：源还没返回页列表、页数据也还没下载，此时屏幕上
    // 没有任何 holder、也没有页面数据可做低清预览，只有 readerContainer 的底色（深色主题 = 纯黑）。
    // 这里垫一张本作封面（抽样到很小再放大 ⇒ 天然模糊，再压暗），既不是纯黑也不是纯白。
    // 页面画出来之后封面被完全盖住；只有「没东西可画」的地方（加载窗口、页间间隙）才露出来。
    private var loadingBackdrop: Drawable? = null
    private var loadingBackdropPrepared = false
    // SY <--

    var isScrollingThroughPages = false
        private set

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        graph.inject(this)
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        binding = ReaderActivityBinding.inflate(layoutInflater)

        setContentView(binding.root)
        binding.setComposeOverlay()

        if (!viewModel.hasValidArgs) {
            finish()
            return
        }

        NotificationReceiver.dismissNotification(
            this,
            viewModel.mangaId.hashCode(),
            Notifications.ID_NEW_CHAPTERS,
        )

        config = ReaderConfig()

        // SY --> 预热当前选中的超分模型（移植自 mihon_img_upscale）。
        // 原生 load() 首次调用要解 param/bin、建 Vulkan 卷积 pipeline，耗时几百毫秒；
        // 把这段代价从「第一页的等待」挪到刚进阅读器时、放在 IO 线程上，不阻塞首帧。
        // ensureLoaded 内部 per-model 幂等，解码器随后再调用只会命中缓存分支。
        if (readerPreferences.realCuganEnabled().get()) {
            lifecycleScope.launchIO {
                val model = Waifu2x.sanitizeModel(readerPreferences.realCuganModel().get())
                val scale = ImageEnhancementCache.getEffectiveScale(
                    model,
                    readerPreferences.realCuganScale().get(),
                )
                Waifu2x.ensureLoaded(
                    context = this@ReaderActivity,
                    model = model,
                    noise = readerPreferences.realCuganNoiseLevel().get(),
                    scale = scale,
                    tileSleepMs = when (readerPreferences.realCuganPerformanceMode().get()) {
                        1 -> 5
                        2 -> 15
                        else -> 0
                    },
                    tileSize = readerPreferences.realCuganTileSize().get(),
                    precision = Waifu2x.sanitizePrecision(readerPreferences.realCuganPrecision().get()),
                    fp16Arithmetic = readerPreferences.realCuganFp16Arithmetic().get(),
                    // SY --> engineId 实参已删除：MihonAc 完美版只有一套引擎。SY <--
                )
            }
        }
        // SY <--

        // SY --> 移植自 TachiyomiX：左下角的全局超分状态显示（文案 + 两条进度条）。
        // SY <--
        startEnhancementStatusObserver()

        setMenuVisibility(viewModel.state.value.menuVisible)

        // Finish when incognito mode is disabled
        preferences.incognitoMode.changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.initError }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setInitialChapterError)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach(::setProgressDialog)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            // SY --> 2026-09-29 OOM 排查加固：此前这里是裸 `onEach { updateViewer() }`。
            // 一旦 updateViewer() 抛异常（例如 setOrientation / getMangaReadingMode 出问题），
            // 整个 collector 会静默终止 —— 之后换书**再也不会重建 viewer**，旧 viewer 的视图树
            // 会一直留在 viewerContainer 里（连带每个页面 holder 的整页位图，单页 25MB+），
            // 表现为「Views 计数随换书单调增长 + Java 堆每次跳一档」。
            // 包一层 runCatching：异常不再杀死 collector，且一定留痕。
            .onEach {
                runCatching { updateViewer() }
                    .onFailure { e -> logcat(LogPriority.ERROR, e) { "ReaderActivity: updateViewer 失败" } }
            }
            .launchIn(lifecycleScope)
        // SY <--

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach(::setChapters)
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page)
                    }
                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }
                    // SY --> 2026-09-29 Part 2：整部看完 → 自动打开下一部漫画。
                    // 用 newIntent（带 FLAG_ACTIVITY_CLEAR_TOP）重启阅读器：
                    // 不堆叠多个 ReaderActivity，且等价于「无缝接着看下一部」。
                    is ReaderViewModel.Event.OpenNextManga -> {
                        openNextManga(event.mangaId, event.chapterId)
                    }
                    // SY <--
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        val state by viewModel.state.collectAsState()
        val showPageNumber by readerPreferences.showPageNumber.collectAsState()
        val settingsviewModel = remember {
            ReaderSettingsViewModel(
                readerState = viewModel.state,
                onChangeReadingMode = viewModel::setMangaReadingMode,
                onChangeOrientation = viewModel::setMangaOrientationType,
                preferences = readerPreferences,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!state.menuVisible && showPageNumber) {
                ReaderPageIndicator(
                    currentPage = state.currentPage,
                    totalPages = state.totalPages,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }

            ContentOverlay(state = state)

            AppBars(state = state)
        }

        val onDismissRequest = viewModel::closeDialog
        when (state.dialog) {
            is ReaderViewModel.Dialog.Loading -> {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(MR.strings.loading))
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.Settings -> {
                ReaderSettingsDialog(
                    onDismissRequest = onDismissRequest,
                    onShowMenus = { setMenuVisibility(true) },
                    onHideMenus = { setMenuVisibility(false) },
                    viewModel = settingsviewModel,
                )
            }
            is ReaderViewModel.Dialog.ReadingModeSelect -> {
                ReadingModeSelectDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsviewModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        if (!readerPreferences.showReadingMode.get()) {
                            menuToggleToast = toast(stringRes)
                        }
                    },
                )
            }
            is ReaderViewModel.Dialog.OrientationModeSelect -> {
                OrientationSelectDialog(
                    onDismissRequest = onDismissRequest,
                    viewModel = settingsviewModel,
                    onChange = { stringRes ->
                        menuToggleToast?.cancel()
                        menuToggleToast = toast(stringRes)
                    },
                )
            }
            is ReaderViewModel.Dialog.PageActions -> {
                ReaderPageActionsDialog(
                    onDismissRequest = onDismissRequest,
                    onSetAsCover = viewModel::setAsCover,
                    onShare = viewModel::shareImage,
                    onSave = viewModel::saveImage,
                )
            }
            null -> {}
        }
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        uiBusyJob?.cancel()
        Waifu2x.setUiBusy(false)
        uiBusyFlagSet = false
        super.onDestroy()
        viewModel.state.value.viewer?.destroy()
        config = null
        menuToggleToast?.cancel()
        readingModeToast?.cancel()
    }

    override fun onPause() {
        lifecycleScope.launchNonCancellable {
            viewModel.updateHistory()
        }
        super.onPause()
    }

    /**
     * SY --> 超分「让路」机制（移植自 mihon_img_upscale / TachiyomiX）。
     *
     * 阅读器里每一个触摸事件都会经过这里：先把原生超分标记为 busy（GPU 推理循环在
     * ui_busy 为真时完全停住、一块 tile 都不提交），再用一个 400ms 定时器复位。
     * 这样翻页 / 缩放 / 横滑时 GPU 全让给 UI 渲染线程，消除卡顿；手指停下 400ms 后
     * 超分自动恢复，不浪费空闲算力。
     *
     * ⚠️ 条漫 / 竖向连续模式是**例外**，见 [onUiInteracted]。
     * SY <--
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        onUiInteracted(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * SY --> 触摸交互 → 让超分给 UI 让路。
     *
     * 【2026-09-23 方案A 落地（出厂默认）】
     * 真机实测结论：这台机型（天玑9400 / Immortalis-G925）上「滚动期边滑边超分」
     * 不可行 —— 楔死帧数只与超分 GPU **提交次数**相关，把让出量放大到 GPU 占空比
     * 仅 5% 仍残留 11~13% 掉帧、且单页慢 22 倍；同一支二进制段间交替切换后，
     * 全停把掉帧压到 0.2~0.4%、4950ms 冻结帧从 48/52 降到 4/10。
     * 因此**连续模式也在滚动期间全停超分**（即 TachiyomiX 的做法）。
     * 「滑动中看不到超分、停下才继续」是**预期取舍**，不是 bug。
     * 实测对照见 output\方案D实测-天玑9400-20260923.md。
     *
     * 分工：
     *  - **离散（翻页 / 缩放）模式**：触摸即 setUiBusy(true)，冷却 UI_BUSY_COOLDOWN_MS
     *    后恢复（翻页 / 缩放期间整段暂停，体验更好）。
     *  - **连续（条漫）模式**：滑动期间只刷新交互时间戳 [Waifu2x.notifyInteraction]，
     *    由**原生** interaction_full_stop() 在 tile 边界处全停 —— 好处是不会打断
     *    正在提交的那一块，比 Kotlin 侧反复 setUiBusy 更平滑（不会把正在爬升的
     *    批量知识清零）；抬起 / 取消时再补一段全停（CONTINUOUS_FLING_COOLDOWN_MS）
     *    盖住惯性滑动尾巴。停下超过原生的 ~800ms 交互窗口后自动恢复全速追帧。
     *
     * continuous_mode 按当前阅读模式实时同步给原生，保证一致。
     * SY <--
     */
    private fun onUiInteracted(ev: MotionEvent? = null) {
        val action = ev?.actionMasked
        val continuous = isContinuousReadingMode()
        Waifu2x.setContinuousMode(continuous)
        if (continuous) {
            // 触摸中：刷新原生交互时间戳（让原生在 tile 边界平滑让出，不打断正在提交的块）。
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                // SY 2026-09-29：超分让路关闭时不刷新原生交互窗口（原生不会在 tile 边界让出），
                // 否则原生侧仍会按交互时间戳降速，违背「关闭后一直超分」。
                if (readerPreferences.upscaleYieldEnabled().get()) {
                    Waifu2x.notifyInteraction()
                }
            }
            // SY --> 修复「滑动时超分不让路」：连续模式**同样走全停**。
            // 旧逻辑在 DOWN/MOVE 只调 notifyInteraction()（原生仅部分让出），UP 后才全停
            // 350ms —— 超分其实一直没真正停下来。现在只要还在交互就全停并持续续期。
            // SY <--
            armUiBusyYield(CONTINUOUS_FLING_COOLDOWN_MS)
            return
        }
        // 离散模式：触摸即全停让路（翻页 / 缩放期间整段暂停），冷却后恢复。
        armUiBusyYield(UI_BUSY_COOLDOWN_MS)
    }

    /**
     * SY --> 让超分「全停让路」[delayMs]，冷却结束后恢复。
     *
     * 原生侧 ui_busy 为真时**一块 tile 都不提交**，从而把 GPU / CPU 让给 UI 渲染线程。
     * 带节流：[notifyViewerScrolling] 会随滚动每帧回调，没必要每帧都 cancel + 重建协程；
     * 且 ui_busy 已置位时不再重复下发 JNI。
     * SY <--
     */
    private fun armUiBusyYield(delayMs: Long, force: Boolean = false) {
        // SY 2026-09-29：超分让路开关——关闭时立即清掉让路状态，超分一直全速跑。
        // 所有让路入口（触摸/滚动续期/开章 force/菜单打开）都经这里，开关实时读取。
        if (!readerPreferences.upscaleYieldEnabled().get()) {
            uiBusyJob?.cancel()
            uiBusyJob = null
            if (uiBusyFlagSet) {
                Waifu2x.setUiBusy(false)
                uiBusyFlagSet = false
            }
            return
        }
        if (!uiBusyFlagSet) {
            Waifu2x.setUiBusy(true)
            uiBusyFlagSet = true
        }
        val now = android.os.SystemClock.uptimeMillis()
        // force=true 用于「开章」这类一次性、且需要**完整时长**的场景（不受滚动续期节流影响）。
        if (!force && uiBusyJob != null && now - lastYieldArmMs < SCROLL_YIELD_REARM_THROTTLE_MS) return
        lastYieldArmMs = now
        uiBusyJob?.cancel()
        uiBusyJob = lifecycleScope.launch {
            delay(delayMs)
            if (!viewModel.state.value.menuVisible) {
                Waifu2x.setUiBusy(false)
                uiBusyFlagSet = false
            }
        }
    }

    /**
     * SY --> 视图**仍在滚动**（含惯性 / fling）时由 viewer 回调，持续为「超分让路」续期，
     * 直到真正停止滚动才让超分恢复。
     *
     * 这是「滑动时超分不让路」的主因修复：fling 期间**没有任何触摸事件**，旧逻辑只能靠
     * UP 后那段固定 350ms 冷却，而一次 fling 常持续 1s 以上 —— 超分在惯性滚动的
     * 后半段就恢复跑了，抢 CPU 与内存带宽，表现为条漫严重掉帧。
     * SY <--
     */
    fun notifyViewerScrolling() {
        if (!isContinuousReadingMode()) return
        armUiBusyYield(CONTINUOUS_FLING_COOLDOWN_MS)
        // SY --> 2026-09-28：顺带刷新「滑动门控」，让网络取图在滑动期间让路。
        // 用户需求：「让 webdav 按顺序取图，取完图再滑，**滑动时不取图**」——
        // 取图（下载 / 解码 / 落盘）与滚动渲染同时进行会抢 CPU、IO 与内存带宽，
        // 这正是未缓存的 WebDAV 滑动掉帧的直接原因。见 [ReaderScrollGate]。
        // SY <--
        ReaderScrollGate.notifyScrolling()
    }

    /**
     * SY --> 当前阅读器是否为「连续滚动」（条漫 WEBTOON / 竖向连续 CONTINUOUS_VERTICAL）。
     * 直接用阅读模式偏好判断：两种模式在 [ReadingMode.toViewer] 里都映射到连续型 viewer，
     * 语义一一对应。SY <--
     */
    private fun isContinuousReadingMode(): Boolean =
        ReadingMode.fromPreference(viewModel.getMangaReadingMode()).type == ReadingMode.ViewerType.Webtoon

    // SY --> 2026-09-23: 原「A/D 对照开关」（读 Settings.Global.waifu2x_continuous_stop）
    // 已删除。该 ROM 上 Kotlin 读 Settings.Global 实测不生效（设置后冷却仍是 350ms），
    // A 段其实是靠原生属性才切成功的，故此处是死代码。
    // 现在 A（滚动期全停超分）为**出厂默认**；D 仅供对照实验，切换统一走原生属性：
    //   adb shell setprop debug.waifu2x.ui_stop_ms 1   → A（默认，无需设置）
    //   adb shell setprop debug.waifu2x.ui_stop_ms 0   → D（对照实验）
    // SY <--

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()
        setMenuVisibility(viewModel.state.value.menuVisible)
    }

    /**
     * SY --> 2026-09-29：⚠️ 这里曾试过「把阅读器窗口刷新率锁到 60Hz」（`preferredDisplayModeId`），
     * 用户明确否决 —— **不能锁帧**（同源的在线源在 144Hz 下并不卡，说明渲染器本身跑得动高刷；
     * 高刷预算不足不是真因）。代码已彻底移除，不要再加回来。
     * SY <--
     */

    /**
     * SY --> 2026-09-29 Part 2：本 Activity 在 manifest 里是 `launchMode="singleTask"`。
     *
     * 「本作读完 → 打开下一部」用 [newIntent] 再 startActivity 时，系统判定为
     * `START_DELIVERED_TO_TOP`（真机 ATM 日志实测 `result code=3`）：intent 交给**已有实例**的
     * `onNewIntent`，**不会新建实例**。此前没有覆写本方法 ⇒ 新 manga/chapter 参数被静默丢弃，
     * 表现就是「完全没有自动接上」。
     *
     * 现在在这里接手：委托 ViewModel **原地换书**（同一个 ViewModel 内换 manga/source/loader，
     * 不重启 Activity，也就没有黑屏/闪一下，真正意义上的「无缝」）。
     * SY <--
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val newMangaId = intent.getLongExtra("manga", -1L)
        val newChapterId = intent.getLongExtra("chapter", -1L)
        if (newMangaId <= 0L || newChapterId <= 0L) return
        if (newMangaId == viewModel.mangaId) return

        logcat(LogPriority.INFO) {
            "ReaderActivity: onNewIntent 原地换书 mangaId=$newMangaId chapterId=$newChapterId"
        }
        setIntent(intent)
        // 换书成功后再读到末尾，应能再次触发「接下一部」。
        nextMangaOpened = false
        // 触发方（viewer 在末尾）会顺手 showMenu()，换书时把它收起来，免得新书一上来就盖着菜单。
        hideMenu()
        // SY --> 2026-09-29：换书要重新开归档 + 建 loader，这段最需要 CPU/IO；
        // 先把超分全停（见 [CHAPTER_OPEN_YIELD_MS]），否则上一本仍在跑的整话超分会把
        // 机器烤到限频、把开书拖到数秒级。setChapters 之后还会再续一次。
        armUiBusyYield(CHAPTER_OPEN_YIELD_MS, force = true)
        // SY <--
        viewModel.switchToManga(newMangaId, newChapterId)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewModel.state.value.viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange.collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter.collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue.collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode.collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    @Composable
    fun AppBars(state: ReaderViewModel.State) {
        val isHttpSource = state.source is HttpSource

        val cropBorderPaged by readerPreferences.cropBorders.collectAsState()
        val cropBorderWebtoon by readerPreferences.cropBordersWebtoon.collectAsState()
        val isPagerType = ReadingMode.isPagerType(viewModel.getMangaReadingMode())
        val cropEnabled = if (isPagerType) cropBorderPaged else cropBorderWebtoon

        val verticalNavigatorModes by readerPreferences.verticalNavigator.collectAsState()
        val verticalNavigator = verticalNavigatorModes.contains(
            ReadingMode.fromPreference(viewModel.getMangaReadingMode()),
        )
        val verticalNavigatorOnLeft by readerPreferences.verticalNavigatorOnLeft.collectAsState()
        val verticalNavigatorHeight by readerPreferences.verticalNavigatorHeight.collectAsState()

        ReaderAppBars(
            visible = state.menuVisible,

            mangaTitle = state.manga?.title,
            chapterTitle = state.currentChapter?.chapter?.name,
            navigateUp = onBackPressedDispatcher::onBackPressed,
            onClickTopAppBar = ::openMangaScreen,
            bookmarked = state.bookmarked,
            onToggleBookmarked = viewModel::toggleChapterBookmark,
            onOpenInWebView = ::openChapterInWebView.takeIf { isHttpSource },
            onOpenInBrowser = ::openChapterInBrowser.takeIf { isHttpSource },
            onShare = ::shareChapter.takeIf { isHttpSource },

            chapterNavigatorType = if (!verticalNavigator) {
                if (state.viewer is R2LPagerViewer || (state.viewer as? WebGpuViewer)?.isReversed ?: false) {
                    ChapterNavigatorType.HORIZONTAL_RTL
                } else {
                    ChapterNavigatorType.HORIZONTAL_LTR
                }
            } else {
                if (verticalNavigatorOnLeft) {
                    ChapterNavigatorType.VERTICAL_LEFT
                } else {
                    ChapterNavigatorType.VERTICAL_RIGHT
                }
            },
            verticalNavigatorHeight = verticalNavigatorHeight / 100f,
            onNextChapter = ::loadNextChapter,
            enabledNext = state.viewerChapters?.nextChapter != null,
            onPreviousChapter = ::loadPreviousChapter,
            enabledPrevious = state.viewerChapters?.prevChapter != null,
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            onPageIndexChange = {
                isScrollingThroughPages = true
                moveToPageIndex(it)
            },
            onPageIndexChangeFinished = {
                isScrollingThroughPages = false
            },

            readingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = false),
            ),
            onClickReadingMode = viewModel::openReadingModeSelectDialog,
            orientation = ReaderOrientation.fromPreference(
                viewModel.getMangaOrientation(resolveDefault = false),
            ),
            onClickOrientation = viewModel::openOrientationModeSelectDialog,
            cropEnabled = cropEnabled,
            onClickCropBorder = {
                val enabled = viewModel.toggleCropBorders()
                menuToggleToast?.cancel()
                menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
            },
            onClickSettings = viewModel::openSettingsDialog,
        )
    }

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        if (visible) {
            // SY --> 菜单（含设置面板）打开时让超分彻底停住，避免推理抢 GPU 导致菜单卡顿。
            // 随「超分让路」开关：关闭让路时保持超分全速（用户要求关闭后一直超分）。
            if (readerPreferences.upscaleYieldEnabled().get()) {
                uiBusyJob?.cancel()
                Waifu2x.setUiBusy(true)
            }
            // SY <--
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            // SY --> 菜单关闭即视为一次交互：先让路 400ms 再复位（见 onUiInteracted）。
            onUiInteracted()
            // SY <--
            if (readerPreferences.fullscreen.get()) {
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * SY --> 移植自 TachiyomiX：左下角**全局**超分状态显示。
     *
     * 参考实现（`ReaderPageIndicatorKt.ReaderProcessingStatusIndicator`）的逻辑：
     *   1. `activePageIndex < 0` → 直接不渲染（空闲即隐藏）；
     *   2. 文案 = 有排队 ? 「增强中 第 N 页 · P% · 待处理 Q 页」: 「增强中 第 N 页 · P%」，N 从 1 开始；
     *   3. 两条 3dp 细进度条：本页 P/100、整批 processed/batch；
     *   4. P 来自 `Waifu2x.getProgressPercent()`，每 200ms 轮询一次。
     *
     * 本工程是 View 体系（不是 Compose），所以用一个 200ms 的轮询循环达成同样的效果。
     * 只有「开关打开 + 确实在算」时才显示 —— 开关沿用原有的
     * `pref_realcugan_show_status`，与超分总开关 `realCuganEnabled` 一起生效。
     *
     * 这里刻意**逐页状态文案全部交给这一个全局视图**，`ReaderPageImageView` 不再各自
     * 维护「排队中 / 收尾中 / 已增强 / 原图」—— 那正是「增强中 → 排队中 → 排队中 →
     * 已增强」来回跳的来源。
     * SY <--
     */
    private fun startEnhancementStatusObserver() {
        val statusView = binding.enhancementStatus
        lifecycleScope.launchIO {
            var lastSignature: String? = null
            var lastVisible = false
            while (isActive) {
                val enabled = readerPreferences.realCuganEnabled().get() &&
                    readerPreferences.realCuganShowStatus().get()
                val status = ImageEnhancer.status.value
                val visible = enabled && status.activePageIndex >= 0

                val percent = if (visible) Waifu2x.getProgressPercent().coerceIn(0, 100) else 0
                val signature = if (visible) {
                    "${status.activePageIndex}|${status.queuedCount}|" +
                        "${status.sessionProcessedCount}|${status.sessionBatchCount}|$percent"
                } else {
                    null
                }

                if (signature != lastSignature || visible != lastVisible) {
                    lastSignature = signature
                    lastVisible = visible
                    withUIContext {
                        statusView.isVisible = visible
                        if (visible) {
                            statusView.render(status, percent)
                        }
                    }
                }
                delay(ENHANCEMENT_STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val prevViewer = viewModel.state.value.viewer
        val newViewer = ReadingMode.toViewer(viewModel.getMangaReadingMode(), this)

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        // SY --> 2026-09-29 OOM 修复：**无条件**清空 viewerContainer。
        // 上游只在 `prevViewer != null` 时才 removeAllViews；但「原地换书」会反复调到这里，
        // 一旦上一次中途抛异常（见 manga collector 的 runCatching）或 prevViewer 为 null，
        // 容器里就会**残留旧 viewer 的视图树**——它带着每个页面 holder 的整页位图
        // （单页 25MB+，实测 OOM 分配点即 SSIV 的 TilesInitTask），且因为一直 attached
        // 而永远不会进 GC。真机实测：Views 计数随换书单调增长（13→71→101→128），
        // Java 堆每次换书跳一档（24→204→262→394MB）。这里必须先保证容器干净。
        logcat(LogPriority.INFO) {
            "updateViewer: 重建 viewer（prev=${prevViewer?.javaClass?.simpleName} " +
                "viewerContainer.childCount=${binding.viewerContainer.childCount} " +
                "readerContainer.childCount=${binding.readerContainer.childCount}）"
        }
        if (prevViewer != null) {
            prevViewer.destroy()
        }
        binding.viewerContainer.removeAllViews()
        // SY <--
        viewModel.onViewerLoaded(newViewer)
        updateViewerInset(readerPreferences.fullscreen.get(), readerPreferences.drawUnderCutout.get())
        binding.viewerContainer.addView(newViewer.getView())

        if (readerPreferences.showReadingMode.get()) {
            showReadingModeToast(viewModel.getMangaReadingMode())
        }

        // SY --> 2026-09-29 OOM 修复：**先移除上一个 loadingIndicator**。
        // `ReaderProgressIndicator` 是 Compose 视图（AbstractComposeView，组合策略为
        // DisposeOnDetachedFromWindow），而旧代码每次 updateViewer 都 new 一个再 addView，
        // 只由 setChapters 移除「最新的那一个」⇒ 换书时旧的会**永久 attached** 在
        // readerContainer 上：既算活视图，其 Compose 组合也永不 dispose。
        loadingIndicator?.let { binding.readerContainer.removeView(it) }
        loadingIndicator = ReaderProgressIndicator(this)
        binding.readerContainer.addView(loadingIndicator)
        // SY <--

        startPostponedEnterTransition()

        // SY --> Komiho Phase12: 阅读器模式确定后，把连续（条漫）模式标志同步给原生，
        // 让「滑动期间 GPU 交错让出」在原生侧正确生效（onUiInteracted 每触摸也会再同步一次）。
        Waifu2x.setContinuousMode(isContinuousReadingMode())
        // SY <--
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        val source = viewModel.getSource() ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, source.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(intent)
        }
    }

    private fun showReadingModeToast(mode: Int) {
        try {
            readingModeToast?.cancel()
            readingModeToast = toast(ReadingMode.fromPreference(mode).stringRes)
        } catch (_: ArrayIndexOutOfBoundsException) {
            logcat(LogPriority.ERROR) { "Unknown reading mode: $mode" }
        }
    }

    // SY --> 2026-10-18：加载期封面背景（见 [loadingBackdrop] 注释）。
    //
    // 只解码很小的一张：封面抽样到 ~72px 宽后用 FILL + 线性插值铺满屏幕，放大本身就带来模糊，
    // 再叠一层半透明黑压暗；纯 CPU、几毫秒、单次，不额外引入依赖（项目里没有 blur 工具）。
    private fun prepareLoadingBackdrop() {
        if (loadingBackdropPrepared) return
        val file = viewModel.loadingBackdropFile() ?: return
        loadingBackdropPrepared = true
        lifecycleScope.launchIO {
            val backdrop = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sampleSize = 1
                while (bounds.outWidth / (sampleSize * 2) >= 72) {
                    sampleSize *= 2
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return@runCatching null
                val cover = BitmapDrawable(resources, bitmap).apply {
                    gravity = Gravity.FILL
                    isFilterBitmap = true
                }
                LayerDrawable(arrayOf(cover, ColorDrawable(0xA6000000.toInt())))
            }.getOrNull() ?: return@launchIO
            withUIContext {
                loadingBackdrop = backdrop
                updateReaderBackground()
            }
        }
    }

    /** 重设阅读器底色：有加载期封面就用它，否则用主题色。 */
    private fun updateReaderBackground() {
        val backdrop = loadingBackdrop
        binding.readerContainer.background = backdrop
            ?: ColorDrawable(baseContext.readerBackgroundColor(readerPreferences.readerTheme.get()))
    }
    // SY <--

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        binding.readerContainer.removeView(loadingIndicator)
        viewModel.state.value.viewer?.setChapters(viewerChapters)

        // SY --> 2026-09-29：开章 / 切章（含原地换书）后让超分全停数秒。
        // 页面就绪的这一刻正是首屏解码 + 贴图最需要 CPU/GPU 的时候，而整话超分此时往往
        // 正在满负荷跑（并已把机器烤到限频）。见 [CHAPTER_OPEN_YIELD_MS]。
        armUiBusyYield(CHAPTER_OPEN_YIELD_MS, force = true)
        // SY <--

        lifecycleScope.launchIO {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (show) {
            viewModel.showLoadingDialog()
        } else {
            viewModel.closeDialog()
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewModel.state.value.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadNextChapter() {
        lifecycleScope.launch {
            viewModel.loadNextChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadPreviousChapter() {
        lifecycleScope.launch {
            viewModel.loadPreviousChapter()
            moveToPageIndex(0)
        }
    }

    // SY --> 2026-09-29 Part 2：整部看完 → 打开下一部漫画。
    //
    // 用 [ReaderActivity.newIntent] 重启阅读器（intent 带 FLAG_ACTIVITY_CLEAR_TOP，
    // 不会堆叠多个阅读器实例）。
    // 只做一次（用 nextMangaOpened 去重）：避免末尾反复触发导致连开多个。
    private var nextMangaOpened = false

    private fun openNextManga(mangaId: Long, chapterId: Long) {
        if (nextMangaOpened) return
        nextMangaOpened = true
        logcat(LogPriority.INFO) { "ReaderActivity: 本作已读完，衔接下一部 mangaId=$mangaId chapterId=$chapterId" }
        startActivity(newIntent(this, mangaId, chapterId))
    }

    /**
     * 由 viewer 在「滑到末尾且已无下一话」时调用：请求衔接下一部漫画。
     */
    fun requestNextMangaOnFinish() {
        viewModel.requestNextMangaOnFinish()
    }
    // SY <--

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage) {
        viewModel.onPageSelected(page)
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage) {
        viewModel.openPageDialog(page)
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launchIO { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    private fun onShareImageResult(uri: Uri, page: ReaderPage) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number),
        )
        startActivity(intent)
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    /**
     * SY 2026-10-09 双页移植：页面布局 = 「自动」时，横屏双页 / 竖屏单页要跟着朝向切换。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (viewModel.state.value.viewer as? eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer)
            ?.refreshAutomaticPageLayout()
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        /*
         * Initializes the reader subscriptions.
         */
        init {
            readerPreferences.readerTheme.changes()
                .onEach { updateReaderBackground() }
                .launchIn(lifecycleScope)

            // SY --> 2026-10-18：manga 一到手就准备加载期封面背景（越早越好，赶在首屏黑屏之前）。
            viewModel.state
                .map { it.manga }
                .filterNotNull()
                .distinctUntilChanged()
                .onEach { prepareLoadingBackdrop() }
                .launchIn(lifecycleScope)
            // SY <--

            readerPreferences.keepScreenOn.changes()
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness.changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale.changes(),
                readerPreferences.invertedColors.changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen.changes(),
                readerPreferences.drawUnderCutout.changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)

            // SY 2026-09-29：超分让路开关——关闭时立即清掉当前让路状态（点击后立即生效，
            // 不等下一次触摸/滚动）。
            readerPreferences.upscaleYieldEnabled().changes()
                .onEach { enabled ->
                    if (!enabled) {
                        uiBusyJob?.cancel()
                        uiBusyJob = null
                        Waifu2x.setUiBusy(false)
                        uiBusyFlagSet = false
                    }
                }
                .launchIn(lifecycleScope)

            // SY 2026-09-29：「预载页数」变化后立即对当前条漫阅读器重设预绑定窗口
            // （点击即生效，无需重开章节；翻页模式由 PagerConfig 的监听走 refreshAdapter）。
            readerPreferences.realCuganPreloadSize().changes()
                .onEach {
                    (viewModel.state.value.viewer as? WebtoonViewer)?.refreshPreloadWindow()
                }
                .launchIn(lifecycleScope)
            // SY <--
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                readerPreferences.customBrightnessValue.changes()
                    .sample(0.1.seconds)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(value)
        }
        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }
}
