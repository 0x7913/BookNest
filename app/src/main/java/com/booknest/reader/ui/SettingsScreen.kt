package com.booknest.reader.ui

import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.booknest.reader.data.PageMode
import com.booknest.reader.data.ThemeId
import com.booknest.reader.core.readerPalette
import com.booknest.reader.state.AppState

private val SEED_COLORS = listOf(
    Color(0xFF8A6A4F), Color(0xFF3C6E63), Color(0xFF52677A),
    Color(0xFF8A5D76), Color(0xFF8C6A3C), Color(0xFF5A5F8A)
)

/** 阅读器里可只展示某一组设置（主题 / 排版 / 翻页），内容与书库设置页完全共用。 */
enum class SettingsSection { THEME, TYPOGRAPHY, PAGING }

@Composable
fun SettingsScreen(app: AppState, onImportFont: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("设置", fontSize = 27.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 22.dp, bottom = 18.dp))
        SettingsContent(app, onImportFont)
    }
}

/**
 * 统一设置内容：书库「设置」页与阅读器内面板共用同一套 M3 GroupCard / 控件，
 * 因此主题、排版、翻页在两处的视觉与交互完全一致。
 */
@Composable
fun SettingsContent(
    app: AppState,
    onImportFont: () -> Unit,
    section: SettingsSection? = null,
    bookTypography: (@Composable () -> Unit)? = null
) {
    val s = app.settings
    if (section == null || section == SettingsSection.THEME) {
        GroupCard("主题与色彩") {
            SettingSwitch("跟随系统深色模式", s.followSystemDark) { app.setFollowSystem(it) }
            Text("纸感阅读底色（书库与阅读页同步）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeId.entries.toList().chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { theme ->
                            val p = theme.readerPalette()
                            Card(
                                onClick = { app.setTheme(theme); app.setFollowSystem(false) },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = p.background),
                                modifier = Modifier.weight(1f).height(74.dp)
                            ) {
                                Box(Modifier.fillMaxSize().background(
                                    if (s.themeId == theme) Brush.linearGradient(
                                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = .16f), Color.Transparent)
                                    ) else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))) {
                                    Column(Modifier.padding(11.dp)) {
                                        Text("水", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                        Text(theme.label, color = p.secondary, fontSize = 11.sp,
                                            modifier = Modifier.padding(top = 3.dp))
                                        Box(Modifier.padding(top = 6.dp).size(24.dp, 2.dp).background(p.accent))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SettingSwitch("动态取色（Android 12+ 跟随壁纸）",
                s.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { app.setDynamicColor(it) }
            if (!s.dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text("低版本手动种子色", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    SEED_COLORS.forEach { c ->
                        ColorDot(c, s.seedColor == c.toArgb(), "主题色", { app.setSeedColor(c.toArgb()) }, 34.dp)
                    }
                }
            }
        }
    }

    if (section == null || section == SettingsSection.TYPOGRAPHY) {
        GroupCard("阅读排版") {
            Text("段落间距  ${"%.2f".format(s.paragraphSpacing)} 倍行高",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("默认 0：段落之间只换一行，跟静读天下一样紧凑；调大才会出现段间留白。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
            Slider(value = s.paragraphSpacing, onValueChange = { app.setParagraphSpacing(it) },
                valueRange = 0f..1.2f)
            SettingSwitch("段落首行缩进两字符", s.firstLineIndent) { app.setFirstLineIndent(it) }
            SettingSwitch("显示书中插图（EPUB 内嵌图片）", s.showInlineImages) { app.setShowInlineImages(it) }
            Text("阅读进度位置", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !s.progressOnRight, onClick = { app.setProgressOnRight(false) },
                    label = { Text("左下角", fontSize = 12.sp) })
                FilterChip(selected = s.progressOnRight, onClick = { app.setProgressOnRight(true) },
                    label = { Text("右下角", fontSize = 12.sp) })
            }
            if (bookTypography != null) {
                Spacer(Modifier.height(14.dp))
                Text("本书独立排版", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp))
                bookTypography()
            }
        }
    }

    if (section == null || section == SettingsSection.PAGING) {
        GroupCard("翻页与交互") {
            Text("全局翻页模式（下次打开自动恢复）", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                PageMode.entries.chunked(2).forEach { group ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { mode ->
                            FilterChip(selected = s.pageMode == mode, onClick = { app.setPageMode(mode) },
                                label = { Text(mode.label, fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Text("翻页灵敏度  ${"%.1f".format(s.turnSensitivity)}×（越大越容易翻过）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
            Slider(value = s.turnSensitivity, onValueChange = { app.setTurnSensitivity(it) },
                valueRange = 0.6f..2f)
            SettingSwitch("音量键翻页", s.volumeKeys) { app.setVolumeKeys(it) }
            SettingSwitch("阅读时保持屏幕常亮", s.keepScreenOn) { app.setKeepScreenOn(it) }
            SettingSwitch("低端设备将仿真翻页降级为覆盖翻页", s.degradeSimulation) { app.setDegrade(it) }
            Text("朗读语速  ${"%.1f".format(s.ttsSpeed)}×", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            Slider(value = s.ttsSpeed, onValueChange = { app.setTtsSpeed(it) }, valueRange = 0.5f..2f)
        }
    }

    if (section == null) {
        GroupCard("字体与数据") {
            OutlinedButton(onClick = onImportFont, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)) { Text("导入 .ttf / .otf 字体") }
            Text("导入后可在阅读器的「设置 → 本书独立排版」中按书籍单独选用。", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
            Text("隐私承诺", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("· 应用未申请任何网络权限，全部功能离线可用\n· 书籍、进度、高亮、笔记、统计仅存放在本机私有目录\n· 不收集数据、不含广告、不需要账号\n· 卸载应用即清除全部数据",
                fontSize = 12.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
        Text("BookNest 1.0.0  ·  纯本地阅读器", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 100.dp))
    }
}

@Composable
fun GroupCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(18.dp)) {
            SectionLabel(title)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
