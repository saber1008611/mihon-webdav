package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.ActivityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.PagePreviewCache
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import eu.kanade.tachiyomi.util.waifu2x.enhancementConfigHash
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true) : Viewer {

    val graph by lazy { activity.appGraph }
    val downloadManager by lazy { graph.downloadManager }
    val readerPreferences by lazy { graph.readerPreferences }

    private val scope = MainScope()

    // SY --> 2026-10-18：预览窗口预取（跟随阅读位置）。
    //
    // 为什么需要：只有「预览已经在内存里」时，holder 绑定后的那几帧才不是底色。
    // 长距离快滑时 RecyclerView 一次会绑好几页，而按需生成一页预览要读超分 webp（2250×3200）
    // 再低清解码，几十到上百毫秒；真机实测（`ls.mp4`，长距离快滑）赶不上时屏幕上会出现
    // 一段「暗糊」的封面底图（sharpness 1.2~1.6 vs 内容帧 16~38）。
    //
    // 用**单个** worker 服务「最新窗口」：新窗口一到就丢弃旧窗口的剩余项，
    // 避免滑动过程中堆积出几十个并发解码（那些解码很吃内存和 IO）。
    private val previewWindow = AtomicReference<List<PagePreviewCache.Key>?>(null)
    private var previewWorker: Job? = null
    private var previewAnchorIndex = -1

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * SY --> 条漫预绑定窗口（离屏多远就开始绑定 holder、提前下载/解码）。
     *
     * 2026-09-26 曾把它从上游的 3/4 屏放大到 2 屏以压住「底部黑图」——但随后确认黑图/白图的
     * 真凶是「占位底色被清成透明、透出阅读器主题色」（见 ReaderPageImageView.onImageLoaded 的修正），
     * 而不是预绑定窗口太小。放大的窗口只带来副作用：更多离屏 holder 同时绑定 ⇒ RenderThread 的
     * DrawFrames（GPU 贴图 + 缓冲队列）被白白占用，是阅读器残余掉帧的主因。
     *
     * 现在改回上游默认 **3/4 屏**：离屏贴图量回到与上游一致的水平，RenderThread 压力明显下降；
     * 占位底色已是固定白色（与漫画正文同调，见 WebtoonPageHolder 的 init）、且出图前不再被
     * 清成透明，所以窗口变小也不会再看到旧式整片黑/白闪——最多只是新页在下载途中短暂露出
     * 白色占位底（不刺眼，且深色主题下不再透黑）。
     * 与点按滚动距离（scrollDistance = 3/4 屏）解耦，不改变点按翻页行为。
     *
     * 2026-09-28：再 **3/4 屏 → 1/2 屏**。用户仍反馈残余卡顿，而 09-27 已确认瓶颈在
     * RenderThread 离屏贴图量。散图预取窗口同期已提到 10 页（WebDav/LocalImageFolderPageLoader），
     * 预取不再依赖大绑定窗口，故可继续收窗口降贴图。
     *
     * ⚠️ 2026-09-29：**这个字段只是 `layoutManager` 的构造占位值** —— 真正生效的窗口由
     * [applyPreloadWindow] 在每次 `setChapters` 时按 [preloadWindowPx]（「预载页数」偏好）
     * 覆盖。别在这里调窗口，不会生效。
     * SY <--
     */
    private val extraLayoutSpace = activity.resources.displayMetrics.heightPixels * 1 / 2

    /**
     * SY --> 2026-09-29：预载窗口（离屏多远预绑定 / 预下载 / 预解码）改由设置里的
     * 「预载页数」档位（1/2/3/5/8）驱动，单位 = 屏高倍数；本地/归档源与网络源**同值**。
     * 默认 2 屏（2026-09-29 A/B 定档：3 屏在真机上「略微超出性能」）。
     * 改动经 ReaderActivity 的偏好监听实时套用（[refreshPreloadWindow]），点击即生效。
     *
     * ⚠️ 内存代价（务必知道）：SSIV 每个「已绑定」页面常驻约 26MB Java 堆
     * （`Decoder.pixels = w*h*4`）。窗口 × 26MB 就是峰值内存：2 屏 ≈ 6~9 页 ≈ 160~240MB。
     * 选 3/5/8 屏会显著放大峰值与离屏贴图负担——页面更大或低端机可能重现 OOM
     * （崩在 `subscaleview.decoder.Decoder.init`）。历史 A/B（1/2/3/4 屏、网络源对比、
     * 换书 OOM 真凶=旧 viewer 泄漏）见工作区记忆 2026-09-28/29 与 APK 台账。
     * SY <--
     */
    private fun preloadWindowPx(): Int =
        (activity.resources.displayMetrics.heightPixels * effectivePreloadScreens()).toInt()

    /**
     * SY --> 内存感知的预载窗口上限兜底（2026-10-01）。
     *
     * 「预载页数」最高可设 8 屏，窗口**上下对称** ⇒ 前后各 8 屏 ≈ 16 页同时绑定。
     * 每页位图（超分 2x 后）实测可达 ~28MB（真机 `Decoder.init` OOM 实证：`28006416 B`
     * 顶爆 512MB 堆）。这里按「堆预算 ÷ 单页位图估算」反推最多能安全绑几屏，对用户档位
     * 做一次收敛兜底。只在**设窗口时**算一次（lazy 缓存），**不在滑动/出页热路径** ⇒ 零性能负担。
     * 默认档 2 屏不受影响；仅内存不足时把 5/8 收下来。
     * SY <--
     */
    private val maxSafePreloadScreens: Int by lazy {
        val dm = activity.resources.displayMetrics
        val w = dm.widthPixels.toLong()
        // 单页像素估算：宽 × (宽×1.5)（webtoon 单页典型比例）；每像素 4B。
        val pixelsPerPage = w * (w * 3 / 2)
        // 超分开启时按 2x 线性输出算 ⇒ 像素 ×4。
        val enhancedFactor = if (readerPreferences.realCuganEnabled().get()) 4L else 1L
        val bytesPerPage = (pixelsPerPage * 4L * enhancedFactor).coerceAtLeast(1L)
        // 取堆的 40% 给「已绑定页位图」，其余留给基线/解码中/增强。
        val budget = Runtime.getRuntime().maxMemory() * 4 / 10
        val maxPages = (budget / bytesPerPage).coerceAtLeast(2L)
        // 窗口上下对称 ⇒ 前后各占一半；下限 2 屏（红线），上限 8（用户可选最大值）。
        (maxPages / 2).toInt().coerceIn(2, 8)
    }

    /**
     * 「预载页数」偏好经内存上限收敛后的**实际**生效屏数。
     * SY 2026-10-01：新增「半屏」档 —— 偏好值 **0 = 半屏**（0.5 屏），其余仍为整数屏数
     * （1/2/3/5/8）。返回 Float 以承载半屏；内存上限收敛对 0.5 是「更小」所以天然不会触发。
     */
    private fun effectivePreloadScreens(): Float {
        val raw = readerPreferences.realCuganPreloadSize().get()
        val screens = if (raw <= 0) 0.5f else raw.coerceIn(1, 8).toFloat()
        return screens.coerceAtMost(maxSafePreloadScreens.toFloat())
    }

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, extraLayoutSpace)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope, readerPreferences)

    /**
     * SY --> 条漫占位底色不在 Viewer 层处理，而是由 WebtoonPageHolder 在 holder 创建时
     * 把 frame 背景固定为白色（漫画页多为白底），只设一次、不在滑动热路径重设——
     * 既消灭深色主题下透黑的黑闪，又避开 MIUI 上 setBackground 走 getApplicationInfo 的掉帧。
     * SY <--
     */

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    /** SY 2026-09-29 Part 2：末尾衔接的一次性闩锁，避免每帧重复请求；离开末尾后复位。 */
    private var endOfBookNotified: Boolean = false

    /** SY 2026-09-29 诊断闩锁：贴底且末尾非终页过渡时只记一次日志。 */
    private var bottomTailLogged: Boolean = false

    private val threshold: Int by lazy { readerPreferences.readerHideThreshold.get().threshold }

    init {
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    // SY --> 2026-09-28 修复「滑动时超分不让路」：
                    // 只要视图还在滚（**含惯性 / fling**，此时已没有任何触摸事件）就持续
                    // 为「超分让路」续期；停止滚动后由 ReaderActivity 的冷却恢复超分。
                    // 旧逻辑只靠 UP 后 350ms 固定冷却，盖不住 1s 以上的 fling。
                    // SY <--
                    activity.notifyViewerScrolling()
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    // SY --> 2026-09-29 Part 2：本作已读完（没有下一话）且滑到末尾
                    // ⇒ 请求衔接「下一部漫画」（同文件夹优先 → 书架兜底，自动打开）。
                    //
                    // ⚠️ 触发条件放宽过一次：原条件只认「`findLastEndVisibleItemPosition()` 正好落在
                    // 末尾过渡页上」。实测（2026-09-29）该条件可能**永不成立**——过渡页较矮、已贴底时，
                    // 「最后一条 end 可见的项」不一定是它 ⇒ 整条链一次都没跑（日志里连入口都没有）。
                    // 补一条「已经滚到底（不能再往下滚）」的等价判据。
                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    val tail = adapter.items.lastOrNull()
                    val atBottom = !recycler.canScrollVertically(1)
                    val atEndOfBook = tail is ChapterTransition.Next && tail.to == null &&
                        (lastItem === tail || atBottom)

                    if (atEndOfBook) {
                        if (!endOfBookNotified) {
                            endOfBookNotified = true
                            logcat(LogPriority.INFO) {
                                "WebtoonViewer: 已到本作末尾（无下一话）→ 请求衔接下一部" +
                                    "（末尾项可见=${lastItem === tail} 已贴底=$atBottom）"
                            }
                            activity.requestNextMangaOnFinish()
                            // 保留 showMenu：万一本作没有下一部，用户也不会卡在末尾毫无反馈
                            activity.showMenu()
                        }
                    } else {
                        endOfBookNotified = false
                        // 诊断：贴底了，但末尾不是「无下一话过渡页」⇒ 本作还有下一话（或结构不符预期）。
                        // 一次性闩锁，避免每帧刷屏。
                        if (atBottom && !bottomTailLogged) {
                            bottomTailLogged = true
                            logcat(LogPriority.INFO) {
                                "WebtoonViewer: 已贴底但末尾非终页过渡 tail=${tail?.javaClass?.simpleName} " +
                                    "tailTo=${(tail as? ChapterTransition.Next)?.to?.chapter?.url}"
                            }
                        }
                    }
                    if (!atBottom) bottomTailLogged = false
                    // SY <--
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        // SY --> 2026-09-29 OOM 修复：**主动回收当前 attached 的 holder**。
        //
        // 每个 `WebtoonPageHolder` 里的 `ReaderPageImageView` 持有 SSIV，而 SSIV 持有整页
        // base layer / tile 位图（单页 25MB+，崩溃分配点就是它的 `TilesInitTask`）。
        // 已经进池的 holder 走 `WebtoonAdapter.onViewRecycled` → `holder.recycle()`，无需处理；
        // 但**当前还 attached** 的这几个不会 —— 只 `scope.cancel()` 的话它们的位图只能等 GC 释放，
        // 而「本作读完 → 接下一部」会反复重建 viewer，于是每次换书都攒一批位图。
        // 真机实测：Views 计数随换书单调递增、Java 堆每次跳一档，最终 OOM。
        runCatching {
            for (i in 0 until recycler.childCount) {
                val child = recycler.getChildAt(i) ?: continue
                when (val vh = recycler.getChildViewHolder(child)) {
                    is WebtoonPageHolder -> vh.recycle()
                    is WebtoonTransitionHolder -> vh.recycle()
                    else -> Unit
                }
                // SY --> 2026-09-29 OOM 根治（最后一环）：**断掉 itemView ↔ ViewHolder 回引**。
                //
                // 真机堆转储的「GC 根路径」逐跳证明了泄漏链：
                //   ReaderActivity(GC根) → …Compose 组合… → AndroidComposeView
                //     → ReaderTransitionView → LinearLayout(itemView)
                //     → **RecyclerView$LayoutParams.mViewHolder**
                //     → WebtoonTransitionHolder → **WebtoonViewer(旧)** → 其所有页解码器
                //
                // 机制：`RecyclerView.LayoutParams` 长期持有 `mViewHolder` 回引
                // （`WebtoonTransitionHolder.init` 的 `layout.layoutParams = ...` 即入口）。
                // 于是「itemView 视图树只要还被谁引用着」⇒ 反推 holder ⇒
                // holder 又通过 `WebtoonBaseHolder.viewer` 反推**整个旧 viewer** ⇒
                // 每次 `updateViewer()` 换书整份泄漏 ⇒ 连续换书时十几个 SSIV 解码器的
                // 整页像素（26MB/页）同时活着 ⇒ 494MB/512MB ⇒ OOM。
                //
                // 把 LayoutParams 换成「无回引」的普通 LayoutParams，再移除子视图、
                // 清空复用池、卸掉 adapter ⇒ 旧 viewer 与其解码器立刻变可回收，不等 GC 猜。
                child.layoutParams = ViewGroup.LayoutParams(0, 0)
            }
            recycler.removeAllViews()
            recycler.recycledViewPool.clear()
            recycler.adapter = null
        }
        scope.cancel()
    }
    // SY <--

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    init {
        // SY --> 告诉超分队列进入连续阅读模式：此模式下**一律不抢占**。
        // 条漫一屏可见多页、目标页随滚动连续前移，abort 在跑的页几乎必然是浪费 ——
        // 那页稍后仍会被读到，重跑一遍就是用户看到的「重复超分 / 进度从零开始」。
        // `isContinuous=false` 是竖向翻页（一屏一页），语义上仍属翻页模式，不设该标记。
        ImageEnhancer.setContinuousMode(isContinuous)
        // SY <--
    }

    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }

        // SY --> 条漫模式下由这里统一决定「用户当前在读哪一页」。
        //
        // 页面视图那边被显式禁掉了这个权力（WebtoonPageHolder 里
        // `controlsCurrentPageSelection = false`）：条漫一屏会同时绑定好几页（含预载页），
        // 若让每个视图各自上报目标页，最后绑定的那个预载页就会霸占目标页并把正在推理的
        // 页顶掉 —— 表现正是「队列明显有问题、同一页反复超分、进度从零开始」。
        // SY <--
        ReaderPageImageView.currentGlobalPageIndex = page.index
        ImageEnhancer.reprioritizeAround(page.index)
        // SY 2026-10-18：预览窗口跟手（长距离快滑时保证前后几页的占位图已在内存里）。
        requestPreviewWindow(page.chapter, page.index)

        activity.onPageSelected(page)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)
        // SY --> 2026-09-28：按「当前章节的加载器」动态调整条漫预绑定窗口。
        // 2026-09-29：网络源与本地/归档源**统一 2 屏**（用户实测 3 屏略微超出性能）。
        applyPreloadWindow(chapters.currChapter)
        // SY <--

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            val startIndex = min(chapters.currChapter.requestedPage, pages.lastIndex)
            // SY --> 2026-10-18：首次布局**之前**先把开口页附近几页的低清预览算好。
            // holder 的第一次绑定就发生在这次布局里，而预览读取是异步的（几十毫秒）；
            // 不提前算，首帧只能是纯色 —— 实测「打开一个全部命中超分缓存的章节」仍会黑 1.52s。
            prefetchOpeningPreviews(chapters.currChapter, startIndex, pages.size)
            // SY <--
            moveToPage(pages[startIndex])
            recycler.isVisible = true
        }
    }

    /**
     * SY 2026-10-18：预取「开口页 ±1 页」的低清预览（见 [PagePreviewCache.prefetch]）。
     *
     * 只做本地 I/O（读超分缓存里已经算好的 webp 低清解码），不发网络请求、不跑模型；
     * 页面还没被超分过时静默跳过。放在 IO 线程，不阻塞首次布局。
     */
    private fun prefetchOpeningPreviews(chapter: ReaderChapter, startIndex: Int, pageCount: Int) {
        val chapterId = chapter.chapter.id ?: return
        val mangaId = chapter.chapter.manga_id ?: return
        if (!readerPreferences.realCuganEnabled().get()) return
        val variant = config.pageTransformVariant()
        val keys = ((startIndex - 1)..(startIndex + 2))
            .filter { it in 0 until pageCount }
            .map { PagePreviewCache.Key(mangaId, chapterId, it, variant) }
        if (keys.isEmpty()) return
        // 预览按显示宽度解码：占位图必须与最终的清晰图视觉一致（见 PagePreviewCache 里的说明）。
        val targetWidth = PagePreviewCache.previewTargetWidthFor(activity.resources.displayMetrics.widthPixels)
        scope.launchIO {
            val configHash = runCatching { enhancementConfigHash(readerPreferences) }.getOrNull()
                ?: return@launchIO
            PagePreviewCache.prefetch(activity, configHash, keys, targetWidth)
        }
    }

    /**
     * SY 2026-10-18：把全局预览窗口挪到 [pageIndex] 附近（朝阅读前进方向多铺几页）。
     *
     * 由 [onPageSelected] 驱动，跟手：条漫里目标页是连续前移的，所以窗口以「当前页」为锚点，
     * 前进方向给 4 页、反方向给 3 页 —— 用户回头滑时靠 LRU 里残留的预览接上。
     */
    private fun requestPreviewWindow(chapter: ReaderChapter, pageIndex: Int) {
        if (!readerPreferences.realCuganEnabled().get()) return
        val chapterId = chapter.chapter.id ?: return
        val mangaId = chapter.chapter.manga_id ?: return
        val pageCount = chapter.pages?.size ?: return
        if (pageCount <= 0) return

        val forward = if (previewAnchorIndex >= 0 && pageIndex < previewAnchorIndex) -1 else 1
        previewAnchorIndex = pageIndex
        val variant = config.pageTransformVariant()
        val keys = listOf(0, forward, -forward, forward * 2, -forward * 2, forward * 3, -forward * 3, forward * 4)
            .asSequence()
            .map { pageIndex + it }
            .filter { it in 0 until pageCount }
            .distinct()
            .map { PagePreviewCache.Key(mangaId, chapterId, it, variant) }
            .toList()
        if (keys.isEmpty()) return

        previewWindow.set(keys)
        ensurePreviewWorker()
    }

    private fun ensurePreviewWorker() {
        if (previewWorker?.isActive == true) return
        val targetWidth = PagePreviewCache.previewTargetWidthFor(activity.resources.displayMetrics.widthPixels)
        previewWorker = scope.launchIO {
            while (isActive) {
                val keys = previewWindow.get()
                if (keys.isNullOrEmpty()) {
                    delay(120)
                    continue
                }
                val configHash = runCatching { enhancementConfigHash(readerPreferences) }.getOrNull()
                if (configHash == null) {
                    previewWindow.compareAndSet(keys, null)
                    continue
                }
                for (key in keys) {
                    // 窗口已经被更新的位置替换 → 剩下的交给新一轮，不做无效解码。
                    if (previewWindow.get() !== keys) break
                    if (PagePreviewCache.get(key) != null) continue
                    PagePreviewCache.loadFromEnhancedCache(activity, key, configHash, targetWidth)
                        ?.let { PagePreviewCache.put(key, it) }
                }
                previewWindow.compareAndSet(keys, null)
            }
        }
    }

    /**
     * SY --> 2026-09-28：按加载器决定预绑定窗口（见 [WebtoonLayoutManager.setExtraLayoutSpace]）。
     * 2026-09-29：屏数改由「预载页数」偏好驱动（见 [preloadWindowPx]），本地/网络源同值；
     * [eu.kanade.tachiyomi.ui.reader.loader.PageLoader.preloadDistanceExtra] 只再用于日志区分 loader。
     * SY <--
     */
    private fun applyPreloadWindow(chapter: ReaderChapter) {
        // SY 2026-09-29：窗口屏数由「预载页数」偏好驱动（1/2/3/5/8），本地/网络源同值。
        val extra = chapter.pageLoader?.preloadDistanceExtra ?: 0
        val space = preloadWindowPx()
        (layoutManager as WebtoonLayoutManager).setExtraLayoutSpace(space)
        // SY 2026-09-29：把「实际生效的窗口」打出来（INFO，release 可见），
        // 让「预载是几屏」成为实测事实而不是推断。
        logcat(LogPriority.INFO) {
            val screenH = activity.resources.displayMetrics.heightPixels.toFloat()
            "预绑定窗口：loader=${chapter.pageLoader?.javaClass?.simpleName} " +
                "preloadExtra=$extra → ${"%.2f".format(space / screenH)} 屏" +
                "（预载=${readerPreferences.realCuganPreloadSize().get()}" +
                "→生效=${effectivePreloadScreens()} 屏，内存上限=${maxSafePreloadScreens}）"
        }
    }

    /**
     * SY 2026-09-29：「预载页数」变化后立即重设预绑定窗口（点击即生效，无需重开章节）。
     * ReaderActivity 的偏好监听调用；取当前页所在章节，取不到就退回列表第一页的章节。
     */
    fun refreshPreloadWindow() {
        val chapter = (currentPage as? ReaderPage)?.chapter
            ?: adapter.items.filterIsInstance<ReaderPage>().firstOrNull()?.chapter
            ?: return
        applyPreloadWindow(chapter)
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    private fun scrollDown() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}

// SY --> 预绑定窗口 2026-09-28 收到 1/2 屏，缓存同步 4 → 3：进一步压 RenderThread 常驻贴图量，
// 仍保留 3 个已解码 holder 兜住滚动方向切换时的短暂复用
// （那些视图的 GPU 纹理正是 RenderThread 压力的来源）。
private const val RECYCLER_VIEW_CACHE_SIZE = 3
