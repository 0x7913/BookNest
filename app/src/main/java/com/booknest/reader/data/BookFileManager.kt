package com.booknest.reader.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ImportedFile(val file: File, val displayName: String, val size: Long)

/** 所有书籍、封面、字体都落在应用私有目录；卸载应用即全部清除。 */
class BookFileManager(private val context: Context) {

    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
    private val cacheDir = File(context.cacheDir, "publications").apply { mkdirs() }

    fun stableId(uri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)

    fun displayName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx) ?: fallback(uri)
            }
        }
        return fallback(uri)
    }

    private fun fallback(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名书籍"

    /** SAF 多选导入：持久化读权限 + 复制一份到私有目录（避免 Uri 失效）。 */
    suspend fun import(uri: Uri): ImportedFile = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val name = displayName(uri).replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val ext = name.substringAfterLast('.', "")
        val target = File(booksDir, "${UUID.randomUUID().toString().take(8)}_${stableId(uri).take(8)}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: error("无法读取文件：$uri")
        ImportedFile(target, name, target.length())
    }

    /** 本地副本丢失时，尝试从原始 Uri 重新拷贝一份到缓存。 */
    suspend fun readableFile(localPath: String, uriString: String): File = withContext(Dispatchers.IO) {
        val local = File(localPath)
        if (local.isFile && local.length() > 0) return@withContext local
        val uri = Uri.parse(uriString)
        val cached = File(cacheDir, "${stableId(uri)}.${local.extension}")
        if (!cached.isFile) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cached.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: error("原始文件已不可访问，请重新导入")
        }
        cached
    }

    fun saveCover(bitmap: Bitmap, key: String): String {
        val file = File(coversDir, "$key.png")
        runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }
        }
        return file.absolutePath
    }

    suspend fun importFont(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = displayName(uri).replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val target = File(fontsDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 32 * 1024) }
        } ?: error("字体读取失败")
        target.absolutePath
    }

    fun deleteBookFiles(book: BookEntity) {
        runCatching { File(book.localPath).delete() }
        book.coverPath?.let { runCatching { File(it).delete() } }
    }
}
