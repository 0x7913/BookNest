package com.booknest.reader.core

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import com.booknest.reader.data.TypographyEntity
import java.io.File

data class ResolvedTypography(
    val fontSizePx: Float,
    val fontSizeSp: Float,
    val lineSpacing: Float,
    val horizontalMarginPx: Float,
    val verticalMarginPx: Float,
    val typeface: Typeface,
    val justify: Boolean,
    val paragraphIndent: Boolean,
    val fontScale: Float
)

val FONT_CHOICES = listOf("serif" to "衬线", "sans" to "无衬线", "mono" to "等宽", "custom" to "导入字体")
val WEIGHT_CHOICES = listOf("常规", "中等", "粗体")
val MARGIN_PRESETS = listOf(12f to "窄", 24f to "标准", 40f to "宽")

fun resolveTypography(entity: TypographyEntity, context: Context): ResolvedTypography {
    val fontScale = context.resources.configuration.fontScale
    val density = context.resources.displayMetrics.density
    val base: Typeface = when {
        entity.fontKey == "custom" && entity.customFontPath != null -> {
            runCatching { Typeface.createFromFile(File(entity.customFontPath)) }
                .getOrDefault(Typeface.SERIF)
        }
        entity.fontKey == "sans" -> Typeface.SANS_SERIF
        entity.fontKey == "mono" -> Typeface.MONOSPACE
        else -> Typeface.SERIF
    }
    val weighted = when (entity.weight) {
        1 -> if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, 500, false) else Typeface.create(base, Typeface.BOLD)
        2 -> Typeface.create(base, Typeface.BOLD)
        else -> base
    }
    return ResolvedTypography(
        fontSizePx = entity.fontSize * density * fontScale,
        fontSizeSp = entity.fontSize,
        lineSpacing = entity.lineSpacing,
        horizontalMarginPx = entity.marginDp * density,
        verticalMarginPx = 22f * density,
        typeface = weighted,
        justify = entity.justify,
        paragraphIndent = entity.paragraphIndent,
        fontScale = fontScale
    )
}
