package com.booknest.reader.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.booknest.reader.data.BookEntity
import com.booknest.reader.data.SortMode
import com.booknest.reader.data.ViewMode
import com.booknest.reader.state.AppState

@Composable
fun LibraryScreen(app: AppState, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val books = app.visibleBooks
    var searchExpanded by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var shelfDialog by remember { mutableStateOf(false) }
    var newShelfName by remember { mutableStateOf("") }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val columns = when {
        screenWidth >= 1000 -> 5
        screenWidth >= 720 -> 4
        screenWidth >= 480 -> 3
        else -> 2
    }

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("BOOKNEST  ·  私人书房", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, letterSpacing = 1.7.sp)
                Text("让阅读，慢下来。", fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 7.dp))
            }
            IconButton(onClick = { searchExpanded = !searchExpanded }) {
                Icon(I.Search, contentDescription = "搜索书库")
            }
        }

        if (searchExpanded) {
            OutlinedTextField(
                value = app.libraryQuery, onValueChange = { app.libraryQuery = it },
                placeholder = { Text("按书名或作者搜索") },
                leadingIcon = { Icon(I.Search, contentDescription = null) },
                trailingIcon = { IconButton(onClick = { app.libraryQuery = ""; searchExpanded = false }) {
                    Icon(I.Close, contentDescription = "关闭搜索") } },
                singleLine = true, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = app.selectedShelfId == null && !app.favoritesOnly,
                onClick = { app.selectedShelfId = null; app.favoritesOnly = false }, label = { Text("全部") })
            FilterChip(selected = app.favoritesOnly,
                onClick = { app.favoritesOnly = !app.favoritesOnly; app.selectedShelfId = null },
                label = { Text("收藏") }, leadingIcon = { Icon(I.Favorite, contentDescription = null, Modifier.size(16.dp)) })
            app.shelves.forEach { shelf ->
                FilterChip(selected = app.selectedShelfId == shelf.id,
                    onClick = { app.selectedShelfId = if (app.selectedShelfId == shelf.id) null else shelf.id; app.favoritesOnly = false },
                    label = { Text(shelf.name) })
            }
            AssistChip(onClick = { shelfDialog = true }, label = { Text("＋ 分组") })
        }

        if (shelfDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { shelfDialog = false },
                title = { Text("新建分组") },
                text = {
                    OutlinedTextField(value = newShelfName, onValueChange = { newShelfName = it },
                        singleLine = true, label = { Text("分组名称") })
                },
                confirmButton = { TextButton(onClick = { app.createShelf(newShelfName); newShelfName = ""; shelfDialog = false }) { Text("创建") } },
                dismissButton = { TextButton(onClick = { shelfDialog = false }) { Text("取消") } }
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("我的书架", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${books.size} 本", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge)
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(I.More, contentDescription = "排序方式") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                            app.setSortMode(mode); sortMenu = false
                        })
                    }
                }
            }
            IconButton(onClick = {
                app.setViewMode(if (app.settings.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID)
            }) {
                Icon(if (app.settings.viewMode == ViewMode.GRID) I.List else I.Home,
                    contentDescription = if (app.settings.viewMode == ViewMode.GRID) "切换为列表视图" else "切换为网格视图")
            }
        }

        if (books.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyLibraryIllustration(Modifier.size(230.dp))
                    Text(if (app.libraryQuery.isBlank()) "书架还很安静" else "没有找到这本书",
                        fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    Text(if (app.libraryQuery.isBlank()) "把喜欢的故事带进来，\n让它们在这里慢慢展开。" else "换个关键词试试，或重新导入本地书籍。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 9.dp, bottom = 20.dp))
                    Button(onClick = onImport, shape = RoundedCornerShape(18.dp)) { Text("导入本地书籍") }
                }
            }
        } else if (app.settings.viewMode == ViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(books, key = { it.id }) { book -> BookCard(app, book, grid = true) }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(books, key = { it.id }) { book -> BookCard(app, book, grid = false) }
            }
        }
    }
}

@Composable
private fun BookCard(app: AppState, book: BookEntity, grid: Boolean) {
    var pressed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    val interaction = Modifier.scale(scale).pointerInput(book.id) {
        detectTapGestures(
            onPress = { pressed = true; tryAwaitRelease(); pressed = false },
            onTap = { app.openBook(book) },
            onLongPress = { menu = true }
        )
    }

    if (grid) {
        Column(interaction) {
            CoverBox(app, book, Modifier.fillMaxWidth().height((LocalConfiguration.current.screenWidthDp / 4 * 1.35f).dp.coerceIn(160.dp, 220.dp)))
            Text(book.title, Modifier.padding(top = 9.dp, start = 3.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(book.author.ifBlank { book.format }, Modifier.padding(start = 3.dp, top = 2.dp),
                maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            ThinProgress(book.progress, MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.surfaceVariant, Modifier.fillMaxWidth().padding(top = 7.dp))
            TextButton(onClick = { app.openBook(book) }, contentPadding = PaddingValues(0.dp)) {
                Text(if (book.progress > 0.001f) "继续阅读 · ${(book.progress * 100).toInt()}%" else "开始阅读",
                    fontSize = 12.sp)
            }
            BookMenu(app, book, menu) { menu = false }
        }
    } else {
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().then(interaction).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverBox(app, book, Modifier.size(64.dp, 88.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.author.ifBlank { book.format }, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    ThinProgress(book.progress, MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.surfaceVariant, Modifier.fillMaxWidth().padding(top = 10.dp))
                    Text("${(book.progress * 100).toInt()}%  ·  ${(book.fileSize / 1024 / 1024)} MB",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp))
                }
                BookMenu(app, book, menu) { menu = false }
            }
        }
    }
}

@Composable
private fun CoverBox(app: AppState, book: BookEntity, modifier: Modifier) {
    Box(modifier) {
        BookCover(book.coverPath, book.title, book.format, Modifier.fillMaxSize(),
            MaterialTheme.colorScheme.primary)
        Surface(color = Color.Black.copy(alpha = .45f), shape = RoundedCornerShape(10.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp)) {
            Text("${(book.progress * 100).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                color = Color.White, fontSize = 10.sp)
        }
        if (book.favorite) {
            Icon(I.Favorite, contentDescription = "已收藏", tint = Color(0xFFFFD8A8),
                modifier = Modifier.align(Alignment.TopEnd).padding(9.dp).size(18.dp))
        }
    }
}

@Composable
private fun BookMenu(app: AppState, book: BookEntity, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(if (book.favorite) "取消收藏" else "加入收藏") }, onClick = {
            app.toggleFavorite(book); onDismiss()
        })
        DropdownMenuItem(text = { Text("移出分组") }, onClick = { app.moveBookToShelf(book, null); onDismiss() })
        app.shelves.forEach { shelf ->
            DropdownMenuItem(text = { Text("移到「${shelf.name}」") }, onClick = {
                app.moveBookToShelf(book, shelf.id); onDismiss()
            })
        }
        DropdownMenuItem(text = { Text("从书库移除") }, onClick = { app.deleteBook(book); onDismiss() })
    }
}
