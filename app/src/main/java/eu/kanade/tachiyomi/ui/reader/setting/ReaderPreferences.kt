package eu.kanade.tachiyomi.ui.reader.setting

import android.os.Build
import androidx.compose.ui.graphics.BlendMode
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSet
import tachiyomi.i18n.MR

@Inject
@SingleIn(AppScope::class)
class ReaderPreferences(
    // SY --> 超分移植：官方把 preferenceStore 写成「非属性」构造参数，只能在属性初始化器里访问。
    // 我们新增的 realCuganXxx() 是**方法**，方法体里访问不到非属性参数（会 Unresolved reference），
    // 因此这里加上 private val 提升为属性。对外可见性不变（仅本类可见）。
    private val preferenceStore: PreferenceStore,
    // SY <--
) {

    // region General

    val pageTransitions: Preference<Boolean> = preferenceStore.getBoolean("pref_enable_transitions_key", true)

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt("pref_reader_flash_duration", MILLI_CONVERSION)

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    val doubleTapAnimSpeed: Preference<Int> = preferenceStore.getInt("pref_double_tap_anim_speed", 500)

    val showPageNumber: Preference<Boolean> = preferenceStore.getBoolean("pref_show_page_number_key", true)

    val verticalNavigator: Preference<Set<ReadingMode>> = preferenceStore.getEnumSet(
        "pref_vertical_navigator",
        emptySet(),
    )

    val verticalNavigatorOnLeft: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_vertical_navigator_on_left",
        false,
    )

    val verticalNavigatorHeight: Preference<Int> = preferenceStore.getInt(
        "pref_vertical_navigator_height",
        65,
    )

    val showReadingMode: Preference<Boolean> = preferenceStore.getBoolean("pref_show_reading_mode", true)

    val fullscreen: Preference<Boolean> = preferenceStore.getBoolean("fullscreen", true)

    val drawUnderCutout: Preference<Boolean> = preferenceStore.getBoolean("cutout_short", true)

    val keepScreenOn: Preference<Boolean> = preferenceStore.getBoolean("pref_keep_screen_on_key", false)

    val defaultReadingMode: Preference<Int> = preferenceStore.getInt(
        "pref_default_reading_mode_key",
        ReadingMode.RIGHT_TO_LEFT.flagValue,
    )

    val defaultOrientationType: Preference<Int> = preferenceStore.getInt(
        "pref_default_orientation_type_key",
        ReaderOrientation.FREE.flagValue,
    )

    val webtoonDoubleTapZoomEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_enable_double_tap_zoom_webtoon",
        true,
    )

    val imageScaleType: Preference<Int> = preferenceStore.getInt("pref_image_scale_type_key", 1)

    val zoomStart: Preference<Int> = preferenceStore.getInt("pref_zoom_start_key", 1)

    val readerTheme: Preference<Int> = preferenceStore.getInt("pref_reader_theme_key", 1)

    val alwaysShowChapterTransition: Preference<Boolean> = preferenceStore.getBoolean(
        "always_show_chapter_transition",
        true,
    )

    val cropBorders: Preference<Boolean> = preferenceStore.getBoolean("crop_borders", false)

    val navigateToPan: Preference<Boolean> = preferenceStore.getBoolean("navigate_pan", true)

    val landscapeZoom: Preference<Boolean> = preferenceStore.getBoolean("landscape_zoom", true)

    val cropBordersWebtoon: Preference<Boolean> = preferenceStore.getBoolean("crop_borders_webtoon", false)

    val webtoonSidePadding: Preference<Int> = preferenceStore.getInt("webtoon_side_padding", WEBTOON_PADDING_MIN)

    val readerHideThreshold: Preference<ReaderHideThreshold> = preferenceStore.getEnum(
        "reader_hide_threshold",
        ReaderHideThreshold.LOW,
    )

    val folderPerManga: Preference<Boolean> = preferenceStore.getBoolean("create_folder_per_manga", false)

    val skipRead: Preference<Boolean> = preferenceStore.getBoolean("skip_read", false)

    val skipFiltered: Preference<Boolean> = preferenceStore.getBoolean("skip_filtered", true)

    val skipDupe: Preference<Boolean> = preferenceStore.getBoolean("skip_dupe", false)

    val webtoonDisableZoomOut: Preference<Boolean> = preferenceStore.getBoolean("webtoon_disable_zoom_out", false)

    // endregion

    // region Split two-page spread

    // SY --> 2026-10-09 双页移植（上游 mihon_img_upscale 的 page_layout / invert_double_pages）
    /** 页面布局：0=单页，1=双页，2=自动（横屏双页）。见 [PageLayout]。 */
    val pageLayout: Preference<Int> = preferenceStore.getInt("page_layout", 0)

    /** 双页模式下左右两页互换（阅读方向相反时开）。 */
    val invertDoublePages: Preference<Boolean> = preferenceStore.getBoolean("invert_double_pages", false)
    // SY <--

    val dualPageSplitPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split", false)

    val dualPageInvertPaged: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert", false)

    val dualPageSplitWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_split_webtoon", false)

    val dualPageInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_invert_webtoon", false)

    val dualPageRotateToFit: Preference<Boolean> = preferenceStore.getBoolean("pref_dual_page_rotate", false)

    val dualPageRotateToFitInvert: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert",
        false,
    )

    val dualPageRotateToFitWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_webtoon",
        false,
    )

    val dualPageRotateToFitInvertWebtoon: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_dual_page_rotate_invert_webtoon",
        false,
    )

    val dualPageView: Preference<DualPageView> = preferenceStore.getEnum(
        "pref_dual_page_view",
        DualPageView.NEVER,
    )

    // endregion

    // region Color filter

    val customBrightness: Preference<Boolean> = preferenceStore.getBoolean("pref_custom_brightness_key", false)

    val customBrightnessValue: Preference<Int> = preferenceStore.getInt("custom_brightness_value", 0)

    val colorFilter: Preference<Boolean> = preferenceStore.getBoolean("pref_color_filter_key", false)

    val colorFilterValue: Preference<Int> = preferenceStore.getInt("color_filter_value", 0)

    val colorFilterMode: Preference<Int> = preferenceStore.getInt("color_filter_mode", 0)

    val grayscale: Preference<Boolean> = preferenceStore.getBoolean("pref_grayscale", false)

    val invertedColors: Preference<Boolean> = preferenceStore.getBoolean("pref_inverted_colors", false)

    // endregion

    // region Controls

    val readWithLongTap: Preference<Boolean> = preferenceStore.getBoolean("reader_long_tap", true)

    val readWithVolumeKeys: Preference<Boolean> = preferenceStore.getBoolean("reader_volume_keys", false)

    val readWithVolumeKeysInverted: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_volume_keys_inverted",
        false,
    )

    val navigationModePager: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_pager", 0)

    val navigationModeWebtoon: Preference<Int> = preferenceStore.getInt("reader_navigation_mode_webtoon", 0)

    val pagerNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted",
        TappingInvertMode.NONE,
    )

    val webtoonNavInverted: Preference<TappingInvertMode> = preferenceStore.getEnum(
        "reader_tapping_inverted_webtoon",
        TappingInvertMode.NONE,
    )

    val showNavigationOverlayNewUser: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_new_user",
        true,
    )

    val showNavigationOverlayOnStart: Preference<Boolean> = preferenceStore.getBoolean(
        "reader_navigation_overlay_on_start",
        false,
    )

    // endregion

    // region WebGpu

    val transitionAnimation: Preference<TransitionAnimation> =
        preferenceStore.getEnum("webgpu_transition_animation", TransitionAnimation.BASIC)

    val transitionAnimationDual: Preference<TransitionAnimation> =
        preferenceStore.getEnum("webgpu_dual_transition_animation", TransitionAnimation.BASIC)

    val cutoutMode: Preference<CutoutMode> = preferenceStore.getEnum("webgpu_cutout_mode", CutoutMode.AVOID)

    val cutoutModeDual: Preference<CutoutMode> = preferenceStore.getEnum("webgpu_dual_cutout_mode", CutoutMode.IGNORE)

    val continuousMinWidth: Preference<Int> = preferenceStore.getInt("webgpu_continuous_minwidth", 100)

    val continuousGap: Preference<Int> = preferenceStore.getInt("webgpu_continuous_gap", 10)

    // endregion

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    enum class TappingInvertMode(
        val titleRes: StringResource,
        val shouldInvertHorizontal: Boolean = false,
        val shouldInvertVertical: Boolean = false,
    ) {
        NONE(MR.strings.tapping_inverted_none),
        HORIZONTAL(
            MR.strings.tapping_inverted_horizontal,
            shouldInvertHorizontal = true,
        ),
        VERTICAL(
            MR.strings.tapping_inverted_vertical,
            shouldInvertVertical = true,
        ),
        BOTH(MR.strings.tapping_inverted_both, shouldInvertHorizontal = true, shouldInvertVertical = true),
    }

    enum class ReaderHideThreshold(val threshold: Int) {
        HIGHEST(5),
        HIGH(13),
        LOW(31),
        LOWEST(47),
    }

    enum class TransitionAnimation(val titleRes: StringResource) {
        BASIC(MR.strings.transition_animation_basic),
        FLIP(MR.strings.transition_animation_flip),
        FLIP_LEFT(MR.strings.transition_animation_flip_left),
        FLIP_RIGHT(
            MR.strings.transition_animation_flip_right,
        ),
        STACK_LEFT(MR.strings.transition_animation_stack_left),
        STACK_RIGHT(MR.strings.transition_animation_stack_right),
        STACK_UP(
            MR.strings.transition_animation_stack_up,
        ),
        STACK_DOWN(MR.strings.transition_animation_stack_down),
        SPHERE(MR.strings.transition_animation_sphere),
        CUBE_INSIDE(
            MR.strings.transition_animation_cube_inside,
        ),
        CUBE_OUTSIDE(MR.strings.transition_animation_cube_outside),
        FADE(MR.strings.transition_animation_fade),
        FADE_WHITE(
            MR.strings.transition_animation_fade_white,
        ),
        NONE(MR.strings.transition_animation_none),
    }

    enum class CutoutMode(val titleRes: StringResource) {
        IGNORE(MR.strings.cutout_mode_ignore),
        AVOID(MR.strings.cutout_mode_avoid),
        SHIFT(MR.strings.cutout_mode_shift),
    }

    enum class DualPageView(val titleRes: StringResource) {
        NEVER(MR.strings.dual_page_view_never),
        ALWAYS(MR.strings.dual_page_view_always),
        WIDE(MR.strings.dual_page_view_wide),
    }

    // SY --> 超分（由 mihon_img_upscale 移植）：画面增强相关偏好。
    // **MihonAc 完美版只保留 Anime4K ACNet(6) 一款模型**，其它编号一律由
    // Waifu2x.sanitizeModel 收敛到它。键名沿用旧分支，便于老用户直接继承配置。
    // 「超分引擎」偏好（pref_realcugan_engine）已随 TX 引擎一并删除。

    fun realCuganEnabled() = preferenceStore.getBoolean("pref_realcugan_enabled", false)

    /** 0: No Denoise, 1: Denoise 1x, 2: Denoise 2x, 3: Denoise 3x（仅 UpConv7 有档位） */
    fun realCuganNoiseLevel() = preferenceStore.getInt("pref_realcugan_noise_level", 0)

    /** 输出倍率；三款模型都只支持 2x，实际值由 ImageEnhancementCache.getEffectiveScale 收敛 */
    fun realCuganScale() = preferenceStore.getInt("pref_realcugan_scale", 2)

    /**
     * 本构建唯一的模型：Anime4K ACNet(6)。默认值直接写它；
     * 旧偏好里残留的 4/5 等编号由 Waifu2x.sanitizeModel 在读取处收敛。
     */
    fun realCuganModel() = preferenceStore.getInt("pref_realcugan_model", Waifu2x.MODEL_ANIME4K_ACNET)

    // SY 2026-09-29：本偏好现在**同时**驱动条漫预绑定窗口（WebtoonViewer.preloadWindowPx）。
    // 默认从 3 改为 2：此前它没有消费者、UI 默认高亮 3，而 2026-09-29 A/B 实测 3 屏
    // 「略微超出性能」、定档 2 屏。已手动选过档位的用户保持自己的选择。
    fun realCuganPreloadSize() = preferenceStore.getInt("pref_realcugan_preload_size", 2)

    /** 0: 全速, 1: 50%, 2: 30%（越省电越慢） */
    fun realCuganPerformanceMode() = preferenceStore.getInt("pref_realcugan_performance_mode", 0)

    fun realCuganTileSize() = preferenceStore.getInt("pref_realcugan_tile_size", Waifu2x.DEFAULT_TILE_SIZE)

    /** 0: FP16, 1: FP32, 2: INT8, 3: BF16 */
    fun realCuganPrecision() = preferenceStore.getInt("pref_realcugan_precision", 0)

    // SY --> 「超分引擎」偏好已删除。
    // MihonAc 完美版只保留自研融合引擎（libwaifu2x-jni.so）：ACNet 在 TX 引擎上没有
    // 可用入口（TX 融合管线只有 3 通道版，而 ACNet 是 1 通道 luma 权重，且 TX 是
    // 闭源二进制、加不了符号），只留一款模型就没有保留第二个引擎的理由。
    // 旧的 pref_realcugan_engine 键不再读取。
    // SY <--

    // SY --> 2026-09-23：默认改为 true。
    //
    // SY --> 2026-09-23 定稿：默认回到「关」（= ncnn opt.use_fp16_arithmetic false）。
    //
    // 当天曾把它改成 true 并做一次性补种，为了吃 Immortalis 的 fp16 ALU 双倍吞吐，
    // 实测确实快 1.4~1.5×（同章交错 A/B：44.6 → 31 ms/tile）。但**画质不可接受**：
    // 在这台 GPU 上只要走 FP16（存储或运算任一），整页就会铺满规则噪点场——
    // 白纸撒灰点率 0.28~0.29（FP32 为 0.09，TX 基准 0.0635），
    // 用户报「所有的图都是这样，彩图就是方格形态」。
    // 关 packing_layout / shader_local_memory 都救不回来（0.286 / 0.277）。
    // ⇒ 这份速度收益在 Immortalis-G925 上拿不到，除非移植 TX 的 fp16 融合实现。
    // 注意：引擎侧还有一层 ARM 兜底（waifu2x.cpp 的 arm_precision 默认 1），
    // 即使这个偏好被手动打开，FP32 存储下 use_fp16_arithmetic 也不会生效。
    fun realCuganFp16Arithmetic() = preferenceStore.getBoolean("pref_realcugan_fp16_arithmetic", false)

    // SY --> 2026-09-23：一次性「回滚」（**只回滚我们自己种下去的**）。
    //
    // 当天为提速用过 v1 版本键把该开关补种成 true（F1~F3 诊断包）。既然 FP16
    // 在本机画质不可用、默认已回到 FP32，就该把那个种下去的 true 收回来。
    // 关键点：**先检查 v1 键是否存在**，只有确定是「我们自己改的」才动它 ——
    // 否则会把用户在设置里**主动打开**的选择覆盖掉（在 Adreno 等 GPU 上
    // FP16 运算本来就是正确且有收益的，不该被静默关闭）。
    // v1 键只存在于跑过 F1~F3 诊断包的设备（即本机），正式用户不会有。
    private val fp16ArithmeticSeededV1 =
        preferenceStore.getBoolean("pref_realcugan_fp16_arith_seeded_v1", false)
    private val fp16ArithmeticRolledBack =
        preferenceStore.getBoolean("pref_realcugan_fp16_arith_rolled_back_v2", false)

    init {
        if (!fp16ArithmeticRolledBack.get()) {
            if (fp16ArithmeticSeededV1.get()) {
                realCuganFp16Arithmetic().set(false)
            }
            fp16ArithmeticRolledBack.set(true)
        }
    }

    fun realCuganMaxSizeWidth() = preferenceStore.getInt("pref_realcugan_max_size_width", 1600)

    fun realCuganMaxSizeHeight() = preferenceStore.getInt("pref_realcugan_max_size_height", 1600)

    fun realCuganSkipMaxSizeWidth() = preferenceStore.getInt("pref_realcugan_skip_max_size_width", 0)

    fun realCuganSkipMaxSizeHeight() = preferenceStore.getInt("pref_realcugan_skip_max_size_height", 0)

    fun realCuganShowStatus() = preferenceStore.getBoolean("pref_realcugan_show_status", false)

    /**
     * SY --> 超分让路（2026-09-29 替换原「整话超分」开关）：开启后，触摸 / 滑动 / 开章时
     * 超分全停、把算力让给流畅度（既有默认行为）；关闭后超分**一直全速跑**、从不让路。
     * 所有让路入口（armUiBusyYield / notifyInteraction / 菜单打开时全停）都会实时读这个开关，
     * 改动立即生效，无需重启阅读器。默认开启 = 与本开关引入前的行为完全一致。
     * SY <--
     */
    fun upscaleYieldEnabled() = preferenceStore.getBoolean("pref_upscale_yield_enabled", true)
    // SY <--

    companion object {
        const val WEBTOON_PADDING_MIN = 0
        const val WEBTOON_PADDING_MAX = 25

        const val MILLI_CONVERSION = 100

        val TapZones = listOf(
            MR.strings.label_default,
            MR.strings.l_nav,
            MR.strings.kindlish_nav,
            MR.strings.edge_nav,
            MR.strings.right_and_left_nav,
            MR.strings.disabled_nav,
        )

        val ImageScaleType = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_stretch,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
            MR.strings.scale_type_smart_fit,
        )

        val ImageScaleTypeWebGpuViewer = listOf(
            MR.strings.scale_type_fit_screen,
            MR.strings.scale_type_fit_width,
            MR.strings.scale_type_fit_height,
            MR.strings.scale_type_original_size,
        )

        val ZoomStart = listOf(
            MR.strings.zoom_start_automatic,
            MR.strings.zoom_start_left,
            MR.strings.zoom_start_right,
            MR.strings.zoom_start_center,
        )

        val ColorFilterMode = buildList {
            addAll(
                listOf(
                    MR.strings.label_default to BlendMode.SrcOver,
                    MR.strings.filter_mode_multiply to BlendMode.Modulate,
                    MR.strings.filter_mode_screen to BlendMode.Screen,
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAll(
                    listOf(
                        MR.strings.filter_mode_overlay to BlendMode.Overlay,
                        MR.strings.filter_mode_lighten to BlendMode.Lighten,
                        MR.strings.filter_mode_darken to BlendMode.Darken,
                    ),
                )
            }
        }
    }
}
