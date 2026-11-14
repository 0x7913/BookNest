package com.booknest.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.booknest.reader.core.FONT_CHOICES
import com.booknest.reader.core.MARGIN_PRESETS
import com.booknest.reader.core.ReaderPalette
import com.booknest.reader.core.WEIGHT_CHOICES
import com.booknest.reader.core.highlightColor
import com.booknest.reader.data.HighlightEntity
import com.booknest.reader.data.PageMode
import com.booknest.reader.data.TypographyEntity
import com.booknest.reader.reader.TtsManager
import com.booknest.reader.state.AppState
import com.booknest.reader.state.ReaderSheet
import com.booknest.reader.state.ReaderState

@Composable
fun ReaderSheetHost(
    app: AppState,
    state: ReaderState,
    palette: ReaderPalette,
    tts: TtsManager,
    onImportFont: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onEditNote: (HighlightEntity) -> Unit
) {
    // 面板不做位移动画、不铺黑色遮罩、不投影：点开即出现，页面不会晃动
    if (state.sheet == null) return
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(
            indication = null, interactionSource = null
        ) { state.sheet = null })
        Surface(color = palette.surface, shadowElevation = 0.dp, tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 600.dp)) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sheetTitle(state.sheet), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = { state.sheet = null }) {
                        Icon(I.Close, contentDescription = "关闭面板", tint = palette.onSurface)
                    }
                }
                Spacer(Modifier.height(8.dp))
                when (state.sheet) {
                    ReaderSheet.TOC -> TocPanel(state, palette) { state.sheet = null }
                    ReaderSheet.SEARCH -> SearchPanel(state, palette) { state.sheet = null }
                    ReaderSheet.NOTES -> NotesPanel(state, palette, onEditNote, { state.sheet = null })
                    ReaderSheet.BOOKMARKS -> BookmarksPanel(state, palette, { state.sheet = null })
                    ReaderSheet.THEME -> SettingsGroup(app, onImportFont, SettingsSection.THEME)
                    ReaderSheet.SETTINGS -> SettingsGroup(app, onImportFont, null) {
                        TypographyPanel(state, palette, onImportFont)
                    }
                    ReaderSheet.PDF_TOOLS -> PdfToolsPanel(state, palette)
                    ReaderSheet.MORE -> MorePanel(app, state, palette, tts, onOpenSettings, onOpenStats, onImportFont)
                    null -> Unit
                }
            }
        }
    }
}

/** 与书库设置页完全相同的分组卡片，放进可滚动容器以便阅读器内直接使用。 */
@Composable
private fun SettingsGroup(
    app: AppState,
    onImportFont: () -> Unit,
    section: SettingsSection?,
    bookTypography: (@Composable () -> Unit)? = null
) {
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        SettingsContent(app, onImportFont, section, bookTypography)
        Spacer(Modifier.height(8.dp))
    }
}

private fun sheetTitle(sheet: ReaderSheet?): String = when (sheet) {
    ReaderSheet.TOC -> "目录与标记"
    ReaderSheet.SEARCH -> "书内搜索"
    ReaderSheet.NOTES -> "本书笔记"
    ReaderSheet.BOOKMARKS -> "书签"
    ReaderSheet.THEME -> "主题设置"
    ReaderSheet.SETTINGS -> "阅读设置"
    ReaderSheet.PDF_TOOLS -> "页面工具"
    ReaderSheet.MORE -> "更多"
    null -> ""
}

@Composable
private fun TocPanel(state: ReaderState, palette: ReaderPalette, dismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val toc = state.toc
    val listState = rememberLazyListState()
    // 打开目录时自动定位到当前章节，千章级书籍也不用手动翻找
    LaunchedEffect(Unit) {
        val current = toc.indexOfFirst { it.chapterIndex == state.chapterIndex }
        if (current > 2) listState.scrollToItem(current - 2)
    }
    TabRow(selectedTabIndex = tab, containerColor = palette.surface) {
        listOf("目录", "书签", "笔记").forEachIndexed { i, label ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, fontSize = 13.sp) })
        }
    }
    Spacer(Modifier.height(10.dp))
    when (tab) {
        0 -> LazyColumn(state = listState, modifier = Modifier.heightIn(max = 360.dp)) {
            items(toc.size, key = { it }) { index ->
                val entry = toc[index]
                val chapter = entry.chapterIndex
                if (chapter == null) {
                    Text(entry.title, Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                        color = palette.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    val current = chapter == state.chapterIndex
                    Row(Modifier.fillMaxWidth()
                        .clickable(indication = null, interactionSource = null) { state.changeChapter(chapter, 0); state.toolbarVisible = false; dismiss() }
                        .padding(start = (entry.depth.coerceAtMost(3) * 14).dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(if (current) 4.dp else 0.dp, 20.dp)
                            .background(if (current) palette.accent else Color.Transparent,
                                RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(entry.title, color = if (current) palette.accent else palette.onSurface,
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        if (current) Text("在读", color = palette.accent, fontSize = 11.sp)
                    }
                }
            }
        }
        1 -> BookmarksPanel(state, palette, dismiss, showHeader = false)
        2 -> NotesPanel(state, palette, {}, dismiss, showHeader = false)
    }
}

@Composable
private fun BookmarksPanel(state: ReaderState, palette: ReaderPalette, dismiss: () -> Unit, showHeader: Boolean = true) {
    if (state.bookmarks.isEmpty()) {
        EmptyHint("还没有书签。阅读时点右上角书签图标即可标记。", palette)
        return
    }
    LazyColumn(Modifier.heightIn(max = if (showHeader) 360.dp else 280.dp)) {
        items(state.bookmarks, key = { it.id }) { bm ->
            Row(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = null) { state.goTo(bm.chapterIndex, bm.charOffset); state.toolbarVisible = false; dismiss() }
                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(I.Favorite, contentDescription = "书签", tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.chapterTitles.getOrElse(bm.chapterIndex) { "第 ${bm.chapterIndex + 1} 章" },
                        fontSize = 12.sp, color = palette.secondary)
                    Text(bm.preview.ifBlank { "（无预览）" }, fontSize = 13.sp, color = palette.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { state.deleteBookmarkLocal(bm.id) }) {
                    Icon(I.Delete, contentDescription = "删除书签", tint = palette.secondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun NotesPanel(
    state: ReaderState, palette: ReaderPalette, onEditNote: (HighlightEntity) -> Unit,
    dismiss: () -> Unit, showHeader: Boolean = true
) {
    val list = state.highlights.filter { it.note.isNotBlank() }
    if (list.isEmpty()) { EmptyHint("还没有笔记。长按文字选「笔记」即可写下想法。", palette); return }
    LazyColumn(Modifier.heightIn(max = if (showHeader) 360.dp else 280.dp)) {
        items(list, key = { it.id }) { item ->
            Row(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = null) { state.goTo(item.chapterIndex, item.start); state.toolbarVisible = false; dismiss() }
                .padding(vertical = 9.dp)) {
                Box(Modifier.size(4.dp, 52.dp).background(highlightColor(item.color), RoundedCornerShape(3.dp)))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("第 ${item.chapterIndex + 1} 章", fontSize = 11.sp, color = palette.secondary)
                    Text(item.note, fontSize = 13.sp, color = palette.onSurface, maxLines = 2)
                    Text("“${item.quote.take(28)}”", fontSize = 11.sp, color = palette.secondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onEditNote(item) }) {
                    Icon(I.Edit, contentDescription = "编辑笔记", tint = palette.accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun TypographyPanel(state: ReaderState, palette: ReaderPalette, onImportFont: () -> Unit) {
    var draft by remember(state.typography) { mutableStateOf(state.typography) }
    val commit = { state.updateTypography(draft) }
    Column {
        SheetLabel("字体", palette)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FONT_CHOICES.forEach { (key, label) ->
                FilterChip(selected = draft.fontKey == key, onClick = {
                    if (key == "custom") onImportFont() else { draft = draft.copy(fontKey = key); commit() }
                }, label = { Text(label.take(6), fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(12.dp))
        DraftSlider("字号", draft.fontSize, 12f..32f, "${draft.fontSize.toInt()} sp", palette,
            { draft = draft.copy(fontSize = it) }, commit)
        DraftSlider("行距", draft.lineSpacing, 1.2f..2.2f, "${"%.2f".format(draft.lineSpacing)} 倍", palette,
            { draft = draft.copy(lineSpacing = it) }, commit)
        SheetLabel("页边距", palette)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MARGIN_PRESETS.forEach { (value, label) ->
                FilterChip(selected = draft.marginDp == value, onClick = {
                    draft = draft.copy(marginDp = value); commit()
                }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        DraftSlider("精细调节", draft.marginDp, 8f..56f, "${draft.marginDp.toInt()} dp", palette,
            { draft = draft.copy(marginDp = it) }, commit)
        SheetLabel("对齐与字重", palette)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !draft.justify, onClick = { draft = draft.copy(justify = false); commit() },
                label = { Text("左对齐", fontSize = 12.sp) })
            FilterChip(selected = draft.justify, onClick = { draft = draft.copy(justify = true); commit() },
                label = { Text("两端对齐", fontSize = 12.sp) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            WEIGHT_CHOICES.forEachIndexed { index, label ->
                FilterChip(selected = draft.weight == index, onClick = { draft = draft.copy(weight = index); commit() },
                    label = { Text(label, fontSize = 12.sp) })
            }
        }
        Text("以上设置按书籍独立保存，切换书籍不会互相影响。", fontSize = 11.sp,
            color = palette.secondary, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun DraftSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueLabel: String,
    palette: ReaderPalette, onChange: (Float) -> Unit, commit: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = palette.onSurface, modifier = Modifier.width(62.dp))
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = commit,
            valueRange = range, modifier = Modifier.weight(1f))
        Text(valueLabel, fontSize = 11.sp, color = palette.secondary, modifier = Modifier.width(62.dp))
    }
}

@Composable
private fun SearchPanel(state: ReaderState, palette: ReaderPalette, dismiss: () -> Unit) {
    if (!state.isText) {
        EmptyHint("PDF / 漫画暂不支持全文检索，可在目录面板按页跳转。", palette)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = state.searchQuery, onValueChange = { state.searchQuery = it },
            singleLine = true, placeholder = { Text("输入关键词") }, modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.size(8.dp))
        Button(onClick = { state.search(state.searchQuery) }, shape = RoundedCornerShape(14.dp)) { Text("搜索") }
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${state.searchResults.size} 条结果", fontSize = 11.sp, color = palette.secondary,
            modifier = Modifier.weight(1f))
        TextButton(onClick = { state.applySearchTerm(null); state.searchQuery = ""; state.searchResults = emptyList() }) {
            Text("清除高亮", fontSize = 11.sp)
        }
    }
    if (state.searchBusy) { WaveLoading(Modifier.fillMaxWidth().height(80.dp), palette.accent); return }
    LazyColumn(Modifier.heightIn(max = 300.dp)) {
        items(state.searchResults, key = { "${it.chapterIndex}_${it.offset}" }) { hit ->
            Column(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = null) {
                state.applySearchTerm(state.searchQuery)
                state.goTo(hit.chapterIndex, hit.offset)
                dismiss()
            }.padding(vertical = 8.dp)) {
                Text(hit.chapterTitle, fontSize = 11.sp, color = palette.secondary)
                Text(hit.snippet, fontSize = 13.sp, color = palette.onSurface, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PdfToolsPanel(state: ReaderState, palette: ReaderPalette) {
    Column {
        DraftSlider("缩放", state.pdfZoom, 0.8f..2.5f, "${"%.1f".format(state.pdfZoom)}×", palette,
            { state.changePdfZoom(it) }, { })
        DraftSlider("裁边", state.pdfCrop, 0f..1f, "${(state.pdfCrop * 100).toInt()}%", palette,
            { state.changePdfCrop(it) }, { })
        Text("夜间模式使用基于亮度曲线的重映射（保留彩色插图的色相），并非简单颜色反转。",
            fontSize = 12.sp, color = palette.secondary, modifier = Modifier.padding(top = 10.dp))
        Text("双指捏合也可直接缩放页面。", fontSize = 12.sp, color = palette.secondary,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun MorePanel(
    app: AppState, state: ReaderState, palette: ReaderPalette, tts: TtsManager,
    onOpenSettings: () -> Unit, onOpenStats: () -> Unit, onImportFont: () -> Unit
) {
    Column {
        if (state.isText) {
            Button(onClick = { tts.speak(state.chapterContent.text, app.settings.ttsSpeed); state.sheet = null },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text("朗读本章（从开头）")
            }
        }
        Spacer(Modifier.height(8.dp))
        listOf(
            "主题设置" to { state.sheet = ReaderSheet.THEME },
            "阅读设置（排版 / 翻页 / 交互）" to { state.sheet = ReaderSheet.SETTINGS },
            "书内搜索" to { state.sheet = ReaderSheet.SEARCH },
            "笔记列表" to { state.sheet = ReaderSheet.NOTES },
            "页面工具（缩放 / 裁边）" to { state.sheet = ReaderSheet.PDF_TOOLS },
            "导入字体" to { onImportFont(); state.sheet = null },
            "书库设置页" to { state.sheet = null; onOpenSettings() },
            "阅读统计" to { state.sheet = null; onOpenStats() },
            "返回书库" to { state.sheet = null; app.backToLibrary() }
        ).forEach { (label, action) ->
            TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                Text(label, color = palette.onSurface, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String, palette: ReaderPalette) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.secondary,
        modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun EmptyHint(text: String, palette: ReaderPalette) {
    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = palette.secondary)
    }
}
