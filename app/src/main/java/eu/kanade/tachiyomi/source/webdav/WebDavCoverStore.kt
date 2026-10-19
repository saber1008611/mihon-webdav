package eu.kanade.tachiyomi.source.webdav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import coil3.imageLoader
import coil3.memory.MemoryCache
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import logcat.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SY --> 2026-09-28 封面保存策略（移植自 Komiho `WebDavCoverCache`，适配 mihonw 的目录模型）。
 *
 * 背景：此前 WebDAV / 本地文件夹图源的封面**只在浏览网格项可见时**才按需生成
 * （Coil → `WebDavCoverProvider` / `LocalWebDavCoverProvider` → `resolveCover`）。
 * 后果是：没逛到过的漫画永远没封面；书架/历史/书签行首次出现时才现抽一次
 * （大归档要解析中央目录 + 读首图），既慢又让服务器多背一次请求。
 *
 * 策略（与 Komiho 同口径，但键取「漫画目录」而非「章节文件」——mihonw 的书架/历史/
 * 书签行显示的是**漫画**封面，Komiho 是章节封面，故此处按漫画粒度落盘）：
 *  - **打开章节时顺手落盘**：`ChapterLoader` 拿到页加载器后调用 [generateAsync]，
 *    后台（守护协程、同 key 去重、失败静默）走既有的 `ensureCover` → `resolveCover`，
 *    产出 `filesDir/<covers>/<mangaUrl 转下划线>.jpg`；
 *  - **之后一律命中磁盘**：书架网格 / 历史 / 书签 / 文件夹视图共用同一目录，
 *    Provider 命中磁盘即直接返回 fd，**零网络请求**（与浏览页「无封面」同口径，防风控）；
 *  - **失败不固化**：抽不出封面（rar/cbr 等无法随机访问、加密、网络失败）只影响本次，
 *    下次打开章节自动重试，与 `ensureCover` 的「空白页不跳过」自愈逻辑一致。
 *
 * 缓存目录名必须与 `WebDavCoverProvider` / `LocalWebDavCoverProvider` / 两个 `ensureCover`
 * 完全一致，否则「存进去」和「读出来」会落到两处（此前 17:52 那次封面写错目录的坑）。
 * SY <--
 */
object WebDavCoverStore {

    /** WebDAV 云盘源的封面目录（filesDir 下），与 WebDavCoverProvider / WebDavSource.ensureCover 一致。 */
    private const val DIR_WEBDAV = "webdav-covers"

    /** 本地文件夹版图源的封面目录，与 LocalWebDavCoverProvider / LocalWebDavSource.ensureCover 一致。 */
    private const val DIR_LOCAL = "localwebdav-covers"

    /** SY 2026-10-05：网盘源（夸克 / 阿里云盘）的封面目录，与 WebDavCoverProvider（authority 路由）一致。 */
    private const val DIR_QUARK = "quark-covers"
    private const val DIR_ALIYUNPAN = "aliyun-covers"

    /** SY 2026-10-08：115 开放平台（OAuth）源，id 体系与 Cookie 模式不同，封面缓存独立。 */
    private const val DIR_CLOUD115OPEN = "cloud115open-covers"

    /** 与两个 Provider 的候选扩展名同序（先命中的优先）。 */
    private val COVER_EXTENSIONS = listOf("jpg", "webp", "png", "jpeg")

    /**
     * SY --> 占位封面目录：**必须与真实封面目录分开**。
     *
     * 否则占位图会被 `existingCoverFile` 当成「已生成」，导致用户真正读过该卷之后
     * （整本进章节缓存、可以零请求抽真封面）也永远补不上真封面。
     * 分开后：真实目录保持为空 → 每次仍会尝试；Provider 在真实封面缺失时才拿占位兜底显示。
     * SY <--
     */
    private const val DIR_FALLBACK = "webdav-covers-fallback"

    /**
     * SY --> 深链 PDF 占位封面上的固定说明：**一行一句**（2026-09-28 用户要求「白底黑字、文字放大、分行」）。
     *
     * 缩略图里小字看不清，所以标题与说明都显著放大，说明拆成三行独立短句。
     * SY <--
     */
    private val PLACEHOLDER_NOTE_LINES = listOf("深链PDF", "无法流式", "浏览后出封面")

    /** 占位封面文件（真实封面缺失时由 Provider 兜底返回）。 */
    fun fallbackCoverFile(context: Context, base: String): File =
        File(File(context.filesDir, DIR_FALLBACK).apply { mkdirs() }, "$base.jpg")

    /**
     * SY 2026-10-08：占位图对应的深链章节路径（sidecar `<base>.deep`）。
     * 返回 null = 旧版占位图（无 sidecar），调用方回退 mangaUrl 判定。
     */
    fun deepChainChapterPath(context: Context, base: String): String? = runCatching {
        File(File(context.filesDir, DIR_FALLBACK), "$base.deep")
            .takeIf { it.exists() && it.length() > 0L }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * SY --> 画一张「深链 PDF 占位封面」并落盘（B1，2026-09-28）。
     *
     * **为什么用占位而不是继续抽**：这类卷是「每页一个增量 revision」拼出来的 PDF（实测 `/Prev`
     * 链 **205 跳**），用 Range 抽一张首页封面要**串行做 ~230 次请求 / ~232MB**（`openProxy` 4MB 块
     * 背后仍是 1MB 取块），在后端是 **115 + OpenList** 时（风控按「短时间调用次数」触发）足以把
     * 账号踢下线；换整本下载又要 200MB+ 流量。
     *
     * 所以批量场景（扫描封面 / 网格可见项）一律不抽，改画这张说明图；等用户真正读过该卷
     * （整本进了 `webdav-archives` 缓存）后，`resolveCover` 会改从**本地**抽真封面（零请求）。
     *
     * 排版（用户 2026-09-28 指定）：白底黑字、4 行——① 漫画名/文件夹名 ② 深链PDF ③ 无法流式
     * ④ 浏览后出封面。
     * SY <--
     */
    fun writeDeepChainPlaceholder(
        context: Context,
        base: String,
        title: String,
        deepChapterPath: String? = null,
    ) {
        val file = fallbackCoverFile(context, base)
        // SY 2026-10-08：落一个「深链章节路径」sidecar（<base>.deep），供 ensureCover 的
        // 「读后升级」判定零网络命中。单文件漫画 mangaUrl 即章节路径（旧判定可用），
        // 但文件夹漫画的 mangaUrl 是目录、缓存按章节路径存——旧判定永远 miss，
        // 占位图读后永远不升级。sidecar 与占位图同目录同名，仅扩展名不同。
        if (!deepChapterPath.isNullOrBlank()) {
            runCatching {
                File(File(context.filesDir, DIR_FALLBACK), "$base.deep").writeText(deepChapterPath)
            }
        }
        // 注意：**故意不做「已存在就跳过」**。占位图是纯 Canvas 绘制（几毫秒），而缓存会让
        // 样式改版后旧图永远留在设备上（改成白底黑字那次就是这么踩的：深色旧图一直不更新）。
        runCatching {
            val w = 450
            val h = 600
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bmp)
                canvas.drawColor(BACKGROUND_COLOR)
                // 细描边：纯白封面放在浅色书架网格里会和背景糊成一片，需要一圈浅灰边界
                canvas.drawRect(
                    1f,
                    1f,
                    w - 1f,
                    h - 1f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = BORDER_COLOR
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                    },
                )

                // 第 1 行：漫画名 / 文件夹名（单行、自动缩号、超长省略）
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = TEXT_COLOR
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val titleLine = fitSingleLine(title, titlePaint, w - 48f, TITLE_MAX_TEXT_SIZE, TITLE_MIN_TEXT_SIZE)
                canvas.drawText(titleLine, w / 2f, TITLE_BASELINE_Y, titlePaint)

                // 第 2-4 行：固定三句说明，一行一句
                val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = TEXT_COLOR
                    textSize = NOTE_TEXT_SIZE
                    textAlign = Paint.Align.CENTER
                }
                var y = NOTE_FIRST_BASELINE_Y
                PLACEHOLDER_NOTE_LINES.forEach { line ->
                    canvas.drawText(line, w / 2f, y, notePaint)
                    y += NOTE_LINE_HEIGHT
                }

                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            } finally {
                bmp.recycle()
            }
            logcat(LogPriority.INFO) { "WebDavCoverStore: 写入深链 PDF 占位封面 $base" }
        }.onFailure { e ->
            // 注意：本文件用的是 `logcat.logcat`（不带 Throwable 参数的重载），异常信息自己拼进消息里。
            logcat(LogPriority.WARN) { "WebDavCoverStore: 占位封面生成失败 $base：${e.message}" }
        }
    }

    /**
     * 单行自适应：从 [maxSize] 逐号缩小直到整串放得进 [maxWidth]；仍放不下就截断加省略号。
     * 最终字号写回 [paint]，调用方随后直接 `drawText` 即可。
     */
    private fun fitSingleLine(
        text: String,
        paint: Paint,
        maxWidth: Float,
        maxSize: Float,
        minSize: Float,
    ): String {
        val src = text.trim().ifBlank { "（未命名）" }
        var size = maxSize
        while (size > minSize) {
            paint.textSize = size
            if (paint.measureText(src) <= maxWidth) return src
            size -= 2f
        }
        paint.textSize = minSize
        val ellipsis = "…"
        var end = src.length
        while (end > 1 && paint.measureText(src.substring(0, end) + ellipsis) > maxWidth) end--
        return src.substring(0, end) + ellipsis
    }

    // 白底黑字（用户 2026-09-28 指定）；放大字号让缩略图里也能看清。
    private const val BACKGROUND_COLOR = 0xFFFFFFFF.toInt()
    private const val TEXT_COLOR = 0xFF000000.toInt()
    private const val BORDER_COLOR = 0xFFDDDDDD.toInt()
    private const val TITLE_MAX_TEXT_SIZE = 44f
    private const val TITLE_MIN_TEXT_SIZE = 24f
    private const val TITLE_BASELINE_Y = 235f
    private const val NOTE_TEXT_SIZE = 32f
    private const val NOTE_FIRST_BASELINE_Y = 335f
    private const val NOTE_LINE_HEIGHT = 56f

    /**
     * 延迟多久再抽封面。避开与阅读线程同时读同一本书的竞态：
     * 打开章节的首帧会去读归档/缓存文件，封面抽取若同时进行会争抢磁盘与连接
     * （本地图源有 SAF→cache 的整本拷贝，WebDAV 有 Range 首图读取）。延迟几百毫秒
     * 对用户无感，却能让首屏更快。
     */
    private const val GENERATE_DELAY_MS = 800L

    /** 正在生成的 key（`backend:url`）。同窗口去重：连开同一本的多章只触发一次抽取。 */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 抽取段的分条带互斥（固定 64 条，按 key 哈希取用；不随漫画数量增长）。
     *
     * 为什么必须有：`resolveCover` 的中间文件路径是**按漫画确定的**
     * （`{coverDir}/{mangaUrl}.jpg.tmp`），而 `openFile` 可能被并发调用
     * （网格项 + 历史行 + 文件夹视图同时可见同一本，或左右预载）。两个抽取同时
     * 往同一个 `.tmp` 写 → 内容交错、`renameTo` 后得到损坏封面（且是「成功」的，
     * 会被缓存固化）。磁盘命中检查本身不需要锁（只读且原子命中）。
     */
    private val stripLocks = Array(64) { Mutex() }

    /** 在 [key] 所属条带上串行执行 [block]（suspend，供 Provider 的抽取段/后台生成包裹）。 */
    suspend fun <T> withCoverLock(key: String, block: suspend () -> T): T {
        val idx = (key.hashCode() and 0x7FFFFFFF) % stripLocks.size
        return stripLocks[idx].withLock { block() }
    }

    /**
     * 互斥键：**必须**由 [base]（下划线化后的基名）与后端构成。
     * Provider 与 [generateAsync] 都用它取键，否则「网格按需抽取」与「打开章节顺手生成」
     * 会各持一把锁去写同一个 `.tmp` —— 锁形同虚设。
     */
    fun lockKey(backend: Backend, base: String): String = "${backend.name}:$base"

    /**
     * 封面后端：决定缓存目录与走哪个源的 `ensureCover`。
     * SY 2026-10-05：新增 CLOUD115 / QUARK（网盘源），源分发统一按 `(source as? BaseWebDavSource)`，
     * 不再逐后端 hard-cast。
     */
    enum class Backend { WEBDAV, LOCAL, QUARK, ALIYUNPAN, CLOUD115OPEN }

    private fun coverDir(context: Context, backend: Backend): File = File(
        context.filesDir,
        when (backend) {
            Backend.WEBDAV -> DIR_WEBDAV
            Backend.LOCAL -> DIR_LOCAL
            Backend.QUARK -> DIR_QUARK
            Backend.ALIYUNPAN -> DIR_ALIYUNPAN
            Backend.CLOUD115OPEN -> DIR_CLOUD115OPEN
        },
    ).apply { mkdirs() }

    /** 封面缓存文件的基础名（与 `resolveCover` / Provider 的命名规则逐字一致）。 */
    private fun baseName(mangaUrl: String): String = mangaUrl.trimStart('/').replace('/', '_')

    /**
     * 磁盘上已存在的封面文件（历史 / 书签 / 文件夹视图用）。
     * 返回 null 表示「尚未生成」——调用方应显示占位图而**不要**向服务器发请求（与 Komiho 同口径）。
     * 空白页（坏版本遗留的纯白图）视为未命中，走 [generateAsync] 重新抽取，实现自愈。
     */
    fun existingCoverFile(context: Context, backend: Backend, mangaUrl: String): File? {
        val base = baseName(mangaUrl)
        if (base.isBlank()) return null
        val dir = coverDir(context, backend)
        return COVER_EXTENSIONS.firstNotNullOfOrNull { ext ->
            File(dir, "$base.$ext")
                .takeIf { it.exists() && it.length() > 0 && !CoverQuality.isBlank(it) }
        }
    }

    /**
     * 异步生成并落盘 [mangaUrl] 的封面。命中缓存立即返回；同 key 去重；失败静默（仅 logcat）。
     *
     * [mangaUrl] 必须是**漫画在源里的 url**（= 漫画目录相对路径，如 `CloudDrive/loll/1LBBA`），
     * 即 `ChapterLoader.manga.url`——与浏览列表填进 `thumbnail_url` 的值同源，因此键必然对齐。
     */
    fun generateAsync(context: Context, backend: Backend, mangaUrl: String, source: Source) {
        val url = mangaUrl.trimStart('/')
        if (url.isBlank()) return
        // 已有有效封面：零成本返回（绝大多数情况，浏览过/读过一次之后）。
        if (existingCoverFile(context, backend, url) != null) return
        val key = lockKey(backend, baseName(url))
        if (!inFlight.add(key)) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(GENERATE_DELAY_MS)
                // 延迟期间可能已被别的路径生成（或用户已在浏览页扫过），再确认一次。
                if (existingCoverFile(appContext, backend, url) != null) return@launch
                // 与 Provider 的按需抽取走同一把锁：`resolveCover` 的 `.tmp` 中间文件按漫画确定，
                // 两者若并发就会互相覆盖出损坏封面（且是「成功」的，会被缓存固化）。
                val ok = withCoverLock(key) {
                    // SY 2026-10-05：四个后端的源都是 BaseWebDavSource（ensureCover 就在基类），
                    // 统一按基类分发，新增网盘源不再需要在这里加分支。
                    existingCoverFile(appContext, backend, url) != null ||
                        (source as? BaseWebDavSource)?.ensureCover(url) ?: false
                }
                logcat(LogPriority.INFO) {
                    "WebDavCoverStore: 打开章节生成封面 ${if (ok) "OK" else "未产出"} [$backend] $url"
                }
            } catch (e: Throwable) {
                logcat(LogPriority.INFO) { "WebDavCoverStore: 封面生成失败 [$backend] $url：${e.message}" }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /**
     * SY --> 章节加载完成后（深链 PDF 此时已整本缓存）再补一次封面生成。
     * [generateAsync] 在打开章节 800ms 后触发，那时深链 PDF 往往还没下完，只会写出占位图；
     * 等用户真正读完一话、PDF 已落盘后再抽一次，才能零请求拿到真封面。这里只在「真封面（
     * webdav-covers 真实目录）尚缺」时才动手，避免与 generateAsync 重复抽取；抽成后立刻
     * [invalidateCoverImage] 让网格从占位图切到真封面。
     * SY <--
     */
    fun generateNowIfMissing(context: Context, backend: Backend, mangaUrl: String, source: Source) {
        val url = mangaUrl.trimStart('/')
        if (url.isBlank()) return
        if (existingCoverFile(context, backend, url) != null) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val key = lockKey(backend, baseName(url))
                val ok = withCoverLock(key) {
                    // SY 2026-10-05：同 generateAsync —— 统一按基类分发。
                    existingCoverFile(appContext, backend, url) != null ||
                        (source as? BaseWebDavSource)?.ensureCover(url) ?: false
                }
                if (ok && existingCoverFile(appContext, backend, url) != null) {
                    invalidateCoverImage(appContext, backend, url)
                }
            } catch (e: Throwable) {
                logcat(LogPriority.INFO) { "WebDavCoverStore: 章节后补封面失败 [$backend] $url：${e.message}" }
            }
        }
    }

    /**
     * SY --> 深链 PDF 占位图替换真封面后，必须让网格立即刷新。
     * 内容 URI 封面不走 Coil 磁盘缓存（MangaCoverFetcher 对 Type.URI 不写 diskCacheKey），
     * 但会进**内存缓存**：占位图一旦被解码进内存，网格就一直显示旧占位，即使真封面已落盘。
     * 按 MangaCoverKeyer 的键（"$url;$lastModified"）摘除对应内存缓存条目。lastModified 对
     * 网盘漫画多为 0，并兜底遍历所有以该 URI 开头的键，确保摘除干净。
     * SY <--
     */
    fun invalidateCoverImage(context: Context, backend: Backend, mangaUrl: String) {
        val uri = coverContentUri(backend, mangaUrl)
        try {
            val mc = context.imageLoader.memoryCache ?: return
            val prefix = "$uri;"
            mc.keys.filter { it.key.startsWith(prefix) }.forEach { mc.remove(it) }
            mc.remove(MemoryCache.Key("$uri;0"))
        } catch (e: Throwable) {
            logcat(LogPriority.WARN) { "WebDavCoverStore: 封面缓存失效失败 $uri：${e.message}" }
        }
    }

    /** 拼出与列表 thumbnail_url 完全一致的 content:// 直链（供 Coil 内存缓存键对齐）。 */
    private fun coverContentUri(backend: Backend, mangaUrl: String): String {
        val authority = when (backend) {
            Backend.WEBDAV -> WebDavCoverBridge.COVER_AUTHORITY
            Backend.LOCAL -> LocalWebDavCoverBridge.COVER_AUTHORITY
            Backend.QUARK -> WebDavCoverBridge.QUARK_AUTHORITY
            Backend.ALIYUNPAN -> WebDavCoverBridge.ALIYUN_AUTHORITY
            Backend.CLOUD115OPEN -> WebDavCoverBridge.CLOUD115OPEN_AUTHORITY
        }
        return "content://$authority/${Uri.encode(mangaUrl)}"
    }
}
