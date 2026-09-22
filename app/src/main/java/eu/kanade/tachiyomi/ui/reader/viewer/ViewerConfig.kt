package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.waifu2x.ImageEnhancer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.core.common.preference.Preference

/**
 * Common configuration for all viewers.
 */
abstract class ViewerConfig(readerPreferences: ReaderPreferences, private val scope: CoroutineScope) {

    var imagePropertyChangedListener: (() -> Unit)? = null

    var navigationModeChangedListener: (() -> Unit)? = null

    var tappingInverted = ReaderPreferences.TappingInvertMode.NONE
    var longTapEnabled = true
    var usePageTransitions = false
    var doubleTapAnimDuration = 500
    var volumeKeysEnabled = false
    var volumeKeysInverted = false
    var alwaysShowChapterTransition = true
    var navigationMode = 0
        protected set

    var forceNavigationOverlay = false

    var navigationOverlayOnStart = false

    // SY --> 超分移植：画面增强开关。默认 false 时阅读器的取图路径与原生完全一致，
    // 只有用户显式开启后才改走 Coil + 增强解码（避免给不用的用户引入任何行为变化）。
    var enhanceEnabled = false
    // SY <--

    var dualPageSplit = false
        protected set

    var dualPageInvert = false
        protected set

    var dualPageRotateToFit = false
        protected set

    var dualPageRotateToFitInvert = false
        protected set

    abstract var navigator: ViewerNavigation
        protected set

    init {
        readerPreferences.readWithLongTap
            .register({ longTapEnabled = it })

        readerPreferences.pageTransitions
            .register({ usePageTransitions = it })

        readerPreferences.doubleTapAnimSpeed
            .register({ doubleTapAnimDuration = it })

        readerPreferences.readWithVolumeKeys
            .register({ volumeKeysEnabled = it })

        readerPreferences.readWithVolumeKeysInverted
            .register({ volumeKeysInverted = it })

        readerPreferences.alwaysShowChapterTransition
            .register({ alwaysShowChapterTransition = it })

        forceNavigationOverlay = readerPreferences.showNavigationOverlayNewUser.get()
        if (forceNavigationOverlay) {
            readerPreferences.showNavigationOverlayNewUser.set(false)
        }

        readerPreferences.showNavigationOverlayOnStart
            .register({ navigationOverlayOnStart = it })

        // SY --> 超分移植
        readerPreferences.realCuganEnabled()
            .register({ enhanceEnabled = it })

        // 增强参数一变，缓存键就变了 —— 队列里还没跑完的旧任务算出来也只会写进
        // 另一个键，纯属浪费 GPU。直接清空重排（与上游 PagerConfig/WebtoonConfig 一致）。
        watchEnhancementConfig(readerPreferences)
        // SY <--
    }

    // SY --> 超分移植
    private fun watchEnhancementConfig(readerPreferences: ReaderPreferences) {
        watchForEnhancementInvalidation(readerPreferences.realCuganEnabled())
        watchForEnhancementInvalidation(readerPreferences.realCuganModel())
        watchForEnhancementInvalidation(readerPreferences.realCuganNoiseLevel())
        watchForEnhancementInvalidation(readerPreferences.realCuganScale())
        watchForEnhancementInvalidation(readerPreferences.realCuganMaxSizeWidth())
        watchForEnhancementInvalidation(readerPreferences.realCuganMaxSizeHeight())
        watchForEnhancementInvalidation(readerPreferences.realCuganSkipMaxSizeWidth())
        watchForEnhancementInvalidation(readerPreferences.realCuganSkipMaxSizeHeight())
        watchForEnhancementInvalidation(readerPreferences.realCuganTileSize())
        watchForEnhancementInvalidation(readerPreferences.realCuganPrecision())
        watchForEnhancementInvalidation(readerPreferences.realCuganFp16Arithmetic())
    }

    private fun <T> watchForEnhancementInvalidation(preference: Preference<T>) {
        preference.changes()
            .drop(1)
            .onEach { ImageEnhancer.cancelAll("enhancement config changed") }
            .launchIn(scope)
    }
    // SY <--

    protected abstract fun defaultNavigation(): ViewerNavigation

    abstract fun updateNavigation(navigationMode: Int)

    fun <T> Preference<T>.register(
        valueAssignment: (T) -> Unit,
        onChanged: (T) -> Unit = {},
    ) {
        changes()
            .onEach { valueAssignment(it) }
            .distinctUntilChanged()
            .onEach { onChanged(it) }
            .launchIn(scope)
    }
}
