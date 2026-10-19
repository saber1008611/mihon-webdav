package eu.kanade.presentation.theme.colorscheme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.DynamicScheme
import com.materialkolor.toColorScheme

internal class MonetColorScheme(context: Context) : BaseColorScheme() {

    // SY --> 动态色板构建极贵，必须进程内复用一份（原因见 MonetSchemeCache 注释）。
    private val monet = MonetSchemeCache.get(context)
    // SY <--

    override val darkScheme
        get() = monet.darkScheme

    override val lightScheme
        get() = monet.lightScheme
}

// SY --> 动态取色（Material You）方案缓存。
//
// 2026-09-26 真机取证（`kill -3` 抓主线程栈，卡顿窗口内，可复现）：
//     AssetManager.nativeApplyStyle          ← 解析系统主题，且**持有 AssetManager 锁**
//   ← Resources$Theme.obtainStyledAttributes
//   ← androidx.compose.material3.BadgeKt.dynamicTonalPalette
//   ← MonetSystemColorScheme.<init> ← MonetColorScheme.<init>
//   ← BaseTachiyomiTheme ← TachiyomiTheme
//   ← ReaderProgressIndicator.Content      ← 阅读器**每个页 holder 一个**的加载圈
//   ← androidx.compose.ui.platform.ComposeView
//
// 即：阅读器的加载转圈是独立 ComposeView（每个页 holder 一个，且
// DisposeOnDetachedFromWindowOrReleasedFromPool ⇒ 回池/重挂还会再组合一次），
// 每次组合都新造一张 Material-You 色板（解析系统主题 + 生成整套 CAM16 调色板，数十毫秒）。
// 条漫一屏会创建/复用多个 holder ⇒ 主线程被这种调用连续卡住 ⇒「开卷后最初几轮甩动掉帧」
// （实测该窗口 slowUI>0 而 slowDraw≈0、g50 低 ⇒ 瓶颈在主线程，不在绘制/GPU）。
//
// 色板只取决于系统主题/壁纸 ⇒ 按壁纸 id 缓存，换壁纸自动失效。
internal object MonetSchemeCache {
    @Volatile
    private var cachedKey: Int = Int.MIN_VALUE

    @Volatile
    private var cached: BaseColorScheme? = null

    @Synchronized
    fun get(context: Context): BaseColorScheme {
        val appContext = context.applicationContext
        val key = wallpaperId(appContext)
        cached?.let { if (key == cachedKey) return it }

        val built = build(appContext)
        cached = built
        cachedKey = key
        return built
    }

    private fun wallpaperId(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            }.getOrDefault(Int.MIN_VALUE)
        } else {
            Int.MIN_VALUE
        }
    }

    private fun build(context: Context): BaseColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> MonetSystemColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 -> {
            val seed = runCatching {
                WallpaperManager.getInstance(context)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor
                    ?.toArgb()
            }.getOrNull()
            if (seed != null) MonetCompatColorScheme(Color(seed)) else TachiyomiColorScheme
        }
        else -> TachiyomiColorScheme
    }
}
// SY <--

@RequiresApi(Build.VERSION_CODES.S)
private class MonetSystemColorScheme(context: Context) : BaseColorScheme() {
    // SY --> 懒构建：原本 light/dark 都在构造时就算完，而每次实际只用其中一张 ⇒ 白算一半。
    private val light by lazy { dynamicLightColorScheme(context) }
    private val dark by lazy { dynamicDarkColorScheme(context) }

    override val lightScheme
        get() = light

    override val darkScheme
        get() = dark
    // SY <--
}

internal class MonetCompatColorScheme(seed: Color) : BaseColorScheme() {
    override val lightScheme = generateColorSchemeFromSeed(seed = seed, dark = false)
    override val darkScheme = generateColorSchemeFromSeed(seed = seed, dark = true)

    companion object {
        fun generateColorSchemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
            return DynamicScheme(
                seedColor = seed,
                isDark = dark,
                specVersion = ColorSpec.SpecVersion.SPEC_2025,
                style = PaletteStyle.Expressive,
            )
                .toColorScheme(isAmoled = false)
        }
    }
}
