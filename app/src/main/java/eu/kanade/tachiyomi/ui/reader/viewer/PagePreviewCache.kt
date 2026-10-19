package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import java.io.File
import java.io.InputStream
import kotlin.math.sqrt

/**
 * SY 2026-10-18 --> 条漫「既不黑也不白」的页面低清预览（blur-up 占位）缓存。
 *
 * 【为什么需要它】
 * 条漫每绑定一页都要让 SubsamplingScaleImageView 重新分块解码；解码窗口（几十毫秒到一秒）
 * 里 SSIV 什么都画不出来，唯一能显示的像素就是「View 自己的 background」。
 * 历史实现给 SSIV 垫的是一层**纯白**（见 ReaderPageImageView.prepareNonAnimatedImageView），
 * 于是深色主题下用户看到的就是白闪；而「超分换图后的视图」「还没建立 SSIV 的加载态」
 * 连白底都没有，透出阅读器底色就是黑闪。
 *
 * 真机实测（G:\code\_tmp_probe，同一滑动脚本 30s）：
 *  · 全部命中磁盘缓存的一轮：纯白占位 3.14s / 纯黑 0s；
 *  · 冷缓存 + 超分实时跑的一轮：纯黑 0.64s + 纯白 0.24s。
 * 用户明确要求「黑底白底都不能接受」，所以这里改成垫**这一页自己的低清缩略图**：
 * 解码窗口里看到的是同一页的模糊版，SSIV 分块解码完成后自然变清晰 —— 没有任何纯色突现。
 *
 * 【预览从哪来】（按代价从低到高）
 *  1. 本内存 LRU：滑回去重绑时零 I/O 命中；
 *  2. **超分缓存里已经算好的 webp 文件**：按 inSampleSize 低清解码，纯本地 I/O。
 *     ⚠️ 这是关键来源 —— 超分 worker 的预载窗口跑在阅读器前面，所以「这一页的页数据
 *     还没下载下来」时超分文件往往已经有了，此时预览是**唯一**能显示出来的页面内容；
 *  3. 换图时手上已经有的那张全分辨率增强位图（[seed]，零额外 I/O）。
 *
 * 【内存】每项按单张像素上限收敛：典型 240×360 的 RGB_565 ≈ 0.17MB，超长条漫页最多 0.4MB，
 *        16 项合计约 3~6MB（阅读器在 2x 超分下内存本就紧张，见 MAX_ENTRIES 的说明）。
 * 【线程】全部方法线程安全；[seed] / [loadFromEnhancedCache] / [decodePreview] 会解码/缩放位图，
 *        **必须在 IO 线程调用**。被淘汰的位图**不 recycle**（可能仍被某个 View 的
 *        BitmapDrawable 引用，回收会导致「Canvas: trying to use a recycled bitmap」崩溃），
 *        交给 GC。
 */
object PagePreviewCache {

    /** 一页在预览缓存里的定位信息（与超分缓存键同构）。 */
    data class Key(
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val variant: String = "",
    )

    /**
     * 预览目标宽度的兜底值（px）。真实调用方应传入**显示宽度**
     * （见 [ReaderPageImageView.buildPagePreview] / [WebtoonViewer.prefetchOpeningPreviews]）。
     *
     * 2026-10-18 第二次调整：原来是 240（约屏宽的 1/4），放大 4.5 倍后在滑动中肉眼可见地糊，
     * 用户反馈「滑动的距离稍微长一点，过程中图片就会变得很模糊…会伤眼睛」。
     * 现在按显示宽度解码 —— 占位图与 SSIV 最终画出来的清晰图在 fit-width 下**视觉一致**，
     * 于是「模糊→清晰」的那次切换不再可见，而占位本身也始终是可读的页面内容。
     */
    private const val DEFAULT_TARGET_WIDTH = 1080

    /**
     * 单张预览的像素上限（RGB_565 下 1.8M px ≈ 3.6MB）。
     *
     * 存在的理由：条漫里长条图很常见（例如 800×4000），只按目标宽度缩会得到 1080×5400 的预览，
     * 单张就 11MB。阅读器本身在 2x 超分下内存已经很紧张（真机实测：SSIV 给单页分配
     * 28.8MB 的 base layer，多个 holder 叠加就会 OOM），所以这里必须给单张也设上限。
     */
    private const val MAX_PREVIEW_PIXELS = 1_800_000

    /**
     * 内存上限：8 项 ≈ 12~29MB（典型 918×1300 RGB_565 ≈ 2.4MB）。
     *
     * 2026-10-18 历程：48 →（OOM 期间收紧）16 → 6 →（占位图改成显示分辨率后）8。
     * 8 这个数是跟着「全局预览窗口」定的：窗口 = 当前页 ±3/4，见
     * [WebtoonViewer.requestPreviewWindow]，LRU 正好吃下整个窗口。
     * 不能再往上加 —— 超分 2x 下 SSIV 的 base layer（单页 28.8MB）才是内存大头。
     */
    private const val MAX_ENTRIES = 8

    /**
     * 预览目标宽度 = 显示宽度 × 0.85。
     *
     * 留 15% 余量是内存与清晰度的折中：918px 的占位图放大到 1080 只有 1.18 倍，
     * 肉眼与最终清晰图无法区分（用户抱怨的是 240px → 1080，4.5 倍的那种糊），
     * 但单张内存比 1080 宽省 30%。
     */
    fun previewTargetWidthFor(displayWidthPx: Int): Int =
        if (displayWidthPx > 0) (displayWidthPx * 0.85f).toInt() else DEFAULT_TARGET_WIDTH

    private val lock = Any()

    private val entries = object : LinkedHashMap<Key, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Bitmap>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun get(key: Key?): Bitmap? {
        if (key == null) return null
        return synchronized(lock) { entries[key] }
    }

    /** 这一页的高宽比 (height / width)；没有预览时为 null。 */
    fun aspectRatio(key: Key?): Float? {
        val bitmap = get(key) ?: return null
        return if (bitmap.width > 0) bitmap.height.toFloat() / bitmap.width.toFloat() else null
    }

    fun put(key: Key, bitmap: Bitmap) {
        synchronized(lock) { entries[key] = bitmap }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * 用一张全分辨率位图生成预览（IO 线程）。已经有预览就不重复做 ——
     * 预览只是占位，不值得为此多花一次缩放。
     */
    fun seed(key: Key, full: Bitmap?, targetWidth: Int = DEFAULT_TARGET_WIDTH): Bitmap? {
        if (full == null || full.isRecycled || full.width <= 0 || full.height <= 0) return null
        get(key)?.let { return it }
        val preview = runCatching { scale(full, targetWidthFor(full.width, full.height, targetWidth)) }
            .getOrNull() ?: return null
        put(key, preview)
        return preview
    }

    /**
     * 超分缓存文件 → 低清预览（IO 线程）。这一页还没被超分过时返回 null。
     *
     * @param configHash 必须与超分落盘用的指纹同源（`enhancementConfigHash(preferences)`）。
     */
    fun loadFromEnhancedCache(
        context: Context,
        key: Key,
        configHash: String,
        targetWidth: Int = DEFAULT_TARGET_WIDTH,
    ): Bitmap? {
        return runCatching {
            ImageEnhancementCache.init(context)
            val file = ImageEnhancementCache.getCachedImage(
                key.mangaId,
                key.chapterId,
                key.pageIndex,
                configHash,
                key.variant,
            ) ?: return null
            decodeFile(file, targetWidth)
        }.getOrNull()
    }

    /**
     * 预取一批页的预览（IO 线程）。
     *
     * 存在的理由：holder 的**第一次绑定**发生在 RecyclerView 首次布局的那一帧，
     * 而预览的读取天生是异步的（读超分 webp + 低清解码，几十毫秒）。不提前算好，
     * 首帧就只能是纯色 —— 真机实测「打开一个全部命中缓存的章节」仍会黑 1.52s
     * （frame 透明 + 当时还没有 SSIV，屏幕上只有转圈）。
     * 由 WebtoonViewer 在首次布局**之前**调用，把开口页附近几页先塞进 LRU。
     *
     * 没有超分缓存的页（从没超分过 / 换过配置）直接跳过 —— 预览只是占位，不是必需。
     */
    fun prefetch(context: Context, configHash: String, keys: List<Key>, targetWidth: Int = DEFAULT_TARGET_WIDTH) {
        for (key in keys) {
            if (get(key) != null) continue
            val preview = loadFromEnhancedCache(context, key, configHash, targetWidth) ?: continue
            put(key, preview)
        }
    }

    /**
     * 从「可重开的流」里解出低清预览（IO 线程）。
     *
     * [open] 会被调用两次（先读图片头拿尺寸，再按 inSampleSize 真解码），所以调用方必须
     * 传一个**可以重复打开**的数据源（例如 okio 的 `peek().inputStream()` 或一个文件）。
     */
    fun decodePreview(open: () -> InputStream?, targetWidth: Int = DEFAULT_TARGET_WIDTH): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null

            val target = targetWidthFor(bounds.outWidth, bounds.outHeight, targetWidth)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, target)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            finishDecode(decoded, target)
        }.getOrNull()
    }

    private fun decodeFile(file: File, targetWidth: Int = DEFAULT_TARGET_WIDTH): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        val target = targetWidthFor(bounds.outWidth, bounds.outHeight, targetWidth)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, target)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return finishDecode(decoded, target)
    }

    /** 2 的幂采样后仍可能偏大：统一缩到目标宽度，避免单页占过多内存。 */
    private fun finishDecode(decoded: Bitmap, targetWidth: Int): Bitmap {
        if (decoded.width <= targetWidth) return decoded
        val scaled = scale(decoded, targetWidth)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /**
     * 这一页的预览目标宽度：先取 [targetWidth]（调用方传显示宽度），再按单张像素上限收敛
     * （超长条漫页会被压得更小，保证单张预览不会占掉几 MB）。
     */
    private fun targetWidthFor(width: Int, height: Int, targetWidth: Int = DEFAULT_TARGET_WIDTH): Int {
        if (width <= 0 || height <= 0) return targetWidth
        val aspect = height.toFloat() / width.toFloat()
        val maxByPixels = sqrt(MAX_PREVIEW_PIXELS / aspect).toInt()
        return targetWidth.coerceAtMost(maxByPixels).coerceAtLeast(48)
    }

    /** 缩到 [targetWidth] 宽，输出 RGB_565（漫画页不透明，省一半内存）。 */
    private fun scale(source: Bitmap, targetWidth: Int): Bitmap {
        val targetHeight = (source.height.toFloat() * targetWidth / source.width)
            .toInt()
            .coerceAtLeast(1)
        val target = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        val paint = Paint().apply { isFilterBitmap = true }
        Canvas(target).drawBitmap(source, null, Rect(0, 0, targetWidth, targetHeight), paint)
        return target
    }

    private fun sampleSizeFor(width: Int, targetWidth: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth) {
            sample *= 2
        }
        return sample
    }
}
