package com.booknest.reader.format

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Xml
import com.booknest.reader.core.ChapterContent
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

data class Fb2Book(
    val title: String, val author: String, val cover: Bitmap?,
    val chapters: List<ChapterContent>, val chapterTitles: List<String>
)

/** FB2：XmlPullParser 流式解析 description / body / binary 封面，不引入第三方 XML 库。 */
fun parseFb2(file: File): Fb2Book {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    FileInputStream(file).buffered().use { input ->
        parser.setInput(input, null)
        var title = file.nameWithoutExtension
        val authorParts = mutableListOf<String>()
        var cover: Bitmap? = null
        val chapters = mutableListOf<ChapterContent>()
        val titles = mutableListOf<String>()
        var body = false
        var sectionDepth = 0
        var inParagraph = false
        var inTitle = false
        var inBinary = false
        var inBookTitle = false
        var inAuthorName = false
        var binaryIsImage = false
        val paragraph = StringBuilder()
        val caption = StringBuilder()
        val binary = StringBuilder()
        val chapterText = StringBuilder()
        var currentTitle = "正文"
        var inDescription = true

        fun localName(): String = parser.name?.substringAfter(':')?.lowercase() ?: ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (localName()) {
                    "body" -> { body = true; inDescription = false }
                    "section" -> if (body) sectionDepth++
                    "title" -> {
                        caption.setLength(0)
                        inTitle = true
                        if (body && sectionDepth <= 2 && chapterText.isNotBlank()) {
                            chapters.add(ChapterContent(chapterText.toString().trimEnd()))
                            titles.add(currentTitle)
                            chapterText.setLength(0)
                            currentTitle = ""
                        }
                    }
                    "p" -> if (body) { paragraph.setLength(0); inParagraph = true }
                    "empty-line" -> if (body) chapterText.append('\n')
                    "book-title" -> { inBookTitle = true; caption.setLength(0) }
                    "first-name", "last-name", "middle-name" -> if (inDescription) inAuthorName = true
                    "binary" -> {
                        val type = parser.getAttributeValue(null, "content-type") ?: ""
                        binaryIsImage = type.startsWith("image/")
                        inBinary = binaryIsImage && cover == null
                        binary.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    if (inParagraph) paragraph.append(text)
                    if (inTitle) caption.append(text)
                    if (inBinary) binary.append(text)
                    if (inBookTitle) caption.append(text)
                    if (inAuthorName) authorParts.add(text.trim())
                }
                XmlPullParser.END_TAG -> when (localName()) {
                    "p" -> {
                        if (body) chapterText.append(paragraph.toString().trim()).append("\n\n")
                        inParagraph = false
                    }
                    "title" -> {
                        val text = caption.toString().trim()
                        if (body) currentTitle = text.ifBlank { "第 ${chapters.size + 1} 节" }
                        inTitle = false
                    }
                    "book-title" -> { title = caption.toString().trim().ifBlank { title }; inBookTitle = false }
                    "first-name", "last-name", "middle-name" -> inAuthorName = false
                    "section" -> if (body) sectionDepth = (sectionDepth - 1).coerceAtLeast(0)
                    "binary" -> {
                        if (inBinary && binary.isNotBlank()) {
                            cover = runCatching {
                                val bytes = Base64.decode(binary.toString().replace(Regex("\\s"), ""), Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull()
                        }
                        inBinary = false
                    }
                    "body" -> body = false
                }
            }
            parser.next()
        }
        if (chapterText.isNotBlank()) {
            chapters.add(ChapterContent(chapterText.toString().trimEnd()))
            titles.add(currentTitle)
        }
        if (chapters.isEmpty()) chapters.add(ChapterContent("这本 FB2 没有可解析的正文内容。"))
        if (titles.isEmpty()) titles.add("正文")
        return Fb2Book(title, authorParts.filter { it.isNotEmpty() }.distinct().joinToString(" "), cover, chapters, titles)
    }
}
