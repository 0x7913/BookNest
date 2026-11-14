package com.booknest.reader.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object I {
    val Back = Icons.Default.KeyboardArrowLeft
    val Forward = Icons.Default.KeyboardArrowRight
    val Up = Icons.Default.KeyboardArrowUp
    val Down = Icons.Default.KeyboardArrowDown
    val Add = Icons.Default.Add
    val Search = Icons.Default.Search
    val Settings = Icons.Default.Settings
    val Close = Icons.Default.Close
    val Check = Icons.Default.Check
    val Delete = Icons.Default.Delete
    val Edit = Icons.Default.Edit
    val More = Icons.Default.MoreVert
    val List = Icons.Default.List
    val Home = Icons.Default.Home
    val Info = Icons.Default.Info
    val Play = Icons.Default.PlayArrow
    val Star = Icons.Default.Star
    val Favorite = Icons.Default.Favorite
    val FavoriteBorder = Icons.Default.FavoriteBorder
    val Refresh = Icons.Default.Refresh
}

/** 形变波浪加载指示器，替代默认圆形进度条。 */
@Composable
fun WaveLoading(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "wave")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { index ->
                val h by transition.animateFloat(
                    initialValue = 8f,
                    targetValue = 34f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(520, delayMillis = index * 85),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "wave_$index"
                )
                Box(
                    Modifier.size(7.dp, h.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .55f + index * .08f))
                )
            }
        }
        Text("正在铺开纸页…", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 空态装饰插画（非纯文字）：纸感书脊 + 柔和光晕 + 漂浮圆点。 */
@Composable
fun EmptyLibraryIllustration(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(224.dp).background(
            Brush.radialGradient(listOf(Color(0xFFE9DCC6), Color(0x00E9DCC6))), CircleShape))
        Box(Modifier.offset(x = (-30).dp, y = 8.dp).size(96.dp, 132.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFB28C64), Color(0xFF7E5C3E)))))
        Box(Modifier.offset(x = 32.dp, y = (-8).dp).size(94.dp, 136.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF6F8A7D), Color(0xFF344C45))))) {
            Text("静\n读", Modifier.padding(14.dp), color = Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
        }
        Box(Modifier.offset(x = 74.dp, y = (-80).dp).size(14.dp).background(Color(0xFFD7A46B), CircleShape))
        Box(Modifier.offset(x = (-78).dp, y = 74.dp).size(9.dp).background(Color(0xFF8FA898), CircleShape))
    }
}

/** 封面：EPUB 内嵌 / PDF 首页 / CBZ 首图，缺失时用主题色占位卡。 */
@Composable
fun BookCover(
    coverPath: String?,
    title: String,
    format: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val bitmap = remember(coverPath) {
        coverPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = "《$title》封面", modifier = modifier.clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop)
    } else {
        val pair = when (format) {
            "PDF" -> listOf(Color(0xFF8B6F6A), Color(0xFF4B3937))
            "CBZ" -> listOf(Color(0xFF7A6C99), Color(0xFF3F3557))
            "MOBI", "AZW3" -> listOf(Color(0xFF8A8272), Color(0xFF4A453B))
            else -> listOf(Color(0xFF6C8275), Color(0xFF344C44))
        }
        Box(modifier.clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(pair)),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(14.dp)) {
                Text(title.take(14), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    lineHeight = 25.sp, maxLines = 3, textAlign = TextAlign.Center)
                Box(Modifier.padding(top = 14.dp).width(28.dp).height(1.dp).background(Color.White.copy(.65f)))
                Text(format, Modifier.padding(top = 10.dp), color = Color.White.copy(.65f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
}

@Composable
fun ColorDot(color: Color, selected: Boolean, description: String, onClick: () -> Unit, size: Dp = 30.dp) {
    Surface(onClick = onClick, shape = CircleShape,
        color = color, modifier = Modifier.size(size).semantics { contentDescription = description }) {
        if (selected) Icon(I.Check, contentDescription = null,
            tint = if (color.red + color.green + color.blue > 1.6f) Color(0xFF212121) else Color.White,
            modifier = Modifier.size(size * 0.55f).padding(0.dp))
    }
}

/** 细线进度条（自定义实现，避免依赖 M3 进度指示器的版本差异）。 */
@Composable
fun ThinProgress(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(6.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).clip(RoundedCornerShape(6.dp)).background(color))
    }
}
