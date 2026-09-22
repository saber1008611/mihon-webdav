package eu.kanade.tachiyomi.data.coil

import coil3.Extras
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import coil3.size.isOriginal
import coil3.size.pxOrElse

internal inline fun Size.widthPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else width.toPx(scale)
}

internal inline fun Size.heightPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else height.toPx(scale)
}

internal fun Dimension.toPx(scale: Scale): Int = pxOrElse {
    when (scale) {
        Scale.FILL -> Int.MIN_VALUE
        Scale.FIT -> Int.MAX_VALUE
    }
}

fun ImageRequest.Builder.cropBorders(enable: Boolean) = apply {
    extras[cropBordersKey] = enable
}

val Options.cropBorders: Boolean
    get() = getExtra(cropBordersKey)

private val cropBordersKey = Extras.Key(default = false)

fun ImageRequest.Builder.customDecoder(enable: Boolean) = apply {
    extras[customDecoderKey] = enable
}

val Options.customDecoder: Boolean
    get() = getExtra(customDecoderKey)

private val customDecoderKey = Extras.Key(default = false)

// SY --> 超分移植：这段官方的 newDecoder 扩展曾被我们的增强扩展覆盖掉，导致
// MangaCoverDialog.kt 报 Unresolved reference 'newDecoder'。移植时务必保留。
fun ImageRequest.Builder.newDecoder(enable: Boolean) = apply {
    extras[newDecoderKey] = enable
}

val Options.newDecoder: Boolean
    get() = getExtra(newDecoderKey)

private val newDecoderKey = Extras.Key(default = false)
// SY <--

fun ImageRequest.Builder.enhanced(enable: Boolean) = apply {
    extras[enhancedKey] = enable
}

val Options.enhanced: Boolean
    get() = getExtra(enhancedKey)

private val enhancedKey = Extras.Key(default = false)

// SY --> mihon_img_upscale 移植：把「用增强缓存」与「现在就跑一次增强」两件事拆开。
//
// 显示请求只带 enhanced=true（缓存命中就直接用，没命中就老实显示原图，绝不阻塞解码，
// 也不凭空烧 GPU）；后台 ImageEnhancer 发出的请求才带 enhanceNow=true，
// 由 ImageDecoder 真正跑一遍模型并把结果落进磁盘缓存。
fun ImageRequest.Builder.enhanceNow(enable: Boolean) = apply {
    extras[enhanceNowKey] = enable
}

val Options.enhanceNow: Boolean
    get() = getExtra(enhanceNowKey)

private val enhanceNowKey = Extras.Key(default = false)
// SY <--

fun ImageRequest.Builder.mangaId(id: Long) = apply {
    extras[mangaIdKey] = id
}

val Options.mangaId: Long
    get() = getExtra(mangaIdKey)

private val mangaIdKey = Extras.Key(default = -1L)

fun ImageRequest.Builder.chapterId(id: Long) = apply {
    extras[chapterIdKey] = id
}

val Options.chapterId: Long
    get() = getExtra(chapterIdKey)

private val chapterIdKey = Extras.Key(default = -1L)

fun ImageRequest.Builder.pageIndex(index: Int) = apply {
    extras[pageIndexKey] = index
}

val Options.pageIndex: Int
    get() = getExtra(pageIndexKey)

private val pageIndexKey = Extras.Key(default = -1)

fun ImageRequest.Builder.pageVariant(variant: String) = apply {
    extras[pageVariantKey] = variant
}

val Options.pageVariant: String
    get() = getExtra(pageVariantKey)

private val pageVariantKey = Extras.Key(default = "")
