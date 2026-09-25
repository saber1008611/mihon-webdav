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
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
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
    // SY --> 整话超分：独立开关（不依赖「画面增强」主开关）。开启后进入章节即把整章每一页
    // 后台超分并写入缓存，而不只是用户滑到的那几页。在线源会被逐页联网拉取。
    CheckboxItem(
        label = stringResource(MR.strings.reader_image_enhancement_whole_chapter),
        pref = viewModel.preferences.realCuganWholeChapter(),
    )
    Text(
        text = stringResource(MR.strings.reader_image_enhancement_whole_chapter_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    // SY <--

    if (realCuganEnabled) {
        // SY --> 三款 ncnn 超分模型：4 = sudo UltraCompact，5 = Waifu2x (Fast)/UpConv7，
        // 6 = Anime4K v4 ACNet。权重都随主包内置，所以正常情况三款都可用；
        // 若某一款被模型包覆盖或 assets 被裁剪，芯片上会标「未安装」。
        // 旧偏好里可能残留已移除模型的编号（Real-CUGAN / Real-ESRGAN / W2xEX 等），
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

        // SY --> 本构建只保留 Anime4K ACNet 一款超分模型，因此
        // 「超分引擎」（MM / TX）与「超分模型」（sudo / UpConv7 / ACNet）两行芯片整体删除
        // —— 各自只剩一个选项，摆出来只会让人以为还能选。
        // 随之删除的还有：模型可用性探测（ACNet 权重随主包内置，恒可用）、
        // TX 引擎下的模型改派逻辑、以及只有 UpConv7 才有的去噪档位 UI。
        // 旧偏好里残留的模型编号由 Waifu2x.sanitizeModel 统一收敛到 ACNet。
        // SY <--

        // SY --> Anime4K ACNet 是单通道 luma 网络，画质取向与常规 3 通道模型不同，
        // 给一句常驻说明（本构建只有它一款，不再需要条件判断）。
        Text(
            text = stringResource(MR.strings.reader_model_anime4k_acnet_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // ACNet 没有去噪档位（原 UpConv7 的档位 UI 已删），把旧偏好里残留的档位
        // 收敛到 0，避免它进配置指纹导致缓存白白失效。
        LaunchedEffect(realCuganNoiseLevel) {
            if (realCuganNoiseLevel != 0) {
                viewModel.preferences.realCuganNoiseLevel().set(0)
            }
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

        // SY --> tile / 精度 / fp16 是 ncnn 路线的性能与精度开关，三款模型都吃这几个参数。
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
        // 三款模型共用同一套融合 GPU 管线，而两支 preproc/postproc 着色器把元素
        // 类型写死成 float16_t / float（std430 紧凑排布）；INT8 的 VkMat 是 1 字节、
        // BF16 是 2 字节但位模式不同，都对不上，ncnn 会因此直接关闭融合管线，
        // 于是**三款模型一起掉回 CPU staged 路径**。旧偏好里的 2/3 就地收敛到 FP16。
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

private fun getColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int {
    return (color shl bitShift) or (currentColor and mask.inv().toInt())
}
private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF
