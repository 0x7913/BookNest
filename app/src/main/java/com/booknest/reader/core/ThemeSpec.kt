package com.booknest.reader.core

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.booknest.reader.data.AppSettings
import com.booknest.reader.data.ThemeId
import kotlin.math.pow

/** 阅读界面的「纸感」调色板，与应用外壳的 M3 动态取色相互独立。 */
data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val surface: Color,
    val onSurface: Color,
    val accent: Color,
    val pageEdge: Color
)

fun ThemeId.readerPalette(): ReaderPalette = when (this) {
    ThemeId.DAY_CREAM -> ReaderPalette(
        background = Color(0xFFF7F2E7), text = Color(0xFF2C2822), secondary = Color(0xFF7A7163),
        surface = Color(0xFFFFFCF4), onSurface = Color(0xFF2C2822), accent = Color(0xFF8A6A4F),
        pageEdge = Color(0xFFE6DDCB)
    )
    ThemeId.DAY_GRAY -> ReaderPalette(
        background = Color(0xFFEFF0F1), text = Color(0xFF23262A), secondary = Color(0xFF6B7076),
        surface = Color(0xFFFAFBFB), onSurface = Color(0xFF23262A), accent = Color(0xFF52677A),
        pageEdge = Color(0xFFDADDE0)
    )
    ThemeId.DAY_WHITE -> ReaderPalette(
        background = Color(0xFFFFFFFF), text = Color(0xFF1B1B1B), secondary = Color(0xFF737373),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1B1B1B), accent = Color(0xFF3C6E63),
        pageEdge = Color(0xFFECECEC)
    )
    ThemeId.NIGHT_GRAY -> ReaderPalette(
        background = Color(0xFF1E2023), text = Color(0xFFD6D8DB), secondary = Color(0xFF9298A0),
        surface = Color(0xFF282B2F), onSurface = Color(0xFFE2E4E7), accent = Color(0xFF9EB6CE),
        pageEdge = Color(0xFF303439)
    )
    ThemeId.NIGHT_BLACK -> ReaderPalette(
        background = Color(0xFF000000), text = Color(0xFFC9CDD2), secondary = Color(0xFF7C838B),
        surface = Color(0xFF0B0B0B), onSurface = Color(0xFFDDE0E4), accent = Color(0xFF8FA9C4),
        pageEdge = Color(0xFF141414)
    )
    ThemeId.NIGHT_SEPIA -> ReaderPalette(
        background = Color(0xFF29231D), text = Color(0xFFDCC9AC), secondary = Color(0xFFA69177),
        surface = Color(0xFF332B23), onSurface = Color(0xFFE6D5B9), accent = Color(0xFFC9A46F),
        pageEdge = Color(0xFF3C3128)
    )
}

/** WCAG 相对亮度。 */
private fun relativeLuminance(c: Color): Double {
    fun channel(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

fun contrastRatio(a: Color, b: Color): Double {
    val l1 = relativeLuminance(a)
    val l2 = relativeLuminance(b)
    val hi = maxOf(l1, l2)
    val lo = minOf(l1, l2)
    return (hi + 0.05) / (lo + 0.05)
}

/** 深色模式下正文对比度兜底：不足 4.5:1 时自动向纯白/纯黑逼近，直至达标（WCAG AA）。 */
fun ensureContrast(foreground: Color, background: Color, minimum: Double = 4.5): Color {
    if (contrastRatio(foreground, background) >= minimum) return foreground
    val towards = if (relativeLuminance(background) > 0.18) Color.Black else Color.White
    for (step in 1..20) {
        val t = step / 20f
        val candidate = Color(
            red = foreground.red + (towards.red - foreground.red) * t,
            green = foreground.green + (towards.green - foreground.green) * t,
            blue = foreground.blue + (towards.blue - foreground.blue) * t,
            alpha = 1f
        )
        if (contrastRatio(candidate, background) >= minimum) return candidate
    }
    return towards
}

private fun tint(c: Color, amount: Float): Color = Color(
    red = (c.red + (1f - c.red) * amount).coerceIn(0f, 1f),
    green = (c.green + (1f - c.green) * amount).coerceIn(0f, 1f),
    blue = (c.blue + (1f - c.blue) * amount).coerceIn(0f, 1f),
    alpha = 1f
)

private fun shade(c: Color, amount: Float): Color = Color(
    red = (c.red * (1f - amount)).coerceIn(0f, 1f),
    green = (c.green * (1f - amount)).coerceIn(0f, 1f),
    blue = (c.blue * (1f - amount)).coerceIn(0f, 1f),
    alpha = 1f
)

/** 外壳配色：Android 12+ 跟随壁纸动态取色；低版本使用手动种子色。 */
fun appColorScheme(settings: AppSettings, systemDark: Boolean, context: Context): ColorScheme {
    val night = if (settings.followSystemDark) systemDark else settings.themeId.isNight
    val seed = Color(settings.seedColor)
    if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return if (night) {
        darkColorScheme(
            primary = tint(seed, .35f), onPrimary = Color(0xFF1A120C),
            primaryContainer = shade(seed, .55f), onPrimaryContainer = tint(seed, .75f),
            secondary = tint(seed, .2f), tertiary = tint(seed, .5f),
            background = Color(0xFF141313), surface = Color(0xFF1C1B1A),
            surfaceContainer = Color(0xFF242220), onSurface = Color(0xFFEDE7E0),
            onSurfaceVariant = Color(0xFFB8AFA6)
        )
    } else {
        lightColorScheme(
            primary = seed, onPrimary = Color(0xFFFFFFFF),
            primaryContainer = tint(seed, .78f), onPrimaryContainer = shade(seed, .65f),
            secondary = shade(seed, .15f), tertiary = tint(seed, .35f),
            background = Color(0xFFFDFAF6), surface = Color(0xFFFFFDFA),
            surfaceContainer = Color(0xFFF6F1EA), onSurface = Color(0xFF26221E),
            onSurfaceVariant = Color(0xFF6E655C)
        )
    }
}

/** 6 种高亮颜色（与浮动菜单顺序一致）。 */
val HIGHLIGHT_COLORS = listOf(
    Color(0xFFFFE27A) to "琥珀黄",
    Color(0xFFA8DEB0) to "薄荷绿",
    Color(0xFFA6CFF0) to "晴空蓝",
    Color(0xFFF5B4CE) to "樱花粉",
    Color(0xFFCDB4EC) to "丁香紫",
    Color(0xFFFFC58A) to "暖阳橙"
)

fun highlightColor(index: Int): Color = HIGHLIGHT_COLORS[index.coerceIn(0, 5)].first
