package com.booknest.reader.format

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val MAX_CHAPTER_CHARS = 22000

val CHAPTER_HEADING_REGEX = Regex(
    """^\s{0,6}(第\s?[零〇一二三四五六七八九十百千两0-9０-９]{1,12}\s?[章节回卷篇部集幕折][^\n]{0,36}|Chapter\s+\d+[^\n]{0,40}|CHAPTER\s+[IVXLCDM0-9]+[^\n]{0,40}|卷\s?[零一二三四五六七八九十0-9]+[^\n]{0,30}|序\s?[章言幕]?|楔子|引子|尾声|后记|番外[^\n]{0,20}|Part\s+[IVXLCDM0-9]+[^\n]{0,30})\s*$""",
    RegexOption.IGNORE_CASE
)

data class TxtChapterRange(
    val title: String,
    val startLine: Int,
    val endLine: Int,
    val charStart: Int,
    val charEnd: Int
)

data class TxtIndex(val normalizedPath: String, val chapters: List<TxtChapterRange>, val totalChars: Int)

/** BOM + 严格 UTF-8 校验 + 零字节分布，覆盖 UTF-8 / GBK(GB18030) / UTF-16LE / UTF-16BE。 */
fun detectCharset(sample: ByteArray): Charset {
    if (sample.size >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte())
        return StandardCharsets.UTF_8
    if (sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte())
        return Charset.forName("UTF-16LE")
    if (sample.size >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte())
        return Charset.forName("UTF-16BE")
    val strict = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(sample))
        true
    }.getOrDefault(false)
    if (strict) return StandardCharsets.UTF_8
    if (sample.size > 64) {
        val evenZero = (0 until sample.size step 2).count { sample[it] == 0.toByte() }
        val oddZero = (1 until sample.size step 2).count { sample[it] == 0.toByte() }
        val n = sample.size / 2
        if (evenZero > n * 0.35) return Charset.forName("UTF-16BE")
        if (oddZero > n * 0.35) return Charset.forName("UTF-16LE")
    }
    return runCatching { Charset.forName("GB18030") }.getOrDefault(StandardCharsets.UTF_8)
}

private fun matchHeading(line: String): String? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.length > 60) return null
    return CHAPTER_HEADING_REGEX.find(trimmed)?.value?.trim()
}

/** 把纯文本按「第X章」等标题切分为章节；过长章节自动再分段，避免一次性排版造成卡顿。 */
fun splitPlainChapters(text: String, defaultTitle: String = "正文"): Pair<List<String>, List<com.booknest.reader.core.ChapterContent>> {
    val lines = text.lines()
    val titles = mutableListOf<String>()
    val bodies = mutableListOf<StringBuilder>()
    var current: StringBuilder? = null
    var currentTitle = defaultTitle
    for (line in lines) {
        val heading = matchHeading(line)
        if (heading != null) {
            current?.let { if (it.isNotBlank()) { titles.add(currentTitle); bodies.add(it) } }
            current = StringBuilder()
            currentTitle = heading
            continue
        }
        if (current == null) { current = StringBuilder(); currentTitle = defaultTitle }
        if (current.length > MAX_CHAPTER_CHARS) {
            titles.add(currentTitle + "（续）")
            bodies.add(current)
            current = StringBuilder()
            currentTitle = currentTitle + "（续）"
        }
        current.append(line).append('\n')
    }
    current?.let { if (it.isNotBlank()) { titles.add(currentTitle); bodies.add(it) } }
    if (bodies.isEmpty()) {
        titles.add(defaultTitle)
        bodies.add(StringBuilder(text.ifBlank { "（空白内容）" }))
    }
    return titles to bodies.map { com.booknest.reader.core.ChapterContent(it.toString().trimEnd()) }
}

/**
 * 大文件流式预处理：一次扫描完成「编码归一 + 行索引 + 正则分章」。
 * 归一化副本按 UTF-8 保存，章节按需读取，>50MB 也不会把整本灌进内存或阻塞主线程。
 */
fun prepareTxt(src: File, dest: File): TxtIndex {
    val sample = ByteArray(65536)
    val sampleLen = FileInputStream(src).use { it.read(sample) }.coerceAtLeast(0)
    val charset = detectCharset(if (sampleLen == sample.size) sample else sample.copyOf(sampleLen))
    val ranges = mutableListOf<TxtChapterRange>()
    var lineNo = 0
    var charCount = 0
    var currentStartLine = 0
    var currentStartChar = 0
    var currentTitle = "正文"
    var foundHeading = false

    fun finalize(title: String, endLine: Int, endChar: Int) {
        if (endLine > currentStartLine || endChar > currentStartChar) {
            ranges.add(TxtChapterRange(title, currentStartLine, endLine, currentStartChar, endChar))
        }
    }

    BufferedReader(InputStreamReader(FileInputStream(src), charset)).use { reader ->
        BufferedWriter(OutputStreamWriter(FileOutputStream(dest), StandardCharsets.UTF_8)).use { writer ->
            while (true) {
                val raw = reader.readLine() ?: break
                val line = if (raw.startsWith('\uFEFF')) raw.drop(1) else raw
                val heading = matchHeading(line)
                if (heading != null && lineNo > 0) {
                    finalize(currentTitle, lineNo, charCount)
                    currentTitle = heading
                    currentStartLine = lineNo
                    currentStartChar = charCount
                    foundHeading = true
                } else if (heading != null && lineNo == 0) {
                    currentTitle = heading
                    foundHeading = true
                } else if (charCount - currentStartChar > MAX_CHAPTER_CHARS) {
                    finalize(currentTitle, lineNo, charCount)
                    currentTitle = if (foundHeading) currentTitle + "（续）" else "第 ${ranges.size + 1} 段"
                    currentStartLine = lineNo
                    currentStartChar = charCount
                }
                writer.write(line)
                writer.write("\n")
                charCount += line.length + 1
                lineNo++
            }
            finalize(currentTitle, lineNo, charCount)
        }
    }
    if (ranges.isEmpty()) ranges.add(TxtChapterRange("正文", 0, lineNo.coerceAtLeast(1), 0, charCount))
    return TxtIndex(dest.absolutePath, ranges, charCount)
}

fun readTxtChapter(normalized: File, range: TxtChapterRange): String {
    if (!normalized.isFile) return ""
    val sb = StringBuilder((range.charEnd - range.charStart).coerceAtLeast(0))
    BufferedReader(InputStreamReader(FileInputStream(normalized), StandardCharsets.UTF_8)).use { reader ->
        var lineNo = 0
        while (lineNo < range.startLine) {
            reader.readLine() ?: return ""
            lineNo++
        }
        while (lineNo < range.endLine) {
            val line = reader.readLine() ?: break
            sb.append(line).append('\n')
            lineNo++
        }
    }
    return sb.toString().trimEnd()
}

fun normalizedFileFor(localPath: String): File = File("$localPath.norm.txt")

/** 从归一化副本重建章节行索引（进程重启后无需重新扫描原始大文件）。 */
fun scanTxtRanges(normalized: File): List<TxtChapterRange> {
    if (!normalized.isFile) return emptyList()
    val ranges = mutableListOf<TxtChapterRange>()
    var lineNo = 0
    var charCount = 0
    var startLine = 0
    var startChar = 0
    var title = "正文"

    fun finalize(name: String, endLine: Int, endChar: Int) {
        if (endLine > startLine || endChar > startChar) {
            ranges.add(TxtChapterRange(name, startLine, endLine, startChar, endChar))
        }
    }

    BufferedReader(InputStreamReader(FileInputStream(normalized), StandardCharsets.UTF_8)).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            val heading = matchHeading(line)
            if (heading != null && lineNo > 0) {
                finalize(title, lineNo, charCount)
                title = heading
                startLine = lineNo
                startChar = charCount
            } else if (heading != null && lineNo == 0) {
                title = heading
            } else if (charCount - startChar > MAX_CHAPTER_CHARS) {
                finalize(title, lineNo, charCount)
                title += "（续）"
                startLine = lineNo
                startChar = charCount
            }
            charCount += line.length + 1
            lineNo++
        }
        finalize(title, lineNo, charCount)
    }
    return ranges.ifEmpty { listOf(TxtChapterRange("正文", 0, lineNo.coerceAtLeast(1), 0, charCount)) }
}
