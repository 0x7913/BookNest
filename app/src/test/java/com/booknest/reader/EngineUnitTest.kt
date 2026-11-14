package com.booknest.reader

import com.booknest.reader.core.InlineKind
import com.booknest.reader.format.detectCharset
import com.booknest.reader.format.htmlToChapterContent
import com.booknest.reader.format.prepareTxt
import com.booknest.reader.format.readTxtChapter
import com.booknest.reader.format.splitPlainChapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class EngineUnitTest {

    @Test
    fun `encoding detection covers UTF8 UTF16 and GBK`() {
        val utf8 = "第一章 开始\n中文正文".toByteArray(StandardCharsets.UTF_8)
        assertEquals(StandardCharsets.UTF_8, detectCharset(utf8))

        val gbk = "第一章 开始\n中文正文".toByteArray(Charset.forName("GBK"))
        val detected = detectCharset(gbk)
        assertTrue(detected.name().uppercase().contains("GB") || detected.name().uppercase().contains("UTF"))

        val utf16le = "\uFEFF第一章".toByteArray(Charset.forName("UTF-16LE"))
        assertEquals(Charset.forName("UTF-16LE").name(), detectCharset(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16le).name())
    }

    @Test
    fun `chapter regex splits chinese novels`() {
        val text = buildString {
            append("序章 引子\n")
            repeat(120) { append("这是序章里的一段文字，用来占位。\n") }
            append("第一章 初见\n")
            repeat(120) { append("正文内容一行，讲述发生的事情。\n") }
            append("第二章 别离\n")
            append("结尾一句。\n")
        }
        val (titles, chapters) = splitPlainChapters(text)
        assertTrue(titles.size >= 3)
        assertTrue(titles.any { it.contains("第一章") })
        assertTrue(chapters.all { it.text.isNotBlank() })
    }

    @Test
    fun `large txt is streamed and first chapter reads back correctly`() {
        val temp = File.createTempFile("booknest", ".txt")
        val normalized = File(temp.absolutePath + ".norm.txt")
        try {
            temp.bufferedWriter(Charset.forName("GBK")).use { w ->
                w.write("第一章 开端\n")
                repeat(30000) { w.write("第一段内容，中文文本用于验证流式分章与按需读取是否一致。\n") }
                w.write("第二章 发展\n")
                repeat(50) { w.write("第二段很短。\n") }
            }
            val index = prepareTxt(temp, normalized)
            assertTrue(index.chapters.size >= 2)
            val first = readTxtChapter(normalized, index.chapters[0])
            assertTrue(first.startsWith("第一章 开端"))
            assertTrue(first.length < 22000 + 200)
            // 超长章节会被自动再分段，因此「第二章」位于后续某个分段中
            assertTrue(index.chapters.any { readTxtChapter(normalized, it).contains("第二章 发展") })
            assertTrue(readTxtChapter(normalized, index.chapters.last()).contains("第二段很短"))
        } finally {
            temp.delete(); normalized.delete()
        }
    }

    @Test
    fun `xhtml conversion keeps semantic inline styles`() {
        val html = "<html><head><style>p{color:red}</style></head><body>" +
            "<h1>第一章</h1><p>这是<strong>加粗</strong>与<em>斜体</em>。</p>" +
            "<p>第二&amp;段<br/>换行</p></body></html>"
        val content = htmlToChapterContent(html)
        assertTrue(content.text.contains("第一章"))
        assertTrue(content.text.contains("加粗"))
        assertTrue(content.text.contains("第二&段"))
        assertTrue(!content.text.contains("color:red"))
        assertTrue(content.spans.any { it.start < it.end && it.end <= content.text.length })
    }

    @Test
    fun `paragraphs stay compact and epub images and styles survive`() {
        val html = "<html><head><style>p{margin:1em}</style></head><body>" +
            "<div class=\"logo\"><img alt=\"logo\" class=\"logo\" src=\"../Images/logo.png\"/></div>" +
            "<h3 class=\"head\"><span class=\"num\">第97章</span><br/> 亲手释放的厉鬼</h3>" +
            "<p>杨间疯了么？</p><p>他没有疯。</p><p>他只是在赌。</p></body></html>"
        val content = htmlToChapterContent(html)
        // 段落之间只有单换行，不再出现整行空白
        assertTrue(!content.text.contains("\n\n"))
        assertTrue(content.text.contains("杨间疯了么？"))
        assertTrue(!content.text.contains("margin:1em"))
        // 章首 logo 保留为占位插图并标记为装饰图
        assertEquals(1, content.images.size)
        assertTrue(content.images[0].decorative)
        assertEquals('\uFFFC', content.text[content.images[0].offset])
        // 正文段落首行缩进、标题居中、章号小字
        assertTrue(content.blocks.count { it.indent } >= 3)
        assertTrue(content.blocks.any { it.center })
        assertTrue(content.spans.any { it.kind == InlineKind.HEADING })
        assertTrue(content.spans.any { it.kind == InlineKind.SMALL })
    }
}
