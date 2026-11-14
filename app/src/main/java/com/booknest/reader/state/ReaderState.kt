package com.booknest.reader.state

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.booknest.reader.core.ChapterContent
import com.booknest.reader.core.PagedPublication
import com.booknest.reader.core.Publication
import com.booknest.reader.core.ReaderPalette
import com.booknest.reader.core.SearchHit
import com.booknest.reader.core.TextPublication
import com.booknest.reader.data.AppSettings
import com.booknest.reader.data.BookEntity
import com.booknest.reader.data.BookNestRepository
import com.booknest.reader.data.BookmarkEntity
import com.booknest.reader.data.HighlightEntity
import com.booknest.reader.data.TypographyEntity
import com.booknest.reader.core.readerPalette
import com.booknest.reader.core.resolveTypography
import com.booknest.reader.format.EpubTextSource
import com.booknest.reader.format.PublicationLoader
import com.booknest.reader.reader.ChapterLayout
import com.booknest.reader.reader.PageSlice
import com.booknest.reader.reader.buildChapterLayout
import com.booknest.reader.core.TocEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderStatus { LOADING, READY, ERROR }
enum class ReaderSheet { TOC, SEARCH, NOTES, BOOKMARKS, THEME, SETTINGS, PDF_TOOLS, MORE }

data class Selection(
    val chapter: Int,
    val page: PageSlice,
    val start: Int,
    val end: Int,
    val anchorX: Float,
    val anchorY: Float
)

class ReaderState(
    val book: BookEntity,
    private val repo: BookNestRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    settings: AppSettings
) {
    var status by mutableStateOf(ReaderStatus.LOADING)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var publication by mutableStateOf<Publication?>(null)
        private set
    var chapterIndex by mutableStateOf(book.chapterIndex)
        private set
    var pageIndex by mutableStateOf(0)
        private set
    var pagedIndex by mutableStateOf(book.pageIndex)
        private set
    var chapterContent by mutableStateOf(ChapterContent(""))
        private set
    var layout by mutableStateOf<ChapterLayout?>(null)
        private set
    var typography by mutableStateOf(TypographyEntity(book.id))
        private set
    var highlights by mutableStateOf(emptyList<HighlightEntity>())
        private set
    var bookmarks by mutableStateOf(emptyList<BookmarkEntity>())
        private set
    var palette: ReaderPalette by mutableStateOf(settings.themeId.readerPalette())
    var night: Boolean by mutableStateOf(settings.themeId.isNight)

    var toolbarVisible by mutableStateOf(false)
    var sheet by mutableStateOf<ReaderSheet?>(null)
    var selection by mutableStateOf<Selection?>(null)
    var noteTarget by mutableStateOf<HighlightEntity?>(null)
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf(emptyList<SearchHit>())
    var activeSearchTerm by mutableStateOf<String?>(null)
    var searchBusy by mutableStateOf(false)
    var pdfBitmap by mutableStateOf<Bitmap?>(null)
    var pdfZoom by mutableStateOf(1f)
    var pdfCrop by mutableStateOf(0f)
    var showProgressBubble by mutableStateOf(false)

    /** 最近一次翻页方向（1 下一页 / -1 上一页），供覆盖/仿真动画使用。 */
    var turnDirection by mutableStateOf(1)
        private set
    /** 翻页序号：每次翻页自增，保证相同页码的连续点击也能触发过渡动画。 */
    var turnSerial by mutableStateOf(0)
        private set

    /**
     * 当前翻页模式注册的动画播放器（back = true 表示往回翻）。
     * 点击/音量键统一走 requestTurn：仿真、覆盖模式能播放跟手动画，
     * 滑动、滚动模式未注册时直接改页码，由分页器/列表响应。
     */
    var turnAnimator: ((Boolean) -> Unit)? = null

    fun requestTurn(back: Boolean) {
        val animator = turnAnimator
        if (animator != null) animator(back) else if (back) previousPage() else nextPage()
    }
    /** 跨章切换中：UI 在章节边界页据此显示加载态，并阻止手势写回旧章页码。 */
    var chapterLoading by mutableStateOf(false)
        private set
    /** 每次成功切章自增，供分页器/列表把滚动位置重置到新章开头。 */
    var chapterSerial by mutableStateOf(0)
        private set

    private var viewWidth = 0
    private var viewHeight = 0
    private var restoreOffset: Int? = book.charOffset.takeIf { it > 0 }
    private var chapterJob: Job? = null
    private var layoutJob: Job? = null
    private var prefetchJob: Job? = null
    private var pendingChapterIndex: Int? = null
    private var chapterToken = 0L
    private var layoutToken = 0L
    private var prefs: AppSettings = settings

    /**
     * 每章在全书字符流中的起始偏移（前缀和）。
     * 旧实现用 chapters.take(i).sumOf 现算，1618 章的书每取一次进度都要走一遍列表，
     * 角落百分比在翻页时每帧都读，白白吃掉主线程时间；这里打开书时算一次，之后 O(1)。
     */
    private var chapterStarts: IntArray = IntArray(0)

    /** 相邻章预取缓存（最多 5 章）：切章时直接命中，不再每次现场解压 + 重排。 */
    private val chapterCache = java.util.LinkedHashMap<Int, ChapterContent>(6, 0.75f, true)
    private val cacheLock = Any()

    private fun cached(index: Int): ChapterContent? = synchronized(cacheLock) { chapterCache[index] }

    private fun putCache(index: Int, content: ChapterContent) = synchronized(cacheLock) {
        chapterCache[index] = content
        while (chapterCache.size > 5) {
            val oldest = chapterCache.keys.firstOrNull() ?: break
            chapterCache.remove(oldest)
        }
    }

    private suspend fun loadChapter(index: Int): ChapterContent {
        cached(index)?.let { return it }
        val source = textPub?.source ?: return ChapterContent("")
        val content = source.chapter(index)
        putCache(index, content)
        return content
    }

    private fun prefixSums(pub: TextPublication): IntArray {
        val starts = IntArray(pub.chapters.size + 1)
        var acc = 0
        for (i in pub.chapters.indices) {
            starts[i] = acc
            acc += pub.chapters[i].length.coerceAtLeast(1)
        }
        starts[pub.chapters.size] = acc
        return starts
    }

    private fun prefetch(index: Int) {
        val count = textPub?.chapters?.size ?: return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            // 往后多取两章：连翻到章末时不会每次都等一次解压
            listOf(index + 1, index + 2, index - 1).forEach { j ->
                if (!isActive || j !in 0 until count || cached(j) != null) return@forEach
                runCatching { loadChapter(j) }
            }
        }
    }

    private val textPub: TextPublication? get() = publication as? TextPublication
    val hasNextChapter: Boolean get() = textPub?.let { chapterIndex < it.chapters.lastIndex } ?: false
    val hasPreviousChapter: Boolean get() = chapterIndex > 0
    val nextChapterTitle: String
        get() = textPub?.chapters?.getOrNull(chapterIndex + 1)?.title ?: ""
    val previousChapterTitle: String
        get() = textPub?.chapters?.getOrNull(chapterIndex - 1)?.title ?: ""

    val isText: Boolean get() = publication is TextPublication
    /** 全局翻页灵敏度（越大越容易翻过），供覆盖 / 仿真手势读取。 */
    val turnSensitivity: Float get() = prefs.turnSensitivity
    val pageCount: Int get() = layout?.pages?.size ?: 1
    val chapterTitles: List<String> get() = publication?.chapterTitles ?: emptyList()
    val toc: List<TocEntry>
        get() = (publication as? TextPublication)?.toc?.takeIf { it.isNotEmpty() }
            ?: chapterTitles.mapIndexed { i, t -> TocEntry(t, i, 0) }
    val totalUnits: Int get() = publication?.totalUnits ?: 1
    val currentPage: PageSlice? get() = layout?.pages?.getOrNull(pageIndex)

    init {
        scope.launch { repo.typography(book.id).collectLatest { it?.let { t -> typography = t } } }
        scope.launch { repo.highlights(book.id).collectLatest { highlights = it } }
        scope.launch { repo.bookmarks(book.id).collectLatest { bookmarks = it } }
        load()
    }

    private fun load() {
        scope.launch {
            status = ReaderStatus.LOADING
            runCatching {
                val pub = PublicationLoader.open(book, repo)
                publication = pub
                if (pub is TextPublication) {
                    chapterStarts = prefixSums(pub)
                    val suggested = pub.suggestedChapter
                    val index = (if (suggested >= 0) suggested else chapterIndex)
                        .coerceIn(0, (pub.chapters.size - 1).coerceAtLeast(0))
                    chapterIndex = index
                    // 解压 + 解析一定不能在主线程：10 MB 的 EPUB 单章解析能占掉 40~80 ms
                    chapterContent = withContext(Dispatchers.IO) { loadChapter(index) }
                    recompute().join()
                    prefetch(index)
                } else {
                    pagedIndex = pagedIndex.coerceIn(0, (pub.totalUnits - 1).coerceAtLeast(0))
                    renderPaged()
                }
                status = ReaderStatus.READY
            }.onFailure { e ->
                errorMessage = e.message ?: "打开书籍失败"
                status = ReaderStatus.ERROR
            }
        }
    }

    /** 供 UI 在视口测量完成 / 阅读状态就绪后主动触发一次排版。 */
    fun refreshLayout() { recompute() }

    /** 全局阅读偏好变化：只有影响排版的字段才需要重排。 */
    fun updateSettings(value: AppSettings) {
        val needRelayout = prefs.paragraphSpacing != value.paragraphSpacing ||
            prefs.firstLineIndent != value.firstLineIndent ||
            prefs.showInlineImages != value.showInlineImages
        prefs = value
        if (needRelayout) recompute()
    }

    fun setViewport(widthPx: Int, heightPx: Int) {
        val w = widthPx.coerceAtLeast(0)
        val h = heightPx.coerceAtLeast(0)
        if (w == viewWidth && h == viewHeight) return
        viewWidth = w
        viewHeight = h
        recompute()
        if (!isText) renderPaged()
    }

    /**
     * 排版在后台线程完成：切章时旧页面一直保持到新 layout 就绪，主线程不阻塞、不闪白。
     * token 保证只有最后一次重排能写回状态（连续改字号 / 拖动滑杆不会乱序）。
     */
    fun recompute(jumpToLast: Boolean = false, onDone: (() -> Unit)? = null): Job {
        val token = ++layoutToken
        val job = scope.launch(Dispatchers.Default) {
            if (!isText || viewWidth < 100 || viewHeight < 100) return@launch
            val previousOffset = layout?.pages?.getOrNull(pageIndex)?.start ?: restoreOffset ?: 0
            val chapterHighlights = highlights.filter { it.chapterIndex == chapterIndex }
            val built = buildChapterLayout(
                content = chapterContent,
                chapter = chapterIndex,
                widthPx = pageContentWidth(),
                heightPx = pageContentHeight(),
                typography = resolveTypography(typography, context),
                palette = palette,
                highlights = chapterHighlights,
                searchTerm = activeSearchTerm,
                paragraphSpacingEm = prefs.paragraphSpacing,
                firstLineIndent = prefs.firstLineIndent,
                showImages = prefs.showInlineImages
            )
            if (token != layoutToken || !isActive) return@launch
            withContext(Dispatchers.Main.immediate) {
                if (token != layoutToken) return@withContext
                layout = built
                val target = restoreOffset ?: previousOffset
                restoreOffset = null
                pageIndex = if (jumpToLast) built.pages.lastIndex.coerceAtLeast(0)
                else built.pages.indexOfFirst { target in it.start until (it.end + 1) }
                    .takeIf { it >= 0 } ?: 0
                saveProgress()
                onDone?.invoke()
            }
        }
        layoutJob = job
        return job
    }

    fun pageContentWidth(): Int = (viewWidth - resolveTypography(typography, context).horizontalMarginPx * 2)
        .toInt().coerceAtLeast(200)

    fun pageContentHeight(): Int = (viewHeight - resolveTypography(typography, context).verticalMarginPx * 2)
        .toInt().coerceAtLeast(200)

    fun updatePageIndex(index: Int) {
        if (chapterLoading) return
        val max = (layout?.pages?.lastIndex ?: 0).coerceAtLeast(0)
        val next = index.coerceIn(0, max)
        if (pageIndex != next) { pageIndex = next; saveProgress() }
    }

    fun updateEnvironment(themePalette: ReaderPalette, isNight: Boolean) {
        if (palette != themePalette || night != isNight) {
            palette = themePalette
            night = isNight
            recompute()
            if (!isText) renderPaged()
        }
    }

    fun nextPage() {
        if (publication == null) return
        turnDirection = 1
        turnSerial++
        if (isText) {
            val pages = layout?.pages ?: return
            if (pageIndex < pages.lastIndex) {
                pageIndex++
            } else if (hasNextChapter) {
                // 章末自动续章；changeChapter 内部会串行化，快速连续翻页只会保留最后一次请求
                changeChapter(chapterIndex + 1)
                return
            }
        } else {
            if (pagedIndex < totalUnits - 1) { pagedIndex++; renderPaged() }
        }
        saveProgress()
    }

    fun previousPage() {
        if (publication == null) return
        turnDirection = -1
        turnSerial++
        if (isText) {
            if (pageIndex > 0) {
                pageIndex--
            } else if (hasPreviousChapter) {
                changeChapter(chapterIndex - 1, jumpToLast = true)
                return
            }
        } else {
            if (pagedIndex > 0) { pagedIndex--; renderPaged() }
        }
        saveProgress()
    }

    /** 供章节边界页调用：滑到章首/章尾之外时进入相邻章。 */
    fun nextChapter() {
        if (!hasNextChapter) return
        turnDirection = 1
        turnSerial++
        changeChapter(chapterIndex + 1)
    }

    fun previousChapter() {
        if (!hasPreviousChapter) return
        turnDirection = -1
        turnSerial++
        changeChapter(chapterIndex - 1, jumpToLast = true)
    }

    /**
     * 跨章加载串行化。两个历史 bug 都在这里：
     * 1) 旧实现用 pendingChapterIndex 去重，但被取消的协程不会清空它 → 永久卡死跨章；
     * 2) 去掉去重后，章末连点会反复 cancel 同一个加载任务，谁都跑不完 → 界面停在「下一章」。
     * 现在：同一章正在加载时直接忽略重复请求，用 token 保证只有最后一次任务能收尾。
     */
    fun changeChapter(index: Int, offset: Int = 0, jumpToLast: Boolean = false) {
        val pub = textPub ?: return
        val i = index.coerceIn(0, (pub.chapters.size - 1).coerceAtLeast(0))
        if (pendingChapterIndex == i && chapterJob?.isActive == true) return
        chapterJob?.cancel()
        val token = ++chapterToken
        pendingChapterIndex = i
        // 命中预取缓存时不进入加载态：章末翻页不再出现「卡一下」的等待
        chapterLoading = cached(i) == null
        val job = scope.launch {
            val content = withContext(Dispatchers.IO) { loadChapter(i) }
            if (!isActive || chapterToken != token) return@launch
            chapterContent = content
            chapterIndex = i
            restoreOffset = offset
            recompute(jumpToLast = jumpToLast, onDone = {
                chapterSerial++
                prefetch(i)
            })
        }
        chapterJob = job
        job.invokeOnCompletion {
            if (chapterToken == token) {
                pendingChapterIndex = null
                chapterLoading = false
            }
        }
    }

    fun goTo(chapter: Int, offset: Int) {
        turnDirection = if (chapter >= chapterIndex && offset >= (currentPage?.start ?: 0)) 1 else -1
        turnSerial++
        if (isText) changeChapter(chapter, offset) else {
            pagedIndex = offset.coerceIn(0, totalUnits - 1)
            renderPaged()
        }
    }

    fun seekFraction(fraction: Float) {
        if (isText) {
            val pub = publication as? TextPublication ?: return
            val target = (pub.totalUnits * fraction).toInt()
            val starts = chapterStarts
            if (starts.size == pub.chapters.size + 1 && pub.chapters.isNotEmpty()) {
                // 二分定位目标章：1618 章的书也不用手动遍历
                var lo = 0
                var hi = pub.chapters.size - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (starts[mid] <= target) lo = mid else hi = mid - 1
                }
                changeChapter(lo, (target - starts[lo]).coerceAtLeast(0))
                return
            }
            var acc = 0
            for ((i, meta) in pub.chapters.withIndex()) {
                if (target < acc + meta.length.coerceAtLeast(1)) {
                    changeChapter(i, (target - acc).coerceAtLeast(0))
                    return
                }
                acc += meta.length.coerceAtLeast(1)
            }
        } else {
            pagedIndex = ((totalUnits - 1) * fraction).toInt().coerceIn(0, totalUnits - 1)
            renderPaged()
        }
    }

    fun progressFraction(): Float {
        val pub = publication ?: return 0f
        if (!isText) return (pagedIndex + 1f) / totalUnits.coerceAtLeast(1)
        val tp = pub as TextPublication
        val starts = chapterStarts
        if (starts.size != tp.chapters.size + 1 || tp.chapters.isEmpty()) return 0f
        val offset = currentPage?.start ?: 0
        val base = starts[chapterIndex.coerceIn(0, tp.chapters.lastIndex)]
        return ((base + offset).toFloat() / tp.totalUnits.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /** 角落百分比文案：不足 10% 时保留一位小数，长书开头不会一直停在「0%」。 */
    fun progressLabel(): String {
        val pct = progressFraction() * 100f
        return when {
            pct <= 0f -> "0%"
            pct < 10f -> String.format(java.util.Locale.US, "%.1f%%", pct)
            else -> "${pct.toInt()}%"
        }
    }

    fun saveProgress() {
        val pub = publication ?: return
        scope.launch {
            val offset = if (isText) currentPage?.start ?: 0 else pagedIndex
            val page = if (isText) pageIndex else pagedIndex
            repo.dao.saveProgress(book.id, chapterIndex, offset, page, progressFraction(), System.currentTimeMillis())
        }
    }

    private fun renderPaged() {
        val source = (publication as? PagedPublication)?.source ?: return
        scope.launch {
            pdfBitmap = source.render(
                pagedIndex,
                (viewWidth.coerceAtLeast(600) * pdfZoom).toInt(),
                night,
                pdfCrop
            )
        }
    }

    fun changePdfZoom(value: Float) { pdfZoom = value.coerceIn(0.8f, 2.5f); renderPaged() }
    fun changePdfCrop(value: Float) { pdfCrop = value.coerceIn(0f, 1f); renderPaged() }

    // ---------- 标注 / 笔记 / 书签 ----------
    fun addHighlight(colorIndex: Int) {
        val sel = selection ?: return
        val text = chapterContent.text
        val start = sel.start.coerceIn(0, text.length)
        val end = sel.end.coerceIn(start, text.length)
        if (end <= start) { selection = null; return }
        scope.launch {
            repo.dao.insertHighlight(
                HighlightEntity(
                    bookId = book.id, chapterIndex = chapterIndex, start = start, end = end,
                    color = colorIndex, quote = text.substring(start, end)
                )
            )
        }
        selection = null
    }

    fun changeHighlightColor(entity: HighlightEntity, color: Int) {
        scope.launch { repo.dao.updateHighlightColor(entity.id, color) }
    }

    fun deleteHighlight(entity: HighlightEntity) {
        scope.launch { repo.dao.deleteHighlight(entity.id) }
    }

    fun deleteBookmarkLocal(id: Long) {
        scope.launch { repo.dao.deleteBookmark(id) }
    }

    fun saveNote(entity: HighlightEntity, note: String) {
        scope.launch { repo.dao.updateHighlightNote(entity.id, note) }
    }

    fun highlightAt(offset: Int): HighlightEntity? =
        highlights.firstOrNull { it.chapterIndex == chapterIndex && offset in it.start until it.end }

    fun toggleBookmark() {
        val offset = if (isText) currentPage?.start ?: 0 else pagedIndex
        val preview = if (isText) chapterContent.text.substring(
            offset.coerceAtMost(chapterContent.text.length),
            (offset + 40).coerceAtMost(chapterContent.text.length)
        ).replace('\n', ' ').trim() else "第 ${pagedIndex + 1} 页"
        scope.launch {
            val existing = repo.dao.findBookmark(book.id, chapterIndex, offset)
            if (existing != null) repo.dao.deleteBookmark(existing.id) else {
                repo.dao.insertBookmark(
                    BookmarkEntity(
                        bookId = book.id, chapterIndex = chapterIndex, charOffset = offset,
                        pageIndex = pageIndex, preview = preview
                    )
                )
            }
        }
    }

    fun isBookmarked(): Boolean {
        val offset = if (isText) currentPage?.start ?: 0 else pagedIndex
        return bookmarks.any { it.chapterIndex == chapterIndex && it.charOffset == offset }
    }

    // ---------- 搜索 ----------
    fun search(query: String) {
        val pub = publication as? TextPublication ?: return
        scope.launch {
            searchBusy = true
            searchResults = runCatching { pub.source.search(query, pub.chapterTitles) }.getOrDefault(emptyList())
            searchBusy = false
        }
    }

    fun applySearchTerm(term: String?) {
        activeSearchTerm = term
        recompute()
    }

    fun updateTypography(changed: TypographyEntity) {
        scope.launch {
            repo.dao.insertTypography(changed)
            typography = changed
            recompute()
        }
    }

    fun close() {
        chapterJob?.cancel()
        layoutJob?.cancel()
        prefetchJob?.cancel()
        (publication as? PagedPublication)?.source?.close()
        (textPub?.source as? EpubTextSource)?.close()
    }
}
