package eu.kanade.tachiyomi.data.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import ca.mpreg.imagedecoder.ImageDecoder
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancementCache
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import eu.kanade.tachiyomi.util.waifu2x.enhancementConfigHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [Decoder] that uses [ImageDecoder] (libvips-based) to decode image formats not supported
 * by the Android system decoder (AVIF, JXL, HEIF, etc.).
 *
 * SY --> 超分移植：在此之上叠加 ncnn/Vulkan 画面增强。
 * 逻辑从 mihon_img_upscale 的 TachiyomiImageDecoder 移植，适配官方 Mihon 的
 * 「libvips 统一解码」与 Metro DI：
 *  - 偏好不再走 Injekt（官方 injekt 兼容层只暴露 7 个类型），改为
 *    `(options.context.applicationContext as? App)?.graph?.readerPreferences`；
 *  - 解码沿用官方的 libvips 路径，增强发生在解码出 Bitmap 之后；
 *  - 命中磁盘增强缓存时**完全跳过源码解码**（这是连续翻页时最大的一笔收益）。
 * SY <--
 */
class ImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    /**
     * Wraps a raw [ImageDecoder.DecodeResult] as a Coil [Image] for callers that want
     * direct access to the RGBA [java.nio.ByteBuffer] (e.g. the new-decoder path).
     */
    class DecodeResultImage(val res: ImageDecoder.DecodeResult) : Image {
        override val size: Long get() = res.image.capacity().toLong()
        override val width: Int get() = res.width
        override val height: Int get() = res.height
        override val shareable: Boolean get() = true
        override fun draw(canvas: Canvas) {}
    }

    override suspend fun decode(): DecodeResult {
        // SY --> 超分：先看磁盘上有没有这一页的增强结果。
        // 命中就直接返回，连源码解码都省掉——连续翻页时这一步是决定性的。
        decodeCachedEnhancedImage()?.let { cachedBitmap ->
            return DecodeResult(
                image = prepareForDisplay(cachedBitmap).asImage(),
                isSampled = false,
            )
        }
        // SY <--

        val res = resources.source().use {
            ImageDecoder.new(it.inputStream()).use { dec -> dec.decode() }
        }

        val srcWidth = res.width
        val srcHeight = res.height

        // newDecoder path: caller wants the raw DecodeResult (e.g. for custom rendering).
        // Hand it back as-is; sampling is the caller's responsibility.
        if (options.newDecoder) {
            return DecodeResult(
                image = DecodeResultImage(res),
                isSampled = false,
            )
        }

        // Normal path: produce a Bitmap scaled to the requested output size.
        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }
        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )

        // Copy RGBA pixels from the native buffer into a full-resolution bitmap.
        // We must do this while `res` (and its native memory) is still alive.
        val fullBitmap = createBitmap(srcWidth, srcHeight)
        res.image.rewind()
        fullBitmap.copyPixelsFromBuffer(res.image)

        // Downsample if needed. sampleSize is a power-of-two factor; the target
        // dimensions are src / sampleSize, matching BitmapFactory inSampleSize behaviour.
        var bitmap = if (sampleSize > 1) {
            val scaledWidth = (srcWidth / sampleSize).coerceAtLeast(1)
            val scaledHeight = (srcHeight / sampleSize).coerceAtLeast(1)
            val scaled = fullBitmap.scale(scaledWidth, scaledHeight)
            fullBitmap.recycle()
            scaled
        } else {
            fullBitmap
        }

        // SY --> 超分：在降采样后的 Bitmap 上做增强（与 fork 的语义一致——
        // 只对「显示尺寸」的图做推理，避免对整页原图做无用功）。
        bitmap = enhanceIfNeeded(bitmap, sampleSize)
        // SY <--

        return DecodeResult(
            image = prepareForDisplay(bitmap).asImage(),
            isSampled = sampleSize > 1,
        )
    }

    // SY --> 超分：以下三块逻辑自 mihon_img_upscale 移植

    /** 取当前进程的 ReaderPreferences（官方走 Metro 图，injekt 兼容层不暴露它）。 */
    private val readerPreferences: ReaderPreferences?
        get() = (options.context.applicationContext as? App)?.graph?.readerPreferences

    /**
     * 命中增强缓存则直接返回 Bitmap；否则返回 null，让调用方继续走正常解码。
     * 这里刻意放在解码之前调用：缓存命中时零解码、零推理。
     */
    private fun decodeCachedEnhancedImage(): Bitmap? {
        if (!options.enhanced) return null

        val mangaId = options.mangaId
        val chapterId = options.chapterId
        val pageIndex = options.pageIndex
        if (mangaId == -1L || chapterId == -1L || pageIndex == -1) return null

        val preferences = readerPreferences ?: return null
        if (!preferences.realCuganEnabled().get()) return null

        val context: Context = options.context.applicationContext
        ImageEnhancementCache.init(context)
        val configHash = configHash(preferences)

        val cachedFile = ImageEnhancementCache.getCachedImage(
            mangaId,
            chapterId,
            pageIndex,
            configHash,
            options.pageVariant,
        ) ?: return null

        val cachedBitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
        if (cachedBitmap != null && ImageEnhancementCache.isDisplayable(cachedBitmap)) {
            logcat(LogPriority.DEBUG) {
                "ImageDecoder: Page $pageIndex/${options.pageVariant} served from enhanced cache before source decode"
            }
            return cachedBitmap
        }

        cachedBitmap?.recycle()
        ImageEnhancementCache.removeCachedImage(mangaId, chapterId, pageIndex, configHash, options.pageVariant)
        return null
    }

    /**
     * 解码完成后的即时增强。返回**应当用于显示**的 Bitmap：
     * 增强成功则是增强结果，否则原样返回（绝不返回 null，避免调用方空指针）。
     */
    private suspend fun enhanceIfNeeded(source: Bitmap, sampleSize: Int): Bitmap {
        if (!options.enhanced) return source
        // SY --> mihon_img_upscale 移植：只有后台 ImageEnhancer 发起的请求才在这里跑模型。
        // 阅读器的显示请求「只查缓存」，没命中就立刻显示原图，避免解码被推理阻塞
        // （否则用户会盯着空白等好几秒，也拿不到左下角的进度提示）。
        if (!options.enhanceNow) return source
        // SY <--

        val mangaId = options.mangaId
        val chapterId = options.chapterId
        val pageIndex = options.pageIndex
        if (mangaId == -1L || chapterId == -1L || pageIndex == -1) return source

        val preferences = readerPreferences ?: return source
        if (!preferences.realCuganEnabled().get()) return source

        val context: Context = options.context.applicationContext
        ImageEnhancementCache.init(context)

        val pageVariant = options.pageVariant
        val configHash = configHash(preferences)

        var bitmap = source
        var ownsBitmap = false

        try {
            // 命中缓存（正常路径下前面已查过，这里兜底）
            ImageEnhancementCache.getCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)?.let { cachedFile ->
                val cachedBitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                if (cachedBitmap != null && ImageEnhancementCache.isDisplayable(cachedBitmap)) {
                    return cachedBitmap
                }
                cachedBitmap?.recycle()
                ImageEnhancementCache.removeCachedImage(mangaId, chapterId, pageIndex, configHash, pageVariant)
            }

            // 上一轮已判定「这张图不值得/不能增强」，直接跳过，别重复烧 GPU
            if (ImageEnhancementCache.isSkipped(mangaId, chapterId, pageIndex, configHash, pageVariant)) {
                return source
            }

            val model = Waifu2x.sanitizeModel(preferences.realCuganModel().get())
            val noise = preferences.realCuganNoiseLevel().get()
            val scale = preferences.realCuganScale().get()

            val processMaxWidth = preferences.realCuganMaxSizeWidth().get()
            val processMaxHeight = preferences.realCuganMaxSizeHeight().get()
            val skipMaxWidth = preferences.realCuganSkipMaxSizeWidth().get()
            val skipMaxHeight = preferences.realCuganSkipMaxSizeHeight().get()

            val exceedsSkipLimit = (skipMaxWidth > 0 && bitmap.width > skipMaxWidth) ||
                (skipMaxHeight > 0 && bitmap.height > skipMaxHeight)
            if (exceedsSkipLimit) {
                logcat(LogPriority.DEBUG) {
                    "ImageDecoder: Skipping enhancement for page $pageIndex - " +
                        "source ${bitmap.width}x${bitmap.height} exceeds max resolution ${skipMaxWidth}x${skipMaxHeight}"
                }
                ImageEnhancementCache.saveSkippedToCache(mangaId, chapterId, pageIndex, configHash, pageVariant)
                return source
            }

            val perfMode = preferences.realCuganPerformanceMode().get()
            val tileSleepMs = when (perfMode) {
                1 -> 5
                2 -> 15
                else -> 0
            }
            val tileSize = preferences.realCuganTileSize().get().coerceAtLeast(32)
            val precision = Waifu2x.sanitizePrecision(preferences.realCuganPrecision().get())
            val fp16Arithmetic = preferences.realCuganFp16Arithmetic().get()
            // SY --> Anime4K GLSL 的三个专属参数；非 GLSL 模型传给 ensureLoaded 也会被忽略。
            val anime4kPreset = preferences.realCuganAnime4kPreset().get()
            val anime4kThin = preferences.realCuganAnime4kThinLines().get()
            val anime4kDarken = preferences.realCuganAnime4kDarkenLines().get()
            // SY <--

            val effectiveScale = ImageEnhancementCache.getEffectiveScale(model, scale)
            if (effectiveScale != scale) {
                logcat(LogPriority.DEBUG) {
                    "ImageDecoder: Model $model only supports ${effectiveScale}x, clamping from ${scale}x"
                }
            }

            // SY --> 先把引擎初始化（原来放在预缩放之后）。
            // 原因：GLSL 路线的纹理上限 `GL_MAX_TEXTURE_SIZE` 只有 init 之后才查得到，
            // 而下面的输入分辨率裁剪必须把这个上限算进去，才能让 GLSL 吃下长条图
            // ——否则只能整页跳过增强。
            // 模型分派统一收敛到 Waifu2x.ensureLoaded：
            //   7=waifu2x CUnet（3 通道，模型包提供）、6=Anime4K ACNet（单通道 luma，内置）、
            //   8=Anime4K GLSL（模型包提供，独立执行器，不吃 tile/precision）。
            val initialized = Waifu2x.ensureLoaded(
                context = context,
                model = model,
                noise = noise,
                scale = effectiveScale,
                tileSleepMs = tileSleepMs,
                tileSize = tileSize,
                precision = precision,
                fp16Arithmetic = fp16Arithmetic,
                anime4kPreset = anime4kPreset,
                anime4kThinLines = anime4kThin,
                anime4kDarkenLines = anime4kDarken,
            )
            if (!initialized) {
                // 没装模型包 / GLSL 编译失败 / 解包失败：跳过增强显示原图。
                logcat(LogPriority.DEBUG) {
                    "ImageDecoder: Model $model not available, skipping enhancement for page $pageIndex"
                }
                return bitmap
            }

            val isGlsl = Waifu2x.isGlslEngineActive()
            // GLSL 的输出 = 输入 × effectiveScale，必须 <= 纹理上限 ⇒ 输入上限 = 上限 / 倍率。
            val glslInputCap = if (isGlsl) {
                val limit = Waifu2x.anime4kTextureLimit()
                if (limit > 0) limit / effectiveScale else 0
            } else {
                0
            }
            // SY <--

            // --- 输入分辨率上限：超限先缩小，避免推理时间爆炸 ---
            // SY --> GLSL 的纹理上限也折进来一起取 min。
            val capWidth = if (glslInputCap > 0) {
                if (processMaxWidth > 0) min(processMaxWidth, glslInputCap) else glslInputCap
            } else {
                processMaxWidth
            }
            val capHeight = if (glslInputCap > 0) {
                if (processMaxHeight > 0) min(processMaxHeight, glslInputCap) else glslInputCap
            } else {
                processMaxHeight
            }
            val hasProcessMaxResolution = capWidth > 0 || capHeight > 0
            val widthRatio = if (capWidth > 0) {
                capWidth / bitmap.width.toFloat()
            } else {
                Float.POSITIVE_INFINITY
            }
            val heightRatio = if (capHeight > 0) {
                capHeight / bitmap.height.toFloat()
            } else {
                Float.POSITIVE_INFINITY
            }
            val ratio = if (hasProcessMaxResolution) min(widthRatio, heightRatio) else 1f
            // SY <--

            if (ratio in 0f..<1f) {
                val newWidth = max(1, (bitmap.width * ratio).roundToInt())
                val newHeight = max(1, (bitmap.height * ratio).roundToInt())
                logcat(LogPriority.DEBUG) {
                    "ImageDecoder: Prescaling page $pageIndex input with native scaling " +
                        "${bitmap.width}x${bitmap.height} -> ${newWidth}x${newHeight}, " +
                        "processingMax=${capWidth}x${capHeight}, outputScale=${effectiveScale}x" +
                        if (isGlsl) ", glslTextureLimit=${Waifu2x.anime4kTextureLimit()}" else ""
                }
                val scaledBitmap = nativeScaleBitmap(bitmap, newWidth, newHeight)
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = scaledBitmap
                    ownsBitmap = true
                }
            }

            currentCoroutineContext().ensureActive()

            // SY --> 按当前生效的引擎选推理入口：GLSL 走整图 FBO，ncnn 走分块。
            val processed = (if (isGlsl) {
                Waifu2x.processAnime4K(bitmap)
            } else {
                Waifu2x.processWaifu2x(bitmap, pageIndex)
            }) ?: return bitmap
            // SY <--

            var result: Bitmap = processed
            var ownsResult = true
            try {
                currentCoroutineContext().ensureActive()

                // --- 输出分辨率上限：超过 GPU 纹理上限会让 Canvas 直接报错 ---
                val textureLimit = GLUtil.DEVICE_TEXTURE_LIMIT
                logcat(LogPriority.DEBUG) {
                    "ImageDecoder: Page $pageIndex enhanced result: ${result.width}x${result.height}, " +
                        "DEVICE_TEXTURE_LIMIT=$textureLimit"
                }
                if (result.width > textureLimit || result.height > textureLimit) {
                    val outRatio = min(
                        textureLimit.toFloat() / result.width,
                        textureLimit.toFloat() / result.height,
                    )
                    val newWidth = (result.width * outRatio).toInt().coerceAtLeast(1)
                    val newHeight = (result.height * outRatio).toInt().coerceAtLeast(1)
                    logcat(LogPriority.DEBUG) {
                        "ImageDecoder: Output downscale page $pageIndex: " +
                            "${result.width}x${result.height} -> ${newWidth}x${newHeight} (Texture Limit: $textureLimit)"
                    }
                    val downscaled = nativeScaleBitmap(result, newWidth, newHeight)
                    if (downscaled != result) {
                        result.recycle()
                        result = downscaled
                    }
                }

                if (ImageEnhancementCache.isDisplayable(result)) {
                    // enqueueSaveToCache 一旦被调用就接管该 Bitmap（即使挂起或被拒绝）。
                    ownsResult = false
                    val queued = ImageEnhancementCache.enqueueSaveToCache(
                        mangaId,
                        chapterId,
                        pageIndex,
                        configHash,
                        result,
                        pageVariant,
                    )
                    if (queued) {
                        logcat(LogPriority.DEBUG) { "ImageDecoder: Page $pageIndex/$pageVariant queued for cache encoding" }
                    } else {
                        logcat(LogPriority.WARN) { "ImageDecoder: Page $pageIndex/$pageVariant cache encoding already pending or rejected" }
                    }
                } else {
                    logcat(LogPriority.ERROR) {
                        "ImageDecoder: Page $pageIndex/$pageVariant produced a nearly transparent result, keeping original image"
                    }
                }
            } finally {
                if (ownsResult && result !== bitmap && !result.isRecycled) result.recycle()
            }

            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "ImageDecoder: Failed to enhance image on-the-fly" }
            return if (ownsBitmap) bitmap else source
        }
    }

    // SY --> mihon_img_upscale 移植：指纹统一走 enhancementConfigHash，
    // 保证与视图侧的轮询、ImageEnhancer 的去重键完全一致。
    private fun configHash(preferences: ReaderPreferences): String = enhancementConfigHash(preferences)
    // SY <--

    /**
     * 把解码/增强结果按 Coil 请求的位图配置交付。
     *
     * 官方实现是把软件位图直接交给 Coil 自行上传；这里额外做一次
     * 「按需转 HARDWARE」的短路，省掉 Coil 侧的一次拷贝。
     * 超过安全纹理上限的图不转——转 HARDWARE 会超出 GPU 纹理限制而直接失败。
     * 注意：本函数只做「软件 → 硬件」单向转换，绝不改回软件位图。
     */
    private fun prepareForDisplay(source: Bitmap): Bitmap {
        if (options.bitmapConfig != Bitmap.Config.HARDWARE) return source
        if (source.config == Bitmap.Config.HARDWARE) return source
        if (maxOf(source.width, source.height) > GLUtil.SAFE_TEXTURE_LIMIT) return source

        val hardwareBitmap = source.copy(Bitmap.Config.HARDWARE, false) ?: return source
        source.recycle()
        return hardwareBitmap
    }

    // SY <--

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.newDecoder || options.customDecoder || isApplicable(result.source.source())) {
                ImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().use {
                ImageUtil.findImageType(it)
            }
            return when (type) {
                ImageUtil.ImageType.AVIF,
                ImageUtil.ImageType.JXL,
                ImageUtil.ImageType.HEIF,
                ImageUtil.ImageType.JP2,
                -> true

                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }
}

// SY --> 超分：缩放优先交给原生（Vulkan），失败再退回系统双线性。
private fun nativeScaleBitmap(
    source: Bitmap,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap {
    if (source.width == targetWidth && source.height == targetHeight) return source
    return Waifu2x.scaleBitmapNative(
        source,
        max(1, targetWidth),
        max(1, targetHeight),
    ) ?: Bitmap.createScaledBitmap(
        source,
        max(1, targetWidth),
        max(1, targetHeight),
        true,
    )
}
// SY <--
