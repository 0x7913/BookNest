package com.booknest.reader.state

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.booknest.reader.core.BookFormat
import com.booknest.reader.data.AppSettings
import com.booknest.reader.data.BookEntity
import com.booknest.reader.data.BookNestRepository
import com.booknest.reader.data.HighlightEntity
import com.booknest.reader.data.PageMode
import com.booknest.reader.data.ReadingDayEntity
import com.booknest.reader.data.ShelfEntity
import com.booknest.reader.data.SettingsStore
import com.booknest.reader.data.SortMode
import com.booknest.reader.data.ThemeId
import com.booknest.reader.data.ViewMode
import com.booknest.reader.format.PublicationLoader
import com.booknest.reader.format.detectFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class Screen { LIBRARY, READER, STATS, SETTINGS, NOTES }

/** 全局导航 / 书库 / 设置 / 统计状态；Room Flow 统一在此收集，UI 只负责渲染。 */
class AppState(
    val repo: BookNestRepository,
    private val context: Context,
    private val scope: CoroutineScope
) {
    var settings by mutableStateOf(AppSettings())
        private set
    var books by mutableStateOf(emptyList<BookEntity>())
        private set
    var shelves by mutableStateOf(emptyList<ShelfEntity>())
        private set
    var readingDays by mutableStateOf(emptyList<ReadingDayEntity>())
        private set
    var allNotes by mutableStateOf(emptyList<HighlightEntity>())
        private set

    var screen by mutableStateOf(Screen.LIBRARY)
    var activeBookId by mutableStateOf<String?>(null)
    var pendingJump by mutableStateOf<Pair<Int, Int>?>(null)
    var isImporting by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var libraryQuery by mutableStateOf("")
    var selectedShelfId by mutableStateOf<String?>(null)
    var favoritesOnly by mutableStateOf(false)
    var noteBookFilter by mutableStateOf<String?>(null)
    var immersive by mutableStateOf(true)
    var volumeKeyHandler by mutableStateOf< (Int) -> Boolean >({ false })
    var fontImportedHandler by mutableStateOf< (String) -> Unit >({})

    init {
        scope.launch { repo.settings.settings.collectLatest { settings = it } }
        scope.launch { repo.books.collectLatest { books = it } }
        scope.launch { repo.shelves.collectLatest { shelves = it } }
        scope.launch { repo.readingDays.collectLatest { readingDays = it } }
        scope.launch { repo.allNotes.collectLatest { allNotes = it } }
    }

    val visibleBooks: List<BookEntity>
        get() {
            val q = libraryQuery.trim()
            var list = books
            if (selectedShelfId != null) list = list.filter { it.groupId == selectedShelfId }
            if (favoritesOnly) list = list.filter { it.favorite }
            if (q.isNotEmpty()) list = list.filter {
                it.title.contains(q, true) || it.author.contains(q, true)
            }
            return when (settings.sortMode) {
                SortMode.RECENT -> list.sortedWith(compareByDescending<BookEntity> { it.lastReadAt }.thenByDescending { it.addedAt })
                SortMode.TITLE -> list.sortedBy { it.title }
                SortMode.IMPORTED -> list.sortedByDescending { it.addedAt }
            }
        }

    fun openBook(book: BookEntity, jump: Pair<Int, Int>? = null) {
        pendingJump = jump
        activeBookId = book.id
        screen = Screen.READER
    }

    fun backToLibrary() {
        screen = Screen.LIBRARY
        activeBookId = null
        pendingJump = null
    }

    fun navigate(screen: Screen) {
        activeBookId = null
        this.screen = screen
    }

    fun showToast(text: String) { toast = text }
    fun clearToast() { toast = null }

    /** SAF 多选导入：整个过程在 IO 线程，UI 只看到导入中状态。 */
    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            isImporting = true
            var success = 0
            val warnings = mutableListOf<String>()
            uris.forEach { uri ->
                runCatching {
                    val id = repo.files.stableId(uri)
                    if (books.any { it.id == id }) return@forEach
                    val imported = repo.files.import(uri)
                    val format = detectFormat(imported.file, imported.displayName)
                    val result = PublicationLoader.parseForImport(imported, format, id, repo.files)
                    val book = BookEntity(
                        id = id,
                        uri = uri.toString(),
                        localPath = imported.file.absolutePath,
                        title = result.parsed.title.ifBlank { imported.displayName.substringBeforeLast('.') },
                        author = result.parsed.author,
                        format = result.parsed.format.name,
                        coverPath = result.parsed.coverPath,
                        fileSize = imported.size,
                        chapterCount = result.chapters.size
                    )
                    repo.dao.insertBook(book)
                    repo.dao.insertChapters(result.chapters)
                    repo.dao.insertTypography(com.booknest.reader.data.TypographyEntity(bookId = id))
                    success++
                    result.warning?.let { warnings.add("${book.title}：$it") }
                }.onFailure { e ->
                    android.util.Log.w("BookNest", "导入失败: $uri", e)
                    warnings.add("${repo.files.displayName(uri)}：${e.message ?: "解析失败"}")
                }
            }
            isImporting = false
            toast = when {
                success > 0 && warnings.isEmpty() -> "已导入 $success 本书"
                success > 0 -> "已导入 $success 本；${warnings.joinToString("；")}"
                else -> "导入失败：${warnings.joinToString("；")}"
            }
        }
    }

    fun importFont(uri: Uri, onDone: (String) -> Unit) {
        scope.launch {
            val path = runCatching { repo.files.importFont(uri) }.getOrNull()
            if (path == null) toast = "字体导入失败" else onDone(path)
        }
    }

    fun createShelf(name: String) {
        scope.launch { if (name.isNotBlank()) repo.createShelf(name.trim()) }
    }

    fun deleteShelf(id: String) {
        scope.launch { repo.removeShelf(id); if (selectedShelfId == id) selectedShelfId = null }
    }

    fun moveBookToShelf(book: BookEntity, shelfId: String?) {
        scope.launch { repo.dao.moveToShelf(book.id, shelfId) }
    }

    fun toggleFavorite(book: BookEntity) {
        scope.launch { repo.dao.setFavorite(book.id, !book.favorite) }
    }

    fun deleteBook(book: BookEntity) {
        scope.launch { repo.removeBook(book); toast = "已移除《${book.title}》" }
    }

    fun clearStats() {
        scope.launch { repo.clearStatistics(); toast = "阅读统计已清零" }
    }

    fun deleteHighlightGlobal(id: Long) {
        scope.launch { repo.dao.deleteHighlight(id) }
    }

    fun addNoteForQuote(bookId: String, entity: HighlightEntity) {
        scope.launch { repo.dao.insertHighlight(entity.copy(bookId = bookId)) }
    }

    fun setting(block: suspend SettingsStore.() -> Unit) {
        scope.launch { block(repo.settings) }
    }

    fun setTheme(theme: ThemeId) = setting { setTheme(theme) }
    fun setPageMode(mode: PageMode) = setting { setPageMode(mode) }
    fun setViewMode(mode: ViewMode) = setting { setViewMode(mode) }
    fun setSortMode(mode: SortMode) = setting { setSortMode(mode) }
    fun setDynamicColor(value: Boolean) = setting { setDynamicColor(value) }
    fun setFollowSystem(value: Boolean) = setting { setFollowSystem(value) }
    fun setSeedColor(value: Int) = setting { setSeedColor(value) }
    fun setVolumeKeys(value: Boolean) = setting { setVolumeKeys(value) }
    fun setKeepScreenOn(value: Boolean) = setting { setKeepScreenOn(value) }
    fun setTtsSpeed(value: Float) = setting { setTtsSpeed(value) }
    fun setDegrade(value: Boolean) = setting { setDegrade(value) }
    fun setParagraphSpacing(value: Float) = setting { setParagraphSpacing(value) }
    fun setFirstLineIndent(value: Boolean) = setting { setFirstLineIndent(value) }
    fun setShowInlineImages(value: Boolean) = setting { setShowInlineImages(value) }
    fun setProgressOnRight(value: Boolean) = setting { setProgressOnRight(value) }
    fun setTurnSensitivity(value: Float) = setting { setTurnSensitivity(value) }
}
