package com.booknest.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookNestDao {

    // ---------- 书籍 ----------
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, addedAt DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("DELETE FROM highlights WHERE bookId = :id")
    suspend fun deleteHighlightsOfBook(id: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :id")
    suspend fun deleteBookmarksOfBook(id: String)

    @Query("DELETE FROM book_typography WHERE bookId = :id")
    suspend fun deleteTypographyOfBook(id: String)

    // ---------- 目录 ----------
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    fun observeChapters(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun chapters(bookId: String): List<ChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(list: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :id")
    suspend fun deleteChaptersOfBook(id: String)

    @Query("UPDATE books SET lastReadAt = :time WHERE id = :id")
    suspend fun touchBook(id: String, time: Long)

    @Query("UPDATE books SET chapterIndex = :chapter, charOffset = :offset, pageIndex = :page, progress = :progress, lastReadAt = :time WHERE id = :id")
    suspend fun saveProgress(id: String, chapter: Int, offset: Int, page: Int, progress: Float, time: Long)

    @Query("UPDATE books SET groupId = :groupId WHERE id = :id")
    suspend fun moveToShelf(id: String, groupId: String?)

    @Query("UPDATE books SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE books SET chapterCount = :count WHERE id = :id")
    suspend fun setChapterCount(id: String, count: Int)

    // ---------- 高亮 / 笔记 ----------
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex, start")
    fun observeHighlights(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND chapterIndex = :chapter ORDER BY start")
    fun observeChapterHighlights(bookId: String, chapter: Int): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    fun observeAllNotes(): Flow<List<HighlightEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(entity: HighlightEntity): Long

    @Query("UPDATE highlights SET color = :color WHERE id = :id")
    suspend fun updateHighlightColor(id: Long, color: Int)

    @Query("UPDATE highlights SET note = :note WHERE id = :id")
    suspend fun updateHighlightNote(id: Long, note: String)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: Long)

    // ---------- 书签 ----------
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND chapterIndex = :chapter AND charOffset = :offset LIMIT 1")
    suspend fun findBookmark(bookId: String, chapter: Int, offset: Int): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    // ---------- 分组 ----------
    @Query("SELECT * FROM shelves ORDER BY createdAt")
    fun observeShelves(): Flow<List<ShelfEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShelf(entity: ShelfEntity)

    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteShelf(id: String)

    @Query("UPDATE books SET groupId = NULL WHERE groupId = :id")
    suspend fun clearShelfBooks(id: String)

    // ---------- 排版（按书籍独立） ----------
    @Query("SELECT * FROM book_typography WHERE bookId = :bookId")
    fun observeTypography(bookId: String): Flow<TypographyEntity?>

    @Query("SELECT * FROM book_typography WHERE bookId = :bookId")
    suspend fun typography(bookId: String): TypographyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTypography(entity: TypographyEntity)

    // ---------- 阅读统计 ----------
    @Query("SELECT * FROM reading_days ORDER BY date DESC")
    fun observeReadingDays(): Flow<List<ReadingDayEntity>>

    @Query("SELECT seconds FROM reading_days WHERE date = :date")
    suspend fun secondsOn(date: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReadingDay(entity: ReadingDayEntity)

    @Query("DELETE FROM reading_days")
    suspend fun clearReadingDays()
}
