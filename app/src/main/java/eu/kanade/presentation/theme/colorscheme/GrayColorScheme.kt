package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 灰色主题
 *
 * 专为「灰底 + 浅色内容块」这套界面结构设计：
 *  - [androidx.compose.material3.ColorScheme.surfaceContainer] 一族 = 灰 `#D8D8D8`，
 *    顶栏 / 侧边栏 / 底部导航栏都取它 → 三块灰连成一片（平板是 L 型）
 *  - [androidx.compose.material3.ColorScheme.surface] = 浅色 `#FBFBFB`，是内容块
 *  - 侧栏未选中方片取 surfaceContainerHighest `#E9E9E9`，选中取 secondaryContainer `#C4C4C4`
 */
internal object GrayColorScheme : BaseColorScheme() {

    override val darkScheme = darkColorScheme(
        primary = Color(0xFFC4C4C4),
        onPrimary = Color(0xFF1B1B1B),
        primaryContainer = Color(0xFF3A3A3C),
        onPrimaryContainer = Color(0xFFE9E9E9),
        inversePrimary = Color(0xFF4A4A4C),
        secondary = Color(0xFFC4C4C4),
        onSecondary = Color(0xFF1B1B1B),
        secondaryContainer = Color(0xFF4A4A4C),
        onSecondaryContainer = Color(0xFFF2F2F2),
        tertiary = Color(0xFFB4B4B8),
        onTertiary = Color(0xFF1B1B1B),
        tertiaryContainer = Color(0xFF3A3A3C),
        onTertiaryContainer = Color(0xFFE9E9E9),
        background = Color(0xFF2C2C2E),
        onBackground = Color(0xFFE9E9E9),
        surface = Color(0xFF1C1C1E),
        onSurface = Color(0xFFE9E9E9),
        surfaceVariant = Color(0xFF2C2C2E),
        onSurfaceVariant = Color(0xFFC5C6D0),
        surfaceTint = Color(0xFFC4C4C4),
        inverseSurface = Color(0xFFE9E9E9),
        inverseOnSurface = Color(0xFF1B1B1F),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8F9099),
        outlineVariant = Color(0xFF46464A),
        surfaceContainerLowest = Color(0xFF161618),
        surfaceContainerLow = Color(0xFF242426),
        surfaceContainer = Color(0xFF2C2C2E),
        surfaceContainerHigh = Color(0xFF333335),
        surfaceContainerHighest = Color(0xFF3A3A3C),
    )

    override val lightScheme = lightColorScheme(
        primary = Color(0xFF2C2C2E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE9E9E9),
        onPrimaryContainer = Color(0xFF1B1B1B),
        inversePrimary = Color(0xFFC4C4C4),
        secondary = Color(0xFF5A5A5E),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFC4C4C4),
        onSecondaryContainer = Color(0xFF000000),
        tertiary = Color(0xFF6E6E73),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD8D8D8),
        onTertiaryContainer = Color(0xFF1B1B1B),
        background = Color(0xFFD8D8D8),
        onBackground = Color(0xFF1B1B1B),
        surface = Color(0xFFFBFBFB),
        onSurface = Color(0xFF1B1B1B),
        surfaceVariant = Color(0xFFD8D8D8),
        onSurfaceVariant = Color(0xFF4A4A4A),
        surfaceTint = Color(0xFF2C2C2E),
        inverseSurface = Color(0xFF1B1B1F),
        inverseOnSurface = Color(0xFFF2F2F2),
        error = Color(0xFFA32D2D),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF9A9AA0),
        outlineVariant = Color(0xFFC6C6CC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF5F5F7),
        surfaceContainer = Color(0xFFD8D8D8),
        surfaceContainerHigh = Color(0xFFD2D2D4),
        surfaceContainerHighest = Color(0xFFE9E9E9),
    )
}
