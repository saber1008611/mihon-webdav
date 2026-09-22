package eu.kanade.tachiyomi.util.waifu2x

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Waifu2x image upscaler using ncnn.
 * Provides 2x upscaling with denoising for manga images.
 *
 * SY --> mihon-webdav「可达式双引擎」版（2026-09-22 改造）。
 *
 * 本构建把超分引擎换成 **waifu2x(CUnet) + Anime4K v4 GLSL** 双引擎，对齐 iOS「可达阅读器」
 * 的方案；其中两款引擎的权重/着色器**都不再内置**，全部走「模型包」（安装后才出现）。
 *
 *  6 = Anime4K v4 ACNet      —— 单通道 luma **cnn 权重**，唯一随主包内置的引擎。
 *                               9 层 3x3 卷积 + 2x2 反卷积，输入只有亮度。
 *                               有专属的单通道融合 Vulkan 管线（waifu2x_fused_*_luma.comp），
 *                               所以和 3 通道模型一样全程走 GPU；只有着色器创建失败时才退 CPU。
 *  7 = waifu2x CUnet         —— **模型包**提供（assets 目录 `waifu2x-models-cunet`）。
 *                               cascaded U-Net，3 通道，带 0/1/2/3 四档降噪，2x。
 *                               选它是因为可达的 waifu2x 支经考证最吻合 CUnet
 *                               （强降噪、网点涂抹感重），详见 output/可达阅读器-waifu2x模型考证-*.md。
 *  8 = Anime4K v4 GLSL       —— **模型包**提供（assets 目录 `anime4k`，13 个 .glsl）。
 *                               六预设 + 「细化线条 / 线条加黑」两个开关，见 [Anime4KPresets]。
 *
 * 三款都只支持 2x。被删除的是原先的 4=sudo UltraCompact(AnimeJaNai) 与 5=Waifu2x Fast/UpConv7。
 *
 * ## 未安装模型包时的行为
 *
 * 7 / 8 的资产不在主包里，`isModelAvailable()` 会返回 false → 设置页标注「未安装」，
 * `ensureLoaded()` 返回 false → 解码器跳过超分、直接显示原图（不是崩溃，也不是卡住）。
 * 所以「装了包 = 模型可用」，这条链路必须靠 [ModelPackLoader] 的 resolveDir/resolveAssetDir。
 *
 * ## 共享与隔离
 *
 * 6 / 7 共用同一个 ncnn 单例 `g_waifu2x` 与同一套融合管线，靠 [isWaifu2xInitialized] 管；
 * 8 走完全独立的 GLSL 执行器（`g_anime4k`），靠 [isAnime4kInitialized] 管。
 * **两者必须互斥**：ncnn 侧建过 Vulkan 实例、Anime4K 侧建过 EGL 上下文，
 * 同时初始化会在部分机型上抢 GPU 资源，所以各自 init 时会把对方置为未初始化。
 *
 * 注意 [Waifu2xVariant] 必须进配置指纹：6 与 7 在 noise/scale/precision
 * 三者相同的情况下会被误判成「已初始化、无需重载」，从而继续复用上一套权重。
 *
 * 上游基线是 Mihon 0.19.x，本文件是适配到 0.20.4 的移植结果。
 * SY <--
 */
object Waifu2x {

    // Bump when bundled model assets change so existing installations refresh their cache.
    // SY --> 19 → 20（2026-09-22 可达式双引擎改造）。
    // 主包内置的资产从 3 个目录（sudo / upconv7 / acnet）变成只剩 acnet，
    // 且新增了 7=CUnet、8=Anime4K GLSL 两个「模型包才提供」的引擎。
    // 递增会让老安装的 cacheDir 重新解压 —— 顺带把已被 REMOVED_MODEL_ASSETS
    // 接管的 sudo-ultracompact / waifu2x-models-upconv7 残留清掉。
    private const val BUNDLED_MODEL_CACHE_VERSION = "20"
    // SY <--

    /**
     * SY --> Komiho Phase11: 三款超分模型共用同一个 tilesize 默认值。
     * 从 128 提到 192：tile 越大，每块多算的 prepadding 重叠越少、块数越少，
     * 推理更快；ncnn 侧还会按 GPU 显存预算再收敛一次上限（见 set_tilesize）。
     * SY <--
     */
    const val DEFAULT_TILE_SIZE = 192

    // SY --> 本构建保留的三款超分引擎编号，设置页与解码器共用。
    // 6 沿用 TachiyomiX modelpack 的既有约定；7/8 是本构建新增（可达式双引擎）。

    /**
     * Anime4K v4 ACNet —— 单通道 luma **ncnn 权重**，唯一随主包内置的引擎。
     * 权重由 `anime4k_acnet.onnx` 手写转换成 ncnn param/bin（9 层 3x3 卷积 + 2x2 反卷积）。
     * 有专属的单通道融合 Vulkan 管线，同样全程走 GPU。
     */
    const val MODEL_ANIME4K_ACNET = 6

    /**
     * waifu2x **CUnet** —— cascaded U-Net，3 通道，带 0/1/2/3 四档降噪，2x。
     *
     * 这是本构建「补回」的 waifu2x 支：可达阅读器的 waifu2x 经考证最吻合 CUnet
     * （降噪最激进 ⇒ 网点涂抹感重，与可达的实测表现一致）。
     *
     * 权重由**模型包**提供，主包不带；与 6 共用 ncnn 单例，可走融合 Vulkan 管线。
     */
    const val MODEL_WAIFU2X_CUNET = 7

    /**
     * Anime4K v4 GLSL —— 官方 6 个模式预设 + 「细化线条 / 线条加黑」两个开关。
     *
     * 走独立的 GLSL 执行器（全图 FBO，不分 tile），所以受 `GL_MAX_TEXTURE_SIZE` 限制，
     * 需要 [anime4kSupportsSize] 做尺寸守卫，超限时上层回退到 ncnn 模型。
     *
     * 着色器由**模型包**提供，主包不带。
     */
    const val MODEL_ANIME4K_GLSL = 8
    // SY <--

    // SY --> 融合 GPU 管线只认 fp16 / fp32 两种存储布局：两支 preproc/postproc
    // SPIR-V 里把元素类型写死成 float16_t（或 float）并按 std430 紧凑排布索引，
    // 而 INT8 的 VkMat 元素是 1 字节、BF16 是 2 字节但位模式不同，都对不上。
    // ncnn 在 load() 里也会因此直接把 gpu_pipeline_available 置 false
    // （见 waifu2x.cpp：`precision_mode == 1 || (precision_mode == 0 && fp16_storage)`）。
    //
    // 后果是：一旦用户选了 INT8/BF16，**三款模型会一起掉回 CPU staged 路径**。
    // 为了兑现「所有超分模型都走 GPU」，这里把精度收敛到 FP16/FP32 二选一。
    // 另外 ARM GPU 上 precision_mode 会被强制改成 FP32（见 waifu2x.cpp 的
    // Immortalis/Mali fp16 grid artifact 修复），所以 FP16 在那些机型上等价于 FP32。
    const val PRECISION_FP16 = 0
    const val PRECISION_FP32 = 1

    /** 把任意偏好值收敛到融合管线支持的精度：只可能是 FP16 或 FP32。 */
    fun sanitizePrecision(precision: Int): Int =
        if (precision == PRECISION_FP32) PRECISION_FP32 else PRECISION_FP16
    // SY <--

    // SY --> 每个引擎对应的 assets/ 子目录名。它同时是「模型包内的目录名」和宿主解析的键。
    //
    // ⚠️ 新增/改名这里的任何一项之前，先 grep 一遍 REMOVED_MODEL_ASSETS：
    // 该清单里的目录名会在每次准备模型时被从 cacheDir 里删掉，
    // 若与这里的名字撞上，就会出现「刚解压出来的权重立刻被自己删掉」这种无声故障。
    // 本构建新用的两个名字已避开该清单 —— 其中 `anime4k` 只作**模型包内**目录名
    // （落在 filesDir/modelpacks/<id>/anime4k），与清单里清理的 cacheDir/anime4k
    // 不是同一条路径，因此可以安全重名。
    private const val CUNET_ASSET_PATH = "waifu2x-models-cunet"
    private const val ANIME4K_ACNET_ASSET_PATH = "anime4k-acnet-models"
    private const val ANIME4K_GLSL_ASSET_PATH = "anime4k"

    /** 引擎编号 → assets 子目录名；未知编号返回 null。 */
    fun assetPathForModel(model: Int): String? = when (model) {
        MODEL_WAIFU2X_CUNET -> CUNET_ASSET_PATH
        MODEL_ANIME4K_ACNET -> ANIME4K_ACNET_ASSET_PATH
        MODEL_ANIME4K_GLSL -> ANIME4K_GLSL_ASSET_PATH
        else -> null
    }

    /**
     * 该模型当前是否可用：对应模型包已安装，或宿主仍内置了该 assets 目录。
     *
     * 设置页用它标注「未安装」——宿主已不带权重，用户选到一个没装的模型时只会静默显示原图，
     * 没有任何反馈，很容易被误判成「超分坏了」。
     */
    fun isModelAvailable(context: Context, model: Int): Boolean {
        val assetPath = assetPathForModel(model) ?: return false
        if (ModelPackLoader.resolveDir(context, assetPath) != null) return true
        return context.assets.list(assetPath)?.isNotEmpty() == true
    }
    // SY <--

    /**
     * 旧版本的偏好里可能残留已移除模型的编号（0/1/2/3/4/5/9/16/18/19 …，
     * 含被砍掉的 sudo UltraCompact、UpConv7、Real-CUGAN、Real-ESRGAN），
     * 统一收敛到保留的三款之一，避免解码器与设置页拿到无效值。
     *
     * 兜底值取 [MODEL_ANIME4K_ACNET]：三款里**唯一随主包内置**的，
     * 所以无论用户有没有装模型包，收敛结果都一定可用 ——
     * 若兜底到 7/8，没装包的用户会落到「所有引擎都未安装」的空状态。
     */
    fun sanitizeModel(model: Int): Int = when (model) {
        MODEL_WAIFU2X_CUNET -> MODEL_WAIFU2X_CUNET
        MODEL_ANIME4K_ACNET -> MODEL_ANIME4K_ACNET
        MODEL_ANIME4K_GLSL -> MODEL_ANIME4K_GLSL
        else -> MODEL_ANIME4K_ACNET
    }
    // SY <--

    /**
     * SY --> 被移除模型在旧版本中解压到 cacheDir 的目录名。
     * 升级安装后这些目录会残留下来（合计约 80MB 的模型副本），
     * 在每次准备模型时顺手清理一次，避免继续白占空间。
     *
     * ⚠️ 本构建（可达式双引擎）**随主包内置的只有 `anime4k-acnet-models`**，
     * 它必须留在清单**外面** —— 它解压后正好落在 cacheDir/<同名目录>，
     * 写进清单会在每次准备模型时把刚解出来的权重删掉。
     *
     * 反过来，`sudo-ultracompact` 与 `waifu2x-models-upconv7` 这次**被删掉了**，
     * 所以必须加进清单，否则老安装的 cacheDir 里会残留约 6.6MB 的废弃权重副本。
     *
     * `anime4k` 保留在这里做 cacheDir 清理（老版本曾经把 GLSL 解压到 cacheDir/anime4k）；
     * 注意它不会影响模型包 —— 模型包的目录在 filesDir/modelpacks/<id>/anime4k，
     * 是另一条路径。
     * SY <--
     */
    private val REMOVED_MODEL_ASSETS = listOf(
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
        // SY --> 2026-09-22：本构建不再内置这两款，加进来清老缓存。
        "sudo-ultracompact",
        "waifu2x-models-upconv7",
        // SY <--
    )

    // SY --> 两条**互斥**的初始化通道。
    //
    // isWaifu2xInitialized：6=ACNet 与 7=CUnet 共用。它们在 C++ 侧都落在同一个
    //   g_waifu2x 单例上，同时只可能有一套权重生效。
    // isAnime4kInitialized：8=Anime4K GLSL 专用，走独立的 g_anime4k 执行器。
    //
    // 互斥的理由：ncnn 侧会 create_gpu_instance()（Vulkan），Anime4K 侧会建 EGL 上下文，
    // 同时活着会在部分机型上争抢 GPU 资源。所以任一侧 init 成功时都把另一侧置 false
    // —— 注意只是**标记**为未初始化，真正的资源释放交给各自的 destroy/析构。
    @Volatile private var isWaifu2xInitialized = false
    @Volatile private var isAnime4kInitialized = false
    // SY <--

    @Volatile private var removedModelCachesPurged = false

    init {
        try {
            System.loadLibrary("waifu2x-jni")
        } catch (e: UnsatisfiedLinkError) {
            // Native library not available
        }
    }

    /**
     * SY --> 哪一套 ncnn 权重被加载。
     * 必须进入配置指纹，否则「CUnet / ACNet」在 noise/scale/precision
     * 相同的情况下会被判定为「已初始化、无需重载」，从而继续复用上一套权重。
     *
     * Anime4K GLSL 不进这个枚举：它不走 ncnn，有自己的 [isAnime4kInitialized]。
     * SY <--
     */
    private enum class Waifu2xVariant { CUNET, ANIME4K_ACNET }

    // Track Waifu2x config
    private data class Waifu2xConfig(
        val variant: Waifu2xVariant,
        val noise: Int,
        val scale: Int,
        val precision: Int,
        val fp16Arithmetic: Boolean,
    )
    private var lastWaifu2xConfig: Waifu2xConfig? = null

    /**
     * SY --> Anime4K v4 ACNet（单通道 luma 权重）。
     *
     * 权重由 `anime4k_acnet.onnx` 手写转换成 ncnn param/bin（9 层 3x3 卷积 + 2x2 反卷积）。
     * 输入 1 通道亮度、输出 1 通道 2 倍亮度，色度由 C++ 侧 luma 分支用原图补回。
     *
     * 单通道模型也有自己的融合 Vulkan 管线：`waifu2x_fused_{preproc,postproc}_luma.comp`
     * 把 RGBA8 转成 BT.601 亮度喂网络、再用 Y' + 全图双三次色度合成回 RGBA8，
     * 所以 ACNet 与另两款一样**全程走 GPU**（见 waifu2x.cpp 的 luma_model 分支）。
     * 只有着色器创建失败 / 老驱动不支持时才会退回 CPU staged 路径。
     * SY <--
     */
    fun initAnime4kAcnet(context: Context, tileSleepMs: Int = 0, tileSize: Int = DEFAULT_TILE_SIZE, precision: Int = 0, fp16Arithmetic: Boolean = false): Boolean = synchronized(this) {
        val newConfig = Waifu2xConfig(Waifu2xVariant.ANIME4K_ACNET, 0, 2, sanitizePrecision(precision), fp16Arithmetic)

        if (lastWaifu2xConfig != newConfig) {
            android.util.Log.d("Waifu2x", "Anime4K ACNet config changed from $lastWaifu2xConfig to $newConfig, reinitializing...")
            isWaifu2xInitialized = false
        }

        if (isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        val modelDir = extractModelsToCache(context, ANIME4K_ACNET_ASSET_PATH)
        if (modelDir == null) return false

        isWaifu2xInitialized = nativeInitAnime4kAcnet(modelDir, newConfig.precision, newConfig.fp16Arithmetic)
        if (isWaifu2xInitialized) {
            // SY --> ncnn 侧拿到 GPU 之后，把 GLSL 侧标记为失效（见 isAnime4kInitialized 的互斥说明）。
            isAnime4kInitialized = false
            // SY <--
            lastWaifu2xConfig = newConfig
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)

            android.util.Log.d("Waifu2x", "Initialized Anime4K ACNet (luma): tileSleepMs=$tileSleepMs, tileSize=$tileSize, precision=${newConfig.precision}")
        }
        isWaifu2xInitialized
    }

    /**
     * SY --> waifu2x **CUnet**（3 通道，带 0/1/2/3 四档降噪，2x）。
     *
     * 权重由**模型包**提供（assets 目录 `waifu2x-models-cunet`，只有 2x 的四档降噪模型，
     * 共 8 个文件 ≈11MB）。主包不带 → 没装包时 [extractModelsToCache] 返回 null，
     * 本函数返回 false，上层跳过超分显示原图。
     *
     * C++ 侧复用的是原 UpConv7 的入口（现已改名 nativeInitWaifu2xCunet）：
     * 两者同源 nihui/waifu2x-ncnn-vulkan，`noise{N}_scale2.0x_model.param` 命名完全一致；
     * 感受野差异（UpConv7 小、CUnet 大）由 Waifu2x::load() 的
     * derive_prepadding_from_param() 自动推导，无需特调。
     * SY <--
     */
    fun initWaifu2xCunet(context: Context, noise: Int, scale: Int, tileSleepMs: Int = 0, tileSize: Int = DEFAULT_TILE_SIZE, precision: Int = 0, fp16Arithmetic: Boolean = false): Boolean = synchronized(this) {
        val newConfig = Waifu2xConfig(Waifu2xVariant.CUNET, noise, scale, sanitizePrecision(precision), fp16Arithmetic)

        // Force reinit if config changed
        if (lastWaifu2xConfig != newConfig) {
            android.util.Log.d("Waifu2x", "waifu2x CUnet config changed from $lastWaifu2xConfig to $newConfig, reinitializing...")
            isWaifu2xInitialized = false
        }

        if (isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
            return true
        }

        val modelDir = extractModelsToCache(context, CUNET_ASSET_PATH)
        if (modelDir == null) {
            android.util.Log.w("Waifu2x", "waifu2x CUnet model not available (model pack not installed?)")
            return false
        }

        isWaifu2xInitialized = nativeInitWaifu2xCunet(modelDir, noise, scale, newConfig.precision, newConfig.fp16Arithmetic)
        if (isWaifu2xInitialized) {
            // SY --> 与 GLSL 侧互斥，见 isAnime4kInitialized 的说明。
            isAnime4kInitialized = false
            lastAnime4kChain = null
            // SY <--
            lastWaifu2xConfig = newConfig
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)

            android.util.Log.d("Waifu2x", "Initialized waifu2x CUnet: noise=$noise, scale=$scale, tileSleepMs=$tileSleepMs, tileSize=$tileSize, precision=${newConfig.precision}")
        }
        isWaifu2xInitialized
    }

    /**
     * SY --> Anime4K v4 **GLSL**（可达式六预设 + 两个线条开关）。
     *
     * 与 ncnn 路线的三处结构性差异：
     *  1. **不吃 tile / precision**：整幅图渲进 FBO，受 `GL_MAX_TEXTURE_SIZE` 限制
     *     → 必须先用 [anime4kSupportsSize] 守卫，超限由上层回退到 ncnn 模型；
     *  2. **source 是文本**：把 `.glsl` 原文读成字符串数组传给 native（不是文件路径），
     *     因为执行器要按 `//!DESC` 切多 pass 现场编译；
     *  3. **着色器只在模型包里**：主包不自带，所以没装包 → 返回 false。
     *
     * 已经把同一套链初始化过就直接复用（链相同 = 无需重编译，编译几十个 pass 很贵）。
     * SY <--
     */
    @Volatile private var lastAnime4kChain: List<String>? = null

    fun initAnime4K(
        context: Context,
        presetId: Int,
        thinLines: Boolean = false,
        darkenLines: Boolean = false,
    ): Boolean = synchronized(this) {
        val chain = Anime4KPresets.buildChain(presetId, thinLines, darkenLines)
        if (isAnime4kInitialized && lastAnime4kChain == chain) {
            return true
        }

        val dir = ModelPackLoader.resolveDir(context, ANIME4K_GLSL_ASSET_PATH)
        if (dir == null) {
            android.util.Log.w("Waifu2x", "Anime4K GLSL shaders not available (model pack not installed?)")
            return false
        }

        // 按链逐个读原文；缺任何一个都判定为包不完整，宁可整体失败也不跑半条链。
        val shaders = ArrayList<String>(chain.size)
        val names = ArrayList<String>(chain.size)
        for (name in chain) {
            val f = File(dir, name)
            if (!f.isFile) {
                android.util.Log.e("Waifu2x", "Anime4K shader missing in pack: $name")
                return false
            }
            shaders += try {
                f.readText()
            } catch (e: Exception) {
                android.util.Log.e("Waifu2x", "Failed to read Anime4K shader: $name", e)
                return false
            }
            names += name
        }

        // SY --> 切到 GLSL 前先把 ncnn 单例释放掉：两个后端都会建 GPU 实例/EGL 上下文，
        // 同时活着会在部分机型上抢资源。nativeDestroy() 在 C++ 侧会同时清掉 g_waifu2x 与 g_anime4k。
        if (isWaifu2xInitialized) {
            nativeDestroy()
            isWaifu2xInitialized = false
            lastWaifu2xConfig = null
        }
        // SY <--

        isAnime4kInitialized = nativeInitAnime4K(shaders.toTypedArray(), names.toTypedArray())
        if (isAnime4kInitialized) {
            lastAnime4kChain = chain
            anime4kMaxTextureSize = nativeGetAnime4KMaxTextureSize().coerceAtLeast(0)
            android.util.Log.d(
                "Waifu2x",
                "Initialized Anime4K GLSL: ${chain.size} shader(s), maxTex=$anime4kMaxTextureSize, chain=$chain",
            )
        } else {
            android.util.Log.e("Waifu2x", "Anime4K GLSL init failed for chain=$chain")
        }
        isAnime4kInitialized
    }

    /** 强制用新链重新编译（切预设/切开关时用；[initAnime4K] 内部已按链去重）。 */
    fun resetAnime4K() {
        isAnime4kInitialized = false
        lastAnime4kChain = null
    }

    /**
     * SY --> 按模型编号加载对应权重。
     *
     * 设置页、解码器、预热三条路径共用这一份分派，避免「哪里漏改一个 when」
     * 导致实际加载的权重与 UI 上选的不一致。model 必须已经过 [sanitizeModel] 收敛。
     * SY <--
     */
    fun ensureLoaded(
        context: Context,
        model: Int,
        noise: Int = 0,
        scale: Int = 2,
        tileSleepMs: Int = 0,
        tileSize: Int = DEFAULT_TILE_SIZE,
        precision: Int = 0,
        fp16Arithmetic: Boolean = false,
        anime4kPreset: Int = 0,
        anime4kThinLines: Boolean = false,
        anime4kDarkenLines: Boolean = false,
    ): Boolean = when (model) {
        MODEL_WAIFU2X_CUNET -> initWaifu2xCunet(context, noise, scale, tileSleepMs, tileSize, precision, fp16Arithmetic)
        MODEL_ANIME4K_ACNET -> initAnime4kAcnet(context, tileSleepMs, tileSize, precision, fp16Arithmetic)
        MODEL_ANIME4K_GLSL -> initAnime4K(context, anime4kPreset, anime4kThinLines, anime4kDarkenLines)
        else -> initAnime4kAcnet(context, tileSleepMs, tileSize, precision, fp16Arithmetic)
    }

    /** 当前加载的引擎是否走 GLSL 路线（决定上层该调 [processAnime4K] 还是 [processWaifu2x]）。 */
    fun isGlslEngineActive(): Boolean = isAnime4kInitialized

    fun processWaifu2x(input: Bitmap, id: Int = -1): Bitmap? {
        if (!isWaifu2xInitialized) return null
        return processBitmapHelper(input, id)
    }

    /**
     * SY --> Anime4K GLSL 推理：整幅图进出，不分 tile、不回传进度。
     *
     * 返回 null 表示「本次不予处理」，上层会保留原图 —— 三种情况：
     *  · 引擎未初始化（没装包 / 编译失败）；
     *  · 输入或 2x 输出超出 GL_MAX_TEXTURE_SIZE；
     *  · 输入位图已回收。
     *
     * 输入位图**不会被修改**（native 侧只读它、另建输出位图），所以不需要拷贝。
     * SY <--
     */
    fun processAnime4K(input: Bitmap): Bitmap? {
        if (!isAnime4kInitialized) return null
        if (input.isRecycled) return null
        if (!anime4kSupportsSize(input.width, input.height)) {
            android.util.Log.w(
                "Waifu2x",
                "Anime4K skipped: ${input.width}x${input.height} (2x -> ${input.width * 2}x${input.height * 2}) " +
                    "exceeds GL_MAX_TEXTURE_SIZE=$anime4kMaxTextureSize",
            )
            return null
        }
        val argb = if (input.config == Bitmap.Config.ARGB_8888) {
            input
        } else {
            try {
                input.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        } ?: return null

        return try {
            val result = nativeProcessAnime4K(argb)
            // C++ 侧在失败时（输出位图创建失败 / g_anime4k 为空）会**原样返回入参**，
            // 所以这里要和 ncnn 路径一样用引用比较挡一道，否则会把入参当成结果返回，
            // 并在 finally 里把它回收掉 —— 上层拿到一张已 recycle 的位图。
            if (result === argb) null else result
        } finally {
            if (argb !== input && !argb.isRecycled) {
                argb.recycle()
            }
        }
    }

    /**
     * SY --> 本机 GPU 的 GL_MAX_TEXTURE_SIZE（首次查询后缓存）。
     * 0 表示尚未查询到（引擎没初始化）。
     * SY <--
     */
    @Volatile private var anime4kMaxTextureSize = 0

    /**
     * GLSL 路线是否吃得下这张图。
     *
     * 判据是**输入与 2x 输出都要在纹理上限内**：全图 FBO 管线不做分块，
     * 输出超限就会直接失败。长条图（webtoon 高度动辄上万）几乎必然撞上这条，
     * 所以调用方在 false 时必须回退到 ncnn 模型，而不是硬着头皮跑。
     */
    fun anime4kSupportsSize(width: Int, height: Int): Boolean {
        val limit = anime4kMaxTextureSize
        if (limit <= 0) return false
        return width <= limit && height <= limit &&
            width.toLong() * 2 <= limit && height.toLong() * 2 <= limit
    }

    /**
     * GLSL 路线的纹理上限（首次 init 时查询并缓存；0 = 引擎未初始化/查询失败）。
     *
     * 解码器用它**先把输入缩到 (limit / 2x)**，从而让 GLSL 也能处理长条图
     * ——否则那些图只能整页跳过增强。返回 0 表示拿不到上限，调用方不要据此裁剪。
     */
    fun anime4kTextureLimit(): Int = anime4kMaxTextureSize

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
            val result = nativeProcessUpscale(argbBitmap, id)
            return if (result === argbBitmap) null else result
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
    fun getProgress(): Long = nativeGetProgress()

    /**
     * Get only the progress percentage (0-100) from the packed value.
     *
     * SY --> Anime4K GLSL 路线**没有页内进度**：整幅图一次性提交给 FBO 管线，
     * 中途没有可上报的分块。它的执行器也不去写 g_progress，所以本函数在 GLSL
     * 生效期间会稳定返回 0 —— 这是预期行为，不是「卡住了」。
     * 之所以不会读到上一款 ncnn 模型的残留值：切到 GLSL 时 [initAnime4K] 会先
     * nativeDestroy()，C++ 侧把 g_progress 清零。
     * SY <--
     */
    fun getProgressPercent(): Int {
        val packed = nativeGetProgress()
        return (packed and 0xFFFFFFFF).toInt()
    }

    /**
     * Get only the processing ID from the packed value.
     */
    fun getProgressId(): Int {
        val packed = nativeGetProgress()
        return (packed shr 32).toInt()
    }

    /**
     * Reset the upscaler to allow re-initialization with new settings.
     */
    fun resetUpscaler() {
        // SY --> nativeDestroy() 在 C++ 侧会同时清掉 g_waifu2x 与 g_anime4k，
        // 所以两条通道的状态都要归零，否则下次 ensureLoaded 会误判「还活着」。
        isWaifu2xInitialized = false
        lastWaifu2xConfig = null
        isAnime4kInitialized = false
        lastAnime4kChain = null
        anime4kMaxTextureSize = 0
        // SY <--
        nativeDestroy()
    }

    /**
     * Ask any active native upscaling operation to stop at its next cancellation check.
     */
    fun abortProcessing() {
        nativeAbortProcessing()
    }

    fun prepareProcessing() {
        nativeClearAbortProcessing()
    }

    /**
     * Release native resources.
     *
     * SY --> 双引擎版：ncnn 路线（6/7）与 GLSL 路线（8）各有一个初始化标志，
     * 而 nativeDestroy() 一次会把 C++ 侧的 g_waifu2x 与 g_anime4k 都清掉，
     * 所以两条标志必须一起归零 —— 只要有一条还留在 true，
     * 下次 [ensureLoaded] 就会走「已初始化」的快速返回分支，复用已被释放的资源。
     * SY <--
     */
    fun destroy() {
        if (isWaifu2xInitialized || isAnime4kInitialized) {
            nativeDestroy()
            isWaifu2xInitialized = false
            lastWaifu2xConfig = null
            isAnime4kInitialized = false
            lastAnime4kChain = null
            anime4kMaxTextureSize = 0
        }
    }

    /**
     * 把打包在 assets 里的模型解压到 cacheDir 并返回目录路径。
     *
     * SY --> 原先这里还有「assets 缺失时从 GitHub/HuggingFace 在线下载模型」的兜底逻辑，
     * 它只服务于已移除的模型；保留的 waifu2x 权重本身完整打包在 assets 中，
     * 因此该兜底与相关下载实现一并删除。若解压失败则返回 null，
     * 上层会跳过本次超分、直接显示原图。
     * SY <--
     */
    private fun extractModelsToCache(context: Context, assetPath: String): String? {
        return try {
            purgeRemovedModelCaches(context)

            // SY --> PoC: 优先从「已安装的模型包」（安装包模式）加载；找不到再回退宿主自带 assets。
            ModelPackLoader.resolveAssetDir(context, assetPath)?.let { packDir ->
                android.util.Log.i("Waifu2x", "Model source=PACK '$assetPath' -> ${packDir.absolutePath}")
                return packDir.absolutePath
            }
            android.util.Log.i("Waifu2x", "Model source=BUILTIN '$assetPath'")
            // SY <--

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
        nativeSetUiBusy(busy)
        // SY --> 验证用：仅在状态翻转时打印，避免每个 touch 事件刷屏。
        if (busy != lastUiBusyLogged) {
            lastUiBusyLogged = busy
            android.util.Log.d("Waifu2x", "setUiBusy=$busy (native yield to UI render thread)")
        }
    }

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
            nativeScaleBitmap(argbBitmap, targetWidth, targetHeight)
        } finally {
            if (argbBitmap !== input) {
                argbBitmap.recycle()
            }
        }
    }

    // Native methods
    // SY --> 双引擎版的三个初始化入口。
    //
    // 6/7 共用 ncnn：nativeInitAnime4kAcnet / nativeInitWaifu2xCunet
    //   —— 后者在 C++ 侧复用原 UpConv7 的实现（同源 waifu2x-ncnn-vulkan，
    //      `noise{N}_scale2.0x_model.{param,bin}` 命名一致，感受野由
    //      derive_prepadding_from_param() 自动推导），只是改了导出符号名。
    // 8 走 GLSL：nativeInitAnime4K(shaders, names) 收的是**着色器原文**而非路径
    //   —— 执行器要按 `//!DESC` 现场切多 pass 编译；返回 false 表示整体失败。
    private external fun nativeInitAnime4kAcnet(modelDir: String, precision: Int, fp16Arithmetic: Boolean): Boolean
    private external fun nativeInitWaifu2xCunet(modelDir: String, noiseLevel: Int, scale: Int, precision: Int, fp16Arithmetic: Boolean): Boolean
    private external fun nativeInitAnime4K(shaders: Array<String>, names: Array<String>): Boolean
    private external fun nativeGetAnime4KMaxTextureSize(): Int
    private external fun nativeProcessAnime4K(input: Bitmap): Bitmap?
    // SY <--
    private external fun nativeDestroy()
    private external fun nativeAbortProcessing()
    private external fun nativeClearAbortProcessing()
    private external fun nativeSetUiBusy(busy: Boolean)

    // SY --> nativeInitRealcugan 仍未声明 —— Real-CUGAN 本构建不含。
    // C++ 侧仍有同名导出符号，但没有任何 Kotlin 调用点（JNI 是静态符号绑定，
    // 不注册就不会被解析），等价于不可用。
    // SY <--

    private external fun nativeUpdatePerformanceConfig(tileSleepMs: Int, tileSize: Int)

    fun updatePerformance(tileSleepMs: Int, tileSize: Int) {
        if (isWaifu2xInitialized) {
            nativeUpdatePerformanceConfig(tileSleepMs, tileSize)
        }
    }

    private external fun nativeProcessUpscale(input: Bitmap, id: Int): Bitmap?
    private external fun nativeScaleBitmap(input: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap?
    private external fun nativeGetProgress(): Long
}
