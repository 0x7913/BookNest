package com.booknest.reader.core

import com.booknest.reader.data.BookEntity

enum class BookFormat(val label: String, val extensions: List<String>) {
    EPUB("EPUB", listOf("epub")),
    PDF("PDF", listOf("pdf")),
    TXT("TXT", listOf("txt", "text", "md", "log")),
    MOBI("MOBI", listOf("mobi", "prc")),
    AZW3("AZW3", listOf("azw3", "azw", "kfx")),
    FB2("FB2", listOf("fb2")),
    CBZ("CBZ / 漫画", listOf("cbz", "cbr", "zip")),
    UNKNOWN("未知", emptyList());

    val isText: Boolean get() = this == EPUB || this == TXT || this == MOBI || this == AZW3 || this == FB2
    val isPaged: Boolean get() = this == PDF || this == CBZ

    companion object {
        fun from(fileName: String): BookFormat {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions } ?: UNKNOWN
        }
    }
}

/** 章节元信息：长度是「本章内的字符数」，高亮偏移量以本章为坐标系。 */
data class ChapterMeta(val index: Int, val title: String, val length: Int)

enum class InlineKind { BOLD, ITALIC, HEADING, CENTER, QUOTE, SMALL }

data class InlineSpan(val start: Int, val end: Int, val kind: InlineKind, val scale: Float = 1f)

/**
 * 段落级排版信息（原书 CSS 的语义化投影）。
 * indent = 首行缩进两字符；center = 居中；heading = 标题放大加粗；
 * spaceBefore = 段前额外间距（em 倍数，最终由用户设置叠加）。
 */
data class BlockStyle(
    val start: Int,
    val end: Int,
    val indent: Boolean = false,
    val center: Boolean = false,
    val heading: Boolean = false,
    val spaceBefore: Float = 0f
)

/**
 * 章内插图：text 中 offset 处是一个占位符字符（\uFFFC），
 * bitmap 由 EPUB 资源按需解码并缓存；decorative 表示章首 logo / 底纹等装饰图，需缩小显示。
 */
data class InlineImage(
    val offset: Int,
    val src: String,
    val bitmap: android.graphics.Bitmap? = null,
    val decorative: Boolean = false
)

/** 一章的纯文本 + 原书保留的行内样式 / 段落样式 / 插图（均可被自定义排版覆盖）。 */
data class ChapterContent(
    val text: String,
    val spans: List<InlineSpan> = emptyList(),
    val blocks: List<BlockStyle> = emptyList(),
    val images: List<InlineImage> = emptyList()
)

data class SearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val offset: Int,
    val snippet: String
)

/** 目录项。chapterIndex 为空表示卷/部等分组标题（不可跳转），depth 表示层级。 */
data class TocEntry(
    val title: String,
    val chapterIndex: Int?,
    val depth: Int = 0
)

/** 阅读引擎的统一 Publication 抽象（对齐 Readium 的「资源 / 章节 / 定位符」模型）。 */
sealed interface Publication {
    val bookId: String
    val format: BookFormat
    val chapterTitles: List<String>
    val totalUnits: Int
}

/** 文字类出版物：EPUB / TXT / FB2 / MOBI / AZW3。 */
class TextPublication(
    override val bookId: String,
    override val format: BookFormat,
    override val chapterTitles: List<String>,
    val chapters: List<ChapterMeta>,
    val source: TextSource,
    val toc: List<TocEntry> = emptyList(),
    /** 目录结构升级后，把旧的章节进度映射到新索引；-1 表示沿用书籍自身的进度。 */
    val suggestedChapter: Int = -1
) : Publication {
    override val totalUnits: Int = chapters.sumOf { it.length }
}

/** PDF / CBZ 等按「页」定位的出版物。 */
class PagedPublication(
    override val bookId: String,
    override val format: BookFormat,
    override val chapterTitles: List<String> = emptyList(),
    override val totalUnits: Int,
    val source: PagedSource
) : Publication

/** 章节内容的懒加载源；大文件只在 IO 线程按需读取，不会一次性灌进内存。 */
interface TextSource {
    val chapterCount: Int
    suspend fun chapter(index: Int): ChapterContent
    suspend fun search(query: String, titles: List<String>, limit: Int = 200): List<SearchHit>
}

interface PagedSource {
    val pageCount: Int
    suspend fun render(index: Int, targetWidthPx: Int, night: Boolean, crop: Float): android.graphics.Bitmap?
    fun close()
}

data class ParsedBook(
    val title: String,
    val author: String,
    val coverPath: String?,
    val format: BookFormat,
    val chapters: List<ChapterMeta>,
    val pageCount: Int = 0
)

fun BookEntity.formatOrUnknown(): BookFormat = runCatching { BookFormat.valueOf(format) }.getOrDefault(BookFormat.UNKNOWN)
