package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Loader used to load a chapter from a .pdf file.
 *
 * SY --> 用 Android 内置的 `PdfRenderer`（API 21+）逐页渲染成 JPEG 字节流，再交给现有阅读器。
 * 不引入任何第三方 PDF 库，APK 体积不变。翻页、缩放、超分、缓存全部复用现有链路，
 * 因为阅读器的契约只要求每页能提供一个 `InputStream`（见 ArchivePageLoader / EpubPageLoader）。
 *
 * 注意点：
 *  - `PdfRenderer` 需要**可随机访问**的 fd。本 fork 的 WebDAV 源本来就是「先下载到缓存再读本地
 *    文件」（`WebDavSource.getArchiveFile`），因此天然满足；本地源同理。
 *  - `PdfRenderer` 非线程安全，且**同一时刻只能打开一页**，故渲染整段加锁、并在 finally 里 close。
 *  - 只能「按页渲染」，无法提取 PDF 内嵌的原始图片；对扫描版漫画 PDF 观感等同原图，
 *    对矢量/文本型 PDF 反而更好。加密或异常的 PDF 会打开失败。
 * SY <--
 */
internal class PdfPageLoader(private val file: UniFile) : PageLoader() {
    private val context: Context by injectLazy()

    // SY --> 超分移植：与 ArchivePageLoader / DownloadPageLoader 一致，读一次偏好判断整话超分开关。
    private val readerPreferencesOrNull: ReaderPreferences?
        get() = (context.applicationContext as? App)?.graph?.readerPreferences
    // SY <--

    override var isLocal: Boolean = true

    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    // SY --> 整话超分：缓存 getPages() 产出的页，供 enqueueAllPagesForEnhancement 遍历。
    private var loadedPages: List<ReaderPage>? = null
    // SY <--

    private fun openRenderer(): PdfRenderer {
        renderer?.let { return it }
        val fd = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: error("PdfPageLoader: 无法打开 ${file.uri}")
        val created = PdfRenderer(fd)
        pfd = fd
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

    /**
     * 把第 [index] 页渲染成 JPEG 流。渲染宽度限制在
     * [MIN_TARGET_WIDTH]..[MAX_TARGET_WIDTH] 之间：太窄会糊，太宽会占大量内存
     * （一页 ARGB_8888 上限约 2048×2900×4B ≈ 23MB，渲染完立即 recycle）。
     */
    private fun renderPage(pdf: PdfRenderer, index: Int): InputStream {
        return synchronized(lock) {
            val page = pdf.openPage(index)
            try {
                val targetWidth = page.width
                    .coerceAtLeast(MIN_TARGET_WIDTH)
                    .coerceAtMost(MAX_TARGET_WIDTH)
                val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    // PDF 可能透明，先铺白底，避免渲染出黑块。
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    ByteArrayInputStream(out.toByteArray())
                } finally {
                    bitmap.recycle()
                }
            } finally {
                page.close()
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    // SY --> 整话超分：PDF 的页同样都是 Ready 状态，逐页触发 enhance() 即可，
    // 与 ArchivePageLoader 保持一致的语义（「整话超分」开关对 PDF 章节也生效）。
    override fun enqueueAllPagesForEnhancement() {
        if (readerPreferencesOrNull?.realCuganWholeChapter()?.get() != true) return
        val pages = loadedPages ?: return
        pages.forEach { page ->
            runCatching { ImageEnhancer.enhance(context, page, highPriority = false) }
        }
        logcat(LogPriority.DEBUG) {
            "PdfPageLoader: whole-chapter enhancement enqueued ${pages.size} pages"
        }
    }
    // SY <--

    override fun recycle() {
        super.recycle()
        renderer?.close()
        renderer = null
        pfd?.close()
        pfd = null
    }

    private companion object {
        const val MIN_TARGET_WIDTH = 1200
        const val MAX_TARGET_WIDTH = 2048
        const val JPEG_QUALITY = 92
    }
}
