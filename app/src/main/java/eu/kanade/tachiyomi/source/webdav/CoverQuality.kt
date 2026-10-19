package eu.kanade.tachiyomi.source.webdav

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * SY --> 封面「空白页」判定（2026-09-26）。
 *
 * 背景事故：`WebDavRandomAccessSource.read()` 的偏移 bug 时期，pdfium 拿到的是错位数据，
 * 会把 PDF 渲染成**纯白无内容**的一页，但**不抛异常**、编码出来的还是一张合法 JPEG——
 * 于是被当成「封面抽取成功」写进 `webdav-covers/`。之后所有扫描都因为
 * 「缓存已存在就跳过」而永不重算，书架上是 5 个白格子（冰菓 / 圣君想要纯洁的生活 /
 * 最强漫画家… / 乱马 / Q-强殖装甲 高版本，全部写于 17:41–17:45 那段坏版本时期）。
 *
 * 另一条固化路径：`CloudSyncService.downloadCover()` 的 `if (file.exists()) return file`
 * 会把这个空白文件当成「远端 cover 已下载」直接返回，`resolveCover` 连抽取都进不去。
 *
 * 因此判定必须同时用在三处：缓存命中检查、扫描跳过检查、远端 cover 命中检查。
 * 判据用「墨点占比」而不是整幅均值：纯白页实测墨点 0.00%，而最浅的真实封面
 * （平行天堂，书底/条码页）也有 8.33%，两者相差一个数量级，阈值取 0.5% 极安全。
 * SY <--
 */
internal object CoverQuality {

    /** 取样缩略的最长边（越小越快；128 已足够区分空白页与真封面）。 */
    private const val SAMPLE_MAX = 128

    /** 亮度低于此值算「墨点」（0-255）。 */
    private const val INK_LEVEL = 200

    /** 墨点占比低于此值即判定为空白页。 */
    private const val MIN_INK_RATIO = 0.005

    /** [file] 是否是一张「内容空白」的图（纯白/近白页）。解码失败一律返回 false（不误杀）。 */
    fun isBlank(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return@runCatching false
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= SAMPLE_MAX) sample *= 2
            val bmp = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@runCatching false
            try {
                isBlank(bmp)
            } finally {
                bmp.recycle()
            }
        }.getOrDefault(false)
    }

    /** [bmp] 是否是一张「内容空白」的图。 */
    fun isBlank(bmp: Bitmap): Boolean {
        if (bmp.width <= 0 || bmp.height <= 0) return true
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        var ink = 0
        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r * 299 + g * 587 + b * 114 < INK_LEVEL * 1000) ink++
        }
        return ink.toDouble() / pixels.size < MIN_INK_RATIO
    }
}
