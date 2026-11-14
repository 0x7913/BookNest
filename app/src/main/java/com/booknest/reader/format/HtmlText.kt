package com.booknest.reader.format

import com.booknest.reader.core.BlockStyle
import com.booknest.reader.core.ChapterContent
import com.booknest.reader.core.InlineImage
import com.booknest.reader.core.InlineKind
import com.booknest.reader.core.InlineSpan

private val BLOCK_TAGS = setOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol",
    "tr", "td", "th", "table", "section", "article", "blockquote", "hr",
    "title", "subtitle", "epigraph", "poem", "stanza", "v", "cite", "pre"
)
private val SKIP_TAGS = setOf("script", "style", "head", "svg", "math", "nav")
private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6", "title", "subtitle")
private val TITLE_CLASS = listOf("head", "title", "desc", "introduction", "cover", "logo", "vertitle", "ver")
private val NO_INDENT_TAGS = setOf("blockquote", "pre", "li", "td", "th", "tr", "table", "v", "stanza")
private val ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to "\u00A0", "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "ldquo" to "“",
    "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "middot" to "·", "bull" to "•"
)

private const val IMAGE_PLACEHOLDER = '\uFFFC'

private fun decodeEntities(raw: String): String {
    if ('&' !in raw) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        if (raw[i] == '&') {
            val end = raw.indexOf(';', i + 1)
            if (end > i && end - i <= 12) {
                val key = raw.substring(i + 1, end)
                val decoded = when {
                    ENTITIES.containsKey(key) -> ENTITIES[key]
                    key.startsWith("#x") || key.startsWith("#X") -> runCatching {
                        key.substring(2).toInt(16).toChar().toString()
                    }.getOrNull()
                    key.startsWith("#") -> runCatching { key.substring(1).toInt().toChar().toString() }.getOrNull()
                    else -> null
                }
                if (decoded != null) {
                    out.append(decoded)
                    i = end + 1
                    continue
                }
            }
        }
        out.append(raw[i])
        i++
    }
    return out.toString()
}

private data class Attrs(val cls: String = "", val style: String = "", val src: String = "", val alt: String = "") {
    val center: Boolean get() = style.contains("text-align") &&
        (style.contains("center") || style.contains("centre"))
    val zeroIndent: Boolean get() = style.contains("text-indent") &&
        Regex("""text-indent\s*:\s*0""").containsMatchIn(style)
    val boldStyle: Boolean get() = style.contains("font-weight") &&
        (style.contains("bold") || style.contains("600") || style.contains("700") || style.contains("800"))
    val italicStyle: Boolean get() = style.contains("font-style") && style.contains("italic")
}

private fun parseAttrs(raw: String): Attrs {
    fun value(key: String): String {
        val m = Regex("""$key\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
            .find(raw) ?: return ""
        return m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
    }
    return Attrs(
        cls = value("class").lowercase(),
        style = value("style").lowercase().replace(" ", ""),
        src = value("src"),
        alt = value("alt")
    )
}

/** 一个开始标签映射到「CSS 语义」：是否标题 / 居中 / 首行缩进 / 段前留白。 */
private data class BlockSpec(
    val indent: Boolean,
    val center: Boolean,
    val heading: Boolean,
    val spaceBefore: Float
)

private fun blockSpec(tag: String, a: Attrs): BlockSpec {
    val isHeading = tag in HEADING_TAGS || "head" in a.cls || "title" in a.cls || "desc" in a.cls
    val titled = TITLE_CLASS.any { it in a.cls }
    val centered = a.center || titled || a.cls.contains("center")
    val plainParagraph = tag == "p" || tag == "div" || tag == "section" || tag == "article"
    val indent = plainParagraph && !isHeading && !titled && !a.center && !a.zeroIndent &&
        tag !in NO_INDENT_TAGS
    return BlockSpec(
        indent = indent,
        center = centered,
        heading = isHeading,
        spaceBefore = if (isHeading) 0.7f else if (titled) 0.35f else 0f
    )
}

/** childStart：第一个子块的起点。外层容器的样式只作用于自己的直接文本，不覆盖内部段落。 */
private data class BlockDraft(
    val tag: String, val start: Int, val spec: BlockSpec, var childStart: Int? = null
)
private data class InlineDraft(val tag: String, val kind: InlineKind, val start: Int, val scale: Float)

/**
 * 轻量 XHTML/HTML → 「文本 + 语义样式 + 插图」转换器。
 *
 * 与常见阅读器一致：段落之间只留一个换行（段间距由用户设置控制），段落首行用
 * LeadingMarginSpan 缩进两字符；标题 / 居中 / 章号 / 粗斜体保留为可覆盖的语义样式；
 * <img> 以 \uFFFC 占位并交排版器渲染成真实图片。全部在解析线程完成，不依赖 WebView。
 */
fun htmlToChapterContent(html: String): ChapterContent {
    val out = StringBuilder(html.length / 2)
    val spans = mutableListOf<InlineSpan>()
    val blocks = mutableListOf<BlockStyle>()
    val images = mutableListOf<InlineImage>()
    val openBlocks = ArrayDeque<BlockDraft>()
    val openInline = ArrayDeque<InlineDraft>()
    var skipTag: String? = null
    var i = 0
    var pendingSpace = false

    /** 保证「行尾恰好一个换行」：段落之间不会留出整行空白。 */
    fun breakLine() {
        pendingSpace = false
        if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append('\n')
    }

    fun appendText(s: String) {
        if (s.isEmpty()) return
        val decoded = decodeEntities(s)
        for (c in decoded) {
            when {
                c == '\r' || c == '\t' -> Unit
                c == '\n' -> breakLine()
                c == ' ' || c == '\u00A0' || c == '\u3000' -> {
                    // 行首缩进由排版器统一处理；行内连续空白折叠为一个
                    val atLineStart = out.isEmpty() || out[out.length - 1] == '\n'
                    if (!atLineStart) pendingSpace = true
                }
                else -> {
                    if (pendingSpace && out.isNotEmpty() && out[out.length - 1] != '\n') out.append(' ')
                    pendingSpace = false
                    out.append(c)
                }
            }
        }
    }

    fun closeBlock(tag: String) {
        val idx = openBlocks.indexOfLast { it.tag == tag }
        if (idx < 0) return
        val draft = openBlocks.removeAt(idx)
        breakLine()
        // 外层容器只结算到第一个子块之前，避免它的居中/标题样式压到内部正文段落
        var end = draft.childStart ?: out.length
        while (end > draft.start && out[end - 1] == '\n') end--
        if (end > draft.start) {
            blocks.add(
                BlockStyle(
                    draft.start, end, draft.spec.indent, draft.spec.center,
                    draft.spec.heading, draft.spec.spaceBefore
                )
            )
            if (draft.spec.heading) {
                spans.add(InlineSpan(draft.start, end, InlineKind.HEADING, 1.16f))
            }
        }
    }

    fun closeInline(tag: String) {
        val idx = openInline.indexOfLast { it.tag == tag }
        if (idx < 0) return
        val draft = openInline.removeAt(idx)
        var end = out.length
        while (end > draft.start && out[end - 1] == '\n') end--
        if (end > draft.start) spans.add(InlineSpan(draft.start, end, draft.kind, draft.scale))
    }

    while (i < html.length) {
        val ch = html[i]
        if (ch != '<') {
            val next = html.indexOf('<', i)
            val end = if (next < 0) html.length else next
            if (skipTag == null) appendText(html.substring(i, end))
            i = end
            continue
        }
        if (html.startsWith("<!--", i)) {
            i = html.indexOf("-->", i + 4).let { if (it < 0) html.length else it + 3 }
            continue
        }
        if (html.startsWith("<![CDATA[", i)) {
            val end = html.indexOf("]]>", i + 9).let { if (it < 0) html.length else it }
            if (skipTag == null) appendText(html.substring(i + 9, end))
            i = (end + 3).coerceAtMost(html.length)
            continue
        }
        val close = html.indexOf('>', i + 1)
        if (close < 0) break
        var inside = html.substring(i + 1, close).trim()
        if (inside.startsWith('!') || inside.startsWith('?')) { i = close + 1; continue }
        val isEnd = inside.startsWith('/')
        if (isEnd) inside = inside.drop(1)
        val selfClose = inside.endsWith('/')
        if (selfClose) inside = inside.dropLast(1).trim()
        val tag = inside.substringBefore(' ').substringBefore('\t').lowercase()
        val attrs = parseAttrs(inside)
        i = close + 1

        if (skipTag != null) {
            if (isEnd && tag == skipTag) skipTag = null
            continue
        }
        if (!isEnd && tag in SKIP_TAGS && !selfClose) { skipTag = tag; continue }

        when {
            tag == "img" -> {
                breakLine()
                val start = out.length
                out.append(IMAGE_PLACEHOLDER)
                blocks.add(BlockStyle(start, out.length, indent = false, center = true,
                    heading = false, spaceBefore = 0.2f))
                val hint = "${attrs.cls} ${attrs.alt} ${attrs.src}".lowercase()
                val decorative = listOf("logo", "cover", "back", "banner", "head").any { it in hint }
                images.add(InlineImage(start, attrs.src, null, decorative))
                breakLine()
            }
            tag == "br" || tag == "hr" -> breakLine()
            tag in BLOCK_TAGS -> {
                if (isEnd) {
                    closeBlock(tag)
                } else {
                    breakLine()
                    if (!selfClose) {
                        val parent = openBlocks.lastOrNull()
                        if (parent != null && parent.childStart == null) parent.childStart = out.length
                        openBlocks.addLast(BlockDraft(tag, out.length, blockSpec(tag, attrs)))
                    }
                    // 自闭合块标签只当作一个换行处理
                }
            }
            else -> {
                // 结束标签没有属性，按标签名结算即可（<span class="num"> 的 </span> 也要能闭合）
                if (isEnd) {
                    closeInline(tag)
                } else if (!selfClose) {
                    val kind: InlineKind? = when {
                        tag == "b" || tag == "strong" || tag == "mark" || attrs.boldStyle -> InlineKind.BOLD
                        tag == "em" || tag == "i" || tag == "dfn" || tag == "code" || attrs.italicStyle -> InlineKind.ITALIC
                        "num" in attrs.cls || tag == "small" || tag == "sup" || tag == "sub" -> InlineKind.SMALL
                        else -> null
                    }
                    if (kind != null) {
                        openInline.addLast(InlineDraft(tag, kind, out.length, if (kind == InlineKind.SMALL) 0.78f else 1f))
                    }
                }
            }
        }
    }
    while (openBlocks.isNotEmpty()) closeBlock(openBlocks.last().tag)

    // 去掉首尾空白，并同步平移所有坐标（否则高亮 / 图片偏移会错位）
    var lead = 0
    while (lead < out.length && (out[lead] == '\n' || out[lead] == ' ')) lead++
    var tail = out.length
    while (tail > lead && (out[tail - 1] == '\n' || out[tail - 1] == ' ')) tail--
    val text = if (tail > lead) out.substring(lead, tail) else ""
    fun shift(v: Int) = (v - lead).coerceIn(0, text.length)
    val validSpans = spans.mapNotNull { s ->
        val a = shift(s.start); val b = shift(s.end)
        if (a < b) InlineSpan(a, b, s.kind, s.scale) else null
    }
    val validBlocks = blocks.mapNotNull { b ->
        val a = shift(b.start); val e = shift(b.end)
        if (a < e) b.copy(start = a, end = e) else null
    }
    val validImages = images.mapNotNull { im ->
        val a = shift(im.offset)
        if (a < text.length && text[a] == IMAGE_PLACEHOLDER) im.copy(offset = a) else null
    }
    return ChapterContent(text, validSpans, validBlocks, validImages)
}

/** 供 MOBI / FB2 兜底使用的纯文本清洗。 */
fun plainTextFromHtml(html: String): String = htmlToChapterContent(html).text

private val LENGTH_SKIP = Regex("""<(script|style|head)\b[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
private val LENGTH_TAG = Regex("""<[^>]+>""")

/**
 * 导入时快速估算正文字符数：只做去标签与空白折叠，不构造 span / block / 图片对象。
 * 千章级 EPUB 因此不必逐章跑完整解析器，导入时间可以从数十秒降到数秒。
 */
fun estimateTextLength(html: String): Int {
    val withoutBlocks = LENGTH_SKIP.replace(html, "")
    val withBreaks = LENGTH_TAG.replace(withoutBlocks, "\n")
    val decoded = decodeEntities(withBreaks)
    var count = 0
    var lastWasSpace = true
    for (c in decoded) {
        if (c.isWhitespace()) {
            if (!lastWasSpace) { count++; lastWasSpace = true }
        } else {
            count++
            lastWasSpace = false
        }
    }
    return count
}
