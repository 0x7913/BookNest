package com.booknest.reader.format

import com.booknest.reader.core.ChapterContent
import java.io.File

data class MobiBook(
    val title: String,
    val author: String = "",
    val chapters: List<ChapterContent>,
    val chapterTitles: List<String>,
    val warning: String? = null
)

private fun be16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

private fun be32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)

/** PalmDOC LZ77 解压（MOBI 未压缩 / compression=2 的主流情形）。 */
private fun palmDocDecompress(data: ByteArray): ByteArray {
    val out = ByteArray(1 shl 16)
    var o = 0
    var i = 0
    while (i < data.size && o < out.size) {
        val c = data[i++].toInt() and 0xff
        when {
            c == 0 -> out[o++] = 0
            c <= 8 -> repeat(c) { if (i < data.size && o < out.size) out[o++] = data[i++] }
            c < 0x80 -> out[o++] = c.toByte()
            else -> {
                if (i >= data.size) break
                val dij = (c shl 8) or (data[i++].toInt() and 0xff)
                val distance = (dij shr 3) and 0x7ff
                val length = (dij and 7) + 3
                if (distance == 0 || distance > o || o + length >= out.size) break
                repeat(length) { out[o] = out[o - distance]; o++ }
            }
        }
    }
    return out.copyOf(o)
}

/** MOBI / AZW(3)：Palm DB 记录解析 + PalmDOC 解压 + HTML 清洗；HUFF/CDIC 与 KF8 部分降级处理。 */
fun parseMobi(file: File): MobiBook {
    val bytes = file.readBytes()
    require(bytes.size > 78) { "MOBI 文件过小或格式不正确" }
    val recordCount = be16(bytes, 76)
    require(recordCount in 1..20000) { "MOBI 记录数异常" }
    val offsets = IntArray(recordCount)
    for (i in 0 until recordCount) {
        val p = 78 + i * 8
        if (p + 4 > bytes.size) break
        offsets[i] = be32(bytes, p)
    }
    val headerStart = offsets[0]
    val headerEnd = offsets.getOrElse(1) { bytes.size }
    val rec0 = bytes.copyOfRange(headerStart, headerEnd.coerceAtMost(bytes.size))
    val compression = be16(rec0, 0)
    val textLength = be32(rec0, 4)
    val textRecordCount = be16(rec0, 8)
    val encryption = if (rec0.size >= 12) be16(rec0, 10) else 0
    if (encryption != 0) {
        val (titles, chapters) = splitPlainChapters("这本书带有 DRM 加密，无法在本地解析。")
        return MobiBook(file.nameWithoutExtension, "", chapters, titles, "DRM 加密文件")
    }
    val huff = compression == 17480
    val warning = when {
        huff -> "HUFF/CDIC 压缩的 MOBI 暂以原始文本降级显示"
        file.extension.equals("azw3", true) -> "AZW3/KF8 复合版式以纯文本模式显示"
        else -> null
    }

    val name = runCatching {
        String(bytes.copyOfRange(0, 32), Charsets.ISO_8859_1).trim('\u0000').trim()
    }.getOrDefault(file.nameWithoutExtension)
    val title = if (name.isBlank()) file.nameWithoutExtension else name

    val merged = java.io.ByteArrayOutputStream()
    val count = textRecordCount.coerceIn(1, recordCount - 1)
    for (i in 1..count) {
        val start = offsets[i]
        val end = if (i + 1 < recordCount) offsets[i + 1] else bytes.size
        if (start < 0 || end <= start || end > bytes.size) break
        val record = bytes.copyOfRange(start, end)
        val plain = when {
            huff -> record // 未实现 HUFF 字典：清洗阶段会尽量剥离二进制
            compression == 2 -> palmDocDecompress(record)
            else -> record
        }
        merged.write(plain)
        if (merged.size() >= textLength.coerceAtLeast(1) && !huff) break
    }
    val raw = merged.toByteArray().copyOf(textLength.coerceAtMost(merged.size()).coerceAtLeast(0))
    val charset = detectCharset(raw.copyOf(4096.coerceAtMost(raw.size)))
    val cleaned = String(raw, charset)
        .replace(Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F]"""), "")
    val plain = htmlToChapterContent(cleaned).text
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    val (titles, chapters) = splitPlainChapters(plain.ifBlank { "未能从该文件中提取出可阅读文本，建议转换为 EPUB 后导入。" }, "正文")
    return MobiBook(title, "", chapters, titles, warning)
}
