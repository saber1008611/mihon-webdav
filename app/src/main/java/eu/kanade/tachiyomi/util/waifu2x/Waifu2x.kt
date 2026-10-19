package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Waifu2x image upscaler using ncnn.
 *
 * SY --> **MihonAc 完美版（2026-09-23）：本构建只保留 Anime4K v4 ACNet 一款超分模型**，
 * 引擎固定为自研的融合调度（产物 `libwaifu2x-jni.so`，源码在 `app/src/main/cpp/`）。
 *
 *  - 6 = Anime4K v4 ACNet：单通道 luma 网络，9 层 3x3 卷积 + 2x2/stride2 反卷积，只支持 2x。
 *    有专属的单通道融合 Vulkan 管线（`waifu2x_fused_{preproc,postproc}_luma.comp`），
 *    把 RGBA8 转成 BT.601 亮度喂网络、再用 Y' + 原图双三次色度合成回 RGBA8，
 *    所以**全程走 GPU**（见 waifu2x.cpp 的 luma_model 分支）。只有着色器创建失败 /
 *    老驱动不支持时才会退回 CPU staged 路径。
 *
 * ## 相对「三模型 + 双引擎」版删掉的东西
 *
 *  - **TX 引擎**（`libwaifu2x-tx0.so` 及全部引擎分派逻辑）。这不是懒省事：
 *    ACNet 在 TX 引擎上**没有可用入口** —— TX 的融合管线只有 3 通道版
 *    （库里 `luma` 字符串出现 0 次，我们的 MM 库 22 次），而 ACNet 是 1 通道 luma 权重；
 *    TX 还是闭源二进制，加不了符号。既然本版只留 ACNet，就没有保留第二个引擎的理由
 *    （两个 .so 还全局导出 4000+ 同名 C++ 符号，同一进程不能共存，切换必须重启）。
 *  - **sudo UltraCompact(4) / Waifu2x (Fast) UpConv7(5)** 两款模型及其 assets 目录。
 *  - **模型包热插拔体系**（原 `ModelPackLoader`）：ACNet 权重随主包内置，
 *    `assets/anime4k-acnet-models` 解压到 cacheDir 即可，不需要外部安装包。
 *
 * ## 权重来源
 *
 * 只有一处：`assets/anime4k-acnet-models`（`anime4k_acnet.param` / `.bin`）。
 * 权重是 `anime4k_acnet.onnx`（md5 0cb304fe2714abb02a9f9b4d34c2ae47）经
 * 「手写 ncnn param/bin + onnxruntime 数值对齐」转换而来（max|diff| = 1.2e-06）。
 * SY <--
 */
object Waifu2x {

    // Bump when bundled model assets change so existing installations refresh their cache.
    // SY --> 20：本构建模型集合变了（只剩 ACNet），顺带让升级用户重刷一次 cacheDir。
    private const val BUNDLED_MODEL_CACHE_VERSION = "20"
    // SY <--

    /**
     * SY --> 超分 tilesize 默认值。tile 越大，每块多算的 prepadding 重叠越少、块数越少，
     * 推理更快；ncnn 侧还会按 GPU 显存预算再收敛一次上限（见 set_tilesize）。
     * SY <--
     */
    const val DEFAULT_TILE_SIZE = 192

    // SY --> 2026-09-29：块间降温小睡默认值（毫秒）。
    // 整话超分长时间后台满载会把 CPU 烤到 95.7°C / thermal severity 3，导致「打开归档」
    // 这类操作从 ~0.7s 飙到 8.4s。cool_sleep_ms 与原 tile_sleep_ms 解耦——它**只加 sleep、
    // 不降 batch_capacity**（见 waifu2x.h），所以是「持续满载场景降温」而不牺牲太多吞吐。
    // 3ms/块实测足以把温度控制在远低于触发的线，且对单页首帧额外开销约 60~80ms（可接受）。
    const val DEFAULT_COOL_SLEEP_MS = 3

    // SY --> 本构建唯一保留的超分模型（编号沿用 TachiyomiX 约定）。

    /** Anime4K v4 ACNet（单通道 luma，2x）。 */
    const val MODEL_ANIME4K_ACNET = 6
    // SY <--

    // SY --> 融合 GPU 管线只认 fp16 / fp32 两种存储布局：两支 preproc/postproc
    // SPIR-V 里把元素类型写死成 float16_t（或 float）并按 std430 紧凑排布索引，
    // 而 INT8 的 VkMat 元素是 1 字节、BF16 是 2 字节但位模式不同，都对不上。
    // ncnn 在 load() 里也会因此直接把 gpu_pipeline_available 置 false
    // （见 waifu2x.cpp：`precision_mode == 1 || (precision_mode == 0 && fp16_storage)`）。
    //
    // 为了兑现「超分走 GPU」，这里把精度收敛到 FP16/FP32 二选一。
    // ⚠️ 注意：ARM GPU（Mali / Immortalis）上，waifu2x.cpp 会无条件把 fp16 收敛成
    // **FP32 融合** —— 因为 fp16 融合在 Immortalis 上花屏、BF16 staged 会直接驱动崩溃。
    // 所以这台机器上选 FP16 实际等价于 FP32 融合（正确 + 不崩），代价是带宽翻倍、略慢。
    const val PRECISION_FP16 = 0
    const val PRECISION_FP32 = 1

    /** 把任意偏好值收敛到融合管线支持的精度：只可能是 FP16 或 FP32。 */
    fun sanitizePrecision(precision: Int): Int =
        if (precision == PRECISION_FP32) PRECISION_FP32 else PRECISION_FP16
    // SY <--

    // SY --> ACNet 的 assets 子目录名。
    // ⚠️ 这个名字**不在** REMOVED_MODEL_ASSETS 里 —— 它是随主包内置的模型，解压后正好
    // 落在 cacheDir/<同名目录>；写进清理清单会在每次准备模型时把刚解出来的权重删掉
    // （「加载永远失败」的无声故障）。
    private const val ANIME4K_ACNET_ASSET_PATH = "anime4k-acnet-models"
    // SY <--

    /** 模型编号 → assets 子目录名。本构建只有一款，任何入参都指向它。 */
    @Suppress("UNUSED_PARAMETER")
    fun assetPathForModel(model: Int): String = ANIME4K_ACNET_ASSET_PATH

    /**
     * 该模型当前是否可用：宿主内置的 assets 目录非空。
     * 正常情况恒为 true；只有 assets 被裁剪过的构建才会是 false ——
     * 设置页据此标注「未安装」，免得用户以为「超分坏了」。
     */
    @Suppress("UNUSED_PARAMETER")
    fun isModelAvailable(context: Context, model: Int): Boolean =
        context.assets.list(ANIME4K_ACNET_ASSET_PATH)?.isNotEmpty() == true

    /**
     * 旧版本偏好里可能残留已移除模型的编号（0/1/2/3/4/5/7/8/9/16/18/19 …，含本版删掉的
     * sudo UltraCompact(4)、UpConv7(5)，以及更早砍掉的 Real-CUGAN / Real-ESRGAN /
     * W2xEX / AnimeJaNai / SPAN），统一收敛到唯一保留的 ACNet，
     * 避免解码器与设置页拿到无效值。
     */
    @Suppress("UNUSED_PARAMETER")
    fun sanitizeModel(model: Int): Int = MODEL_ANIME4K_ACNET

    /**
     * SY --> 被移除模型在旧版本中解压到 cacheDir 的目录名。
     * 升级安装后这些目录会残留下来（三款权重的副本合计约 15MB），
     * 在每次准备模型时顺手清理一次，避免继续白占空间。
     *
     * ⚠️ 本构建内置的 ACNet（`anime4k-acnet-models`）必须留在清单**外面** ——
     * 它解压后正好落在 cacheDir/<同名目录>，写进清单会在每次准备模型时把刚解出来的
     * 权重删掉。
     * `anime4k`（不带后缀）保留在这里做 cacheDir 清理（老版本曾把 GLSL 着色器解压到
     * cacheDir/anime4k）；它和 `anime4k-acnet-models` 是两个不同的目录名，互不影响。
     * SY <--
     */
    private val REMOVED_MODEL_ASSETS = listOf(
        "sudo-ultracompact",
        "waifu2x-models-upconv7",
        "realcugan-models",
        "realcugan-pro-models",
        "realcugan-up2x-conservative",
        "realesrgan-models",
        "realesrgan-animevideov3",
        "waifu2x-models",
        "waifu2x-models-nose",
        "animejanai-ncnn-vulkan",
        "span-nomosuni",
        "anime4k",
    )

    // SY --> 引擎装载：本构建只有一个引擎，直接在类初始化时装一次。
    //
    // 以前这里是惰性装载 + 失败回退（为在 MM/TX 两个 .so 之间二选一）；现在没有可选项，
    // 装载失败只可能是 APK 被裁过（非 arm64，或 jniLibs 被删），由 libLoaded 守卫兜住，
    // 不再做任何引擎分派。
    private val libLoaded: Boolean = try {
        System.loadLibrary("waifu2x-jni")
        true
    } catch (t: Throwable) {
        android.util.Log.e("Waifu2x", "libwaifu2x-jni load failed", t)
        false
    }
    // SY <--

    // SY --> 只有一套权重、一个引擎，所以只需要一个初始化标志。
    @Volatile private var isWaifu2xInitialized = false
    // SY <--

    @Volatile private var removedModelCachesPurged = false

    /**
     * SY --> 触发重新加载的条件。只剩一款模型后，variant / noise / scale 都成了常量
     * （ACNet 固定 noise=0、scale=2），只有精度与 fp16 运算开关会改变产出的像素，
     * 所以只比这两项。
     * SY <--
     */
    private data class Waifu2xConfig(
        val precision: Int,
        val fp16Arithmetic: Boolean,
    )
    private var lastWaifu2xConfig: Waifu2xConfig? = null

    /**
     * SY --> 加载 Anime4K v4 ACNet（单通道 luma 权重）。
     *
     * 输入 1 通道亮度、输出 1 通道 2 倍亮度，色度由 C++ 侧 luma 分支用原图补回
     * （pre/postproc 的 luma 着色器负责 RGBA8 ↔ 单通道亮度的转换）。
     * SY <--
     */
    fun initAnime4kAcnet(
        context: Context,
        tileSleepMs: Int = 0,
        tileSize: Int = DEFAULT_TILE_SIZE,
        precision: Int = 0,
        fp16Arithmetic: Boolean = false,
        coolSleepMs: Int = DEFAULT_COOL_SLEEP_MS,
    ): Boolean = synchronized(this) {
        val newConfig = Waifu2xConfig(sanitizePrecision(precision), fp16Arithmetic)

        if (lastWaifu2xConfig != newConfig) {
            android.util.Log.d("Waifu2x", "Anime4K ACNet config changed from $lastWaifu2xConfig to $newConfig, reinitializing...")
            isWaifu2xInitialized = false
        }

        if (!libLoaded) {
            android.util.Log.e("Waifu2x", "Native engine unavailable (libwaifu2x-jni not loaded)")
            return false
        }

        if (isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize, coolSleepMs)
            return true
        }

        val modelDir = extractModelsToCache(context, ANIME4K_ACNET_ASSET_PATH)
        if (modelDir == null) return false

        isWaifu2xInitialized = nativeInitAnime4kAcnet(modelDir, newConfig.precision, newConfig.fp16Arithmetic)
        if (isWaifu2xInitialized) {
            lastWaifu2xConfig = newConfig
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize, coolSleepMs)

            android.util.Log.d(
                "Waifu2x",
                "Initialized Anime4K ACNet (luma): tileSleepMs=$tileSleepMs, tileSize=$tileSize, " +
                    "coolSleepMs=$coolSleepMs, precision=${newConfig.precision}",
            )
        }
        isWaifu2xInitialized
    }

    /**
     * SY --> 按模型编号加载权重。设置页、解码器、预热三条路径共用这一份实现，
     * 避免「哪里漏改一个 when」导致实际加载的权重与 UI 上选的不一致。
     *
     * 本构建只有 ACNet 一款，`model` / `noise` / `scale` 三个参数保留是为了不动调用点
     * （调用方仍按「多模型」的语义传参，这里统一收敛到 ACNet，再由
     * [sanitizeModel] 保证 UI 侧看到的编号也是同一个）。
     * SY <--
     */
    @Suppress("UNUSED_PARAMETER")
    fun ensureLoaded(
        context: Context,
        model: Int,
        noise: Int = 0,
        scale: Int = 2,
        tileSleepMs: Int = 0,
        tileSize: Int = DEFAULT_TILE_SIZE,
        precision: Int = 0,
        fp16Arithmetic: Boolean = false,
        coolSleepMs: Int = DEFAULT_COOL_SLEEP_MS,
    ): Boolean = initAnime4kAcnet(context, tileSleepMs, tileSize, precision, fp16Arithmetic, coolSleepMs)

    fun processWaifu2x(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isWaifu2xInitialized) return null
        return processBitmapHelper(input, id)
    }

    @Volatile var processingId: Int = -1

    private fun processBitmapHelper(input: Bitmap, id: Int): Bitmap? {
        if (input.isRecycled) return null

        val argbBitmap = if (input.config != Bitmap.Config.ARGB_8888) {
            try {
                input.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        } else {
            input
        } ?: return null

        processingId = id
        try {
            // SY --> 本构建只有一套引擎，process 入口固定为 nativeProcessUpscale
            // （以前要按 loadedEngine 在 nativeProcess / nativeProcessUpscale 之间选，
            //  两个名字只在各自的 .so 里存在，选错就 UnsatisfiedLinkError）。
            val result = nativeProcessUpscale(argbBitmap, id)
            return if (result === argbBitmap) null else result
            // SY <--
        } finally {
            processingId = -1
            if (argbBitmap !== input) {
                argbBitmap.recycle()
            }
        }
    }

    /**
     * Get the raw packed progress value from native code.
     * Format: [ID (upper 32 bits)] [Progress (lower 32 bits)]
     */
    fun getProgress(): Long = if (!libLoaded) 0L else nativeGetProgress()

    /**
     * Get only the progress percentage (0-100) from the packed value.
     */
    fun getProgressPercent(): Int = (getProgress() and 0xFFFFFFFF).toInt()

    /**
     * Get only the processing ID from the packed value.
     */
    fun getProgressId(): Int = (getProgress() shr 32).toInt()

    /**
     * Reset the upscaler to allow re-initialization with new settings.
     */
    fun resetUpscaler() {
        isWaifu2xInitialized = false
        lastWaifu2xConfig = null
        if (libLoaded) nativeDestroy()
    }

    /**
     * Ask any active native upscaling operation to stop at its next cancellation check.
     */
    fun abortProcessing() {
        if (libLoaded) nativeAbortProcessing()
    }

    fun prepareProcessing() {
        if (libLoaded) nativeClearAbortProcessing()
    }

    /**
     * Release native resources.
     */
    fun destroy() {
        if (isWaifu2xInitialized) {
            nativeDestroy()
            isWaifu2xInitialized = false
            lastWaifu2xConfig = null
        }
    }

    /**
     * 把打包在 assets 里的 ACNet 权重解压到 cacheDir 并返回目录路径。
     *
     * SY --> 本构建只从宿主 assets 取权重（模型包热插拔体系已随 TX 引擎一并删除）。
     * 若解压失败则返回 null，上层会跳过本次超分、直接显示原图。
     * SY <--
     */
    private fun extractModelsToCache(context: Context, assetPath: String): String? {
        return try {
            purgeRemovedModelCaches(context)

            val cacheDir = File(context.cacheDir, assetPath)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            val assetManager = context.assets
            val modelFiles = assetManager.list(assetPath).orEmpty()
            val assetVersionFile = File(cacheDir, ".bundled-model-version")
            val refreshBundledModels = modelFiles.isNotEmpty() &&
                assetVersionFile.takeIf(File::exists)?.readText() != BUNDLED_MODEL_CACHE_VERSION

            for (filename in modelFiles) {
                val outFile = File(cacheDir, filename)
                if (refreshBundledModels || !outFile.exists()) {
                    assetManager.open("$assetPath/$filename").use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            if (refreshBundledModels) {
                assetVersionFile.writeText(BUNDLED_MODEL_CACHE_VERSION)
            }

            if (!cacheDir.hasNcnnModels()) {
                return null
            }

            cacheDir.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("Waifu2x", "Failed to prepare model assets: $assetPath", e)
            null
        }
    }

    /**
     * SY --> 清理旧版本为已移除模型留下的解压副本，只做一次。
     * SY <--
     */
    private fun purgeRemovedModelCaches(context: Context) {
        if (removedModelCachesPurged) return
        removedModelCachesPurged = true
        REMOVED_MODEL_ASSETS.forEach { name ->
            val dir = File(context.cacheDir, name)
            if (dir.exists() && dir.deleteRecursively()) {
                android.util.Log.d("Waifu2x", "Removed stale upscale model cache: $name")
            }
        }
    }

    private fun File.hasNcnnModels(): Boolean {
        val files = listFiles().orEmpty()
        return files.any { it.extension == "param" } && files.any { it.extension == "bin" }
    }

    private var lastUiBusyLogged = false
    fun setUiBusy(busy: Boolean) {
        if (!libLoaded) return
        nativeSetUiBusy(busy)
        // SY --> 验证用：仅在状态翻转时打印，避免每个 touch 事件刷屏。
        if (busy != lastUiBusyLogged) {
            lastUiBusyLogged = busy
            android.util.Log.d("Waifu2x", "setUiBusy=$busy (native yield to UI render thread)")
        }
        // SY <--
    }

    // SY --> Komiho Phase12: 连续滚动模式 GPU 交错让出（条漫）。
    // setContinuousMode：按阅读模式设置（条漫=true），原生据此在滑动期间强制让出 GPU 下限。
    // notifyInteraction：连续模式每次滑动（DOWN/MOVE）调用，刷新最近交互时间戳，
    //   让原生区分「正在滑动」与「停下阅读」。
    // 这两个符号都是自研引擎独有的（TX 的 .so 里没有）。
    fun setContinuousMode(continuous: Boolean) {
        if (!libLoaded) return
        nativeSetContinuousMode(continuous)
    }

    fun notifyInteraction() {
        if (!libLoaded) return
        nativeNotifyInteraction()
    }
    // SY <--

    fun scaleBitmapNative(input: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (input.isRecycled) return null
        if (input.width == targetWidth && input.height == targetHeight) return input

        val argbBitmap = if (input.config != Bitmap.Config.ARGB_8888) {
            try {
                input.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        } else {
            input
        } ?: return null

        return try {
            if (!libLoaded) null else nativeScaleBitmap(argbBitmap, targetWidth, targetHeight)
        } finally {
            if (argbBitmap !== input) {
                argbBitmap.recycle()
            }
        }
    }

    // Native methods
    // SY --> 原生侧导出符号必须与这里逐字一致。
    // 本构建只保留 ACNet 一路（原 nativeInitSudoUltra / nativeInitWaifu2xUpconv7 /
    // nativeInitW2xEx / nativeProcess 四个声明已随对应模型和 TX 引擎删除）。
    private external fun nativeInitAnime4kAcnet(modelDir: String, precision: Int, fp16Arithmetic: Boolean): Boolean
    // SY <--

    private external fun nativeDestroy()
    private external fun nativeAbortProcessing()
    private external fun nativeClearAbortProcessing()
    private external fun nativeSetUiBusy(busy: Boolean)
    private external fun nativeUpdatePerformanceConfig(tileSleepMs: Int, tileSize: Int, coolSleepMs: Int)
    private external fun nativeSetContinuousMode(continuous: Boolean)
    private external fun nativeNotifyInteraction()

    fun updatePerformance(tileSleepMs: Int, tileSize: Int, coolSleepMs: Int = 0) {
        if (isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize, coolSleepMs)
        }
    }

    private external fun nativeProcessUpscale(input: Bitmap, id: Int): Bitmap?
    private external fun nativeScaleBitmap(input: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap?
    private external fun nativeGetProgress(): Long
}
