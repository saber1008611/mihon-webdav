package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.pdf.PdfiumRenderer
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import logcat.LogPriority
import mihon.core.archive.RandomAccessSource
import tachiyomi.core.common.util.system.logcat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min
import uy.kohesive.injekt.injectLazy
import java.io.InputStream

/**
 * Loader used to load a chapter from a .pdf file.
 *
 * SY --> Phase C 起改用 pdfium（[PdfiumRenderer]，`io.legere:pdfiumandroid`）逐页渲染成 JPEG 流。
 * 双构造对应两条读取路径：
 *  - [PdfPageLoader]`(UniFile)`：本地文件（fd 随机读），本地源与「整本回退」后的文件用。
 *  - [PdfPageLoader]`(RandomAccessSource)`：随机读源——WebDAV 的 PDF 经
 *    [eu.kanade.tachiyomi.source.webdav.WebDavRandomAccessSource] 走 HTTP Range **流式直读，不整本下载**；
 *    服务器不支持 Range 时该源会自动整本落盘回退，故功能面覆盖旧行为。
 * 翻页、缩放、超分、缓存全部复用现有链路（阅读器契约只要求每页提供一个 `InputStream`）。
 *
 * 内存模型不变：单页渲染宽度钳在 [MIN_TARGET_WIDTH]..[MAX_TARGET_WIDTH]，
 * ARGB_8888 上限约 2048×2900×4B ≈ 23MB，[lock] 串行化渲染，用完立即 recycle。
 */
internal class PdfPageLoader private constructor(
    private val file: UniFile?,
    private val source: RandomAccessSource?,
) : PageLoader() {

    /** 本地 PDF 文件（内容解析器可随机访问的 fd 路线）。 */
    constructor(file: UniFile) : this(file, null)

    /** 随机读源（WebDAV Range 流式 / 已落盘文件的定位读）。 */
    constructor(source: RandomAccessSource) : this(null, source)

    private val context: Context by injectLazy()

    // SY --> 超分移植：与 ArchivePageLoader / DownloadPageLoader 一致，读一次偏好判断整话超分开关。
    private val readerPreferencesOrNull: ReaderPreferences?
        get() = (context.applicationContext as? App)?.graph?.readerPreferences
    // SY <--

    override var isLocal: Boolean = file != null

    private val lock = Any()
    private var renderer: PdfiumRenderer? = null

    // 缓存 getPages() 产出的页，供 preloadNext 预渲染遍历。
    private var loadedPages: List<ReaderPage>? = null

    // SY --> PDF 提速（2026-09-28）：
    // ④ 渲染结果缓存（LRU, accessOrder=true）：同一页反复 stream() 不再重渲染。
    // ① 后台预渲染：loadPage 时把后续若干页提前渲染好，翻页瞬间直接命中缓存。
    private val renderCache = object : LinkedHashMap<Int, ByteArray>(RENDER_CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean =
            size > RENDER_CACHE_SIZE
    }
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableSetOf<Int>()

    // ③ 渲染宽度自适应：开超分时给足细节（MAX），否则按屏幕宽渲染（不再无脑 2048）。
    private val targetMaxWidth: Int by lazy {
        val upscaling = readerPreferencesOrNull?.realCuganEnabled()?.get() == true
        if (upscaling) {
            MAX_TARGET_WIDTH
        } else {
            context.resources.displayMetrics.widthPixels.coerceIn(MIN_TARGET_WIDTH, MAX_TARGET_WIDTH)
        }
    }
    // SY <--

    private fun openRenderer(): PdfiumRenderer {
        renderer?.let { return it }
        // SY --> 打开耗时取证（WARN 保证 release 可见）：定位 500MB 级 PDF「几分钟打不开」
        val t0 = System.currentTimeMillis()
        val created = if (file != null) {
            val fd = context.contentResolver.openFileDescriptor(file.uri, "r")
                ?: error("PdfPageLoader: 无法打开 ${file.uri}")
            PdfiumRenderer.open(fd)
        } else {
            PdfiumRenderer.open(source!!)
        }
        logcat(LogPriority.WARN) { "PdfPageLoader: pdfium 打开耗时 ${System.currentTimeMillis() - t0}ms，${created.pageCount} 页" }
        // SY <--
        renderer = created
        return created
    }

    override suspend fun getPages(): List<ReaderPage> {
        val pdf = openRenderer()
        return (0 until pdf.pageCount)
            .map { index ->
                ReaderPage(index).apply {
                    stream = { renderPage(pdf, index) }
                    status = Page.State.Ready
                }
            }
            .toList()
            .also { loadedPages = it }
    }

    private fun renderPage(pdf: PdfiumRenderer, index: Int): InputStream {
        // ④ 命中缓存直接返回（预渲染过的页也在这里命中）。
        renderCache[index]?.let { return it.inputStream() }
        val bytes = synchronized(lock) {
            renderCache[index] ?: renderPageLocked(pdf, index).also { renderCache[index] = it }
        }
        return bytes.inputStream()
    }

    /** 实际渲染一页为 JPEG 字节（调用前须持有 [lock]）。 */
    private fun renderPageLocked(pdf: PdfiumRenderer, index: Int): ByteArray {
        val t0 = System.currentTimeMillis()
        val bytes = pdf.renderPageJpegBytes(index, MIN_TARGET_WIDTH, targetMaxWidth, JPEG_QUALITY)
        val ms = System.currentTimeMillis() - t0
        if (ms > 2000) logcat(LogPriority.WARN) { "PdfPageLoader: 第 $index 页渲染耗时 ${ms}ms" }
        return bytes
    }

    /** ① 后台预渲染后续 [PRELOAD_AHEAD] 页（已缓存/在途的跳过）。 */
    private fun preloadNext(pdf: PdfiumRenderer, from: Int) {
        val pages = loadedPages ?: return
        val end = min(from + PRELOAD_AHEAD, pages.size - 1)
        for (j in (from + 1)..end) {
            if (renderCache.containsKey(j)) continue
            synchronized(inFlight) {
                if (!inFlight.add(j)) return@synchronized
            }
            preloadScope.launch {
                try {
                    synchronized(lock) {
                        if (renderCache[j] == null) {
                            renderCache[j] = renderPageLocked(pdf, j)
                        }
                    }
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN, t) { "PdfPageLoader: 预渲染第 $j 页失败" }
                } finally {
                    synchronized(inFlight) { inFlight.remove(j) }
                }
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
        // ① 预渲染后续页（当前页仍由 stream() 渲染；这里只提前准备后面的，让翻页瞬间出图）。
        preloadNext(openRenderer(), page.index)
    }

    override fun recycle() {
        super.recycle()
        // ①④ 预渲染协程域取消、渲染缓存释放。
        runCatching { preloadScope.cancel() }
        synchronized(inFlight) { inFlight.clear() }
        synchronized(lock) { renderCache.clear() }
        // SY --> ANR 兜底：主线程退出阅读器时 PdfiumRenderer.close() 会去拿 pdfium 文档锁，
        // 而后台渲染线程（renderPageJpegStream，慢就慢在 WebDAV Range 读）正持有该锁，
        // 主线程无期限等锁 → 弹「未响应」。改为后台线程关闭、主线程立即返回。
        val r = renderer
        renderer = null
        if (r != null) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { r.close() } }
        }
        // SY <--
    }

    private companion object {
        const val MIN_TARGET_WIDTH = 1200
        const val MAX_TARGET_WIDTH = 2048
        const val JPEG_QUALITY = 92

        // ① 每页就绪后向后预渲染多少页。
        const val PRELOAD_AHEAD = 3

        // ④ 渲染结果缓存页数（每页 JPEG 约数百 KB～1MB）。
        const val RENDER_CACHE_SIZE = 5
    }
}
