package eu.kanade.tachiyomi.util.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import io.legere.pdfiumandroid.api.PdfiumSource
import mihon.core.archive.RandomAccessSource
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min

/**
 * SY --> Phase C：pdfium（`io.legere:pdfiumandroid`）渲染封装，替换 Android 内置 PdfRenderer。
 *
 * 相比 PdfRenderer 的两点提升：
 *  - [PdfiumSource] 支持回调式随机读（FPDF_FILEACCESS），可直接吃 [RandomAccessSource]：
 *    WebDAV 的 PDF 用 HTTP Range 边翻边取，**不再整本下载**（大文件稳健化的核心）。
 *  - 对超大/结构复杂 PDF 的容错远好于 PdfRenderer（后者经常直接抛异常打不开）。
 *
 * PdfiumCore 内部对原生调用统一加锁，进程内共享一个实例即可。
 * SY <--
 */
class PdfiumRenderer private constructor(
    private val document: PdfDocument,
    private val owned: List<Closeable>,
) : Closeable {

    val pageCount: Int
        get() = document.getPageCount()

    /** 渲染第 [index] 页为 JPEG 内存流。宽度钳在 [minWidth]..[maxWidth]（点）之间。 */
    fun renderPageJpegStream(
        index: Int,
        minWidth: Int,
        maxWidth: Int,
        quality: Int,
    ): InputStream = renderPageJpegBytes(index, minWidth, maxWidth, quality).inputStream()

    /**
     * SY --> 渲染第 [index] 页为 JPEG 字节数组。
     * 供 [eu.kanade.tachiyomi.ui.reader.loader.PdfPageLoader] 的渲染结果缓存 / 后台预渲染复用，
     * 避免同一页被多次渲染（原实现每次 stream() 都重渲染一遍）。
     * SY <--
     */
    fun renderPageJpegBytes(
        index: Int,
        minWidth: Int,
        maxWidth: Int,
        quality: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        renderPage(index, out, minWidth, maxWidth, quality)
        return out.toByteArray()
    }

    /** 渲染第 [index] 页为 JPEG 直接写入 [outFile]。 */
    fun renderPageJpegFile(
        index: Int,
        outFile: File,
        minWidth: Int,
        maxWidth: Int,
        quality: Int,
    ) {
        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { renderPage(index, it, minWidth, maxWidth, quality) }
    }

    private fun renderPage(
        index: Int,
        out: OutputStream,
        minWidth: Int,
        maxWidth: Int,
        quality: Int,
    ) {
        val page = document.openPage(index) ?: throw IOException("pdfium 无法打开第 $index 页")
        try {
            val pointWidth = page.getPageWidthPoint().coerceAtLeast(1)
            val pointHeight = page.getPageHeightPoint().coerceAtLeast(1)
            val targetWidth = pointWidth.coerceAtLeast(minWidth).coerceAtMost(maxWidth)
            val scale = targetWidth.toFloat() / pointWidth
            val width = (pointWidth * scale).toInt().coerceAtLeast(1)
            val height = (pointHeight * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                page.renderPageBitmap(
                    bitmap,
                    0,
                    0,
                    width,
                    height,
                    canvasColor = Color.WHITE,
                    pageBackgroundColor = Color.WHITE,
                )
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }

    override fun close() {
        runCatching { document.close() }
        owned.forEach { runCatching { it.close() } }
    }

    companion object {
        private val core: PdfiumCore by lazy { PdfiumCore() }

        /** 本地文件：从已打开的 [fd] 读（fd 由返回的 renderer 负责关闭）。 */
        fun open(fd: ParcelFileDescriptor): PdfiumRenderer =
            PdfiumRenderer(core.newDocument(fd), listOf(fd))

        /** 随机读源（HTTP Range 流式 / 本地定位读均可）：[source] 由返回的 renderer 负责关闭。 */
        fun open(source: RandomAccessSource): PdfiumRenderer =
            PdfiumRenderer(core.newDocument(RandomAccessPdfiumSource(source)), listOf(source))

        /**
         * SY --> 随机读源经「代理 fd」暴露给 pdfium：原生层照常按 fd 随机读，读请求被
         * [RangeProxyCallback] 拦截后聚合成整块拉取。相比 [open] 的逐次转发，稀疏小读
         * 的请求数下降两个数量级，高延迟云盘上打开 PDF 从数分钟降到秒级。
         * [source] 由回调的 onRelease 负责关闭。
         */
        fun openProxy(context: Context, source: RandomAccessSource): PdfiumRenderer {
            val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
            if (storage != null) {
                val pfd = runCatching {
                    storage.openProxyFileDescriptor(
                        ParcelFileDescriptor.MODE_READ_ONLY,
                        RangeProxyCallback(source),
                        null,
                    )
                }.getOrNull()
                if (pfd != null) {
                    val doc = runCatching { core.newDocument(pfd) }.getOrNull()
                    if (doc != null) {
                        // 代理 fd + source 一起归本 renderer 管（onRelease 不再关 source）
                        return PdfiumRenderer(doc, listOf(pfd, source))
                    }
                    runCatching { pfd.close() }
                }
            }
            // 回退：代理 fd 不可用/打开失败时走直连随机读，行为与本轮修复后一致，
            // 保证 PDF 封面不会因为这条路失效而整体失败。
            return PdfiumRenderer(core.newDocument(RandomAccessPdfiumSource(source)), listOf(source))
        }
        // SY <--
    }
}

/**
 * [RandomAccessSource] → [PdfiumSource] 适配。pdfium 要求精确读满 size 字节
 * （不足视为 EOF/错误），而 RandomAccessSource 允许短读（块缓冲命中边界），
 * 故这里循环补齐；任何 I/O 失败返回 -1（pdfium 侧按错误处理，宁可报错不出花图）。
 */
private class RandomAccessPdfiumSource(
    private val source: RandomAccessSource,
) : PdfiumSource {

    override val length: Long
        get() = source.size

    override fun read(
        position: Long,
        buffer: ByteArray,
        size: Int,
    ): Int {
        // SY --> pdfium 会试探性读到文件末尾之外（找 startxref 时固定读尾部一块很常见）。
        // 请求量必须钳在 length 之内：否则 source.read 返回空、这里以 -1 报「I/O 错误」，
        // 整个 newDocument 直接失败（cannot create document: File not in PDF format）。
        // 钳制后最多是短读，pdfium 能正常继续解析。
        val available = length - position
        if (available <= 0L) return 0
        val want = min(size.toLong(), available).toInt()
        // SY <--
        var done = 0
        while (done < want) {
            val part = try {
                source.read(position + done, want - done)
            } catch (t: Throwable) {
                return if (done > 0) done else -1
            }
            if (part.isEmpty()) return if (done > 0) done else 0
            val n = min(part.size, want - done)
            part.copyInto(buffer, done, 0, n)
            done += n
        }
        return done
    }

    override fun close() {
        source.close()
    }
}
