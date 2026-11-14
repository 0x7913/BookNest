package com.booknest.reader.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PointF
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.booknest.reader.core.HIGHLIGHT_COLORS
import com.booknest.reader.core.ReaderPalette
import com.booknest.reader.core.ensureContrast
import com.booknest.reader.core.highlightColor
import com.booknest.reader.core.readerPalette
import com.booknest.reader.data.BookEntity
import com.booknest.reader.data.HighlightEntity
import com.booknest.reader.data.PageMode
import com.booknest.reader.data.ThemeId
import com.booknest.reader.reader.PageSlice
import com.booknest.reader.reader.TtsManager
import com.booknest.reader.reader.localTranslate
import com.booknest.reader.state.AppState
import com.booknest.reader.state.ReaderSheet
import com.booknest.reader.state.ReaderState
import com.booknest.reader.state.ReaderStatus
import com.booknest.reader.state.Selection
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    app: AppState,
    book: BookEntity,
    tts: TtsManager,
    systemDark: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    onImportFont: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val state = remember(book.id) { ReaderState(book, app.repo, context, scope, app.settings) }

    val themeId = if (app.settings.followSystemDark) {
        if (systemDark) ThemeId.NIGHT_GRAY else ThemeId.DAY_CREAM
    } else app.settings.themeId
    val basePalette = themeId.readerPalette()
    val palette = basePalette.copy(text = ensureContrast(basePalette.text, basePalette.background))
    val density = LocalDensity.current
    // 正文避开状态栏 / 挖孔区域：背景仍然铺满全屏，不会有可见的「空隙色带」
    val topSafePx = remember(density) {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val bar = if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        bar + with(density) { 10.dp.toPx() }.toInt()
    }
    val bottomSafePx = with(density) { 14.dp.toPx() }.toInt()
    // 面板与书库设置页共用同一套 M3 配色，主题切换后视觉保持一致
    val scheme = MaterialTheme.colorScheme
    val panelPalette = remember(scheme) {
        ReaderPalette(
            background = scheme.surface, text = scheme.onSurface,
            secondary = scheme.onSurfaceVariant, surface = scheme.surface,
            onSurface = scheme.onSurface, accent = scheme.primary,
            pageEdge = scheme.surfaceVariant
        )
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var highlightEditor by remember { mutableStateOf<HighlightEntity?>(null) }
    var noteDialog by remember { mutableStateOf<HighlightEntity?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var translationText by remember { mutableStateOf<String?>(null) }

    // 返回键按「最上层浮层 → 工具栏 → 书库」逐层消费，不再直接退出 Activity 回桌面
    BackHandler {
        when {
            translationText != null -> translationText = null
            noteDialog != null -> noteDialog = null
            highlightEditor != null -> highlightEditor = null
            state.selection != null -> state.selection = null
            state.sheet != null -> state.sheet = null
            state.toolbarVisible -> state.toolbarVisible = false
            else -> onBack()
        }
    }

    LaunchedEffect(themeId) { state.updateEnvironment(palette, themeId.isNight) }
    LaunchedEffect(app.settings) { state.updateSettings(app.settings) }
    LaunchedEffect(app.settings.volumeKeys, state) {
        app.volumeKeyHandler = { keyCode ->
            if (app.settings.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
                state.requestTurn(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                true
            } else false
        }
    }
    LaunchedEffect(state) {
        app.fontImportedHandler = { path ->
            state.updateTypography(state.typography.copy(fontKey = "custom", customFontPath = path))
            app.showToast("字体已应用到当前书籍")
        }
    }
    LaunchedEffect(state.toolbarVisible) { app.immersive = !state.toolbarVisible }
    LaunchedEffect(state.status, app.pendingJump) {
        val jump = app.pendingJump
        if (state.status == ReaderStatus.READY && jump != null) {
            state.goTo(jump.first, jump.second)
            app.pendingJump = null
        }
    }
    // 章节切换由 ReaderState.changeChapter 内部一次性重排；这里只负责视口尺寸与标注/搜索变化，
    // 避免同一状态被两条路径重复 recompute 造成页面跳动、位置丢失。
    LaunchedEffect(state.status, viewport.width, viewport.height) {
        if (state.status == ReaderStatus.READY) state.setViewport(viewport.width, viewport.height)
    }
    LaunchedEffect(state.highlights, state.activeSearchTerm) {
        if (state.status == ReaderStatus.READY) state.refreshLayout()
    }
    LaunchedEffect(Unit) {
        while (isActive) { delay(30_000); app.repo.addReadingSeconds(30) }
    }
    DisposableEffect(book.id) {
        onDispose {
            state.close()
            tts.stop()
            app.volumeKeyHandler = { false }
            app.fontImportedHandler = {}
            app.immersive = false
        }
    }

    val lowRam = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(info) }
        (am?.isLowRamDevice == true) || (info.totalMem in 1..(3L * 1024 * 1024 * 1024))
    }
    val effectiveMode = if (app.settings.pageMode == PageMode.SIMULATION && lowRam && app.settings.degradeSimulation)
        PageMode.COVER else app.settings.pageMode

    Box(modifier.fillMaxSize().background(palette.background)) {
        // 正文只在安全区内排版：状态栏 / 挖孔区不压首行，背景仍铺满整屏、不出现色带
        Box(Modifier.fillMaxSize()
            .padding(
                top = with(density) { topSafePx.toDp() },
                bottom = with(density) { bottomSafePx.toDp() }
            )
            .onSizeChanged { viewport = it; state.setViewport(it.width, it.height) }) {

        when (state.status) {
            ReaderStatus.LOADING -> WaveLoading(Modifier.fillMaxSize(), palette.accent)
            ReaderStatus.ERROR -> Column(Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("这本书暂时打不开", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Text(state.errorMessage ?: "", Modifier.padding(top = 12.dp), color = palette.secondary,
                    textAlign = TextAlign.Center)
                TextButton(onClick = onBack) { Text("返回书库") }
            }
            ReaderStatus.READY -> {
                if (state.isText) {
                    TextBody(app, state, palette, effectiveMode, viewport, onLongPressSelection = { local, page ->
                        val text = state.chapterContent.text
                        val offset = state.layout?.offsetAt(local.x, local.y, page) ?: page.start
                        val (s, e) = expandSelection(text, offset)
                        val screenX = (viewport.width - state.pageContentWidth()) / 2f + local.x
                        val screenY = topSafePx +
                            (viewport.height - state.pageContentHeight()) / 2f + local.y
                        state.selection = Selection(state.chapterIndex, page, s, e, screenX, screenY)
                        state.toolbarVisible = false
                    }, onTap = { local, page ->
                        if (state.selection != null) {
                            state.selection = null
                        } else {
                            val chapterOffset = state.layout?.offsetAt(local.x, local.y, page) ?: page.start
                            val hit = state.highlightAt(chapterOffset)
                            if (hit != null) {
                                highlightEditor = hit
                            } else {
                                val width = state.pageContentWidth()
                                when {
                                    local.x < width * 0.28f -> state.requestTurn(true)
                                    local.x > width * 0.72f -> state.requestTurn(false)
                                    else -> state.toolbarVisible = !state.toolbarVisible
                                }
                            }
                        }
                    })
                } else {
                    PagedBody(state, palette)
                }
            }
        }
        }

        // 工具栏与面板都不再铺任何渐变压暗层：面板点开即出现，页面不会变暗、不会晃动

        // 顶部标题栏：背景铺满状态栏区域，内容避开状态栏图标，不留可见空隙
        AnimatedVisibility(
            visible = state.toolbarVisible && state.status == ReaderStatus.READY,
            enter = fadeIn(tween(110)), exit = fadeOut(tween(90)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(state, palette, onBack)
        }

        // 底部工具栏：纯淡入淡出，无滑移、无弹跳、无投影
        AnimatedVisibility(
            visible = state.toolbarVisible && state.status == ReaderStatus.READY,
            enter = fadeIn(tween(110)), exit = fadeOut(tween(90)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderToolbar(state, app, palette, onOpenSettings, onOpenStats, onImportFont)
        }

        // 进度改为角落百分比，常驻且不遮挡正文
        if (state.status == ReaderStatus.READY) {
            CornerProgress(
                app, state, palette, bottomSafePx,
                Modifier.align(if (app.settings.progressOnRight) Alignment.BottomEnd else Alignment.BottomStart)
            )
        }

        // 长按选中浮动菜单
        state.selection?.let { sel ->
            SelectionMenu(
                state = state, selection = sel, palette = palette, tts = tts,
                onCopy = { clipboard.setText(AnnotatedString(state.chapterContent.text.substring(
                    sel.start.coerceAtLeast(0), sel.end.coerceAtMost(state.chapterContent.text.length)))) },
                onTranslate = { translationText = localTranslate(state.chapterContent.text.substring(
                    sel.start.coerceAtLeast(0), sel.end.coerceAtMost(state.chapterContent.text.length))) },
                onNote = {
                    val entity = HighlightEntity(
                        bookId = book.id, chapterIndex = state.chapterIndex, start = sel.start, end = sel.end,
                        color = 0, quote = state.chapterContent.text.substring(
                            sel.start.coerceAtLeast(0), sel.end.coerceAtMost(state.chapterContent.text.length))
                    )
                    noteDraft = ""
                    noteDialog = entity.copy(id = -1L)
                    state.selection = null
                },
                onNarrow = { state.selection = sel.copy(start = (sel.start + 1).coerceAtMost(sel.end - 1),
                    end = (sel.end - 1).coerceAtLeast(sel.start + 1)) },
                onWide = {
                    val len = state.chapterContent.text.length
                    state.selection = sel.copy(start = (sel.start - 3).coerceAtLeast(0),
                        end = (sel.end + 3).coerceAtMost(len))
                },
                onDismiss = { state.selection = null }
            )
        }

        // 点击已有高亮：改色 / 删除 / 加笔记
        highlightEditor?.let { entity ->
            HighlightEditor(entity, palette,
                onColor = { state.changeHighlightColor(entity, it); highlightEditor = null },
                onDelete = { state.deleteHighlight(entity); highlightEditor = null },
                onNote = { noteDraft = entity.note; noteDialog = entity; highlightEditor = null },
                onDismiss = { highlightEditor = null })
        }

        // 底部面板
        ReaderSheetHost(
            app = app, state = state, palette = panelPalette, tts = tts,
            onImportFont = onImportFont, onOpenSettings = onOpenSettings, onOpenStats = onOpenStats,
            onEditNote = { entity -> noteDraft = entity.note; noteDialog = entity }
        )
    }

    if (noteDialog != null) {
        val target = noteDialog!!
        AlertDialog(
            onDismissRequest = { noteDialog = null },
            title = { Text("写一条笔记") },
            text = {
                Column {
                    Text("“${target.quote.take(60)}”", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = noteDraft, onValueChange = { noteDraft = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp), minLines = 3,
                        label = { Text("想法") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (target.id == -1L) {
                        app.addNoteForQuote(book.id, target.copy(note = noteDraft))
                    } else state.saveNote(target, noteDraft)
                    noteDialog = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { noteDialog = null }) { Text("取消") } }
        )
    }
    if (translationText != null) {
        AlertDialog(onDismissRequest = { translationText = null },
            title = { Text("划词翻译 · 本地词库") },
            text = { Text(translationText!!) },
            confirmButton = { TextButton(onClick = { translationText = null }) { Text("知道了") } })
    }
}

// ---------------------------------------------------------------------------
// 正文渲染：四种翻页模式共用同一个「不透明页面 + 精确裁剪」渲染器
// ---------------------------------------------------------------------------

private typealias TapHandler = (Offset, PageSlice) -> Unit
private typealias Progress = Animatable<Float, AnimationVector1D>

/** 屏幕坐标 → 页面内容区坐标（PageCanvas 以内容区左上角为原点）。 */
private fun Offset.toPageLocal(state: ReaderState, viewport: IntSize): Offset = Offset(
    x - (viewport.width - state.pageContentWidth()) / 2f,
    y - (viewport.height - state.pageContentHeight()) / 2f
)

/** 卷起纸背的哑光纸色：纯色，不带任何投影 / 渐变阴影。 */
private fun paperBackColor(palette: ReaderPalette): Color =
    if (palette.background.red < 0.5f) Color(0xFF3C4148) else Color(0xFFEBE0CB)

/** 二维卷曲几何：折痕上下端 x 不同 → 斜向卷起；卷边也随之倾斜，跟随手指 y。 */
private data class CurlGeometry(
    val foldTop: Float,
    val foldBottom: Float,
    val edgeTop: Float,
    val edgeBottom: Float,
    val bulge: Float,
    val forward: Boolean,
    val width: Float,
    val height: Float
) {
    val creaseCtrl: Float get() = (foldTop + foldBottom) * 0.5f - bulge
    val edgeCtrl: Float get() = (edgeTop + edgeBottom) * 0.5f - bulge * 0.55f
    fun fx(v: Float): Float = if (forward) v else width - v
}

private fun curlGeometry(
    width: Float, height: Float, p: Float, tilt: Float, forward: Boolean
): CurlGeometry {
    val curlW = (width * p * 0.94f).coerceAtLeast(0f)
    val k = tilt.coerceIn(-0.6f, 0.6f)
    // 折痕上下端 x 差由「抓在页面哪一头」决定，形成斜向卷轴而不是竖直平移
    val foldTop = (width - curlW * (1f - k)).coerceIn(0f, width)
    val foldBottom = (width - curlW * (1f + k)).coerceIn(0f, width)
    // 纸背宽度：p=0 时为零，p=1 时整页已经翻过去，同样收成一条纸边。
    // 中间最宽 → 视觉上是一个被卷起来的筒，而不是一条等宽飘带。
    val swell = sin((p * PI).toFloat()).coerceIn(0f, 1f)
    val back = curlW * 0.68f * swell
    // 掀起的那一头卷得宽，另一头收成尖角：锥形卷页的关键
    val topFactor = if (k >= 0f) 0.22f else 0.95f
    val bottomFactor = if (k >= 0f) 0.95f else 0.22f
    val edgeTop = (foldTop - back * topFactor).coerceAtLeast(0f)
    val edgeBottom = (foldBottom - back * bottomFactor).coerceAtLeast(0f)
    return CurlGeometry(
        foldTop, foldBottom, edgeTop, edgeBottom,
        curlW * 0.30f * swell, forward, width, height
    )
}

/** 未卷起的正面：折痕的贝塞尔弧线一直延伸到上下边，不是竖直硬切。 */
private fun frontPath(g: CurlGeometry): AndroidPath = AndroidPath().apply {
    moveTo(g.fx(0f), 0f)
    lineTo(g.fx(g.foldTop), 0f)
    quadTo(g.fx(g.creaseCtrl), g.height * 0.5f, g.fx(g.foldBottom), g.height)
    lineTo(g.fx(0f), g.height)
    close()
}

/** 卷起纸背：折痕与卷边之间的月牙，两端自然收窄，模拟圆柱卷页。 */
private fun backPath(g: CurlGeometry): AndroidPath = AndroidPath().apply {
    moveTo(g.fx(g.foldTop), 0f)
    quadTo(g.fx(g.creaseCtrl), g.height * 0.5f, g.fx(g.foldBottom), g.height)
    lineTo(g.fx(g.edgeBottom), g.height)
    quadTo(g.fx(g.edgeCtrl), g.height * 0.5f, g.fx(g.edgeTop), 0f)
    close()
}

private fun mirror(p: PointF, a: PointF, b: PointF): PointF {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = (dx * dx + dy * dy).coerceAtLeast(1f)
    val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
    return PointF(2f * (a.x + t * dx) - p.x, 2f * (a.y + t * dy) - p.y)
}

/** 沿斜折痕做真正的 2D 镜像（不是水平翻转 / rotationY），纸背才像被掀起的一角。 */
private fun reflectionMatrix(g: CurlGeometry): Matrix {
    val a = PointF(g.fx(g.foldTop), 0f)
    val b = PointF(g.fx(g.foldBottom), g.height)
    val points = arrayOf(PointF(0f, 0f), PointF(g.width, 0f), PointF(0f, g.height))
    val src = FloatArray(6)
    val dst = FloatArray(6)
    points.forEachIndexed { i, point ->
        src[i * 2] = point.x
        src[i * 2 + 1] = point.y
        val m = mirror(point, a, b)
        dst[i * 2] = m.x
        dst[i * 2 + 1] = m.y
    }
    return Matrix().apply { setPolyToPoly(src, 0, dst, 0, 3) }
}

/** 在任意 Canvas / 任意裁剪下画一页（背景 + 本页切片）。 */
private fun paintPage(
    canvas: AndroidCanvas,
    layout: android.text.StaticLayout,
    page: PageSlice,
    offsetX: Float,
    offsetY: Float,
    widthPx: Int,
    heightPx: Int,
    bg: Int
) {
    canvas.drawColor(bg)
    canvas.save()
    canvas.clipRect(
        offsetX, offsetY, offsetX + widthPx,
        offsetY + (page.bottom - page.top).coerceAtMost(heightPx)
    )
    canvas.translate(offsetX, offsetY - page.top.toFloat())
    layout.draw(canvas)
    canvas.restore()
}

/** 章边界没有目标页时，底层用轻量文字预告，不弹加载动画、不画阴影。 */
private fun drawBridge(
    canvas: AndroidCanvas, w: Float, h: Float, palette: ReaderPalette,
    title: String, label: String, centerX: Float
) {
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.secondary.toArgb(); textAlign = Paint.Align.CENTER; textSize = 32f
    }
    val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.onSurface.toArgb(); textAlign = Paint.Align.CENTER; textSize = 44f
    }
    canvas.drawText(label, centerX, h / 2f - 28f, small)
    canvas.drawText(title.take(16), centerX, h / 2f + 40f, big)
}

/** 只画 1px 纸边线，用于区分层次；不画任何投影、暗带、渐变阴影。 */
private fun drawCurlEdges(canvas: AndroidCanvas, g: CurlGeometry, color: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.6f
    }
    canvas.drawPath(AndroidPath().apply {
        moveTo(g.fx(g.foldTop), 0f)
        quadTo(g.fx(g.creaseCtrl), g.height * 0.5f, g.fx(g.foldBottom), g.height)
    }, paint)
    canvas.drawPath(AndroidPath().apply {
        moveTo(g.fx(g.edgeTop), 0f)
        quadTo(g.fx(g.edgeCtrl), g.height * 0.5f, g.fx(g.edgeBottom), g.height)
    }, paint)
}

@Composable
private fun TextBody(
    app: AppState,
    state: ReaderState,
    palette: ReaderPalette,
    mode: PageMode,
    viewport: IntSize,
    onTap: TapHandler,
    onLongPressSelection: TapHandler
) {
    val pages = state.layout?.pages
    if (pages.isNullOrEmpty()) { WaveLoading(Modifier.fillMaxSize(), palette.accent); return }
    when (mode) {
        PageMode.SLIDE -> SlidePages(state, pages, palette, onTap, onLongPressSelection)
        PageMode.COVER -> CoverPages(state, pages, palette, viewport, onTap, onLongPressSelection)
        PageMode.SIMULATION -> SimulationPages(state, pages, palette, viewport, onTap, onLongPressSelection)
        PageMode.SCROLL -> ScrollPages(state, pages, palette, onTap, onLongPressSelection)
    }
}

/**
 * 滑动翻页：分页器在章首/章末各挂一张「相邻章桥接页」，
 * 滑过边界即触发切章，因此章末继续滑动不会再卡住。
 */
@Composable
private fun SlidePages(
    state: ReaderState, pages: List<PageSlice>, palette: ReaderPalette,
    onTap: TapHandler, onLongPressSelection: TapHandler
) {
    key(state.chapterIndex, state.chapterSerial) {
        val hasPrev = state.hasPreviousChapter
        val hasNext = state.hasNextChapter
        val offset = if (hasPrev) 1 else 0
        val count = pages.size + offset + if (hasNext) 1 else 0
        var bridgeRequested by remember { mutableStateOf(false) }
        val pagerState = rememberPagerState(
            initialPage = (state.pageIndex + offset).coerceIn(0, (count - 1).coerceAtLeast(0))
        ) { count }

        LaunchedEffect(state.pageIndex, state.turnSerial) {
            val target = (state.pageIndex + offset).coerceIn(0, (count - 1).coerceAtLeast(0))
            if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
        }
        LaunchedEffect(Unit) {
            snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
                .collect { (scrolling, page) ->
                    if (scrolling) return@collect
                    when {
                        hasPrev && page < offset -> {
                            if (!bridgeRequested) { bridgeRequested = true; state.previousChapter() }
                        }
                        hasNext && page >= offset + pages.size -> {
                            if (!bridgeRequested) { bridgeRequested = true; state.nextChapter() }
                        }
                        page in offset until (offset + pages.size) -> {
                            if (page - offset != state.pageIndex) state.updatePageIndex(page - offset)
                        }
                    }
                }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(),
            key = { state.chapterIndex * 100000 + it }) { index ->
            when {
                hasPrev && index == 0 -> ChapterBridge(state, palette, back = true)
                hasNext && index == offset + pages.size -> ChapterBridge(state, palette, back = false)
                else -> {
                    val page = pages.getOrNull(index - offset) ?: return@HorizontalPager
                    PageBox(state, page, palette, onTap, onLongPressSelection)
                }
            }
        }
    }
}

/** 章节边界页：既是可达性提示，也是切章期间的加载态。 */
@Composable
private fun ChapterBridge(state: ReaderState, palette: ReaderPalette, back: Boolean) {
    Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp)) {
            Text(if (back) "上一章" else "下一章", fontSize = 12.sp, color = palette.secondary)
            Spacer(Modifier.height(14.dp))
            if (state.chapterLoading) {
                WaveLoading(Modifier.size(76.dp), palette.accent)
            } else {
                Text(if (back) state.previousChapterTitle else state.nextChapterTitle,
                    color = palette.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 3)
                Spacer(Modifier.height(16.dp))
                Text("继续滑动进入", fontSize = 12.sp, color = palette.secondary)
            }
        }
    }
}

/**
 * 覆盖翻页：底层目标页静止，上层当前页跟手水平滑出。
 * 小幅度滑动 / 快速轻扫都能翻过；全程没有投影、暗带或渐变阴影。
 */
@Composable
private fun CoverPages(
    state: ReaderState, pages: List<PageSlice>, palette: ReaderPalette, viewport: IntSize,
    onTap: TapHandler, onLongPressSelection: TapHandler
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sensitivity = state.turnSensitivity.coerceIn(0.6f, 2f)
    val progress = remember { Animatable(0f) }
    var goingBack by remember { mutableStateOf(false) }
    var dragX by remember { mutableStateOf(0f) }
    var startedAt by remember { mutableStateOf(0L) }
    val width = viewport.width.toFloat().coerceAtLeast(1f)
    var turnJob by remember { mutableStateOf<Job?>(null) }
    fun startTurn(body: suspend () -> Unit) {
        turnJob?.cancel()
        turnJob = scope.launch { body() }
    }

    DisposableEffect(state) {
        state.turnAnimator = { back ->
            goingBack = back
            startTurn {
                progress.animateTo(1f, tween(170))
                if (back) state.previousPage() else state.nextPage()
                progress.snapTo(0f)
            }
        }
        onDispose { state.turnAnimator = null; turnJob?.cancel() }
    }

    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { screen ->
                    val page = state.currentPage ?: return@detectTapGestures
                    onTap(screen.toPageLocal(state, viewport), page)
                },
                onLongPress = { screen ->
                    // 已经在拖动的指针不算长按：否则慢速划动会误弹出选词菜单
                    if (dragX.absoluteValue > 12f) return@detectTapGestures
                    val page = state.currentPage ?: return@detectTapGestures
                    onLongPressSelection(screen.toPageLocal(state, viewport), page)
                }
            )
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { turnJob?.cancel(); dragX = 0f; startedAt = System.currentTimeMillis() },
                onDragEnd = {
                    val elapsed = System.currentTimeMillis() - startedAt
                    val absX = dragX.absoluteValue
                    val threshold = with(density) { 20.dp.toPx() } / sensitivity
                    val quick = absX > with(density) { 10.dp.toPx() } && elapsed < 240
                    val commit = absX > threshold || quick
                    val back = goingBack
                    startTurn {
                        if (commit) {
                            progress.animateTo(1f, tween(150))
                            if (back) state.previousPage() else state.nextPage()
                            progress.snapTo(0f)
                        } else progress.animateTo(0f, tween(120))
                    }
                    dragX = 0f
                },
                onDragCancel = {
                    startTurn { progress.animateTo(0f, tween(110)) }
                    dragX = 0f
                }
            ) { _, delta ->
                dragX += delta
                if (dragX.absoluteValue > 8f) goingBack = dragX > 0f
                val full = width * 0.78f / sensitivity
                val target = (dragX.absoluteValue / full).coerceIn(0f, 1f)
                scope.launch { progress.snapTo(target) }
            }
        }) {
        val current = pages.getOrNull(state.pageIndex) ?: return@Box
        val forward = !goingBack
        val nextPage = if (forward) pages.getOrNull(state.pageIndex + 1)
        else pages.getOrNull(state.pageIndex - 1)
        val basePage = if (forward) nextPage else current
        val topPage = if (forward) current else nextPage
        val p = progress.value
        val topX = if (forward) -p * width else -(1f - p) * width

        if (basePage != null) {
            PageBox(state, basePage, palette, onTap, onLongPressSelection, interactive = false)
        } else if (forward && state.hasNextChapter) {
            ChapterBridge(state, palette, back = false)
        } else {
            Box(Modifier.fillMaxSize().background(palette.pageEdge))
        }

        if (topPage != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = topX }) {
                PageBox(state, topPage, palette, onTap, onLongPressSelection, interactive = false)
            }
        } else if (!forward && state.hasPreviousChapter) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = topX }) {
                ChapterBridge(state, palette, back = true)
            }
        }

        // 仅 1px 纸边线区分上下两层，没有任何阴影
        Canvas(Modifier.fillMaxSize()) {
            val edge = size.width + topX
            if (p > 0.001f && edge > 0f && edge < size.width) {
                drawLine(palette.pageEdge, Offset(edge, 0f), Offset(edge, size.height),
                    strokeWidth = 1.5f)
            }
        }
    }
}

/**
 * 仿真翻页（iBooks 式二维卷曲）：卷轴是一条随手指上下位置倾斜的斜线，
 * 折痕与卷边都是贝塞尔弧；纸背是同一页沿斜轴的真实镜像，而不是水平滑动或 rotationY。
 * 底层 = 目标页，正面裁到折痕，卷起纸背覆在正面之上；全程无投影、无暗带。
 */
@Composable
private fun SimulationPages(
    state: ReaderState, pages: List<PageSlice>, palette: ReaderPalette, viewport: IntSize,
    onTap: TapHandler, onLongPressSelection: TapHandler
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sensitivity = state.turnSensitivity.coerceIn(0.6f, 2f)
    val progress = remember { Animatable(0f) }
    var goingBack by remember { mutableStateOf(false) }
    var tilt by remember { mutableStateOf(0.28f) }
    var startYFrac by remember { mutableStateOf(0.5f) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var startedAt by remember { mutableStateOf(0L) }
    val width = viewport.width.toFloat().coerceAtLeast(1f)
    val height = viewport.height.toFloat().coerceAtLeast(1f)
    var turnJob by remember { mutableStateOf<Job?>(null) }
    fun startTurn(body: suspend () -> Unit) {
        turnJob?.cancel()
        turnJob = scope.launch { body() }
    }

    DisposableEffect(state) {
        state.turnAnimator = { back ->
            goingBack = back
            startYFrac = 0.5f
            tilt = if (back) -0.28f else 0.28f
            startTurn {
                progress.animateTo(1f, tween(230))
                if (back) state.previousPage() else state.nextPage()
                progress.snapTo(0f)
            }
        }
        onDispose { state.turnAnimator = null; turnJob?.cancel() }
    }

    Box(Modifier.fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { screen ->
                    val page = state.currentPage ?: return@detectTapGestures
                    onTap(screen.toPageLocal(state, viewport), page)
                },
                onLongPress = { screen ->
                    // 已经在拖动的指针不算长按：否则慢速划动会误弹出选词菜单
                    if (dragX.absoluteValue > 12f) return@detectTapGestures
                    val page = state.currentPage ?: return@detectTapGestures
                    onLongPressSelection(screen.toPageLocal(state, viewport), page)
                }
            )
        }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { pos: Offset ->
                    turnJob?.cancel()
                    dragX = 0f; dragY = 0f
                    // 按在页面上半 / 下半，决定被掀起的是上角还是下角：抓哪儿、哪儿跟着手指走
                    startYFrac = (pos.y / height).coerceIn(0.08f, 0.92f)
                    tilt = ((startYFrac - 0.5f) * 1.5f).coerceIn(-0.55f, 0.55f)
                    startedAt = System.currentTimeMillis()
                },
                onDragEnd = {
                    val elapsed = System.currentTimeMillis() - startedAt
                    val absX = dragX.absoluteValue
                    val threshold = with(density) { 20.dp.toPx() } / sensitivity
                    val quick = absX > with(density) { 10.dp.toPx() } && elapsed < 240
                    val commit = absX > threshold || quick
                    val back = goingBack
                    startTurn {
                        if (commit) {
                            progress.animateTo(1f, tween(160))
                            if (back) state.previousPage() else state.nextPage()
                            progress.snapTo(0f)
                        } else progress.animateTo(0f, tween(130))
                    }
                    dragX = 0f; dragY = 0f
                },
                onDragCancel = {
                    startTurn { progress.animateTo(0f, tween(110)) }
                    dragX = 0f; dragY = 0f
                }
            ) { _, delta ->
                dragX += delta.x
                dragY += delta.y
                if (dragX.absoluteValue > 8f) goingBack = dragX > 0f
                // 斜卷方向由起始落点决定，拖动过程中的上下位移只做小幅修正
                tilt = (((startYFrac - 0.5f) * 1.5f) - (dragY / height) * 0.35f)
                    .coerceIn(-0.6f, 0.6f)
                val full = width * 0.55f / sensitivity
                val target = (dragX.absoluteValue / full).coerceIn(0f, 1f)
                scope.launch { progress.snapTo(target) }
            }
        }) {
        val layout = state.layout?.layout ?: return@Box
        val current = pages.getOrNull(state.pageIndex) ?: return@Box
        val forward = !goingBack
        val nextPage = if (forward) pages.getOrNull(state.pageIndex + 1)
        else pages.getOrNull(state.pageIndex - 1)
        val p = progress.value
        val contentW = state.pageContentWidth()
        val contentH = state.pageContentHeight()
        val offsetX = (width - contentW) / 2f
        val offsetY = (height - contentH) / 2f
        val bg = palette.background.toArgb()

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                // 静止时只画当前页
                if (p <= 0.001f) {
                    paintPage(native, layout, current, offsetX, offsetY, contentW, contentH, bg)
                    return@drawIntoCanvas
                }
                val g = curlGeometry(w, h, p, tilt, forward)
                val revealCenter = if (forward) (min(g.foldTop, g.foldBottom) + w) / 2f
                else min(g.foldTop, g.foldBottom) / 2f
                // 1. 底层：即将露出的目标页（章末则用文字预告）
                native.save()
                if (nextPage != null) {
                    paintPage(native, layout, nextPage, offsetX, offsetY, contentW, contentH, bg)
                } else {
                    native.drawColor(bg)
                    when {
                        forward && state.hasNextChapter ->
                            drawBridge(native, w, h, palette, state.nextChapterTitle, "下一章", revealCenter)
                        !forward && state.hasPreviousChapter ->
                            drawBridge(native, w, h, palette, state.previousChapterTitle, "上一章", revealCenter)
                        else -> native.drawColor(palette.pageEdge.toArgb())
                    }
                }
                native.restore()

                // 2. 正面：裁到斜向弧形折痕
                native.save()
                native.clipPath(frontPath(g))
                paintPage(native, layout, current, offsetX, offsetY, contentW, contentH, bg)
                native.restore()
                // 3. 卷起纸背：沿斜轴镜像 + 哑光纸色，只透出极淡的背印
                native.save()
                native.clipPath(backPath(g))
                native.concat(reflectionMatrix(g))
                paintPage(native, layout, current, offsetX, offsetY, contentW, contentH, bg)
                native.drawColor(paperBackColor(palette).copy(alpha = 0.96f).toArgb())
                native.restore()
                // 4. 两条 1px 纸边线，明确卷曲层次但不产生阴影
                drawCurlEdges(native, g, palette.onSurface.copy(alpha = 0.18f).toArgb())
            }
        }
    }
}

/** 滚动模式：连续流，滑到章末短暂停留后自动衔接下一章，也可点按钮立即进入。 */
@Composable
private fun ScrollPages(
    state: ReaderState, pages: List<PageSlice>, palette: ReaderPalette,
    onTap: TapHandler, onLongPressSelection: TapHandler
) {
    val listState = rememberLazyListState()
    var chapterRequested by remember(state.chapterSerial) { mutableStateOf(false) }

    LaunchedEffect(state.chapterSerial) {
        listState.scrollToItem(0)
        chapterRequested = false
    }
    LaunchedEffect(state.pageIndex, state.turnSerial) {
        val target = state.pageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        if (listState.firstVisibleItemIndex != target) listState.animateScrollToItem(target)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, first) ->
                if (!scrolling && first in pages.indices && first != state.pageIndex) {
                    state.updatePageIndex(first)
                }
            }
    }
    // 章末自动续章：尾部卡片大半进入视口且停止滚动后才触发，短章不会一进来就跳走
    LaunchedEffect(state.chapterSerial, pages.size) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val last = info.visibleItemsInfo.lastOrNull() ?: return@collect
            val visible = (info.viewportEndOffset - last.offset).toFloat() /
                last.size.coerceAtLeast(1)
            val atEnd = last.index >= pages.size && visible > 0.55f &&
                !listState.isScrollInProgress &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
            if (atEnd && !chapterRequested && state.hasNextChapter) {
                chapterRequested = true
                delay(420)
                val stillAtEnd = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                    it >= pages.size
                } == true
                if (stillAtEnd && !listState.isScrollInProgress && state.hasNextChapter) {
                    state.nextChapter()
                } else chapterRequested = false
            }
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(pages.size, key = { it }) { index ->
            val page = pages[index]
            val h = (page.bottom - page.top).coerceAtLeast(1)
            PageBox(state, page, palette, onTap, onLongPressSelection, fixedHeightPx = h)
        }
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                if (state.hasNextChapter) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("下一章 · ${state.nextChapterTitle}", color = palette.secondary, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        if (state.chapterLoading) WaveLoading(Modifier.size(64.dp), palette.accent)
                        else TextButton(onClick = {
                            chapterRequested = true
                            state.nextChapter()
                        }) { Text("继续阅读 →", color = palette.accent) }
                    }
                } else Text("· 全书完 ·", color = palette.secondary, fontSize = 13.sp)
            }
        }
    }
}

/**
 * 单页容器。每页都有不透明背景，且文字只绘制到本页 bottom 为止：
 * 既避免覆盖/仿真叠层时两页文字糊在一起，也不会在页末露出下一页的半行。
 */
@Composable
private fun PageBox(
    state: ReaderState,
    page: PageSlice,
    palette: ReaderPalette,
    onTap: TapHandler,
    onLongPressSelection: TapHandler,
    interactive: Boolean = true,
    fixedHeightPx: Int? = null
) {
    val density = LocalDensity.current
    val widthPx = state.pageContentWidth()
    val pageHeightPx = state.pageContentHeight()
    val contentHeightPx = (page.bottom - page.top).coerceIn(1, pageHeightPx)
    val canvasHeightPx = fixedHeightPx ?: pageHeightPx
    Box(
        Modifier.then(
            if (fixedHeightPx != null) Modifier.height(with(density) { canvasHeightPx.toDp() })
            else Modifier.fillMaxSize()
        ).fillMaxWidth().background(palette.background),
        contentAlignment = Alignment.Center
    ) {
        PageCanvas(
            state = state, page = page, palette = palette,
            widthPx = widthPx, canvasHeightPx = canvasHeightPx,
            clipHeightPx = contentHeightPx.coerceAtMost(canvasHeightPx),
            onTap = onTap, onLongPressSelection = onLongPressSelection,
            interactive = interactive
        )
    }
}

@Composable
private fun PageCanvas(
    state: ReaderState,
    page: PageSlice,
    palette: ReaderPalette,
    widthPx: Int,
    canvasHeightPx: Int,
    clipHeightPx: Int,
    onTap: TapHandler,
    onLongPressSelection: TapHandler,
    interactive: Boolean
) {
    val layout = state.layout?.layout ?: return
    val density = LocalDensity.current
    Canvas(
        Modifier
            .width(with(density) { widthPx.toDp() })
            .height(with(density) { canvasHeightPx.toDp() })
            .then(if (interactive) Modifier.pointerInput(page, state.selection) {
                detectTapGestures(
                    onTap = { onTap(it, page) },
                    onLongPress = { onLongPressSelection(it, page) }
                )
            } else Modifier)
    ) {
        drawRect(palette.background)
        clipRect(0f, 0f, widthPx.toFloat(), clipHeightPx.toFloat()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(0f, -page.top.toFloat())
                layout.draw(native)
                native.restore()
            }
        }
        val sel = state.selection
        if (sel != null && sel.chapter == page.chapter) {
            runCatching {
                val first = layout.getLineForOffset(sel.start)
                val last = layout.getLineForOffset(sel.end.coerceAtMost(layout.text.length))
                for (line in first..last) {
                    val top = (layout.getLineTop(line) - page.top).toFloat()
                    val bottom = (layout.getLineBottom(line) - page.top).toFloat()
                    if (bottom < 0 || top > clipHeightPx) continue
                    val left = if (line == first) layout.getPrimaryHorizontal(sel.start) else 0f
                    val right = if (line == last) layout.getSecondaryHorizontal(sel.end) else widthPx.toFloat()
                    drawRect(
                        color = palette.accent.copy(alpha = 0.22f),
                        topLeft = Offset(left.coerceAtLeast(0f), top.coerceAtLeast(0f)),
                        size = Size((right - left).coerceAtLeast(0f),
                            (bottom.coerceAtMost(clipHeightPx.toFloat()) - top).coerceAtLeast(0f))
                    )
                }
            }
        }
    }
}

@Composable
private fun PagedBody(state: ReaderState, palette: ReaderPalette) {
    val bitmap = state.pdfBitmap
    Box(Modifier.fillMaxSize().background(palette.background)
        .pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ -> state.changePdfZoom(state.pdfZoom * zoom) }
        }
        .pointerInput(Unit) {
            detectTapGestures(onTap = { offset ->
                when {
                    offset.x < size.width * 0.28f -> state.previousPage()
                    offset.x > size.width * 0.72f -> state.nextPage()
                    else -> state.toolbarVisible = !state.toolbarVisible
                }
            })
        }, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "第 ${state.pagedIndex + 1} 页",
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = state.pdfZoom, scaleY = state.pdfZoom
                ))
        } else {
            WaveLoading(Modifier.fillMaxSize(), palette.accent)
        }
    }
}

// ---------------------------------------------------------------------------
// 覆盖层
// ---------------------------------------------------------------------------

@Composable
private fun ReaderTopBar(state: ReaderState, palette: ReaderPalette, onBack: () -> Unit) {
    Surface(color = palette.surface, shadowElevation = 0.dp, tonalElevation = 0.dp,
        shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(I.Back, contentDescription = "返回书库", tint = palette.onSurface) }
            Column(Modifier.weight(1f)) {
                Text(state.book.title, color = palette.onSurface, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(state.chapterTitles.getOrElse(state.chapterIndex) { "第 ${state.chapterIndex + 1} 页" },
                    color = palette.secondary, fontSize = 11.sp, maxLines = 1)
            }
            IconButton(onClick = { state.toggleBookmark() }) {
                Icon(if (state.isBookmarked()) I.Favorite else I.FavoriteBorder,
                    contentDescription = if (state.isBookmarked()) "移除书签" else "添加书签", tint = palette.accent)
            }
        }
    }
}

@Composable
private fun ReaderToolbar(
    state: ReaderState, app: AppState, palette: ReaderPalette,
    onOpenSettings: () -> Unit, onOpenStats: () -> Unit, onImportFont: () -> Unit
) {
    Surface(color = palette.surface, shadowElevation = 0.dp, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolAction(I.List, "目录", palette.onSurface) { state.sheet = ReaderSheet.TOC }
                ToolAction(I.Search, "搜索", palette.onSurface) { state.sheet = ReaderSheet.SEARCH }
                ToolAction(I.FavoriteBorder, "书签", palette.onSurface) { state.sheet = ReaderSheet.BOOKMARKS }
                ToolAction(I.Star, "主题", palette.onSurface) { state.sheet = ReaderSheet.THEME }
                ToolAction(I.Settings, "设置", palette.onSurface) { state.sheet = ReaderSheet.SETTINGS }
                ToolAction(I.More, "更多", palette.onSurface) { state.sheet = ReaderSheet.MORE }
            }
        }
    }
}

/** 无 ripple、无按压阴影的工具栏按钮：点开选项时页面保持静止。 */
@Composable
private fun ToolAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(indication = null, interactionSource = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(23.dp))
        Text(label, fontSize = 10.sp, color = tint.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 3.dp))
    }
}

/** 角落百分比：常驻、点击才展开轻量跳转滑杆，取代整条底部进度条。 */
@Composable
private fun CornerProgress(
    app: AppState,
    state: ReaderState,
    palette: ReaderPalette,
    bottomSafePx: Int,
    modifier: Modifier = Modifier
) {
    val fraction = state.progressFraction()
    var expanded by remember { mutableStateOf(false) }
    var draft by remember(fraction) { mutableStateOf(fraction) }
    val right = app.settings.progressOnRight
    val density = LocalDensity.current
    Column(
        modifier = modifier
            .padding(start = 18.dp, end = 18.dp, bottom = with(density) { (bottomSafePx + 6).toDp() })
            .clickable(indication = null, interactionSource = null) { expanded = !expanded },
        horizontalAlignment = if (right) Alignment.End else Alignment.Start
    ) {
        Text(state.progressLabel(), fontSize = 11.sp,
            color = palette.secondary.copy(alpha = 0.82f))
        if (expanded) {
            Text(state.chapterTitles.getOrElse(state.chapterIndex) { "第 ${state.chapterIndex + 1} 章" },
                fontSize = 10.sp, color = palette.secondary.copy(alpha = 0.65f),
                maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            Slider(
                value = draft, onValueChange = { draft = it },
                onValueChangeFinished = { state.seekFraction(draft) },
                valueRange = 0f..1f,
                modifier = Modifier.width(180.dp).height(26.dp)
            )
        }
    }
}

@Composable
private fun SelectionMenu(
    state: ReaderState, selection: Selection, palette: ReaderPalette, tts: TtsManager,
    onCopy: () -> Unit, onTranslate: () -> Unit, onNote: () -> Unit,
    onNarrow: () -> Unit, onWide: () -> Unit, onDismiss: () -> Unit
) {
    val x = (selection.anchorX - 150f).coerceAtLeast(12f)
    val y = (selection.anchorY - 118f).coerceAtLeast(56f)
    Card(modifier = Modifier.offset { IntOffset(x.toInt(), y.toInt()) }.padding(horizontal = 10.dp),
        shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = palette.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HIGHLIGHT_COLORS.forEachIndexed { index, pair ->
                    ColorDot(pair.first, false, "${pair.second}高亮", { state.addHighlight(index) }, 26.dp)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onNote(); }) { Text("笔记", fontSize = 12.sp) }
                TextButton(onClick = {
                    val text = state.chapterContent.text
                    tts.speak(text.substring(selection.start.coerceAtMost(text.length)), 1f)
                    onDismiss()
                }) { Text("朗读", fontSize = 12.sp) }
                TextButton(onClick = { onCopy(); onDismiss() }) { Text("复制", fontSize = 12.sp) }
                TextButton(onClick = { onTranslate(); onDismiss() }) { Text("翻译", fontSize = 12.sp) }
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onNarrow) { Text("缩选", fontSize = 11.sp) }
                TextButton(onClick = onWide) { Text("扩选", fontSize = 11.sp) }
                TextButton(onClick = onDismiss) { Text("取消", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun HighlightEditor(
    entity: HighlightEntity, palette: ReaderPalette, onColor: (Int) -> Unit,
    onDelete: () -> Unit, onNote: () -> Unit, onDismiss: () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = palette.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 14.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("修改高亮", fontWeight = FontWeight.SemiBold, color = palette.onSurface)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HIGHLIGHT_COLORS.forEachIndexed { index, pair ->
                        ColorDot(pair.first, entity.color == index, pair.second, { onColor(index) }, 28.dp)
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = onNote) { Text("笔记") }
                    TextButton(onClick = onDelete) { Text("删除") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

private fun expandSelection(text: String, offset: Int): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0
    val center = offset.coerceIn(0, text.length - 1)
    val punct = "。！？；，、,.!?;:\n“”‘’\"()（）《》 "
    var start = center
    var end = (center + 1).coerceAtMost(text.length)
    while (start > 0 && center - start < 40 && text[start - 1] !in punct) start--
    while (end < text.length && end - center < 60 && text[end] !in punct) end++
    return start to end
}
