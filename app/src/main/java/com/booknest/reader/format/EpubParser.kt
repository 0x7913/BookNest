package com.booknest.reader.format

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import com.booknest.reader.core.TocEntry
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipFile

/** 一个可读章节在 EPUB 包内的位置。正文不常驻内存，按需从 zip 读取。 */
data class EpubRef(
    val path: String,
    val title: String,
    val length: Int,
    /** 该章在原始 spine 中的位置，用于把旧版阅读进度映射到过滤后的新目录。 */
    val spineIndex: Int
)

data class EpubIndex(
    val title: String,
    val author: String,
    val cover: Bitmap?,
    val refs: List<EpubRef>,
    val toc: List<TocEntry>
)

private val TAG_REGEX = Regex("""<[^>]+>""")
private fun stripTags(s: String) = TAG_REGEX.replace(decode(s), "").trim()
private fun decode(s: String) = s
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
    .trim()

private fun attr(attributes: String, key: String): String? =
    Regex("""$key\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        .find(attributes)?.groupValues?.get(1)

private fun normalizePath(baseDir: String, href: String): String {
    val decoded = runCatching { URLDecoder.decode(href.substringBefore('#').substringBefore('?'), "UTF-8") }
        .getOrDefault(href.substringBefore('#'))
    val combined = if (decoded.startsWith("/")) decoded else (if (baseDir.isEmpty()) "" else "$baseDir/") + decoded
    val parts = mutableListOf<String>()
    combined.split('/').forEach { seg ->
        when (seg) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(seg)
        }
    }
    return parts.joinToString("/")
}

private data class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String)
private data class NavNode(val label: String, val src: String?, val depth: Int)
private data class NavDraft(var label: String = "", var src: String? = null, val depth: Int)

private fun isDocument(item: ManifestItem): Boolean =
    item.mediaType.contains("xhtml", true) || item.mediaType.contains("html", true) ||
        item.href.endsWith(".html", true) || item.href.endsWith(".htm", true) || item.href.endsWith(".xhtml", true)

/** 封面页（纯 SVG）与内嵌目录页（大量链接、无正文段落）不是阅读内容。 */
private fun looksLikeFrontMatter(html: String, text: String): Boolean {
    val lower = html.lowercase()
    val anchorCount = Regex("""<a\b""", RegexOption.IGNORE_CASE).findAll(html).take(16).count()
    val hasParagraph = Regex("""<p\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)
    if ("<svg" in lower && "<image" in lower && text.length < 200) return true
    if (anchorCount >= 8 && !hasParagraph) return true
    if (text.length < 60 && anchorCount >= 3) return true
    return false
}

private val FRONT_LABEL = Regex(
    """^(目录|目次|封面|扉页|版权|书名|Table\s*of\s*Contents|Contents|Cover|Title\s*Page)""",
    RegexOption.IGNORE_CASE
)

/**
 * 扫描 EPUB 2 / 3：container.xml → OPF → manifest/spine/guide → NCX/nav。
 *
 * measureContent=true 用于导入（逐章求精确字数）；false 用于打开，只读取 OPF / 目录 /
 * 前几页前置页，正文长度沿用数据库值，千章级书籍因此能在瞬间打开且不常驻内存。
 */
fun scanEpub(
    file: File,
    measureContent: Boolean = true,
    knownLengths: List<Int> = emptyList()
): EpubIndex {
    ZipFile(file).use { zip ->
        val containerEntry = zip.entries().asSequence()
            .firstOrNull { it.name.equals("META-INF/container.xml", true) }
            ?: error("不是有效的 EPUB（缺少 container.xml）")
        val container = readEntry(zip, containerEntry.name)
        val opfPath = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(container)?.groupValues?.get(1)
            ?.let { normalizePath("", it) }
            ?: error("EPUB container.xml 缺少 rootfile")
        val opfEntry: String = zip.getEntry(opfPath)?.name
            ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf", true) }?.name
            ?: error("找不到 OPF 文件")
        val opf = readEntry(zip, opfEntry)
        val opfDir = opfEntry.substringBeforeLast('/', "")

        val title = Regex("""<dc:title[^>]*>([\s\S]*?)</dc:title>""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.let { stripTags(it) }?.takeIf { it.isNotEmpty() }
            ?: file.nameWithoutExtension
        val author = Regex("""<dc:creator[^>]*>([\s\S]*?)</dc:creator>""", RegexOption.IGNORE_CASE)
            .find(opf)?.groupValues?.get(1)?.let { stripTags(it) } ?: ""

        val items = mutableMapOf<String, ManifestItem>()
        Regex("""<item\b([^>]*)/?>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val a = m.groupValues[1]
            val id = attr(a, "id") ?: return@forEach
            items[id] = ManifestItem(
                id = id,
                href = attr(a, "href") ?: "",
                mediaType = attr(a, "media-type") ?: "",
                properties = attr(a, "properties") ?: ""
            )
        }
        val spine = Regex("""<itemref\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
            .findAll(opf).mapNotNull { attr(it.groupValues[1], "idref") }.toList()

        // guide 明确标注了封面页与书内目录页
        val frontMatter = mutableSetOf<String>()
        Regex("""<reference\b([^>]*)/?>""", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val a = m.groupValues[1]
            val type = attr(a, "type") ?: ""
            if (type.equals("cover", true) || type.equals("toc", true)) {
                val href = attr(a, "href") ?: ""
                if (href.isNotBlank()) frontMatter.add(normalizePath(opfDir, href))
            }
        }
        val navItem = items.values.firstOrNull { it.properties.contains("nav", true) }
        navItem?.let { frontMatter.add(normalizePath(opfDir, it.href)) }

        // 目录节点（保留 navPoint 层级，父节点可作为卷/部分组标题）
        val navNodes = mutableListOf<NavNode>()
        if (navItem != null) {
            val navPath = normalizePath(opfDir, navItem.href)
            zip.getEntry(navPath)?.let { parseNavXhtml(readEntry(zip, it.name), navNodes) }
        }
        if (navNodes.isEmpty()) {
            val tocId = Regex("""<spine[^>]*toc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(opf)?.groupValues?.get(1)
            val ncxItem = tocId?.let { items[it] } ?: items.values.firstOrNull { it.mediaType.contains("ncx", true) }
            if (ncxItem != null) {
                val ncxPath = normalizePath(opfDir, ncxItem.href)
                zip.getEntry(ncxPath)?.let { parseNcx(readEntry(zip, it.name), navNodes) }
            }
        }
        // 目录开头名为「目录 / 封面 / Contents」的节点本身也是前置页
        navNodes.take(3).forEach { node ->
            if (FRONT_LABEL.containsMatchIn(node.label.trim()) && node.src != null) {
                frontMatter.add(normalizePath(opfDir, node.src!!))
            }
        }
        val firstBodyPath = navNodes.firstOrNull { node ->
            node.src != null && normalizePath(opfDir, node.src!!) !in frontMatter
        }?.src?.let { normalizePath(opfDir, it) }

        val paths = mutableListOf<String>()
        val lengths = mutableListOf<Int>()
        val spineIndices = mutableListOf<Int>()
        var reachedBody = firstBodyPath == null
        spine.forEachIndexed { idx, idref ->
            val item = items[idref] ?: return@forEachIndexed
            if (!isDocument(item)) return@forEachIndexed
            val path = normalizePath(opfDir, item.href)
            val entry = zip.getEntry(path) ?: return@forEachIndexed
            if (path == firstBodyPath) reachedBody = true
            if (path in frontMatter || !reachedBody) return@forEachIndexed

            // 前置页只可能位于书首：两种模式都完整解析前 9 篇，保证过滤结果一致
            val needRead = measureContent || idx <= 8
            val html = if (needRead) readEntry(zip, entry.name) else null
            val frontCheck = html != null && idx <= 8
            val text = if (frontCheck) htmlToChapterContent(html!!).text else null
            if (text != null && looksLikeFrontMatter(html!!, text)) return@forEachIndexed

            // 导入千章级 EPUB 时，其余章节只做轻量字符计数，不必逐章构造完整语义模型
            val length = when {
                text != null -> text.length
                html != null -> estimateTextLength(html).coerceAtLeast(1)
                else -> knownLengths.getOrElse(paths.size) { entry.size.toInt() }.coerceAtLeast(1)
            }
            paths.add(path)
            lengths.add(length)
            spineIndices.add(idx)
        }
        if (paths.isEmpty()) error("EPUB 中没有可阅读的正文")
        val fileToChapter = paths.mapIndexed { i, p -> p to i }.toMap()

        // 指向被过滤前置页的条目丢弃；无链接的父节点保留为卷/部分组标题
        val toc = navNodes.mapNotNull { node ->
            val chapter = node.src?.let { fileToChapter[normalizePath(opfDir, it)] }
            if (node.src != null && chapter == null) null
            else TocEntry(node.label, chapter, node.depth)
        }
        val refs = paths.mapIndexed { i, path ->
            val label = toc.firstOrNull { it.chapterIndex == i }?.title
            EpubRef(path, label ?: "第 ${i + 1} 章", lengths[i], spineIndices[i])
        }

        // 封面：properties="cover-image" > <meta name="cover"> > 首个图片
        val coverId = Regex("""<meta\b([^>]*)/?>""", RegexOption.IGNORE_CASE).findAll(opf)
            .firstOrNull { attr(it.groupValues[1], "name").equals("cover", true) }
            ?.let { attr(it.groupValues[1], "content") }
        val coverItem = items.values.firstOrNull { it.properties.contains("cover-image", true) }
            ?: coverId?.let { items[it] }
            ?: items.values.firstOrNull { it.mediaType.startsWith("image/") && it.href.contains("cover", true) }
        var cover: Bitmap? = null
        if (coverItem != null) {
            val coverPath = normalizePath(opfDir, coverItem.href)
            zip.getEntry(coverPath)?.let { e ->
                cover = zip.getInputStream(e).use { stream -> decodeScaled(stream.readBytes(), 720) }
            }
        }
        return EpubIndex(title, author, cover, refs, toc)
    }
}

private fun readEntry(zip: ZipFile, name: String): String =
    zip.getInputStream(zip.getEntry(name)).bufferedReader(Charsets.UTF_8).use { it.readText() }

private fun decodeScaled(bytes: ByteArray, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun parseNavXhtml(nav: String, out: MutableList<NavNode>) {
    runCatching {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(nav))
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("a", true)) {
                val href = parser.getAttributeValue(null, "href")
                val label = runCatching { parser.nextText() }.getOrDefault("").trim()
                if (label.isNotBlank()) out.add(NavNode(label, href, 0))
                continue
            }
            parser.next()
        }
    }
}

/** 支持 navPoint 嵌套：父节点作为卷/部分组标题，子节点显示层级缩进。 */
private fun parseNcx(ncx: String, out: MutableList<NavNode>) {
    runCatching {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(ncx))
        val stack = ArrayDeque<NavDraft>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "navpoint" -> stack.addLast(NavDraft(depth = stack.size))
                    "text" -> if (stack.isNotEmpty()) {
                        val label = runCatching { parser.nextText() }.getOrDefault("").trim()
                        if (label.isNotEmpty()) stack.last().label = label
                    }
                    "content" -> if (stack.isNotEmpty()) {
                        stack.last().src = parser.getAttributeValue(null, "src")
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("navPoint", true) && stack.isNotEmpty()) {
                    val d = stack.removeLast()
                    if (d.label.isNotBlank()) out.add(NavNode(d.label, d.src, d.depth))
                }
            }
            parser.next()
        }
    }
}
