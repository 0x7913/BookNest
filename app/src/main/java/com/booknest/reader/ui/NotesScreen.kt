package com.booknest.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.booknest.reader.core.highlightColor
import com.booknest.reader.data.HighlightEntity
import com.booknest.reader.state.AppState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotesScreen(app: AppState, modifier: Modifier = Modifier) {
    val booksById = app.books.associateBy { it.id }
    val filtered = app.allNotes.filter { app.noteBookFilter == null || it.bookId == app.noteBookFilter }
        .sortedByDescending { it.createdAt }
    val bookIdsWithNotes = app.allNotes.map { it.bookId }.distinct()

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("笔记与高亮", fontSize = 27.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
        Text("共 ${app.allNotes.size} 条标注，点击可跳回原文位置。",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            FilterChip(selected = app.noteBookFilter == null,
                onClick = { app.noteBookFilter = null }, label = { Text("全部") })
            bookIdsWithNotes.take(6).forEach { id ->
                FilterChip(selected = app.noteBookFilter == id,
                    onClick = { app.noteBookFilter = if (app.noteBookFilter == id) null else id },
                    label = { Text(booksById[id]?.title?.take(8) ?: "已移除", maxLines = 1) })
            }
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyLibraryIllustration(Modifier.size(200.dp))
                    Text("还没有留下痕迹", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("阅读时长按选中文字，就能高亮或写下笔记。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { item ->
                    NoteCard(app, item, booksById[item.bookId]?.title ?: "已移除的书籍")
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }
}

@Composable
private fun NoteCard(app: AppState, item: HighlightEntity, bookTitle: String) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp)) {
            Box(Modifier.size(4.dp, 62.dp).background(highlightColor(item.color), RoundedCornerShape(4.dp)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("《$bookTitle》  ·  第 ${item.chapterIndex + 1} 章", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.quote.replace('\n', ' '), fontSize = 14.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                if (item.note.isNotBlank()) {
                    Text(item.note, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp), maxLines = 3)
                }
                Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt)),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
            Column {
                val book = app.books.firstOrNull { it.id == item.bookId }
                IconButton(onClick = {
                    if (book != null) app.openBook(book, item.chapterIndex to item.start)
                    else app.showToast("原书已被移除")
                }) { Icon(I.Forward, contentDescription = "跳回原文") }
                IconButton(onClick = { app.deleteHighlightGlobal(item.id) }) {
                    Icon(I.Delete, contentDescription = "删除标注")
                }
            }
        }
    }
}
