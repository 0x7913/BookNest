package com.booknest.reader.format

import android.graphics.Bitmap
import com.booknest.reader.core.BookFormat
import com.booknest.reader.core.ChapterMeta
import com.booknest.reader.core.PagedPublication
import com.booknest.reader.core.ParsedBook
import com.booknest.reader.core.Publication
import com.booknest.reader.core.TextPublication
import com.booknest.reader.data.BookEntity
import com.booknest.reader.data.BookFileManager
import com.booknest.reader.data.BookNestRepository
import com.booknest.reader.data.ChapterEntity
import com.booknest.reader.data.ImportedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ImportResult(
    val parsed: ParsedBook,
    val chapters: List<ChapterEntity>,
    val warning: String? = null
)

object PublicationLoader {

    /** 导入时只做一次解析：目录、元数据、封面、章节长度落库，之后打开无需再全量解析。 */
    suspend fun parseForImport(
        imported: ImportedFile,
        format: BookFormat,
        bookId: String,
        files: BookFileManager,
        displayName: String = imported.displayName
    ): ImportResult = withContext(Dispatchers.IO) {
        val file = imported.file
        val fallbackTitle = displayName.substringBeforeLast('.').ifBlank { file.nameWithoutExtension }
        when (format) {
            BookFormat.TXT, BookFormat.UNKNOWN -> {
                val index = prepareTxt(file, normalizedFileFor(file.absolutePath))
                val metas = index.chapters.mapIndexed { i, r ->
                    ChapterMeta(i, r.title, (r.charEnd - r.charStart).coerceAtLeast(0))
                }
                textResult(BookFormat.TXT, fallbackTitle, "", null, metas, bookId, files)
            }
            BookFormat.EPUB -> {
                val index = scanEpub(file)
                textResult(format, index.title, index.author, index.cover,
                    index.refs.mapIndexed { i, r -> ChapterMeta(i, r.title, r.length) }, bookId, files)
            }
            BookFormat.FB2 -> {
                val fb2 = parseFb2(file)
                textResult(format, fb2.title, fb2.author, fb2.cover,
                    fb2.contents(fb2.chapterTitles), bookId, files)
            }
            BookFormat.MOBI, BookFormat.AZW3 -> {
                val mobi = parseMobi(file)
                textResult(format, mobi.title, mobi.author, null,
                    mobi.contents(mobi.chapterTitles), bookId, files, mobi.warning)
            }
            BookFormat.PDF -> {
                val source = PdfSource(file)
                val cover = source.render(0, 420, false, 0f)
                val count = source.pageCount
                source.close()
                pagedResult(format, fallbackTitle, count, cover, bookId, files)
            }
            BookFormat.CBZ -> {
                val source = CbzSource(file)
                val cover = source.render(0, 420, false, 0f)
                val count = source.pageCount
                source.close()
                pagedResult(format, fallbackTitle, count, cover, bookId, files)
            }
        }
    }

    private fun Fb2Book.contents(titles: List<String>) = chapters.mapIndexed { i, c ->
        ChapterMeta(i, titles.getOrElse(i) { "第 ${i + 1} 节" }, c.text.length)
    }

    private fun MobiBook.contents(titles: List<String>) = chapters.mapIndexed { i, c ->
        ChapterMeta(i, titles.getOrElse(i) { "第 ${i + 1} 节" }, c.text.length)
    }

    private fun textResult(
        format: BookFormat, title: String, author: String, cover: Bitmap?,
        metas: List<ChapterMeta>, bookId: String, files: BookFileManager, warning: String? = null
    ): ImportResult {
        val coverPath = cover?.let { files.saveCover(it, bookId) }
        return ImportResult(
            ParsedBook(title, author, coverPath, format, metas),
            metas.map { ChapterEntity(bookId, it.index, it.title, it.length) },
            warning
        )
    }

    private fun pagedResult(
        format: BookFormat, title: String, count: Int, cover: Bitmap?,
        bookId: String, files: BookFileManager
    ): ImportResult {
        val coverPath = cover?.let { files.saveCover(it, bookId) }
        return ImportResult(
            ParsedBook(title, "", coverPath, format, emptyList(), count),
            emptyList()
        )
    }

    /** 打开阅读器：构建对应格式的 Publication，所有重活都在 IO 线程。 */
    suspend fun open(book: BookEntity, repo: BookNestRepository): Publication = withContext(Dispatchers.IO) {
        val file = repo.files.readableFile(book.localPath, book.uri)
        val format = runCatching { BookFormat.valueOf(book.format) }.getOrDefault(BookFormat.TXT)
        val stored = repo.dao.chapters(book.id)
        val titles = stored.map { it.title }
        val lengths = stored.map { it.length }
        when {
            format == BookFormat.PDF || format == BookFormat.CBZ -> {
                val source = if (format == BookFormat.PDF) PdfSource(file) else CbzSource(file)
                PagedPublication(
                    bookId = book.id,
                    format = format,
                    chapterTitles = (0 until source.pageCount).map { "第 ${it + 1} 页" },
                    totalUnits = source.pageCount,
                    source = source
                )
            }
            format == BookFormat.TXT || format == BookFormat.UNKNOWN -> {
                val normalized = normalizedFileFor(file.absolutePath)
                val ranges = if (normalized.isFile) scanTxtRanges(normalized)
                else prepareTxt(file, normalized).chapters
                val chapterTitles = if (stored.isEmpty()) ranges.map { it.title } else titles
                TextPublication(
                    bookId = book.id,
                    format = format,
                    chapterTitles = chapterTitles,
                    chapters = ranges.mapIndexed { i, r ->
                        ChapterMeta(i, chapterTitles.getOrElse(i) { r.title }, lengths.getOrElse(i) { r.charEnd - r.charStart })
                    },
                    source = TxtTextSource(normalized, ranges, chapterTitles)
                )
            }
            format == BookFormat.EPUB -> {
                val originalCount = stored.size
                // 打开时不逐章读正文：只解析目录与书首前置页，长度沿用已入库的精确值
                var index = scanEpub(file, measureContent = false)
                if (originalCount != index.refs.size) {
                    // 旧版目录结构（含封面/内嵌目录页）需要一次性重算并迁移
                    index = scanEpub(file, measureContent = true)
                    repo.dao.deleteChaptersOfBook(book.id)
                    repo.dao.insertChapters(index.refs.mapIndexed { i, r ->
                        ChapterEntity(book.id, i, r.title, r.length)
                    })
                    repo.dao.setChapterCount(book.id, index.refs.size)
                }
                val refs = index.refs
                val chapterTitles = refs.map { it.title }
                val lengths = if (originalCount == refs.size) stored.map { it.length }
                else refs.map { it.length }
                // 用原始 spine 位置把旧的阅读进度映射到过滤后的新索引
                val suggested = if (originalCount == refs.size) book.chapterIndex else {
                    refs.indexOfFirst { it.spineIndex >= book.chapterIndex }
                        .let { if (it < 0) refs.lastIndex else it }
                }
                TextPublication(
                    bookId = book.id,
                    format = format,
                    chapterTitles = chapterTitles,
                    chapters = refs.mapIndexed { i, r ->
                        ChapterMeta(i, r.title, lengths.getOrElse(i) { r.length }.coerceAtLeast(1))
                    },
                    source = EpubTextSource(file, refs),
                    toc = index.toc,
                    suggestedChapter = suggested.coerceIn(0, refs.lastIndex.coerceAtLeast(0))
                )
            }
            format == BookFormat.FB2 -> {
                val fb2 = parseFb2(file)
                memoryPublication(book.id, format, fb2.chapters, fb2.chapterTitles, titles, lengths, stored.size)
            }
            else -> {
                val mobi = parseMobi(file)
                memoryPublication(book.id, format, mobi.chapters, mobi.chapterTitles, titles, lengths, stored.size)
            }
        }
    }

    private fun memoryPublication(
        bookId: String, format: BookFormat, contents: List<com.booknest.reader.core.ChapterContent>,
        parsedTitles: List<String>, storedTitles: List<String>, storedLengths: List<Int>, storedCount: Int
    ): TextPublication {
        val titles = if (storedCount == contents.size) storedTitles else parsedTitles
        return TextPublication(
            bookId = bookId,
            format = format,
            chapterTitles = titles,
            chapters = contents.mapIndexed { i, c ->
                ChapterMeta(i, titles.getOrElse(i) { "第 ${i + 1} 节" }, storedLengths.getOrElse(i) { c.text.length })
            },
            source = MemoryTextSource(contents, titles)
        )
    }
}
