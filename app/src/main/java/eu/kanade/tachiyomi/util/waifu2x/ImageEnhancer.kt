package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.data.coil.chapterId
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.data.coil.enhanceNow
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.data.coil.mangaId
import eu.kanade.tachiyomi.data.coil.pageIndex
import eu.kanade.tachiyomi.data.coil.pageVariant
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SY --> mihon_img_upscale 移植：后台画面增强执行器。
 *
 * 单例 + 单工作线程 + 优先队列。核心语义与上游一致：
 *  - **显示与增强彻底解耦**：阅读器永远先用原图把页面显示出来，增强在后台跑，
 *    跑完写进 [ImageEnhancementCache] 的磁盘缓存，视图侧靠轮询发现「缓存出现了」
 *    再把图换掉（见 ReaderPageImageView.setProcessedSource / startEnhancementPolling）。
 *  - **优先级**：用户当前正在看的页（[targetPageIndex]）永远排在前面，
 *    预载页（loader 在加载完一页后以低优先级入队）排后面；
 *    切页时正在跑的「已经落后于目标页」的任务会被抢占（abort + cancel）。
 *  - 真正的推理仍然复用 Coil 解码器（`enhanced + enhanceNow`），
 *    这样解码、缩放上限、模型分派、落盘缓存只有一份实现。
 * SY <--
 */
object ImageEnhancer {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingRequests = ConcurrentHashMap<String, Int>()

    /**
     * SY --> 会话级去重表：`"mangaId_chapterId" -> 本会话已成功算完的页 index 集合`。
     *
     * 移植自 TachiyomiX 的同名机制（`ImageEnhancer.sessionProcessedPages`）。
     *
     * 存在的理由：只靠磁盘缓存判定**存在误判窗口** —— 刚算完但落盘还没 flush、
     * 配置指纹被重算、缓存被容量裁剪、`isSavePending` 的登记与清除之间有间隙。
     * 任何一个窗口里重复入队，同一页就会被再喂一次模型，也就是用户看到的「重复超分」。
     * 这里在内存里再记一层「这一页本轮已经算完了」，命中就直接拒绝入队。
     *
     * 配置变更（换模型/精度/尺寸上限）会走 `cancelAll`，而 `cancelAll` 会清空本表 ——
     * 因为缓存键变了，必须允许按新配置重算，否则用户改了设置却永远看不到效果。
     * SY <--
     */
    private val sessionProcessedPages = ConcurrentHashMap<String, MutableSet<Int>>()

    private val queue = PriorityBlockingQueue<EnhanceRequest>()
    private val seqGenerator = AtomicInteger(0)
    private val generation = AtomicInteger(0)

    /**
     * SY --> 本章是否已经「插队」过一次（对齐 TachiyomiX 的 `initialTargetEnqueued`）。
     *
     * 语义：刚进章节时，让第一页拿到插队权、立刻开算；一旦插过一次，本章后续所有页
     * 一律按绝对页码顺序排队 —— 也就是「按顺序一路超分下去」。`reset()` 时复位。
     * SY <--
     */
    @Volatile
    private var initialTargetEnqueued = false

    /** 用户当前正在看的页；用于抢占与重排优先级。 */
    @Volatile
    var targetPageIndex: Int = 0

    @Volatile
    private var targetPageVariant: String = ""

    /** 双页阅读模式下的副页（跨页拼合后左右两页都可能可见）。 */
    @Volatile
    private var targetSecondaryPageIndex: Int = -1

    @Volatile
    private var targetSecondaryPageVariant: String = ""

    /**
     * SY --> 是否处于连续（条漫）阅读模式。
     *
     * 条漫与翻页模式的调度语义**根本不同**：条漫一屏就同时可见好几页，目标页随滚动连续
     * 前移。此时 abort 掉正在推理的那一页几乎必然是纯浪费 —— 它稍后仍然会被读到，被 abort
     * 就只会从头再算一遍，用户看到的就是「反复超分」「进度冲到一半又从零开始」。
     *
     * 所以条漫模式**一律不抢占**，只做**一次性的最小重排**：把用户正在看的那一页提到
     * 队首（[promoteTargetInQueue]），其余页严格按绝对页码顺序消费 —— 保证
     * 「眼前这页最先算完」且「每一页都算且只算一次，绝不漏页」。
     * SY <--
     */
    @Volatile
    var isContinuousMode: Boolean = false
        private set

    @Volatile
    private var activeMangaId = -1L

    @Volatile
    private var activeChapterId = -1L

    @Volatile
    private var activePageIndex = -1

    @Volatile
    private var activePageVariant = ""

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeRequest: EnhanceRequest? = null

    @Volatile
    private var nativeResetJob: Job? = null

    /**
     * SY --> 移植自 TachiyomiX：**全局**处理状态（不是逐页状态）。
     *
     * 左下角的进度提示是「整个超分 worker 现在在干什么」，与「某一页处于什么状态」
     * 是两件事。旧实现让每个 ReaderPageImageView 各自维护一份逐页状态文案
     * （排队中 / 收尾中… / 已增强 / 原图），条漫一屏多页 + 单 worker 顺序消费时，
     * 同一页的文案会在「增强中 → 排队中 → 排队中 → 已增强」之间来回跳 ——
     * 因为 worker 会先跑去算别的页（本页退回「排队中」），算完本页再变「已增强」。
     *
     * TachiyomiX 的做法是**只有一个**全局状态源，UI 只渲染这一个：
     *   · [activePageIndex] >= 0 → 显示「增强中 第 N 页 · P% [· 待处理 Q 页]」；
     *   · [activePageIndex] < 0 → 什么都不显示（空闲即隐藏）。
     * 于是文案天然单调，不存在来回跳。
     * SY <--
     */
    private val _status = MutableStateFlow(ProcessingStatus(-1, 0, 0, 0))

    /** 全局处理状态，供阅读器左下角显示（对齐 TachiyomiX 的 `ImageEnhancer.status`）。 */
    val status: StateFlow<ProcessingStatus> = _status.asStateFlow()

    /**
     * SY --> 对齐 TachiyomiX 的 `ImageEnhancer.ProcessingStatus`。
     *
     * 字段与构造顺序完全一致：
     *  · [activePageIndex]        worker 正在算哪一页（-1 = 空闲）
     *  · [queuedCount]            队列总长度
     *  · [sessionProcessedCount]  本会话（当前章节）已算完的页数
     *  · [sessionBatchCount]      本会话这一批的总页数 = 本章排队数 + 已算完数 + 是否正在算
     * SY <--
     */
    data class ProcessingStatus(
        val activePageIndex: Int,
        val queuedCount: Int,
        val sessionProcessedCount: Int,
        val sessionBatchCount: Int,
    )

    /**
     * SY --> 重新计算并广播全局状态。语义逐条对齐 TachiyomiX 的 `publishStatus()`。
     *
     * 调用点（与参考实现一致）：
     *  · [enhanceLazy] 入队成功之后；
     *  · [processRequest] 抢到任务、写上 active* 之后（→ 开始显示「增强中 第 N 页」）；
     *  · [processRequest] 的 finally（activePageIndex 复位为 -1 → 空闲即隐藏）；
     *  · [cancelAll] / [reset]。
     *
     * 计数口径刻意与参考实现保持逐字一致，避免 UI 文案与真机行为不符：
     *  · queuedCount = **整条队列**长度（不是本章）；
     *  · sessionProcessedCount = 本章（`mangaId_chapterId`）已算完页数；
     *  · sessionBatchCount = 本章在队数 + 已算完数 + (正在算 ? 1 : 0)。
     * SY <--
     */
    fun publishStatus() {
        // 本章已算完页数：本工程的 [sessionKey] 带 configHash（为了「换引擎后自动放行重算」），
        // 而参考实现的 sessionProcessedPages 只按 mangaId_chapterId 分桶。这里按前缀把本章
        // 所有配置桶并起来，等价于参考实现读到的那个数。
        val processed = sessionProcessedCount(activeMangaId, activeChapterId)

        // 参考实现：只有 activeChapterId > 0 时才去遍历队列统计本章在队数。
        var queuedInChapter = 0
        if (activeChapterId > 0) {
            for (req in queue) {
                if (req.mangaId == activeMangaId && req.chapterId == activeChapterId) {
                    queuedInChapter++
                }
            }
        }

        val queuedCount = queue.size
        var batch = queuedInChapter + processed
        if (activePageIndex >= 0) {
            batch++
        }

        _status.value = ProcessingStatus(
            activePageIndex = activePageIndex,
            queuedCount = queuedCount,
            sessionProcessedCount = processed,
            sessionBatchCount = batch,
        )
    }

    data class EnhanceRequest(
        val context: Context,
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val pageVariant: String,
        val dataProvider: () -> Any?,
        /**
         * 1 = 插队页（本章首屏那一页 / 连续模式下用户正在看的那一页），0 = 普通页。
         *
         * SY --> 用 `var` 而不是 `val`：连续模式下用户滚到新的一页时，要把「他正看着的
         * 这一页」从普通队列里提上来（见 [promoteTargetInQueue]）。改的是**排序键**，
         * 所以必须先从 PriorityBlockingQueue 里摘出来、改完再放回去 —— 就地改会让堆序失效。
         * SY <--
         */
        var priority: Int,
        val generation: Int,
        val seq: Int = 0,
    ) : Comparable<EnhanceRequest> {
        val cancelled = AtomicBoolean(false)
        val requeueOnCancel = AtomicBoolean(false)

        /**
         * SY --> 排序规则对齐 TachiyomiX —— 这是「不管划到哪里，都按章节顺序一路超分下去」的关键。
         *
         *  1) priority 降序：只有「本章第一页」拿插队权（见 [initialTargetEnqueued]），其余一律 0；
         *  2) **绝对页码升序**：0,1,2,3… 严格按章节顺序推进，与用户滚到哪里无关；
         *  3) 同页按入队先后 FIFO。
         *
         * 旧实现第 2 条的排序键是「与当前可见页的距离」，会随滚动实时变化 —— 用户每滚一下
         * 目标页就变、整个堆序失效，队列被反复重排，单线程 worker 一直在追新目标，
         * 谁的进度都留不住，表现就是「一直排队中、就是不超分」。
         * 改成绝对页码后，排序键在入队那一刻就固定，堆序恒定，队列只会单调地向前消费；
         * 后面不断有新页入队也只是排到队尾，依旧按顺序一路算完 —— 与 TachiyomiX 的行为一致。
         * SY <--
         */
        override fun compareTo(other: EnhanceRequest): Int {
            val p = other.priority.compareTo(priority)
            if (p != 0) return p

            val byPage = pageIndex.compareTo(other.pageIndex)
            if (byPage != 0) return byPage

            return seq.compareTo(other.seq)
        }
    }

    init {
        scope.launch {
            while (true) {
                try {
                    val req = runInterruptible { queue.take() }
                    if (req.generation == generation.get()) {
                        processRequest(req)
                    } else {
                        pendingRequests.remove(req.key, req.generation)
                    }
                } catch (t: Throwable) {
                    // SY --> 这里是整条增强队列唯一的消费循环，绝不能让它退出。
                    // 只捕 Exception 时，一次 OutOfMemoryError（大长条图 + 2x 输出
                    // buffer 很容易触发）就会顺着循环抛出去把这条协程彻底干掉 ——
                    // 之后所有请求都只进不出，全书永久停在「排队中」。
                    // 所以放宽到 Throwable；并 sleep 一下，避免持续报错时空转烧 CPU。
                    // 抢占/取消（InterruptedException / CancellationException）是正常路径，静默。
                    // SY <--
                    if (t !is InterruptedException && t !is CancellationException) {
                        logcat(LogPriority.ERROR, t) { "ImageEnhancer: Worker loop fatal, recovering" }
                        delay(50)
                    }
                }
            }
        }
    }

    /** 入队一个 [ReaderPage]（loader 与视图共用入口）。 */
    fun enhance(context: Context, page: ReaderPage, highPriority: Boolean = false) {
        val mangaId = page.chapter.chapter.manga_id ?: return
        val chapterId = page.chapter.chapter.id ?: return

        enhanceLazy(
            context = context.applicationContext,
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = page.index,
            highPriority = highPriority,
            pageVariant = "",
        ) {
            page.stream?.invoke()?.use { Buffer().readFrom(it) }
        }
    }

    fun enhanceLazy(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        highPriority: Boolean,
        pageVariant: String = "",
        dataProvider: () -> Any?,
    ) {
        // SY --> 去重的硬闸门：这一页只要「已在算、算完落盘了、或已被判定不用算」，
        // 就直接返回。旧实现把推理挂在解码里，来回翻页/预载/视图重绑都会
        // 让同一页被反复送进模型（用户看到的「重复超分」），这里从源头掐掉。
        if (ImageEnhancementCache.isSavePending(mangaId, chapterId, pageIndex, pageVariant)) {
            return
        }
        // SY --> 配置指纹是这一页「算不算数」的判据，所以三个判定必须共用同一份。
        // 取不到指纹（非 App context）时退化成空串：此时不做缓存判定，
        // 会话去重也退化成整会话一桶，行为与旧实现一致，不会误杀。
        val configHash = enhancementConfigHashOrNull(context).orEmpty()
        if (configHash.isNotEmpty()) {
            if (ImageEnhancementCache.isCached(mangaId, chapterId, pageIndex, configHash, pageVariant)) {
                return
            }
            if (ImageEnhancementCache.isSkipped(mangaId, chapterId, pageIndex, configHash, pageVariant)) {
                return
            }
        }
        // 会话级去重：本会话**在当前配置下**已经算完并落盘过的页，不再入队。
        // 带上 configHash 是关键 —— 换引擎后指纹变了，这一页会自动重新放行。
        if (isSessionProcessed(mangaId, chapterId, configHash, pageIndex)) {
            return
        }
        // SY <--
        // 内存里的在途登记同样兜一层（isSavePending 只覆盖「落盘阶段」）
        if (pendingRequests.containsKey(requestKey(mangaId, chapterId, pageIndex, pageVariant))) {
            val existingGeneration = pendingRequests[requestKey(mangaId, chapterId, pageIndex, pageVariant)]
            if (!highPriority) return
            if (existingGeneration == generation.get()) return
        }
        // SY <--

        val requestKey = requestKey(mangaId, chapterId, pageIndex, pageVariant)
        val requestGeneration = generation.get()

        val existingGeneration = pendingRequests[requestKey]
        if (existingGeneration != null) {
            if (highPriority) {
                // 低优先级 -> 高优先级提升：从队列里摘掉重排
                val removed = queue.removeIf {
                    it.mangaId == mangaId &&
                        it.chapterId == chapterId &&
                        it.pageIndex == pageIndex &&
                        it.pageVariant == pageVariant &&
                        it.generation == existingGeneration
                }
                if (!removed) {
                    // SY --> 队列里已经找不到这条请求，只有两种可能：
                    //   1) worker 已经把它取走、正在处理（此时 activeRequest 就是它）——
                    //      保持登记不动，processRequest 的 finally 会负责移除；
                    //   2) 它是一条「既不在队列、也没在处理」的泄漏条目 —— 必须清掉。
                    //      否则这一页会永久显示「排队中」，而且因为下面 putIfAbsent 的
                    //      去重，它再也无法重新入队，形成死锁。
                    // 这正是 pendingRequests 需要 TTL 清扫的那类残留，但在这里
                    // 按「是否真在处理」精确判定，比给整张表加时间戳更不容易伤到去重语义。
                    // SY <--
                    if (activeRequest?.key != requestKey) {
                        pendingRequests.remove(requestKey, existingGeneration)
                    }
                    return
                }
                pendingRequests.remove(requestKey, existingGeneration)
            } else {
                return
            }
        }

        if (pendingRequests.putIfAbsent(requestKey, requestGeneration) != null) {
            return
        }

        // SY --> 对齐 TachiyomiX：队列空且没有页在处理时，说明本章这一轮已经全部算完，
        // 此时把会话去重表清空是安全的 —— 之后重新入队的页仍会经过磁盘缓存（isCached）闸门，
        // 不会白算；但可以避免「去重表里一条陈旧登记把某页永久挡在门外」导致的
        // 「这一页永远排队中、永远不超分」。
        // SY <--
        if (queue.isEmpty() && activePageIndex < 0) {
            sessionProcessedPages.clear()
        }

        // SY --> 优先级只在这里、只在入队这一刻决定，之后不再变（对齐 TachiyomiX），
        // 唯一的例外是连续模式下 [promoteTargetInQueue] 会把「正在看的那一页」提到队首。
        //
        //  · 本章第一次入队的那一页 → 插队一次，让刚进章节时正在看的那页立刻开算；
        //  · 翻页模式：视图传来 highPriority 的页直接插队（一屏一页，语义明确）；
        //  · 连续模式：**只有当它就是用户正在看的那一页**才插队。条漫一屏可见多页、
        //    滚动时目标页连续前移，若把每个可见页都当高优插队，队列就会一直围着滚动
        //    位置打转、谁的进度都留不住（这正是历史 bug「一直排队中」的来源）；
        //    而「就这一页」是稳定的单点，等于让用户眼前那一页先算完，其余页不受影响。
        // SY <--
        val isInitialTarget = !initialTargetEnqueued
        val boosted = isInitialTarget ||
            (highPriority && (!isContinuousMode || isTargetPage(pageIndex, pageVariant)))
        if (isInitialTarget) {
            initialTargetEnqueued = true
        }

        queue.offer(
            EnhanceRequest(
                context = context.applicationContext,
                mangaId = mangaId,
                chapterId = chapterId,
                pageIndex = pageIndex,
                pageVariant = pageVariant,
                dataProvider = dataProvider,
                priority = if (boosted) 1 else 0,
                generation = requestGeneration,
                seq = seqGenerator.getAndIncrement(),
            ),
        )
        // SY --> 对齐 TachiyomiX：入队后立刻广播全局状态（队列长度变了，
        // 「待处理 Q 页」要跟着变）。SY <--
        publishStatus()

        if (highPriority) {
            ImageEnhancer.targetPageIndex = pageIndex
            targetPageVariant = pageVariant
        }

        // SY --> 可见页插队时的抢占。这里有三道收紧：
        //  1) 条漫模式完全不抢占 —— 见 [isContinuousMode]；
        //  2) 正在跑的就是当前目标页时不打断自己（[isFocusedTarget]）；
        //  3) 被抢占的页**一律补回队列**（对齐 TachiyomiX 的
        //     "Re-queueing preempted preload page"）—— 保证「每一页都算且只算一次，绝不漏」。
        //
        // 旧实现只在对方面向「用户接下来还会读到」时才 requeue，否则直接丢弃；
        // 而它丢弃的判据又依赖会变的 targetPageIndex，于是被丢掉的页再也不会被补算，
        // 那几页就永远停在「排队中」。现在队列已改成长序（priority → 绝对页码 → seq），
        // 补回队列的页会自动落回它原本的页码位置，不会造成「重复超分」。
        // SY <--
        if (
            highPriority &&
            !isContinuousMode &&
            activePageIndex >= 0 &&
            (activePageIndex != pageIndex || activePageVariant != pageVariant) &&
            !isFocusedTarget(activePageIndex, activePageVariant)
        ) {
            preemptActiveRequest(
                reason = "visible page requested",
                requeue = true,
            )
        }

        logcat(LogPriority.DEBUG) {
            "ImageEnhancer: Enqueued page $pageIndex (high=$highPriority)"
        }
    }

    /** 换章节时调用：清空队列并把目标页重置到新章节的起始页。 */
    fun reset(initialPageIndex: Int = 0) {
        cancelAll(reason = "reset")
        queue.clear()
        pendingRequests.clear()
        targetPageIndex = initialPageIndex
        targetPageVariant = ""
        seqGenerator.set(0)
        // SY --> 新章节重新给一次「首页插队」的机会（对齐 TachiyomiX 的 initialTargetEnqueued 复位）。
        // SY <--
        initialTargetEnqueued = false
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Resetting state to page $initialPageIndex" }
    }

    fun cancelAll(reason: String = "cancelAll", resetNative: Boolean = true) {
        generation.incrementAndGet()
        queue.clear()
        pendingRequests.clear()
        // SY --> 兑现注释承诺：配置变更 / 换章节 / 重置时，会话去重表必须一并清空。
        // 否则旧页仍被判定「已处理」而拒绝重算 —— 表现就是「切换超分引擎后永远不重新超分 /
        // 卡在排队中」。指纹虽已带 configHash（换引擎会自然放行），但这里再清一次可兜住
        // 「触发了 cancelAll 但指纹没变」的边界（例如某些被监听却未进指纹的偏好），
        // 保证 cancelAll 之后任何页都能重新入队。
        sessionProcessedPages.clear()
        // SY <--
        activeJob?.cancel(CancellationException("Image enhancement cancelled: $reason"))
        activeRequest?.cancelled?.set(true)
        activeJob = null
        activeRequest = null
        activeMangaId = -1L
        activeChapterId = -1L
        activePageIndex = -1
        activePageVariant = ""
        if (resetNative) {            try {
                Waifu2x.abortProcessing()
            } catch (t: Throwable) {
                logcat(LogPriority.WARN, t) { "ImageEnhancer: Failed to signal native abort" }
            }
            if (nativeResetJob?.isActive != true) {
                nativeResetJob = scope.launch {
                    try {
                        Waifu2x.prepareProcessing()
                    } catch (t: Throwable) {
                        logcat(LogPriority.ERROR, t) { "ImageEnhancer: Failed to reset native upscaler" }
                    }
                }
            }
        }
        logcat(LogPriority.DEBUG) { "ImageEnhancer: Cancelled all enhancement work (reason=$reason)" }
        // SY --> 对齐 TachiyomiX 的 cancelAll：activePageIndex 已复位为 -1，
        // 广播一次让左下角立刻隐藏（否则会留着上一页的「增强中」文案）。SY <--
        publishStatus()
    }

    /**
     * SY --> 上报「用户当前在读哪一页」，并据此重排队列。
     *
     * 调用方必须是真正知道当前阅读位置的一方：
     *  · 翻页模式：[ReaderPageImageView] 绑定页面时调用（一屏只有一页，绑定即当前页）；
     *  · 条漫模式：由 `WebtoonViewer.onPageSelected` 统一调用 —— 条漫一屏会绑定多页
     *    （含预载页），若让每个视图各自上报，最后一个绑定的预载页就会霸占目标页，
     *    并把正在推理的页顶掉，表现就是队列乱跳 + 同一页反复重算。
     *
     * 抢占语义：只有「在跑的那页排在目标页**之前**」才打断，且被抢占的页**一定补回队列**
     * （对齐 TachiyomiX 的 "Re-queueing preempted preload page"）—— 保证每一页最终都会算到、
     * 绝不漏页；补回的页会落回它原本的页码位置，不会造成「重复超分」。
     * 条漫模式则完全不抢占，理由见 [isContinuousMode]。
     *
     * SY --> 这里**只更新目标页游标**（外加连续模式下的单点提权），不再对队列做批量重排。
     * 旧实现在这里 `drainTo` + `addAll`，把整条队列按「与目标页的距离」重新堆化 ——
     * 而条漫每翻一页这个函数就被调一次，等于队列被以极高频率反复打乱重排：
     * worker 刚取到手的顺序立刻失效，谁的进度都留不住，用户看到的就是
     * 「一直排队中、就是不超分」。顺序现在完全由 [EnhanceRequest.compareTo]
     * （priority → 绝对页码 → seq）决定，是与滚动位置无关的稳定长序；
     * 连续模式下唯一的例外是把「用户正在看的那一页」提为 priority 1
     * （[promoteTargetInQueue]），它只动这一页 + 降级上一轮的插队页，不改其余任何次序。
     * SY <--
     */
    fun reprioritizeAround(
        pageIndex: Int,
        pageVariant: String = "",
        secondaryPageIndex: Int? = null,
        secondaryPageVariant: String = "",
    ) {
        targetPageIndex = pageIndex
        targetPageVariant = pageVariant
        targetSecondaryPageIndex = secondaryPageIndex ?: -1
        targetSecondaryPageVariant = if (secondaryPageIndex != null) secondaryPageVariant else ""

        // SY --> 连续模式：不抢占（理由见 [isContinuousMode]），但要把用户**正在看的这一页**
        // 提到队首，否则「滚动时看不到超分、停下来等一会儿才出现」。见 [promoteTargetInQueue]。
        if (isContinuousMode) {
            promoteTargetInQueue(pageIndex, pageVariant)
            return
        }
        // SY <--

        // 翻页模式（连续模式已在上面提前返回）：只有「在跑的那页落后于目标页」才抢占。
        if (
            activePageIndex >= 0 &&
            activePageIndex < targetPageIndex &&
            !isFocusedTarget(activePageIndex, activePageVariant)
        ) {
            preemptActiveRequest(reason = "active page is behind visible target", requeue = true)
        }
    }

    /**
     * SY --> 把队列里「用户正在看的那一页」提到队首（连续模式专用）。
     *
     * 起因：连续模式下整条队列按**绝对页码升序**消费，这是「不管划到哪里都按顺序一路
     * 超分、每页只算一次、进度不来回跳」的来源（见 [EnhanceRequest.compareTo]）。但它有
     * 一个副作用：用户滚动时会越过 worker 的进度，他**正看着的那一页**还排在一堆页码
     * 更小的页后面，于是「滑动的时候看不到超分，手一停下来等几秒才出现」——
     * 用户描述为「滑动时超分停止，停下才继续」。
     *
     * 这里只做**一次最小重排**：
     *   · 把目标页的 priority 提为 1 → 排在所有普通页之前；
     *   · 把上一轮的插队页降回 0（用户已经不在看它了）；
     *   · 其它任何页的次序都不动，**绝不** abort 正在算的那一页（连续模式的铁律）。
     *
     * 于是：当前页一算完立刻轮到用户看着的页，被跳过的页仍旧按页码顺序补上，不漏页。
     * SY <--
     */
    private fun promoteTargetInQueue(pageIndex: Int, pageVariant: String) {
        val currentGeneration = generation.get()
        var target: EnhanceRequest? = null
        var alreadyBoosted = false
        val staleBoosts = ArrayList<EnhanceRequest>(4)

        // 只读扫描（不在此处改堆），避免中途失败留下「已被摘出但没放回」的请求。
        for (req in queue) {
            if (req.generation != currentGeneration) continue
            if (req.pageIndex == pageIndex && req.pageVariant == pageVariant) {
                if (req.priority >= 1) alreadyBoosted = true else target = req
            } else if (req.priority >= 1) {
                staleBoosts.add(req)
            }
        }
        if (alreadyBoosted || (target == null && staleBoosts.isEmpty())) return

        // 目标页先摘出来（要改它的排序键），旧的插队页降级后放回原位。
        target?.let { queue.remove(it) }
        for (req in staleBoosts) {
            if (queue.remove(req)) {
                req.priority = 0
                queue.offer(req)
            }
        }
        target?.let {
            it.priority = 1
            queue.offer(it)
            logcat(LogPriority.DEBUG) {
                "ImageEnhancer: Boosted visible page ${it.pageIndex} to queue head (continuous)"
            }
        }
        publishStatus()
    }

    /**
     * SY --> 显式设定「用户当前所在页」（0-based）。
     *
     * 与 [reprioritizeAround] 的区别：这里**只改目标页，不动队列**（不做抢占/提权）。
     * 用途是「**整本超分的起始页**」：整章批量入队发生在 `loadChapter` 里，
     * 那一刻视图还没上报页码，`targetPageIndex` 可能还停在 0 —— 若不先把它定到
     * 「用户实际要开始读的那一页」，整章批量就会从第 1 页排起，
     * 把用户早已看过的页全部重算一遍（用户反馈：「整本超分也去掉前面已经看过的，只从当前页往后」）。
     *
     * [pageIndex] < 0 时忽略（调用方可能还没拿到有效位置）。
     * SY <--
     */
    fun setTargetPage(pageIndex: Int, pageVariant: String = "") {
        if (pageIndex < 0) return
        targetPageIndex = pageIndex
        targetPageVariant = pageVariant
        targetSecondaryPageIndex = -1
        targetSecondaryPageVariant = ""
        logcat(LogPriority.DEBUG) { "ImageEnhancer: target page set to $pageIndex" }
    }

    /** 这一页是否就是用户当前在读的页（含双页模式的副页）。 */
    fun isTargetPage(pageIndex: Int, pageVariant: String = ""): Boolean {
        return isFocusedTarget(pageIndex, pageVariant)
    }

    private fun isFocusedTarget(pageIndex: Int, pageVariant: String = ""): Boolean {
        return (pageIndex == targetPageIndex && pageVariant == targetPageVariant) ||
            (pageIndex == targetSecondaryPageIndex && pageVariant == targetSecondaryPageVariant)
    }

    /**
     * SY --> 切换条漫 / 翻页模式。语义见 [isContinuousMode]。
     * 由 `WebtoonViewer` / `PagerViewer` 在构造时调用。
     * SY <--
     */
    fun setContinuousMode(enabled: Boolean) {
        if (isContinuousMode == enabled) return
        isContinuousMode = enabled
        logcat(LogPriority.DEBUG) { "ImageEnhancer: continuousMode=$enabled" }
    }

    fun hasRequest(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        return pendingRequests.containsKey(requestKey(mangaId, chapterId, pageIndex, pageVariant)) ||
            ImageEnhancementCache.isSavePending(mangaId, chapterId, pageIndex, pageVariant)
    }

    fun isActivelyProcessing(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = ""): Boolean {
        return activeMangaId == mangaId &&
            activeChapterId == chapterId &&
            activePageIndex == pageIndex &&
            activePageVariant == pageVariant
    }

    fun cancel(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String = "") {
        val key = requestKey(mangaId, chapterId, pageIndex, pageVariant)
        if (pendingRequests.remove(key) != null) {
            queue.removeIf {
                it.mangaId == mangaId && it.chapterId == chapterId &&
                    it.pageIndex == pageIndex && it.pageVariant == pageVariant
            }
        }
        if (isActivelyProcessing(mangaId, chapterId, pageIndex, pageVariant)) {
            preemptActiveRequest("page cancelled")
        }
    }

    fun cancelRequestsLessThan(mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex < thresholdPageIndex) {
                pendingRequests.remove(req.key, req.generation)
                true
            } else {
                false
            }
        }
    }

    fun cancelRequestsGreaterThan(mangaId: Long, chapterId: Long, thresholdPageIndex: Int) {
        queue.removeIf { req ->
            if (req.mangaId == mangaId && req.chapterId == chapterId && req.pageIndex > thresholdPageIndex) {
                pendingRequests.remove(req.key, req.generation)
                true
            } else {
                false
            }
        }
    }

    private suspend fun processRequest(req: EnhanceRequest) {
        var completed = false
        try {
            if (req.generation != generation.get()) return
            activeMangaId = req.mangaId
            activeChapterId = req.chapterId
            activePageIndex = req.pageIndex
            activePageVariant = req.pageVariant
            activeRequest = req
            // SY --> 对齐 TachiyomiX：抢到任务、写上 active* 之后立刻广播 ——
            // 界面这一刻开始显示「增强中 第 N 页 · P%」。
            // 参考实现在 processRequest 的每个 return 点前都补一次 publishStatus()，
            // 本工程统一放在 finally 里收口（见下），语义等价且不会漏。SY <--
            publishStatus()

            val data = req.dataProvider() ?: return
            if (req.generation != generation.get() || req.cancelled.get()) return

            Waifu2x.prepareProcessing()
            if (req.generation != generation.get() || req.cancelled.get()) return

            val request = ImageRequest.Builder(req.context)
                .data(data)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .customDecoder(true)
                .enhanced(true)
                .enhanceNow(true)
                .mangaId(req.mangaId)
                .chapterId(req.chapterId)
                .pageIndex(req.pageIndex)
                .pageVariant(req.pageVariant)
                .build()

            val disposable = req.context.imageLoader.enqueue(request)
            val job = disposable.job
            activeJob = job
            if (req.generation != generation.get() || req.cancelled.get()) {
                Waifu2x.abortProcessing()
                job.cancel(CancellationException("Image enhancement request became obsolete"))
            }
            // SY --> 唯一的 worker 协程不能有「无限等待」的点。
            //
            // 现象：阅读器长期停在「收尾中… / 排队中」不出结果。
            // 根因：整个 ImageEnhancer 只有一条 worker 协程（见 init 里的 while 循环），
            //   而这里原本是无超时的 `job.await()`。只要某一页在原生层不返回
            //   （驱动挂死 / 显存耗尽 / 融合管线 tile 尺寸不匹配导致的失败路径），
            //   这条 worker 就被永久钉死：后面所有页都排在队里永远轮不到，
            //   于是整本书都显示「排队中」，被抢占后重入队的页则显示「收尾中…」。
            //
            // 处理：加超时兜底。超时后主动 abort 原生 + 取消这一页的 Coil 任务，
            //   让 await 真正返回、worker 交回控制权，队列得以继续消费。
            //   注意 withTimeoutOrNull 只取消内层等待，不会取消 `job` 本身，
            //   所以必须显式 job.cancel()，否则原生那一侧还在跑。
            //
            // 阈值取 90s：正常单页（含长条图）推理仅需数秒，这个值只作为
            //   「原生不再响应」的保险，不参与常规性能调节。
            // SY <--
            val finishedInTime = withTimeoutOrNull(ENHANCEMENT_JOB_TIMEOUT_MS) { job.await() } != null
            if (!finishedInTime) {
                Waifu2x.abortProcessing()
                job.cancel(CancellationException("Image enhancement timed out"))
                logcat(LogPriority.ERROR) {
                    "ImageEnhancer: page ${req.pageIndex} timed out after " +
                        "${ENHANCEMENT_JOB_TIMEOUT_MS}ms, aborting and releasing worker"
                }
            }
            completed = finishedInTime
        } catch (e: CancellationException) {
            // 抢占/换页导致的取消是正常路径
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "ImageEnhancer: Failed to enhance page ${req.pageIndex}" }
        } finally {
            // 成功算完（没被取消、没抛异常）才登记会话去重；
            // 是否真的落盘由 markSessionProcessedIfCached 再确认一次。
            if (completed && !req.cancelled.get() && req.generation == generation.get()) {
                markSessionProcessedIfCached(req)
            }
            val shouldRequeue = req.requeueOnCancel.get() && req.generation == generation.get()
            activeMangaId = -1L
            activeChapterId = -1L
            activePageIndex = -1
            activePageVariant = ""
            activeJob = null
            activeRequest = null
            pendingRequests.remove(req.key, req.generation)
            // SY --> 对齐 TachiyomiX：任务结束（无论成功/取消/超时）后广播，
            // activePageIndex 已复位为 -1 → 界面在空闲时**隐藏**状态文案。
            // 参考实现在 processRequest 的 7 个出口各调一次；本工程在 finally 收口一次即可。
            // SY <--
            publishStatus()

            if (shouldRequeue) {
                logcat(LogPriority.DEBUG) {
                    "ImageEnhancer: Re-queueing preempted page ${req.pageIndex}"
                }
                enhanceLazy(
                    context = req.context,
                    mangaId = req.mangaId,
                    chapterId = req.chapterId,
                    pageIndex = req.pageIndex,
                    highPriority = false,
                    pageVariant = req.pageVariant,
                    dataProvider = req.dataProvider,
                )
            }
        }
    }

    /**
     * SY --> 抢占活跃超分请求（中止它，可选把该页补回队列）。
     *
     * 【2026-09-23 修复：进度感知，消除「翻页把当前页进度清零」】
     *
     * 现象（用户实测）：单页翻页模式下，当前页超分到一半时翻到下一页，该页进度会
     * **从 0 重新开始**。
     *
     * 机制：本函数调用 [Waifu2x.abortProcessing]，对原生而言是**整页作废** ——
     * 已算完的 tile 不保留，重新排到后从第 0 块重算。而翻页模式下可见页入队
     * （见 [enqueue] 的 highPriority 分支）与 [reprioritizeAround] 都可能触发一次
     * 抢占，于是「翻页越快、白扔越多」，最坏一次白扔整整一页算力（本机
     * 1782×2560 ≈ 3.2 秒）。更隐蔽的代价是：这些重复提交还会放大
     * 「滚动期提交次数 ∝ 楔死帧数」的卡顿（见 waifu2x.cpp 中 interaction_full_stop
     * 的实测结论），等于自己加重卡顿。
     *
     * 修法：只在「打断的损失足够小」时才打断。原生进度已过
     * [PREEMPT_MIN_PROGRESS_PCT] 时**放弃抢占**、让它自然收尾 —— 被抢占的页本来
     * 就会补回队列（requeue=true），所以不会漏算，只是顺序上晚一点；
     * 进度还低时才打断（损失小，又能让新可见页尽早开始）。
     * SY <--
     */
    private fun preemptActiveRequest(reason: String, requeue: Boolean = false) {
        val request = activeRequest ?: return

        // SY --> 进度感知拦截：**只拦「会补回队列」的抢占**。
        // cancelAll / 换章节 / 重置 等场景不走这里（或 requeue=false），语义上就是要
        // 立刻中止，不能因为进度高就拖住它们。SY <--
        if (requeue) {
            val progress: Int = try {
                Waifu2x.getProgressPercent()
            } catch (_: Throwable) {
                -1
            }
            if (progress in PREEMPT_MIN_PROGRESS_PCT..100) {
                logcat(LogPriority.DEBUG) {
                    "ImageEnhancer: Keep running page $activePageIndex at $progress% " +
                        "(>= $PREEMPT_MIN_PROGRESS_PCT%: preempting would lose a whole page; $reason)"
                }
                return
            }
        }

        if (requeue) {
            request.requeueOnCancel.set(true)
        }
        if (!request.cancelled.compareAndSet(false, true)) return

        logcat(LogPriority.DEBUG) {
            "ImageEnhancer: Preempting active page $activePageIndex ($reason)"
        }
        Waifu2x.abortProcessing()
        activeJob?.cancel(CancellationException("Image enhancement preempted: $reason"))
    }

    private val EnhanceRequest.key: String
        get() = requestKey(mangaId, chapterId, pageIndex, pageVariant)

    private fun requestKey(mangaId: Long, chapterId: Long, pageIndex: Int, pageVariant: String): String {
        return "${mangaId}_${chapterId}_${pageIndex}_${pageVariant}"
    }

    // SY --> 会话级去重的读写（见 sessionProcessedPages 的说明）
    //
    // 键里**必须带上配置指纹**。旧实现只按 `mangaId_chapterId` 分桶，于是
    // 「本会话已算完」这个状态会跨配置成立：用户一换超分引擎，缓存键确实变了、
    // cancelAll 也确实清了队列，但这里仍把该页判为「已算过」而直接拒绝入队 ——
    // 结果就是切换引擎后这一页永远不重新超分，与 sessionProcessedPages 的
    // 设计注释（「必须允许按新配置重算，否则用户改了设置却永远看不到效果」）正相反。
    //
    // 带上指纹后：同一配置内继续去重（仍然防「重复超分」），换了配置则自动放行；
    // 切回旧配置时因为旧指纹下该页仍有登记 + 磁盘缓存本来就在，也不会白算一遍。
    private fun sessionKey(mangaId: Long, chapterId: Long, configHash: String): String =
        "${mangaId}_${chapterId}_$configHash"

    private fun isSessionProcessed(
        mangaId: Long,
        chapterId: Long,
        configHash: String,
        pageIndex: Int,
    ): Boolean {
        return sessionProcessedPages[sessionKey(mangaId, chapterId, configHash)]
            ?.contains(pageIndex) == true
    }

    /**
     * 只在「缓存确实已经落盘」时才登记。
     *
     * 模型失败时（native 返回 null）解码器不写缓存也不抛异常，若此时登记，
     * 这一页在本会话内就再也不会被重算 —— 那是另一种 bug（永远是原图）。
     * 所以这里必须回查一次缓存文件。
     */
    private fun markSessionProcessedIfCached(req: EnhanceRequest) {
        val configHash = enhancementConfigHashOrNull(req.context) ?: return
        // SY --> 只要这一页本会话成功算完，就登记到会话去重表，不再依赖磁盘缓存「此刻」是否已落盘。
        // 增强图是异步写入磁盘的（saveQueue/saveWorker），processRequest 刚 await 完去查往往还查不到，
        // 于是旧实现几乎每次都走到 `if (!cached) return`，去重表永远填不上 —— 翻回去的页被反复重新入队重算，
        // 单线程 worker 被挤满，可见页长时间卡在「排队中」。
        // 磁盘缓存本身另有 isCached 闸门兜底（enhance() 里），这里只管「本会话已算过」这一层语义。
        // SY <--
        sessionProcessedPages
            .computeIfAbsent(sessionKey(req.mangaId, req.chapterId, configHash)) { ConcurrentHashMap.newKeySet() }
            .add(req.pageIndex)
    }

    /** 供调试/验证使用：本会话已成功处理的页数（跨配置求和）。 */
    internal fun sessionProcessedCount(mangaId: Long, chapterId: Long): Int {
        val prefix = "${mangaId}_${chapterId}_"
        return sessionProcessedPages.entries
            .filter { it.key.startsWith(prefix) }
            .sumOf { it.value.size }
    }
    // SY <--

    /** 供调试日志使用。 */
    internal fun pendingCount(): Int = pendingRequests.size

    /**
     * SY --> 从任意 Context 反查当前阅读器偏好并算出配置指纹。
     *
     * 去重闸门需要在「入队之前」就知道这一页的缓存键长什么样，所以这里必须
     * 用与 ImageDecoder 落盘、ReaderPageImageView 轮询完全相同的来源。
     * `applicationContext` 不是 App（例如单元测试 / 已销毁进程）时返回 null，
     * 此时闸门退化为「不做缓存判定」，只靠内存登记兜底，不会误杀。
     * SY <--
     */
    private fun enhancementConfigHashOrNull(context: Context): String? {
        val preferences = (context.applicationContext as? App)?.graph?.readerPreferences ?: return null
        return try {
            enhancementConfigHash(preferences)
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "ImageEnhancer: Failed to compute enhancement config hash" }
            null
        }
    }
}

// SY --> 单页增强任务的硬超时。
//
// 唯一的 worker 协程靠 `job.await()` 等待 Coil 完成这一页的推理。原生侧一旦
// 不再返回（驱动挂死 / 显存耗尽 / 融合管线失败路径），没有超时的话整条队列会被
// 永久钉死，阅读器就一直停在「收尾中… / 排队中」。
//
// 数值取 90s：正常单页（含长条图）只需要数秒，所以这个值纯属保险，
// 不承担常规性能调节职责。要更激进/更保守只改这里。
// SY <--
private const val ENHANCEMENT_JOB_TIMEOUT_MS = 90_000L

// SY --> 抢占活跃请求的「进度门槛」（见 [ImageEnhancer.preemptActiveRequest]）。
//
// 原生进度 ≥ 此值时**不打断**当前页，让它跑完。理由：原生 abort 是整页作废，
// 重排后从第 0 块重算，损失 = 进度 × 单页耗时（本机 1782×2560 ≈ 3.2s）；
// 而多等的只是 (1 - 进度) × 单页耗时。进度越高，打断越亏。
//
// 调大 → 更少白算算力、但新可见页要多等一会儿才开始；
// 调小 → 新页更早开始、但更常把快算完的页推倒重来。
// 70% 是「白扔不超过三成、新页最多多等约 1 秒」的折中。
// SY <--
private const val PREEMPT_MIN_PROGRESS_PCT = 70
