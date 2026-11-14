package com.booknest.reader.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class BookNestRepository(private val context: Context) {

    private val db = AppDatabase.get(context)
    val dao: BookNestDao = db.dao()
    val settings = SettingsStore(context)
    val files = BookFileManager(context)

    val books: Flow<List<BookEntity>> = dao.observeBooks()
    val shelves: Flow<List<ShelfEntity>> = dao.observeShelves()
    val readingDays: Flow<List<ReadingDayEntity>> = dao.observeReadingDays()
    val allNotes: Flow<List<HighlightEntity>> = dao.observeAllNotes()

    fun highlights(bookId: String) = dao.observeHighlights(bookId)
    fun chapterHighlights(bookId: String, chapter: Int) = dao.observeChapterHighlights(bookId, chapter)
    fun bookmarks(bookId: String) = dao.observeBookmarks(bookId)
    fun typography(bookId: String) = dao.observeTypography(bookId)
    fun chapters(bookId: String) = dao.observeChapters(bookId)

    suspend fun typographyNow(bookId: String): TypographyEntity =
        dao.typography(bookId) ?: TypographyEntity(bookId = bookId)

    /** 阅读时长按「自然日」累加；纯本地，无网络。 */
    suspend fun addReadingSeconds(seconds: Long) {
        if (seconds <= 0) return
        val date = LocalDate.now().toString()
        val previous = dao.secondsOn(date) ?: 0L
        dao.insertReadingDay(ReadingDayEntity(date, previous + seconds))
    }

    suspend fun clearStatistics() = dao.clearReadingDays()

    suspend fun createShelf(name: String): ShelfEntity {
        val shelf = ShelfEntity(id = java.util.UUID.randomUUID().toString(), name = name)
        dao.insertShelf(shelf)
        return shelf
    }

    suspend fun removeShelf(id: String) {
        dao.clearShelfBooks(id)
        dao.deleteShelf(id)
    }

    suspend fun removeBook(book: BookEntity) {
        dao.deleteBook(book.id)
        files.deleteBookFiles(book)
        dao.deleteHighlightsOfBook(book.id)
        dao.deleteBookmarksOfBook(book.id)
        dao.deleteTypographyOfBook(book.id)
        dao.deleteChaptersOfBook(book.id)
    }
}
