package eu.kanade.tachiyomi.util.waifu2x

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/**
 * SY --> mihon_img_upscale 移植：增强配置指纹的唯一来源。
 *
 * 缓存的键是 (mangaId, chapterId, pageIndex, configHash, pageVariant)。
 * **生产者**（ImageDecoder 落盘）与**消费者**（ReaderPageImageView 轮询、
 * ImageEnhancer 去重）必须算出逐字节相同的 hash，否则会出现
 * 「明明增强完了却一直显示原图」这种最难查的问题。所以只留这一处实现。
 * SY <--
 */
fun enhancementConfigHash(preferences: ReaderPreferences): String = ImageEnhancementCache.getConfigHash(
    noise = preferences.realCuganNoiseLevel().get(),
    scale = preferences.realCuganScale().get(),
    model = Waifu2x.sanitizeModel(preferences.realCuganModel().get()),
    maxWidth = preferences.realCuganMaxSizeWidth().get(),
    maxHeight = preferences.realCuganMaxSizeHeight().get(),
    skipMaxWidth = preferences.realCuganSkipMaxSizeWidth().get(),
    skipMaxHeight = preferences.realCuganSkipMaxSizeHeight().get(),
    tileSize = preferences.realCuganTileSize().get(),
    precision = preferences.realCuganPrecision().get(),
    fp16Arithmetic = preferences.realCuganFp16Arithmetic().get(),
    // SY --> Anime4K GLSL 的预设与两个线条开关也决定输出像素，必须进指纹
    // （getConfigHash 内部会按模型折叠：非 GLSL 模型这三个值恒为常量）。
    anime4kPreset = preferences.realCuganAnime4kPreset().get(),
    anime4kThinLines = preferences.realCuganAnime4kThinLines().get(),
    anime4kDarkenLines = preferences.realCuganAnime4kDarkenLines().get(),
    // SY <--
)
