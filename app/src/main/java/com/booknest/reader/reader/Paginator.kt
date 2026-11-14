package com.booknest.reader.reader

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.booknest.reader.core.BlockStyle
import com.booknest.reader.core.ChapterContent
import com.booknest.reader.core.InlineKind
import com.booknest.reader.core.ResolvedTypography
import com.booknest.reader.core.ReaderPalette
import com.booknest.reader.core.highlightColor
import com.booknest.reader.data.HighlightEntity

/** 一页在「整章 StaticLayout」中的坐标切片。重排后只需重新计算切片，标注偏移量不变。 */
data class PageSlice(
    val chapter: Int,
    val start: Int,
    val end: Int,
    val top: Int,
    val bottom: Int,
    val firstLine: Int,
    val lastLine: Int
)

class ChapterLayout(
    val chapter: Int,
    val layout: StaticLayout,
    val pages: List<PageSlice>,
    val highlightRanges: List<HighlightEntity>
) {
    fun offsetAt(x: Float, yInPage: Float, page: PageSlice): Int = runCatching {
        val y = (yInPage + page.top).coerceIn(0f, (layout.height - 1).coerceAtLeast(0).toFloat())
        val line = layout.getLineForVertical(y.toInt()).coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
        layout.getOffsetForHorizontal(line, x)
    }.getOrDefault(page.start)

    fun lineFor(offset: Int): Int = runCatching { layout.getLineForOffset(offset) }.getOrDefault(0)

    fun handlePosition(offset: Int, page: PageSlice): Pair<Float, Float> {
        val line = lineFor(offset)
        val x = runCatching { layout.getPrimaryHorizontal(offset) }.getOrDefault(0f)
        val y = runCatching { layout.getLineBottom(line).toFloat() }.getOrDefault(0f) - page.top
        return x to y
    }
}

private fun blend(foreground: Color, background: Color, alpha: Float): Int {
    fun mix(f: Float, b: Float) = (f * alpha + b * (1f - alpha))
    return Color(
        red = mix(foreground.red, background.red),
        green = mix(foreground.green, background.green),
        blue = mix(foreground.blue, background.blue),
        alpha = 1f
    ).toArgb()
}

/** 段前间距：只在段落首行上方加一点空气，不产生整行空白。 */
private class ParagraphSpacingSpan(private val extra: Int) : LineHeightSpan {
    override fun chooseHeight(
        text: CharSequence, start: Int, end: Int,
        spanstartv: Int, v: Int, fm: Paint.FontMetricsInt
    ) {
        if (spanstartv == v) {
            fm.ascent -= extra
            fm.top -= extra
        }
    }
}

/** 图片解码失败 / 用户关闭插图时的轻量占位，不影响文本偏移。 */
private class ImagePlaceholderSpan(private val color: Int, private val sizePx: Float) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            fm.ascent = -(sizePx * 1.2f).toInt()
            fm.descent = (sizePx * 0.3f).toInt()
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
        return (sizePx * 4.4f).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val p = Paint(paint).apply {
            this.color = color
            textSize = sizePx * 0.72f
            isFakeBoldText = false
        }
        canvas.drawText("〔图片〕", x, y.toFloat(), p)
    }
}

/** TXT / FB2 等无 CSS 的纯文本：按换行切段，套用用户的首行缩进设置。 */
private fun defaultBlocks(text: String, indent: Boolean): List<BlockStyle> {
    val out = mutableListOf<BlockStyle>()
    var start = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            if (i > start) out.add(BlockStyle(start, i, indent = indent))
            start = i + 1
        }
    }
    if (start < text.length) out.add(BlockStyle(start, text.length, indent = indent))
    return out
}

/**
 * 用原生 StaticLayout 做真实分页：行数、断行、两端对齐与绘制使用同一份布局，
 * 因此翻页不会错行；段落缩进 / 标题 / 居中 / 插图 / 高亮 / 搜索命中都以 Span 叠加。
 */
fun buildChapterLayout(
    content: ChapterContent,
    chapter: Int,
    widthPx: Int,
    heightPx: Int,
    typography: ResolvedTypography,
    palette: ReaderPalette,
    highlights: List<HighlightEntity>,
    searchTerm: String? = null,
    paragraphSpacingEm: Float = 0f,
    firstLineIndent: Boolean = true,
    showImages: Boolean = true
): ChapterLayout {
    val text = content.text.ifEmpty { "（本章暂无内容）" }
    val spannable = SpannableString(text)
    val limit = text.length
    val fontSize = typography.fontSizePx
    val paragraphFlags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    // 段落级样式：首行缩进、居中、标题、段前间距
    val blocks = if (content.blocks.isNotEmpty()) content.blocks
    else defaultBlocks(text, typography.paragraphIndent)
    blocks.forEach { b ->
        if (b.start < 0 || b.end > limit || b.start >= b.end) return@forEach
        if (b.indent && firstLineIndent) {
            runCatching {
                spannable.setSpan(
                    LeadingMarginSpan.Standard((fontSize * 2f).toInt(), 0),
                    b.start, b.end, Spanned.SPAN_PARAGRAPH
                )
            }.onFailure {
                spannable.setSpan(
                    LeadingMarginSpan.Standard((fontSize * 2f).toInt(), 0),
                    b.start, b.end, paragraphFlags
                )
            }
        }
        if (b.center) {
            runCatching {
                spannable.setSpan(
                    AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    b.start, b.end, Spanned.SPAN_PARAGRAPH
                )
            }.onFailure {
                spannable.setSpan(
                    AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    b.start, b.end, paragraphFlags
                )
            }
        }
        val extra = ((b.spaceBefore + paragraphSpacingEm) * fontSize).toInt()
        if (extra > 0) {
            runCatching {
                spannable.setSpan(ParagraphSpacingSpan(extra), b.start, b.end, Spanned.SPAN_PARAGRAPH)
            }.onFailure {
                spannable.setSpan(ParagraphSpacingSpan(extra), b.start, b.end, paragraphFlags)
            }
        }
    }

    // 原书语义化行内样式（用户字体 / 字号 / 字重设置优先级更高）
    content.spans.forEach { span ->
        if (span.start < 0 || span.end > limit || span.start >= span.end) return@forEach
        when (span.kind) {
            InlineKind.BOLD -> spannable.setSpan(StyleSpan(Typeface.BOLD), span.start, span.end, paragraphFlags)
            InlineKind.ITALIC -> spannable.setSpan(StyleSpan(Typeface.ITALIC), span.start, span.end, paragraphFlags)
            InlineKind.SMALL -> {
                spannable.setSpan(RelativeSizeSpan(span.scale), span.start, span.end, paragraphFlags)
                spannable.setSpan(
                    ForegroundColorSpan(palette.secondary.toArgb()), span.start, span.end, paragraphFlags
                )
            }
            InlineKind.HEADING -> {
                spannable.setSpan(RelativeSizeSpan(span.scale), span.start, span.end, paragraphFlags)
                spannable.setSpan(StyleSpan(Typeface.BOLD), span.start, span.end, paragraphFlags)
                spannable.setSpan(
                    ForegroundColorSpan(palette.accent.toArgb()), span.start, span.end, paragraphFlags
                )
            }
            InlineKind.CENTER -> spannable.setSpan(
                AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), span.start, span.end, paragraphFlags
            )
            InlineKind.QUOTE -> spannable.setSpan(StyleSpan(Typeface.ITALIC), span.start, span.end, paragraphFlags)
        }
    }

    // 章内插图：同一张图（如每章 logo）只解码一次，按比例缩放避免撑满整屏
    content.images.forEach { im ->
        if (im.offset !in 0 until limit || text[im.offset] != '\uFFFC') return@forEach
        val bitmap = im.bitmap
        val span: Any = if (showImages && bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val maxWidth = widthPx * (if (im.decorative) 0.40f else 0.94f)
            val maxHeight = heightPx * (if (im.decorative) 0.20f else 0.52f)
            var w = minOf(maxWidth, bitmap.width.toFloat())
            var h = w / ratio.coerceAtLeast(0.01f)
            if (h > maxHeight) { h = maxHeight; w = h * ratio }
            val drawable = BitmapDrawable(Resources.getSystem(), bitmap).apply {
                setBounds(0, 0, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            }
            ImageSpan(drawable, im.src, ImageSpan.ALIGN_CENTER)
        } else ImagePlaceholderSpan(palette.secondary.toArgb(), fontSize * 0.7f)
        spannable.setSpan(span, im.offset, im.offset + 1, paragraphFlags)
    }

    // 高亮
    highlights.forEach { h ->
        if (h.start < 0 || h.end > limit || h.start >= h.end) return@forEach
        val tint = blend(highlightColor(h.color), palette.background, if (palette.background.red < 0.5f) 0.42f else 0.48f)
        spannable.setSpan(BackgroundColorSpan(tint), h.start, h.end, paragraphFlags)
    }

    // 书内搜索命中
    if (!searchTerm.isNullOrBlank()) {
        var from = 0
        val hitColor = blend(palette.accent, palette.background, 0.38f)
        while (from < limit) {
            val at = text.indexOf(searchTerm, from, ignoreCase = true)
            if (at < 0) break
            val end = (at + searchTerm.length).coerceAtMost(limit)
            spannable.setSpan(BackgroundColorSpan(hitColor), at, end, paragraphFlags)
            from = end
        }
    }

    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSize
        color = palette.text.toArgb()
        typeface = typography.typeface
        isLinearText = true
    }
    val builder = StaticLayout.Builder.obtain(spannable, 0, limit, paint, widthPx.coerceAtLeast(120))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, typography.lineSpacing)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
    if (Build.VERSION.SDK_INT >= 29 && typography.justify) {
        builder.setJustificationMode(android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
    }
    val layout = builder.build()

    val pageHeight = heightPx.coerceAtLeast(200)
    val pages = mutableListOf<PageSlice>()
    var firstLine = 0
    var pageTop = 0
    for (line in 0 until layout.lineCount) {
        val lineTop = layout.getLineTop(line)
        val lineBottom = layout.getLineBottom(line)
        if (line > firstLine && lineBottom - pageTop > pageHeight) {
            val start = layout.getLineStart(firstLine)
            val end = layout.getLineStart(line)
            pages.add(PageSlice(chapter, start, end, pageTop, lineTop, firstLine, line))
            firstLine = line
            pageTop = lineTop
        }
    }
    if (layout.lineCount == 0) {
        pages.add(PageSlice(chapter, 0, limit, 0, pageHeight, 0, 0))
    } else {
        pages.add(
            PageSlice(
                chapter,
                layout.getLineStart(firstLine),
                limit,
                pageTop,
                layout.height.coerceAtLeast(pageTop + 1),
                firstLine,
                layout.lineCount
            )
        )
    }
    return ChapterLayout(chapter, layout, pages, highlights)
}
