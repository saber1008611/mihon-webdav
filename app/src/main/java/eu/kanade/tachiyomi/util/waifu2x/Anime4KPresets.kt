package eu.kanade.tachiyomi.util.waifu2x

/**
 * SY --> Anime4K v4.x 官方 GLSL 模式表 → 「可达阅读器」式六预设。
 *
 * ## 为什么是这六个
 *
 * 可达阅读器（iOS）的画质增强是 **waifu2x + Anime4K 双引擎**，UI 把 Anime4K 官方模式
 * 摊成 6 个预设：`A+A / B+B / C+A` × `快速 / 高质量`，另加「细化线条」「线条加黑」两个开关。
 * 本表逐条对齐官方 `md/Template/GLSL_*_{High,Low}-end/input.conf` 的 shader 链。
 *
 * ## 与官方原版的一处必要偏离：输出固定 2x
 *
 * 官方链（以 A+A HQ 为例）是 7 段：
 * ```
 * Clamp → Restore_CNN_VL → Upscale_CNN_x2_VL → Restore_CNN_M
 *       → AutoDownscalePre_x2 → AutoDownscalePre_x4 → Upscale_CNN_x2_M
 * ```
 * 尾部的 `AutoDownscalePre` 是 **mpv 专属的自适应件**：它带
 * `//!WHEN OUTPUT.w NATIVE.w / 2.0 < ...` 条件，只有当「播放器输出尺寸 / 片源尺寸」
 * 落在 1.2~2.0 或 2.4~4.0 之间时才触发，把已放大的中间结果缩回输出尺寸。
 * 我们的阅读器没有 `OUTPUT` 这个概念（整条链路固定 2x），该条件恒不成立 ⇒ ADP 是空操作。
 *
 * 因此本表**删掉两个 ADP pass，并删掉末尾那个 2x 上采样 pass**，使每个预设都精确输出 2x：
 * - 与工程既有契约一致（`ImageEnhancementCache.getEffectiveScale` 对所有模型都返回 2，
 *   缓存键里也带 scale=2）；
 * - 4x 输出对 1500×2000 的漫画页 = 6000×8000 ≈ 192MB/页，会直接撞 `GL_MAX_TEXTURE_SIZE`
 *   并被 `ImageDecoder` 缩回去，纯属浪费显存与算力。
 *
 * ## pass 数量级（用于估性能）
 *
 * 每个 `.glsl` 是**多 pass 打包文件**（按 `//!DESC` 切分）：
 * - Clamp_Highlights 3 / Restore_CNN_M 8 / Restore_CNN_VL 17 / Restore_CNN_S 7
 * - Upscale_CNN_x2_M 9 / Upscale_CNN_x2_VL 18 / Upscale_Denoise_CNN_x2_M 9
 * - Thin_HQ 8 / Darken_HQ 3
 *
 * 所以 A+A 高质量 ≈ 3+17+18+8 = **46 个 pass / 页**，是全表最重的一档；C+A 快速最轻。
 * SY <--
 */
object Anime4KPresets {

    // ---- 着色器文件名（模型包 assets/anime4k/ 与宿主 assets/anime4k/ 同名）----

    private const val CLAMP = "Anime4K_Clamp_Highlights.glsl"

    private const val RESTORE_S = "Anime4K_Restore_CNN_S.glsl"
    private const val RESTORE_M = "Anime4K_Restore_CNN_M.glsl"
    private const val RESTORE_VL = "Anime4K_Restore_CNN_VL.glsl"

    private const val RESTORE_SOFT_S = "Anime4K_Restore_CNN_Soft_S.glsl"
    private const val RESTORE_SOFT_M = "Anime4K_Restore_CNN_Soft_M.glsl"
    private const val RESTORE_SOFT_VL = "Anime4K_Restore_CNN_Soft_VL.glsl"

    private const val UPSCALE_M = "Anime4K_Upscale_CNN_x2_M.glsl"
    private const val UPSCALE_VL = "Anime4K_Upscale_CNN_x2_VL.glsl"

    private const val UPSCALE_DENOISE_M = "Anime4K_Upscale_Denoise_CNN_x2_M.glsl"
    private const val UPSCALE_DENOISE_VL = "Anime4K_Upscale_Denoise_CNN_x2_VL.glsl"

    /** 「细化线条」——把线条变细，提升主观清晰度。 */
    const val THIN_LINES = "Anime4K_Thin_HQ.glsl"

    /** 「线条加黑」——加深线条对比度。 */
    const val DARKEN_LINES = "Anime4K_Darken_HQ.glsl"

    /** 六预设共需的着色器全集；`isModelAvailable` 之外还用它做包完整性自检。 */
    val ALL_SHADERS: List<String> = listOf(
        CLAMP,
        RESTORE_S, RESTORE_M, RESTORE_VL,
        RESTORE_SOFT_S, RESTORE_SOFT_M, RESTORE_SOFT_VL,
        UPSCALE_M, UPSCALE_VL,
        UPSCALE_DENOISE_M, UPSCALE_DENOISE_VL,
        THIN_LINES, DARKEN_LINES,
    )

    /**
     * 一个预设 = 预设编号 + 其基础 shader 链（不含两个后处理开关）。
     *
     * @param family 展示用模式名（`A+A` / `B+B` / `C+A`，官方命名，各语言下一致，不进 i18n）
     * @param highQuality true = 官方 High-end 档（VL 级 CNN），false = Low-end 档（S/M 级）
     */
    data class Preset(
        val id: Int,
        val family: String,
        val highQuality: Boolean,
        val shaders: List<String>,
    )

    /**
     * 六预设，顺序与可达 UI 一致：A+A → B+B → C+A，每族先「快速」后「高质量」。
     *
     * 链尾保留的是**第一个** `Upscale_CNN_x2_*`（真正输出 2x 的那一段），
     * 第二段 Restore 保留（这正是 `+` 模式相对单模式多出来的一轮修复，也是画质差异的来源）。
     */
    val PRESETS: List<Preset> = listOf(
        // A+A（Fast）：官方 Low-end Mode A+A —— 去掉尾部 ADP 与 Upscale_x2_S
        Preset(0, "A+A", false, listOf(CLAMP, RESTORE_M, UPSCALE_M, RESTORE_S)),
        // A+A（HQ）：官方 High-end Mode A+A —— 去掉尾部 ADP 与 Upscale_x2_M
        Preset(1, "A+A", true, listOf(CLAMP, RESTORE_VL, UPSCALE_VL, RESTORE_M)),
        // B+B（Fast）：换 Soft 系恢复算子（对降采样锯齿更友好）
        Preset(2, "B+B", false, listOf(CLAMP, RESTORE_SOFT_M, UPSCALE_M, RESTORE_SOFT_S)),
        // B+B（HQ）
        Preset(3, "B+B", true, listOf(CLAMP, RESTORE_SOFT_VL, UPSCALE_VL, RESTORE_SOFT_M)),
        // C+A（Fast）：先「放大同时降噪」，再补一轮恢复（适合压缩很脏的片源）
        Preset(4, "C+A", false, listOf(CLAMP, UPSCALE_DENOISE_M, RESTORE_S)),
        // C+A（HQ）
        Preset(5, "C+A", true, listOf(CLAMP, UPSCALE_DENOISE_VL, RESTORE_M)),
    )

    const val PRESET_COUNT = 6

    /** 旧偏好里的越界编号就地收敛到 0（A+A 快速，最轻）。 */
    fun sanitizePreset(id: Int): Int = if (id in 0 until PRESET_COUNT) id else 0

    fun presetAt(id: Int): Preset = PRESETS[sanitizePreset(id)]

    /**
     * 组装最终 shader 链：基础链 + 可选的两个后处理开关。
     *
     * 开关放在**最后**：Thin / Darken 都是对已完成超分的图做线条处理，
     * 官方模板里它们也是追加在链尾。
     */
    fun buildChain(presetId: Int, thinLines: Boolean, darkenLines: Boolean): List<String> {
        val base = presetAt(presetId).shaders
        if (!thinLines && !darkenLines) return base
        return buildList {
            addAll(base)
            if (thinLines) add(THIN_LINES)
            if (darkenLines) add(DARKEN_LINES)
        }
    }
}
