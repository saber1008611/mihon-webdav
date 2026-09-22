package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.waifu2x.Anime4KPresets
import eu.kanade.tachiyomi.util.waifu2x.Waifu2x
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColumnScope.ColorFilterPage(viewModel: ReaderSettingsViewModel) {
    val customBrightness by viewModel.preferences.customBrightness.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = viewModel.preferences.customBrightness,
    )

    /*
     * Sets the brightness of the screen. Range is [-75, 100].
     * From -75 to -1 a semi-transparent black view is shown at the top with the minimum brightness.
     * From 1 to 100 it sets that value as brightness.
     * 0 sets system brightness and hides the overlay.
     */
    if (customBrightness) {
        val customBrightnessValue by viewModel.preferences.customBrightnessValue.collectAsState()
        SliderItem(
            value = customBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { viewModel.preferences.customBrightnessValue.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val colorFilter by viewModel.preferences.colorFilter.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_color_filter),
        pref = viewModel.preferences.colorFilter,
    )
    if (colorFilter) {
        val colorFilterValue by viewModel.preferences.colorFilterValue.collectAsState()
        SliderItem(
            value = colorFilterValue.red,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_r_value),
            onChange = { newRValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newRValue, RED_MASK, 16)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.green,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_g_value),
            onChange = { newGValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newGValue, GREEN_MASK, 8)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.blue,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_b_value),
            onChange = { newBValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newBValue, BLUE_MASK, 0)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.alpha,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_a_value),
            onChange = { newAValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newAValue, ALPHA_MASK, 24)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        val colorFilterMode by viewModel.preferences.colorFilterMode.collectAsState()
        SettingsChipRow(MR.strings.pref_color_filter_mode) {
            ColorFilterMode.mapIndexed { index, it ->
                FilterChip(
                    selected = colorFilterMode == index,
                    onClick = { viewModel.preferences.colorFilterMode.set(index) },
                    label = { Text(stringResource(it.first)) },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_grayscale),
        pref = viewModel.preferences.grayscale,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_inverted_colors),
        pref = viewModel.preferences.invertedColors,
    )

    // SY --> 超分（从 mihon_img_upscale 移植）：画面增强开关与参数。
    // 开关只在阅读器内生效；关闭时解码链路与上游完全一致（零行为变更）。
    val realCuganEnabled by viewModel.preferences.realCuganEnabled().collectAsState()

    CheckboxItem(
        label = stringResource(MR.strings.reader_image_enhancement),
        checked = realCuganEnabled,
        onClick = {
            viewModel.preferences.realCuganEnabled().set(!realCuganEnabled)
        },
    )
    if (realCuganEnabled) {
        // SY --> mihon-webdav 双引擎版：waifu2x CUnet(7) / Anime4K ACNet(6) / Anime4K GLSL(8)。
        // 其中 6 随主包内置，7/8 由模型包提供（未装则芯片上标「未安装」）。
        // 旧偏好里可能残留已移除模型的编号（Real-CUGAN / Real-ESRGAN / sudo / UpConv7 等），
        // 这里就地收敛，避免出现「没有任何模型被选中」的空状态。
        val storedModel by viewModel.preferences.realCuganModel().collectAsState()
        val realCuganModel = Waifu2x.sanitizeModel(storedModel)
        LaunchedEffect(storedModel) {
            if (storedModel != realCuganModel) {
                viewModel.preferences.realCuganModel().set(realCuganModel)
            }
        }

        val realCuganNoiseLevel by viewModel.preferences.realCuganNoiseLevel().collectAsState()
        val realCuganScale by viewModel.preferences.realCuganScale().collectAsState()

        // 三款模型都只支持 2x，旧偏好里的 3x/4x 残留一并抹平
        LaunchedEffect(realCuganScale) {
            if (realCuganScale != 2) {
                viewModel.preferences.realCuganScale().set(2)
            }
        }

        // SY --> 探测哪些模型当前可用（6 内置恒可用；7/8 看模型包在不在）。
        // 判断会枚举/解压资产，放 IO 线程做，别卡设置页首帧。
        // installedModels == null 表示还在探测，此时先不标「未安装」。
        val context = LocalContext.current
        var installedModels by remember { mutableStateOf<Set<Int>?>(null) }
        LaunchedEffect(Unit) {
            installedModels = withContext(Dispatchers.IO) {
                listOf(
                    Waifu2x.MODEL_WAIFU2X_CUNET,
                    Waifu2x.MODEL_ANIME4K_ACNET,
                    Waifu2x.MODEL_ANIME4K_GLSL,
                ).filter { Waifu2x.isModelAvailable(context, it) }.toSet()
            }
        }
        // SY <--

        SettingsChipRow(MR.strings.reader_model) {
            listOf(
                Waifu2x.MODEL_WAIFU2X_CUNET to stringResource(MR.strings.reader_model_waifu2x_cunet),
                Waifu2x.MODEL_ANIME4K_ACNET to stringResource(MR.strings.reader_model_anime4k_acnet),
                Waifu2x.MODEL_ANIME4K_GLSL to stringResource(MR.strings.reader_model_anime4k_glsl),
            ).map { (modelId, name) ->
                FilterChip(
                    selected = realCuganModel == modelId,
                    onClick = { viewModel.preferences.realCuganModel().set(modelId) },
                    label = {
                        // SY --> 未安装的模型加后缀，避免「选了没反应」被误判成故障。
                        // installedModels == null 表示还在探测，先按原名显示。
                        val missing = installedModels?.contains(modelId) == false
                        Text(
                            if (missing) {
                                "$name · ${stringResource(MR.strings.not_installed)}"
                            } else {
                                name
                            },
                        )
                        // SY <--
                    },
                )
            }
        }

        // SY --> 当前选中模型不在可用集合里时（模型包没装），给一条明确的补救提示。
        if (installedModels?.contains(realCuganModel) == false) {
            Text(
                text = stringResource(MR.strings.reader_model_pack_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // 三款模型各自的一句话说明，帮助用户在「降噪 / 锐利 / 可调」之间选。
        val modelNote = when (realCuganModel) {
            Waifu2x.MODEL_WAIFU2X_CUNET -> MR.strings.reader_model_cunet_note
            Waifu2x.MODEL_ANIME4K_GLSL -> MR.strings.reader_model_glsl_note
            else -> MR.strings.reader_model_anime4k_acnet_note
        }
        Text(
            text = stringResource(modelNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // SY <--

        // SY --> 只有 waifu2x CUnet 有去噪档位；Anime4K ACNet / GLSL 都没有去噪参数，
        // 相关 UI 隐藏，并把残留档位收敛到 0（避免它进配置指纹导致缓存白白失效）。
        if (realCuganModel == Waifu2x.MODEL_WAIFU2X_CUNET) {
            SettingsChipRow(MR.strings.reader_denoise_level) {
                listOf(
                    0 to stringResource(MR.strings.reader_none),
                    1 to "1x",
                    2 to "2x",
                    3 to "3x",
                ).map { (index, name) ->
                    FilterChip(
                        selected = realCuganNoiseLevel == index,
                        onClick = { viewModel.preferences.realCuganNoiseLevel().set(index) },
                        label = { Text(name) },
                    )
                }
            }
        }

        LaunchedEffect(realCuganModel, realCuganNoiseLevel) {
            val validLevels = if (realCuganModel == Waifu2x.MODEL_WAIFU2X_CUNET) 0..3 else 0..0
            if (realCuganNoiseLevel !in validLevels) {
                viewModel.preferences.realCuganNoiseLevel().set(validLevels.first)
            }
        }
        // SY <--

        // SY --> Anime4K GLSL 专属：6 个官方模式预设（A+A / B+B / C+A × 快速/高质量）
        // 与「细化线条 / 线条加黑」两个开关。芯片文案直接用官方模式名 + 档位，
        // 各语言下模式名一致，只有「快速/高质量」需要翻译。
        if (realCuganModel == Waifu2x.MODEL_ANIME4K_GLSL) {
            val storedPreset by viewModel.preferences.realCuganAnime4kPreset().collectAsState()
            val preset = Anime4KPresets.sanitizePreset(storedPreset)
            LaunchedEffect(storedPreset) {
                if (storedPreset != preset) {
                    viewModel.preferences.realCuganAnime4kPreset().set(preset)
                }
            }

            SettingsChipRow(MR.strings.reader_anime4k_preset) {
                Anime4KPresets.PRESETS.map { p ->
                    FilterChip(
                        selected = preset == p.id,
                        onClick = { viewModel.preferences.realCuganAnime4kPreset().set(p.id) },
                        label = { Text(stringResource(presetLabelRes(p.id))) },
                    )
                }
            }

            CheckboxItem(
                label = stringResource(MR.strings.reader_anime4k_thin_lines),
                pref = viewModel.preferences.realCuganAnime4kThinLines(),
            )
            CheckboxItem(
                label = stringResource(MR.strings.reader_anime4k_darken_lines),
                pref = viewModel.preferences.realCuganAnime4kDarkenLines(),
            )
        }
        // SY <--

        SettingsChipRow(MR.strings.reader_scale_factor) {
            FilterChip(
                selected = true,
                onClick = {},
                label = { Text(stringResource(MR.strings.reader_scale_fixed_2x)) },
            )
        }

        SettingsChipRow(MR.strings.reader_preload_pages) {
            val realCuganPreloadSize by viewModel.preferences.realCuganPreloadSize().collectAsState()
            listOf(1, 2, 3, 5, 8).map { size ->
                FilterChip(
                    selected = realCuganPreloadSize == size,
                    onClick = { viewModel.preferences.realCuganPreloadSize().set(size) },
                    label = { Text(stringResource(MR.strings.reader_preload_pages_value, size)) },
                )
            }
        }

        // SY --> tile / 精度 / fp16 算术都只作用于 ncnn 路线（6/7）；
        // GLSL 走整图 FBO，完全不读这几个参数，所以选中 GLSL 时整段隐藏，
        // 免得用户调了没有任何效果。
        if (realCuganModel != Waifu2x.MODEL_ANIME4K_GLSL) {
            SettingsChipRow(MR.strings.reader_gpu_performance_mode) {
                val performanceMode by viewModel.preferences.realCuganPerformanceMode().collectAsState()
                listOf(
                    0 to stringResource(MR.strings.reader_gpu_performance_high),
                    1 to stringResource(MR.strings.reader_gpu_performance_balanced),
                    2 to stringResource(MR.strings.reader_gpu_performance_power_saving),
                ).map { (value, name) ->
                    FilterChip(
                        selected = performanceMode == value,
                        onClick = { viewModel.preferences.realCuganPerformanceMode().set(value) },
                        label = { Text(name) },
                    )
                }
            }

            SettingsChipRow(MR.strings.reader_tile_size) {
                val tileSize by viewModel.preferences.realCuganTileSize().collectAsState()
                listOf(64, 96, 128, 192, 256).map { value ->
                    FilterChip(
                        selected = tileSize == value,
                        onClick = { viewModel.preferences.realCuganTileSize().set(value) },
                        label = { Text(value.toString()) },
                    )
                }
            }

            // SY --> 只保留 FP16 / FP32。
            // 6/7 共用同一套融合 GPU 管线，而两支 preproc/postproc 着色器把元素
            // 类型写死成 float16_t / float（std430 紧凑排布）；INT8 的 VkMat 是 1 字节、
            // BF16 是 2 字节但位模式不同，都对不上，ncnn 会因此直接关闭融合管线，
            // 于是**两款 ncnn 模型一起掉回 CPU staged 路径**。旧偏好里的 2/3 就地收敛到 FP16。
            val storedPrecision by viewModel.preferences.realCuganPrecision().collectAsState()
            val precision = Waifu2x.sanitizePrecision(storedPrecision)
            LaunchedEffect(storedPrecision) {
                if (storedPrecision != precision) {
                    viewModel.preferences.realCuganPrecision().set(precision)
                }
            }
            SettingsChipRow(MR.strings.reader_precision) {
                listOf(
                    Waifu2x.PRECISION_FP16 to stringResource(MR.strings.reader_precision_fp16),
                    Waifu2x.PRECISION_FP32 to stringResource(MR.strings.reader_precision_fp32),
                ).map { (value, name) ->
                    FilterChip(
                        selected = precision == value,
                        onClick = { viewModel.preferences.realCuganPrecision().set(value) },
                        label = { Text(name) },
                    )
                }
            }
            Text(
                text = stringResource(MR.strings.reader_precision_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (precision == Waifu2x.PRECISION_FP16) {
                CheckboxItem(
                    label = stringResource(MR.strings.reader_fp16_arithmetic),
                    pref = viewModel.preferences.realCuganFp16Arithmetic(),
                )
            }
        }
        // SY <--

        val processMaxWidth by viewModel.preferences.realCuganMaxSizeWidth().collectAsState()
        val processMaxHeight by viewModel.preferences.realCuganMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_processing_resolution),
            width = processMaxWidth,
            height = processMaxHeight,
            onWidthChange = { viewModel.preferences.realCuganMaxSizeWidth().set(it) },
            onHeightChange = { viewModel.preferences.realCuganMaxSizeHeight().set(it) },
        )

        val skipMaxWidth by viewModel.preferences.realCuganSkipMaxSizeWidth().collectAsState()
        val skipMaxHeight by viewModel.preferences.realCuganSkipMaxSizeHeight().collectAsState()
        ResolutionLimitFields(
            heading = stringResource(MR.strings.reader_max_resolution),
            width = skipMaxWidth,
            height = skipMaxHeight,
            onWidthChange = { viewModel.preferences.realCuganSkipMaxSizeWidth().set(it) },
            onHeightChange = { viewModel.preferences.realCuganSkipMaxSizeHeight().set(it) },
        )

        CheckboxItem(
            label = stringResource(MR.strings.reader_show_processing_status),
            pref = viewModel.preferences.realCuganShowStatus(),
        )
    }
    // SY <--
}

// SY --> 超分：分辨率上限的两个数字输入框（0 表示不限制）。
@Composable
private fun ResolutionLimitFields(
    heading: String,
    width: Int,
    height: Int,
    onWidthChange: (Int) -> Unit,
    onHeightChange: (Int) -> Unit,
) {
    Column {
        HeadingItem(heading)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ResolutionNumberField(
                modifier = Modifier.weight(1f),
                value = width,
                onValueChange = onWidthChange,
                label = stringResource(MR.strings.reader_resolution_width),
            )
            ResolutionNumberField(
                modifier = Modifier.weight(1f),
                value = height,
                onValueChange = onHeightChange,
                label = stringResource(MR.strings.reader_resolution_height),
            )
        }
    }
}

@Composable
private fun ResolutionNumberField(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value.toResolutionText()) }

    LaunchedEffect(value) {
        val normalized = value.toResolutionText()
        if (text != normalized && (text.toIntOrNull() ?: 0) != value) {
            text = normalized
        }
    }

    OutlinedTextField(
        modifier = modifier,
        value = text,
        onValueChange = { raw ->
            val filtered = raw.filter(Char::isDigit)
            text = filtered
            onValueChange(filtered.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
    )
}

private fun Int.toResolutionText(): String = if (this == 0) "" else toString()
// SY <--

// SY --> Anime4K 六预设的展示文案资源。编号与 [Anime4KPresets.PRESETS] 的 id 一致：
// 0/1 = A+A 快速/高质量，2/3 = B+B，4/5 = C+A。模式名（A+A 等）各语言一致，
// 只有「快速 / 高质量」需要翻译，所以六个文案分开列，便于各语言独立微调。
private fun presetLabelRes(id: Int): StringResource = when (id) {
    0 -> MR.strings.reader_anime4k_preset_0
    1 -> MR.strings.reader_anime4k_preset_1
    2 -> MR.strings.reader_anime4k_preset_2
    3 -> MR.strings.reader_anime4k_preset_3
    4 -> MR.strings.reader_anime4k_preset_4
    else -> MR.strings.reader_anime4k_preset_5
}
// SY <--

private fun getColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int {
    return (color shl bitShift) or (currentColor and mask.inv().toInt())
}
private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF
