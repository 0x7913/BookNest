package com.booknest.reader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 书库中的一本书。id 由导入时的文件内容/Uri 生成，重装后若 Uri 不变可复用。 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val localPath: String,
    val title: String,
    val author: String = "",
    val format: String = "TXT",
    val coverPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val pageIndex: Int = 0,
    val progress: Float = 0f,
    val groupId: String? = null,
    val favorite: Boolean = false,
    val fileSize: Long = 0L,
    val chapterCount: Int = 0
)

/** 目录与章节长度，导入时解析一次并持久化，冷启动无需重新解析整本书。 */
@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "chapterIndex"],
    indices = [Index("bookId")]
)
data class ChapterEntity(
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val length: Int
)

/** 高亮 / 笔记。按「书籍 + 章节 + 字符偏移」定位，重排后不丢失。 */
@Entity(
    tableName = "highlights",
    indices = [Index("bookId"), Index(value = ["bookId", "chapterIndex"])]
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: String,
    val chapterIndex: Int,
    val start: Int,
    val end: Int,
    val color: Int,
    val quote: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 书签：用户主动标记的位置，与自动记录的进度分离。 */
@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val pageIndex: Int,
    val preview: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** 分组 / 收藏夹。 */
@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** 每本书独立的排版方案。 */
@Entity(tableName = "book_typography")
data class TypographyEntity(
    @PrimaryKey val bookId: String,
    val fontSize: Float = 20f,
    val lineSpacing: Float = 1.75f,
    val marginDp: Float = 24f,
    val fontKey: String = "serif",
    val weight: Int = 0,
    val justify: Boolean = false,
    val customFontPath: String? = null,
    val paragraphIndent: Boolean = true
)

/** 每日阅读时长（本地统计，永不联网）。date = yyyy-MM-dd。 */
@Entity(tableName = "reading_days")
data class ReadingDayEntity(
    @PrimaryKey val date: String,
    val seconds: Long
)
