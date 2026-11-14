package com.booknest.reader

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.material3.Shapes
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.booknest.reader.core.appColorScheme
import com.booknest.reader.data.BookNestRepository
import com.booknest.reader.reader.TtsManager
import com.booknest.reader.state.AppState
import com.booknest.reader.state.Screen
import com.booknest.reader.ui.I
import com.booknest.reader.ui.LibraryScreen
import com.booknest.reader.ui.NotesScreen
import com.booknest.reader.ui.ReaderScreen
import com.booknest.reader.ui.SettingsScreen
import com.booknest.reader.ui.StatsScreen
import com.booknest.reader.ui.WaveLoading
import com.booknest.reader.ui.sharedAxisX
import com.booknest.reader.ui.sharedAxisY

class MainActivity : ComponentActivity() {

    private var appState: AppState? = null
    private var pendingImportUri: android.net.Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportUri = intent?.data
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val view = LocalView.current
            val app = remember {
                AppState(BookNestRepository(context), context, scope).also { appState = it }
            }
            val tts = remember { TtsManager(context) }
            val systemDark = isSystemInDarkTheme()
            val scheme = appColorScheme(app.settings, systemDark, context)

            val bookPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris -> app.importBooks(uris) }
            val fontPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) app.importFont(uri) { path ->
                    app.fontImportedHandler(path)
                    app.showToast("字体已导入")
                }
            }
            val importBooks = { bookPicker.launch(arrayOf("*/*")) }
            val importFont = {
                fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream", "*/*"))
            }

            LaunchedEffect(Unit) {
                pendingImportUri?.let { uri ->
                    app.importBooks(listOf(uri))
                    pendingImportUri = null
                }
            }
            LaunchedEffect(app.immersive, app.screen) {
                val controller = WindowCompat.getInsetsController(window, view)
                if (app.screen == Screen.READER && app.immersive) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
            LaunchedEffect(app.settings.keepScreenOn, app.screen) {
                if (app.settings.keepScreenOn && app.screen == Screen.READER) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            DisposableEffect(Unit) { onDispose { tts.shutdown() } }

            MaterialTheme(
                colorScheme = scheme,
                shapes = Shapes(
                    small = RoundedCornerShape(10.dp),
                    medium = RoundedCornerShape(18.dp),
                    large = RoundedCornerShape(26.dp)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BookNestRoot(app, tts, systemDark, importBooks, importFont)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            appState?.volumeKeyHandler?.invoke(keyCode) == true
        ) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            appState?.screen == Screen.READER && appState?.settings?.volumeKeys == true
        ) return true
        return super.onKeyUp(keyCode, event)
    }
}

private data class NavState(val screen: Screen, val bookId: String?)

private fun screenOrder(screen: Screen) = when (screen) {
    Screen.LIBRARY -> 0
    Screen.READER -> 1
    Screen.NOTES -> 2
    Screen.STATS -> 3
    Screen.SETTINGS -> 4
}

@Composable
private fun BookNestRoot(
    app: AppState,
    tts: TtsManager,
    systemDark: Boolean,
    onImportBooks: () -> Unit,
    onImportFont: () -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    // 次级页面返回书库；阅读器内部还有更内层的 BackHandler 负责逐层关闭浮层
    BackHandler(enabled = app.screen != Screen.LIBRARY) { app.backToLibrary() }
    LaunchedEffect(app.toast) {
        app.toast?.let {
            snackbar.showSnackbar(it)
            app.clearToast()
        }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (app.screen != Screen.READER) {
                NavigationBar {
                    NavItem(I.Home, "书库", app.screen == Screen.LIBRARY) { app.navigate(Screen.LIBRARY) }
                    NavItem(I.Star, "笔记", app.screen == Screen.NOTES) { app.navigate(Screen.NOTES) }
                    NavItem(I.Info, "统计", app.screen == Screen.STATS) { app.navigate(Screen.STATS) }
                    NavItem(I.Settings, "设置", app.screen == Screen.SETTINGS) { app.navigate(Screen.SETTINGS) }
                }
            }
        },
        floatingActionButton = {
            if (app.screen == Screen.LIBRARY) {
                ExtendedFloatingActionButton(
                    onClick = onImportBooks,
                    icon = { Icon(I.Add, contentDescription = null) },
                    text = { Text("导入书籍") },
                    shape = RoundedCornerShape(18.dp)
                )
            }
        }
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().then(
            if (app.screen == Screen.READER) Modifier.background(Color.Transparent)
            else Modifier.padding(padding)
        )
        AnimatedContent(
            targetState = NavState(app.screen, app.activeBookId),
            transitionSpec = {
                val forward = screenOrder(targetState.screen) > screenOrder(initialState.screen)
                if (targetState.screen == Screen.READER || initialState.screen == Screen.READER)
                    sharedAxisX(forward) else sharedAxisY(forward)
            },
            label = "screen-transition",
            modifier = contentModifier
        ) { nav ->
            when (nav.screen) {
                Screen.LIBRARY -> LibraryScreen(app, onImportBooks, Modifier.fillMaxSize())
                Screen.READER -> {
                    val book = app.books.firstOrNull { it.id == nav.bookId }
                    if (book != null) {
                        ReaderScreen(
                            app = app, book = book, tts = tts, systemDark = systemDark,
                            onBack = { app.backToLibrary() },
                            onOpenSettings = { app.navigate(Screen.SETTINGS) },
                            onOpenStats = { app.navigate(Screen.STATS) },
                            onImportFont = onImportFont
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text("书籍已不可用")
                        }
                    }
                }
                Screen.STATS -> StatsScreen(app, Modifier.fillMaxSize())
                Screen.SETTINGS -> SettingsScreen(app, onImportFont, Modifier.fillMaxSize())
                Screen.NOTES -> NotesScreen(app, Modifier.fillMaxSize())
            }
        }
    }

    if (app.isImporting) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                WaveLoading(Modifier.padding(horizontal = 36.dp, vertical = 28.dp))
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationBarItem(selected = selected, onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label, fontSize = 11.sp) })
}
