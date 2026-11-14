package com.booknest.reader.format

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.booknest.reader.core.ChapterContent
import com.booknest.reader.core.SearchHit
import com.booknest.reader.core.TextSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** TXT：按行索引懒加载，任何时刻内存中只有当前章的几万字。 */
class TxtTextSource(
    private val normalized: File,
    private val ranges: List<TxtChapterRange>,
    private val titles: List<String>
) : TextSource {
    override val chapterCount: Int get() = ranges.size

    override suspend fun chapter(index: Int): ChapterContent = withContext(Dispatchers.IO) {
        val i = index.coerceIn(0, ranges.lastIndex)
        ChapterContent(readTxtChapter(normalized, ranges[i]))
    }

    override suspend fun search(query: String, titles: List<String>, limit: Int): List<SearchHit> =
        withContext(Dispatchers.IO) { searchChapters(query, titles, limit) { i -> chapter(i).text } }
}

/** FB2 / MOBI：解析结果常驻（通常 < 10MB），按需取章。 */
class MemoryTextSource(
    private val contents: List<ChapterContent>,
    private val chapterTitles: List<String>
) : TextSource {
    override val chapterCount: Int get() = contents.size

    override suspend fun chapter(index: Int): ChapterContent =
        contents.getOrNull(index) ?: ChapterContent("")

    override suspend fun search(query: String, titles: List<String>, limit: Int): List<SearchHit> =
        withContext(Dispatchers.IO) { searchChapters(query, titles, limit) { i -> contents[i].text } }
}

/**
 * EPUB：只常驻章节索引（路径 + 标题 + 长度），每次仅解出当前一章。
 *
 * ZipFile 实例常驻（1618 章的书每次重新打开 central directory 会有明显卡顿），
 * 章内插图按 zip 路径缓存 Bitmap —— 每章重复出现的 logo 只解码一次。
 */
class EpubTextSource(
    private val file: File,
    private val refs: List<EpubRef>
) : TextSource {
    override val chapterCount: Int get() = refs.size

    private var zip: ZipFile? = null
    private val imageCache = HashMap<String, Bitmap?>()

    private fun openZip(): ZipFile = synchronized(this) {
        zip ?: ZipFile(file).also { zip = it }
    }

    fun close() {
        synchronized(this) {
            runCatching { zip?.close() }
            zip = null
            imageCache.clear()
        }
    }

    override suspend fun chapter(index: Int): ChapterContent = withContext(Dispatchers.IO) {
        val ref = refs.getOrNull(index) ?: return@withContext ChapterContent("")
        val baseDir = ref.path.substringBeforeLast('/', "")
        runCatching {
            val z = openZip()
            val entry = z.getEntry(ref.path)
            if (entry == null) ChapterContent("") else {
                val html = z.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val content = htmlToChapterContent(html)
                if (content.images.isEmpty()) content else content.copy(
                    images = content.images.map { im ->
                        im.copy(bitmap = loadImage(resolvePath(baseDir, im.src)))
                    }
                )
            }
        }.getOrDefault(ChapterContent(""))
    }

    /** 图片只解一次；解码按最大边 1100px 采样，避免把 400KB 章首图整张摊进内存。 */
    private fun loadImage(path: String): Bitmap? = synchronized(imageCache) {
        if (imageCache.containsKey(path)) return imageCache[path]
        val bmp = runCatching {
            val e = openZip().getEntry(path) ?: return@runCatching null
            val bytes = openZip().getInputStream(e).use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1100) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
        imageCache[path] = bmp
        bmp
    }

    override suspend fun search(query: String, titles: List<String>, limit: Int): List<SearchHit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val z = openZip()
                searchChapters(query, titles, limit) { i ->
                    val ref = refs.getOrNull(i)
                    val entry = if (ref == null) null else z.getEntry(ref.path)
                    if (entry == null) "" else htmlToChapterContent(
                        z.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    ).text
                }
            }.getOrDefault(emptyList())
        }
}

private fun resolvePath(baseDir: String, href: String): String {
    val raw = href.substringBefore('#').substringBefore('?')
    val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    val combined = if (decoded.startsWith("/")) decoded
    else (if (baseDir.isEmpty()) "" else "$baseDir/") + decoded
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

private inline fun searchChapters(
    query: String,
    titles: List<String>,
    limit: Int,
    textOf: (Int) -> String
): List<SearchHit> {
    if (query.isBlank()) return emptyList()
    val needle = query.trim()
    val result = mutableListOf<SearchHit>()
    val count = titles.size
    for (i in 0 until count) {
        val text = textOf(i)
        var from = 0
        while (result.size < limit) {
            val hit = text.indexOf(needle, from, ignoreCase = true)
            if (hit < 0) break
            val start = (hit - 32).coerceAtLeast(0)
            val end = (hit + needle.length + 44).coerceAtMost(text.length)
            val snippet = text.substring(start, end).replace('\n', ' ').trim()
            result.add(SearchHit(i, titles.getOrElse(i) { "第 ${i + 1} 章" }, hit, snippet))
            from = hit + needle.length
        }
        if (result.size >= limit) break
    }
    return result
}
