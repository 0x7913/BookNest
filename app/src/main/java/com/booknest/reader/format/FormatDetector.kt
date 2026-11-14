package com.booknest.reader.format

import com.booknest.reader.core.BookFormat
import java.io.File
import java.util.zip.ZipFile

/** 扩展名优先，未知扩展名时按文件魔数嗅探。 */
fun detectFormat(file: File, displayName: String): BookFormat {
    val byName = BookFormat.from(displayName)
    if (byName != BookFormat.UNKNOWN) return byName
    val head = ByteArray(8)
    val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
    if (read >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "%PDF") return BookFormat.PDF
    if (read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
        return runCatching {
            ZipFile(file).use { zip ->
                when {
                    zip.getEntry("mimetype") != null -> BookFormat.EPUB
                    zip.entries().asSequence().any { it.name.endsWith(".opf", true) } -> BookFormat.EPUB
                    zip.entries().asSequence().any {
                        it.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "png", "webp", "jpeg")
                    } -> BookFormat.CBZ
                    else -> BookFormat.UNKNOWN
                }
            }
        }.getOrDefault(BookFormat.UNKNOWN)
    }
    return BookFormat.TXT
}
