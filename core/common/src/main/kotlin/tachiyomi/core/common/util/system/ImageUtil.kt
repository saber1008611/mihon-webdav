package tachiyomi.core.common.util.system

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.graphics.alpha
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.blue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import androidx.core.graphics.green
import androidx.core.graphics.red
import ca.mpreg.imagedecoder.ImageDecoder
import com.hippo.unifile.UniFile
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImageUtil {

    fun isImage(name: String?, openStream: (() -> InputStream)? = null): Boolean {
        if (name == null) return false

        val extension = name.substringAfterLast('.')
        return ImageType.entries.any { it.extension == extension } || openStream?.let { findImageType(it) } != null
    }

    fun findImageType(openStream: () -> InputStream): ImageType? {
        return openStream().use { findImageType(it) }
    }

    fun findImageType(stream: InputStream): ImageType? {
        return try {
            val format = ImageDecoder.new(stream).use { dec -> dec.format }
            when (format) {
                "jpeg" -> ImageType.JPEG
                "png" -> ImageType.PNG
                "webp" -> ImageType.WEBP
                "gif" -> ImageType.GIF
                "heif" -> ImageType.HEIF
                "jxl" -> ImageType.JXL
                "jp2" -> ImageType.JP2
                else -> null
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "findImageType: ${e.message}" }
            null
        }
    }

    fun getExtensionFromMimeType(mime: String?, openStream: () -> InputStream): String {
        val type = mime?.let { ImageType.entries.find { it.mime == mime } } ?: findImageType(openStream)
        return type?.extension ?: "jpg"
    }

    fun isAnimatedAndSupported(source: BufferedSource): Boolean {
        return try {
            val type = findImageType(source.peek().inputStream()) ?: return false
            when (type) {
                ImageType.GIF -> true
                ImageType.WEBP, ImageType.HEIF -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
                    ImageDecoder.new(source.peek().inputStream()).use { dec -> dec.pages > 1 }
                }

                else -> false
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "isAnimatedAndSupported: ${e.message}" }
            false
        }
    }

    enum class ImageType(val mime: String, val extension: String) {
        AVIF("image/avif", "avif"),
        GIF("image/gif", "gif"),
        HEIF("image/heif", "heif"),
        JPEG("image/jpeg", "jpg"),
        JXL("image/jxl", "jxl"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
        JP2("image/jp2", "jp2"),
        JPX("image/jpx", "jpx"),
    }

    /**
     * Check whether the image is wide (which we consider a double-page spread).
     *
     * @return true if the width is greater than the height
     */
    fun isWideImage(imageSource: BufferedSource): Boolean {
        val options = extractImageOptions(imageSource)
        return options.outWidth > options.outHeight
    }

    /**
     * Extract the 'side' part from [BufferedSource] and return it as [BufferedSource].
     */
    fun splitInHalf(imageSource: BufferedSource, side: Side): BufferedSource {
        val imageBitmap = BitmapFactory.decodeStream(imageSource.inputStream())
        val height = imageBitmap.height
        val width = imageBitmap.width

        val singlePage = Rect(0, 0, width / 2, height)

        val half = createBitmap(width / 2, height)
        val part = when (side) {
            Side.RIGHT -> Rect(width - width / 2, 0, width, height)
            Side.LEFT -> Rect(0, 0, width / 2, height)
        }
        half.applyCanvas {
            drawBitmap(imageBitmap, part, singlePage, null)
        }
        val output = Buffer()
        half.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())

        return output
    }

    fun rotateImage(imageSource: BufferedSource, degrees: Float): BufferedSource {
        val imageBitmap = BitmapFactory.decodeStream(imageSource.inputStream())
        val rotated = rotateBitMap(imageBitmap, degrees)

        val output = Buffer()
        rotated.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())

        return output
    }

    private fun rotateBitMap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Split the image into left and right parts, then merge them into a new image.
     */
    fun splitAndMerge(imageSource: BufferedSource, upperSide: Side): BufferedSource {
        val imageBitmap = BitmapFactory.decodeStream(imageSource.inputStream())
        val height = imageBitmap.height
        val width = imageBitmap.width

        val result = createBitmap(width / 2, height * 2)
        result.applyCanvas {
            // right -> upper
            val rightPart = when (upperSide) {
                Side.RIGHT -> Rect(width - width / 2, 0, width, height)
                Side.LEFT -> Rect(0, 0, width / 2, height)
            }
            val upperPart = Rect(0, 0, width / 2, height)
            drawBitmap(imageBitmap, rightPart, upperPart, null)
            // left -> bottom
            val leftPart = when (upperSide) {
                Side.LEFT -> Rect(width - width / 2, 0, width, height)
                Side.RIGHT -> Rect(0, 0, width / 2, height)
            }
            val bottomPart = Rect(0, height, width / 2, height * 2)
            drawBitmap(imageBitmap, leftPart, bottomPart, null)
        }

        val output = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())
        return output
    }

    /**
     * SY 2026-10-09 双页移植（来自上游 mihon_img_upscale）：
     * 把两张页的位图左右并排合成一张，供「双页模式」一屏显示。
     *
     * @param isLTR 阅读方向：true 时第一张在左，false（R2L）时第一张在右。
     * @param hingeGap 两页之间留出的缝隙（px，0 = 不留）。
     */
    fun mergeBitmaps(
        iBitmap: Bitmap,
        iBitmap2: Bitmap,
        isLTR: Boolean,
        @ColorInt background: Int = Color.WHITE,
        hingeGap: Int = 0,
        context: Context? = null,
    ): BufferedSource {
        var imageBitmap = iBitmap
        var imageBitmap2 = iBitmap2
        var height = imageBitmap.height
        var width = imageBitmap.width
        var height2 = imageBitmap2.height
        var width2 = imageBitmap2.width
        val maxHeight = max(height, height2)
        val maxWidth = max(width, width2)
        val adjustedHingeGap = context?.let {
            val displayHeight = it.resources.displayMetrics.heightPixels
            if (displayHeight > 0) {
                (maxHeight.toFloat() / displayHeight * hingeGap).toInt()
            } else {
                hingeGap
            }
        } ?: hingeGap

        val result = createBitmap((maxWidth * 2) + adjustedHingeGap, maxHeight)
        result.applyCanvas {
            drawColor(background)
            val widthAndHinge = maxWidth + adjustedHingeGap
            if (height != maxHeight && width != maxWidth) {
                val minRatio = min(maxHeight / height.toFloat(), maxWidth / width.toFloat())
                imageBitmap = Bitmap.createScaledBitmap(
                    imageBitmap,
                    (width * minRatio).toInt(),
                    (height * minRatio).toInt(),
                    true,
                )
            }
            height = imageBitmap.height
            width = imageBitmap.width
            val firstPart = Rect(
                if (isLTR) max(maxWidth - width, 0) else widthAndHinge,
                (maxHeight - height) / 2,
                (if (isLTR) max(maxWidth - width, 0) else widthAndHinge) + width,
                height + (maxHeight - height) / 2,
            )
            drawBitmap(imageBitmap, null, firstPart, null)

            if (height2 != maxHeight && width2 != maxWidth) {
                val minRatio = min(maxHeight / height2.toFloat(), maxWidth / width2.toFloat())
                imageBitmap2 = Bitmap.createScaledBitmap(
                    imageBitmap2,
                    (width2 * minRatio).toInt(),
                    (height2 * minRatio).toInt(),
                    true,
                )
            }
            height2 = imageBitmap2.height
            width2 = imageBitmap2.width
            val secondPart = Rect(
                if (!isLTR) max(maxWidth - width2, 0) else widthAndHinge,
                (maxHeight - height2) / 2,
                (if (!isLTR) max(maxWidth - width2, 0) else widthAndHinge) + width2,
                height2 + (maxHeight - height2) / 2,
            )
            drawBitmap(imageBitmap2, null, secondPart, null)
        }

        val output = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 100, output.outputStream())
        return output
    }

    enum class Side {
        RIGHT,
        LEFT,
    }

    /**
     * Check whether the image is considered a tall image.
     *
     * @return true if the height:width ratio is greater than 3.
     */
    private fun isTallImage(imageSource: BufferedSource): Boolean {
        val options = extractImageOptions(imageSource)
        return TallImageSplitCalculator.shouldSplit(
            imageWidth = options.outWidth,
            imageHeight = options.outHeight,
            optimalImageHeight = optimalImageHeight,
        )
    }

    /**
     * Splits tall images to improve performance of reader
     */
    fun splitTallImage(tmpDir: UniFile, imageFile: UniFile, filenamePrefix: String): Boolean {
        val imageSource = imageFile.openInputStream().use { Buffer().readFrom(it) }
        if (isAnimatedAndSupported(imageSource) || !isTallImage(imageSource)) {
            return true
        }

        val bitmapRegionDecoder = getBitmapRegionDecoder(imageSource.peek().inputStream())
        if (bitmapRegionDecoder == null) {
            logcat { "Failed to create new instance of BitmapRegionDecoder" }
            return false
        }

        val options = extractImageOptions(imageSource).apply {
            inJustDecodeBounds = false
        }
        val splitDataList = options.splitData

        return try {
            splitDataList.forEach { splitData ->
                val splitImageName = splitImageName(filenamePrefix, splitData.index)
                // Remove pre-existing split if exists (this split shouldn't exist under normal circumstances)
                tmpDir.findFile(splitImageName)?.delete()

                val splitFile = tmpDir.createFile(splitImageName)!!

                val region = Rect(0, splitData.topOffset, splitData.splitWidth, splitData.bottomOffset)

                splitFile.openOutputStream().use { outputStream ->
                    val splitBitmap = bitmapRegionDecoder.decodeRegion(region, options)
                    splitBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    splitBitmap.recycle()
                }
                logcat {
                    "Success: Split #${splitData.index + 1} with topOffset=${splitData.topOffset} " +
                        "height=${splitData.splitHeight} bottomOffset=${splitData.bottomOffset}"
                }
            }
            imageFile.delete()
            true
        } catch (e: Exception) {
            // Image splits were not successfully saved so delete them and keep the original image
            splitDataList
                .map { splitImageName(filenamePrefix, it.index) }
                .forEach { tmpDir.findFile(it)?.delete() }
            logcat(LogPriority.ERROR, e)
            false
        } finally {
            bitmapRegionDecoder.recycle()
        }
    }

    private fun splitImageName(filenamePrefix: String, index: Int) = "${filenamePrefix}__${"%03d".format(
        Locale.ENGLISH,
        index + 1,
    )}.jpg"

    private val BitmapFactory.Options.splitData
        get(): List<SplitData> {
            val imageHeight = outHeight
            val imageWidth = outWidth

            val partCount = TallImageSplitCalculator.calculatePartCount(imageHeight, optimalImageHeight)
            val optimalSplitHeight = imageHeight / partCount

            logcat {
                "Generating SplitData for image (height: $imageHeight): " +
                    "$partCount parts @ ${optimalSplitHeight}px height per part"
            }

            return buildList {
                val range = 0..<partCount
                for (index in range) {
                    // Only continue if the list is empty or there is image remaining
                    if (isNotEmpty() && imageHeight <= last().bottomOffset) break

                    val topOffset = index * optimalSplitHeight
                    var splitHeight = min(optimalSplitHeight, imageHeight - topOffset)

                    if (index == range.last) {
                        val remainingHeight = imageHeight - (topOffset + splitHeight)
                        splitHeight += remainingHeight
                    }

                    add(SplitData(index, topOffset, splitHeight, imageWidth))
                }
            }
        }

    data class SplitData(
        val index: Int,
        val topOffset: Int,
        val splitHeight: Int,
        val splitWidth: Int,
    ) {
        val bottomOffset = topOffset + splitHeight
    }

    /**
     * Algorithm for determining what background to accompany a comic/manga page
     */
    fun chooseBackground(context: Context, imageStream: InputStream): Drawable =
        chooseBackground(context, 0L) { imageStream }

    /**
     * 同 [chooseBackground]，但允许先把超大图降采样再取边缘色。
     *
     * SY --> 2026-09-26：旧实现走 `ImageDecoder.new(stream).decode()`，是**全分辨率**解码。
     * 条漫每绑一页都要调一次，而且是在 `frame.setImage` **之前** ⇒ 直接推迟出图。
     * 本机实测页面是 3309×2500(=8.3MP) / 5620×2500(=14.1MP) 的 PNG，每页白白解码
     * 几十 MB、上百毫秒，而这个算法只用十来个像素。这里先按 2 的幂 inSampleSize
     * 降到 [maxPixels] 以内；[maxPixels] <= 0 表示不降采样，行为与旧实现完全一致
     * （分页阅读器的 automaticBackground 路径不受影响）。
     */
    fun chooseBackground(context: Context, maxPixels: Long, openStream: () -> InputStream): Drawable {
        val image = try {
            if (maxPixels <= 0L) {
                ImageDecoder.new(openStream()).use { it.decode() }.let { res ->
                    createBitmap(res.width, res.height).also { bitmap ->
                        res.image.rewind()
                        bitmap.copyPixelsFromBuffer(res.image)
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                runCatching { openStream().use { BitmapFactory.decodeStream(it, null, bounds) } }
                var sample = 1
                val w = bounds.outWidth.toLong()
                val h = bounds.outHeight.toLong()
                if (w > 0 && h > 0) {
                    while (w * h / (sample.toLong() * sample) > maxPixels) {
                        sample *= 2
                    }
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                openStream().use { BitmapFactory.decodeStream(it, null, options) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "chooseBackground: ${e.message}" }
            null
        }

        val whiteColor = Color.WHITE
        if (image == null) return whiteColor.toDrawable()
        if (image.width < 50 || image.height < 50) {
            return whiteColor.toDrawable()
        }

        val top = 5
        val bot = image.height - 5
        val left = (image.width * 0.0275).toInt()
        val right = image.width - left
        val midX = image.width / 2
        val midY = image.height / 2
        val offsetX = (image.width * 0.01).toInt()
        val leftOffsetX = left - offsetX
        val rightOffsetX = right + offsetX

        val topLeftPixel = image[left, top]
        val topRightPixel = image[right, top]
        val midLeftPixel = image[left, midY]
        val midRightPixel = image[right, midY]
        val topCenterPixel = image[midX, top]
        val botLeftPixel = image[left, bot]
        val bottomCenterPixel = image[midX, bot]
        val botRightPixel = image[right, bot]

        val topLeftIsDark = topLeftPixel.isDark()
        val topRightIsDark = topRightPixel.isDark()
        val midLeftIsDark = midLeftPixel.isDark()
        val midRightIsDark = midRightPixel.isDark()
        val topMidIsDark = topCenterPixel.isDark()
        val botLeftIsDark = botLeftPixel.isDark()
        val botRightIsDark = botRightPixel.isDark()

        var darkBG =
            (topLeftIsDark && (botLeftIsDark || botRightIsDark || topRightIsDark || midLeftIsDark || topMidIsDark)) ||
                (topRightIsDark && (botRightIsDark || botLeftIsDark || midRightIsDark || topMidIsDark))

        val topAndBotPixels =
            listOf(topLeftPixel, topCenterPixel, topRightPixel, botRightPixel, bottomCenterPixel, botLeftPixel)
        val isNotWhiteAndCloseTo = topAndBotPixels.mapIndexed { index, color ->
            val other = topAndBotPixels[(index + 1) % topAndBotPixels.size]
            !color.isWhite() && color.isCloseTo(other)
        }
        if (isNotWhiteAndCloseTo.all { it }) {
            return topLeftPixel.toDrawable()
        }

        val cornerPixels = listOf(topLeftPixel, topRightPixel, botLeftPixel, botRightPixel)
        val numberOfWhiteCorners = cornerPixels.map { cornerPixel -> cornerPixel.isWhite() }
            .filter { it }
            .size
        if (numberOfWhiteCorners > 2) {
            darkBG = false
        }

        var blackColor = when {
            topLeftIsDark -> topLeftPixel
            topRightIsDark -> topRightPixel
            botLeftIsDark -> botLeftPixel
            botRightIsDark -> botRightPixel
            else -> whiteColor
        }

        var overallWhitePixels = 0
        var overallBlackPixels = 0
        var topBlackStreak = 0
        var topWhiteStreak = 0
        var botBlackStreak = 0
        var botWhiteStreak = 0
        outer@ for (x in intArrayOf(left, right, leftOffsetX, rightOffsetX)) {
            var whitePixelsStreak = 0
            var whitePixels = 0
            var blackPixelsStreak = 0
            var blackPixels = 0
            var blackStreak = false
            var whiteStreak = false
            val notOffset = x == left || x == right
            inner@ for ((index, y) in (0..<image.height step image.height / 25).withIndex()) {
                val pixel = image[x, y]
                val pixelOff = image[x + (if (x < image.width / 2) -offsetX else offsetX), y]
                if (pixel.isWhite()) {
                    whitePixelsStreak++
                    whitePixels++
                    if (notOffset) {
                        overallWhitePixels++
                    }
                    if (whitePixelsStreak > 14) {
                        whiteStreak = true
                    }
                    if (whitePixelsStreak > 6 && whitePixelsStreak >= index - 1) {
                        topWhiteStreak = whitePixelsStreak
                    }
                } else {
                    whitePixelsStreak = 0
                    if (pixel.isDark() && pixelOff.isDark()) {
                        blackPixels++
                        if (notOffset) {
                            overallBlackPixels++
                        }
                        blackPixelsStreak++
                        if (blackPixelsStreak >= 14) {
                            blackStreak = true
                        }
                        continue@inner
                    }
                }
                if (blackPixelsStreak > 6 && blackPixelsStreak >= index - 1) {
                    topBlackStreak = blackPixelsStreak
                }
                blackPixelsStreak = 0
            }
            if (blackPixelsStreak > 6) {
                botBlackStreak = blackPixelsStreak
            } else if (whitePixelsStreak > 6) {
                botWhiteStreak = whitePixelsStreak
            }
            when {
                blackPixels > 22 -> {
                    if (x == right || x == rightOffsetX) {
                        blackColor = when {
                            topRightIsDark -> topRightPixel
                            botRightIsDark -> botRightPixel
                            else -> blackColor
                        }
                    }
                    darkBG = true
                    overallWhitePixels = 0
                    break@outer
                }
                blackStreak -> {
                    darkBG = true
                    if (x == right || x == rightOffsetX) {
                        blackColor = when {
                            topRightIsDark -> topRightPixel
                            botRightIsDark -> botRightPixel
                            else -> blackColor
                        }
                    }
                    if (blackPixels > 18) {
                        overallWhitePixels = 0
                        break@outer
                    }
                }
                whiteStreak || whitePixels > 22 -> darkBG = false
            }
        }

        val topIsBlackStreak = topBlackStreak > topWhiteStreak
        val bottomIsBlackStreak = botBlackStreak > botWhiteStreak
        if (overallWhitePixels > 9 && overallWhitePixels > overallBlackPixels) {
            darkBG = false
        }
        if (topIsBlackStreak && bottomIsBlackStreak) {
            darkBG = true
        }

        val isLandscape = context.resources.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            return when {
                darkBG -> blackColor.toDrawable()
                else -> whiteColor.toDrawable()
            }
        }

        val botCornersIsWhite = botLeftPixel.isWhite() && botRightPixel.isWhite()
        val topCornersIsWhite = topLeftPixel.isWhite() && topRightPixel.isWhite()

        val topCornersIsDark = topLeftIsDark && topRightIsDark
        val botCornersIsDark = botLeftIsDark && botRightIsDark

        val topOffsetCornersIsDark = image[leftOffsetX, top].isDark() && image[rightOffsetX, top].isDark()
        val botOffsetCornersIsDark = image[leftOffsetX, bot].isDark() && image[rightOffsetX, bot].isDark()

        val gradient = when {
            darkBG && botCornersIsWhite -> {
                intArrayOf(blackColor, blackColor, whiteColor, whiteColor)
            }
            darkBG && topCornersIsWhite -> {
                intArrayOf(whiteColor, whiteColor, blackColor, blackColor)
            }
            darkBG -> {
                return blackColor.toDrawable()
            }
            topIsBlackStreak ||
                (
                    topCornersIsDark &&
                        topOffsetCornersIsDark &&
                        (topMidIsDark || overallBlackPixels > 9)
                    ) -> {
                intArrayOf(blackColor, blackColor, whiteColor, whiteColor)
            }
            bottomIsBlackStreak ||
                (
                    botCornersIsDark &&
                        botOffsetCornersIsDark &&
                        (bottomCenterPixel.isDark() || overallBlackPixels > 9)
                    ) -> {
                intArrayOf(whiteColor, whiteColor, blackColor, blackColor)
            }
            else -> {
                return whiteColor.toDrawable()
            }
        }

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            gradient,
        )
    }

    private fun @receiver:ColorInt Int.isDark(): Boolean =
        red < 40 && blue < 40 && green < 40 && alpha > 200

    private fun @receiver:ColorInt Int.isCloseTo(other: Int): Boolean =
        abs(red - other.red) < 30 && abs(green - other.green) < 30 && abs(blue - other.blue) < 30

    private fun @receiver:ColorInt Int.isWhite(): Boolean =
        red + blue + green > 740

    /**
     * Used to check an image's dimensions without loading it in the memory.
     */
    private fun extractImageOptions(imageSource: BufferedSource): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, options)
        return options
    }

    private fun getBitmapRegionDecoder(imageStream: InputStream): BitmapRegionDecoder? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(imageStream)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(imageStream, false)
        }
    }

    private val optimalImageHeight = getDisplayMaxHeightInPx * 2
}

val getDisplayMaxHeightInPx: Int
    get() = Resources.getSystem().displayMetrics.let { max(it.heightPixels, it.widthPixels) }
