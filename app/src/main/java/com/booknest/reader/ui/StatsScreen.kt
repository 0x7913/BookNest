package com.booknest.reader.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.booknest.reader.state.AppState
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.max

@Composable
fun StatsScreen(app: AppState, modifier: Modifier = Modifier) {
    var confirmClear by remember { mutableStateOf(false) }
    val map = app.readingDays.associate { it.date to it.seconds }
    val today = LocalDate.now()
    val last30 = (29 downTo 0).map { today.minusDays(it.toLong()) }
    val values = last30.map { map[it.toString()] ?: 0L }
    val totalSeconds = map.values.sum()
    val activeDays = map.values.count { it > 0 }
    var streak = 0
    var cursor = today
    if ((map[cursor.toString()] ?: 0) == 0L) cursor = cursor.minusDays(1)
    while ((map[cursor.toString()] ?: 0) > 0) { streak++; cursor = cursor.minusDays(1) }
    val maxValue = max(values.maxOrNull() ?: 1L, 60L)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("阅读统计", fontSize = 27.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
        Text("只记录在这台设备上发生的事，不上传、不联网。",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("累计时长", formatDuration(totalSeconds), Modifier.weight(1f))
            StatCard("阅读天数", "$activeDays 天", Modifier.weight(1f))
            StatCard("连续阅读", "$streak 天", Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                SectionLabel("近 30 天活跃度")
                Text("颜色越深，那天读得越久", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp, bottom = 14.dp))
                Heatmap(values, maxValue, last30, MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("少", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(0.15f, 0.35f, 0.6f, 0.85f, 1f).forEach { a ->
                        Box(Modifier.padding(start = 4.dp).size(11.dp).background(
                            MaterialTheme.colorScheme.primary.copy(alpha = a), RoundedCornerShape(3.dp)))
                    }
                    Text("多", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                SectionLabel("近 7 天阅读时长")
                Spacer(Modifier.height(12.dp))
                val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
                BarChart(week.map { (map[it.toString()] ?: 0L).toFloat() },
                    week.map { it.dayOfWeek.value.let { d -> "一二三四五六日"[d - 1].toString() } },
                    MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                SectionLabel("近 6 个月阅读时长")
                Spacer(Modifier.height(12.dp))
                val months = (5 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }
                val monthValues = months.map { m ->
                    map.filterKeys { YearMonth.from(LocalDate.parse(it)) == m }.values.sum().toFloat()
                }
                BarChart(monthValues, months.map { "${it.monthValue}月" }, MaterialTheme.colorScheme.tertiary)
            }
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(bottom = 100.dp)) {
            Text("清零全部统计数据", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清零阅读统计？") },
            text = { Text("这会删除本设备上记录的所有阅读时长，书籍、进度、笔记与书签不会受到影响。") },
            confirmButton = { TextButton(onClick = { app.clearStats(); confirmClear = false }) { Text("确认清零") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f))
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun Heatmap(values: List<Long>, maxValue: Long, dates: List<LocalDate>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until 10) {
                    val index = row * 10 + col
                    val v = values.getOrNull(index) ?: 0L
                    val alpha = if (v <= 0) 0.08f else (0.2f + 0.8f * (v.toFloat() / maxValue)).coerceIn(0.2f, 1f)
                    Box(Modifier.weight(1f).height(26.dp).background(color.copy(alpha = alpha), RoundedCornerShape(6.dp))
                        .padding(0.dp), contentAlignment = Alignment.Center) {
                        Text("${dates.getOrNull(index)?.dayOfMonth ?: ""}", fontSize = 9.sp,
                            color = if (alpha > 0.55f) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BarChart(values: List<Float>, labels: List<String>, color: Color) {
    val max = max(values.maxOrNull() ?: 1f, 60f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .18f)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val left = 8.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val top = 8.dp.toPx()
        val usable = bottom - top
        drawLine(gridColor, Offset(left, bottom), Offset(size.width, bottom), 1.dp.toPx())
        val slot = (size.width - left) / values.size.coerceAtLeast(1)
        val barWidth = (slot * 0.48f).coerceAtMost(38.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = (v / max * usable).coerceIn(if (v > 0) 4.dp.toPx() else 0f, usable)
            val x = left + slot * i + (slot - barWidth) / 2
            drawRect(
                color = color.copy(alpha = if (v > 0) .92f else .15f),
                topLeft = Offset(x, bottom - h), size = Size(barWidth, h)
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
        labels.forEach { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
