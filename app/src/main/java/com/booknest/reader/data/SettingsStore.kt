package com.booknest.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeId(val label: String) {
    DAY_CREAM("米白"), DAY_GRAY("浅灰"), DAY_WHITE("纯白"),
    NIGHT_GRAY("深灰"), NIGHT_BLACK("纯黑"), NIGHT_SEPIA("护眼棕褐");

    val isNight: Boolean get() = this == NIGHT_GRAY || this == NIGHT_BLACK || this == NIGHT_SEPIA
}

enum class PageMode(val label: String) {
    SIMULATION("仿真翻页"), COVER("覆盖翻页"), SLIDE("滑动翻页"), SCROLL("滚动模式")
}

enum class SortMode(val label: String) { RECENT("最近阅读"), TITLE("书名"), IMPORTED("导入时间") }
enum class ViewMode { GRID, LIST }

data class AppSettings(
    val themeId: ThemeId = ThemeId.DAY_CREAM,
    val followSystemDark: Boolean = true,
    val dynamicColor: Boolean = true,
    val seedColor: Int = 0xFF8A6A4F.toInt(),
    val pageMode: PageMode = PageMode.SLIDE,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortMode: SortMode = SortMode.RECENT,
    val degradeSimulation: Boolean = false,
    val paragraphSpacing: Float = 0f,
    val firstLineIndent: Boolean = true,
    val showInlineImages: Boolean = true,
    val progressOnRight: Boolean = true,
    val turnSensitivity: Float = 1f
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "booknest_settings")

class SettingsStore(private val context: Context) {

    private object K {
        val THEME = stringPreferencesKey("theme")
        val FOLLOW_SYSTEM = booleanPreferencesKey("follow_system_dark")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val SEED = intPreferencesKey("seed_color")
        val PAGE_MODE = stringPreferencesKey("page_mode")
        val VOLUME = booleanPreferencesKey("volume_keys")
        val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val VIEW = stringPreferencesKey("view_mode")
        val SORT = stringPreferencesKey("sort_mode")
        val DEGRADE = booleanPreferencesKey("degrade_simulation")
        val PARAGRAPH_SPACING = floatPreferencesKey("paragraph_spacing")
        val FIRST_LINE_INDENT = booleanPreferencesKey("first_line_indent")
        val SHOW_IMAGES = booleanPreferencesKey("show_inline_images")
        val PROGRESS_RIGHT = booleanPreferencesKey("progress_on_right")
        val TURN_SENSITIVITY = floatPreferencesKey("turn_sensitivity")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeId = p[K.THEME]?.let { runCatching { ThemeId.valueOf(it) }.getOrNull() } ?: ThemeId.DAY_CREAM,
            followSystemDark = p[K.FOLLOW_SYSTEM] ?: true,
            dynamicColor = p[K.DYNAMIC] ?: true,
            seedColor = p[K.SEED] ?: 0xFF8A6A4F.toInt(),
            pageMode = p[K.PAGE_MODE]?.let { runCatching { PageMode.valueOf(it) }.getOrNull() } ?: PageMode.SLIDE,
            volumeKeys = p[K.VOLUME] ?: true,
            keepScreenOn = p[K.KEEP_AWAKE] ?: false,
            ttsSpeed = p[K.TTS_SPEED] ?: 1f,
            viewMode = p[K.VIEW]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.GRID,
            sortMode = p[K.SORT]?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.RECENT,
            degradeSimulation = p[K.DEGRADE] ?: false,
            paragraphSpacing = p[K.PARAGRAPH_SPACING] ?: 0f,
            firstLineIndent = p[K.FIRST_LINE_INDENT] ?: true,
            showInlineImages = p[K.SHOW_IMAGES] ?: true,
            progressOnRight = p[K.PROGRESS_RIGHT] ?: true,
            turnSensitivity = p[K.TURN_SENSITIVITY] ?: 1f
        )
    }

    suspend fun setTheme(id: ThemeId) = update { it[K.THEME] = id.name }
    suspend fun setFollowSystem(value: Boolean) = update { it[K.FOLLOW_SYSTEM] = value }
    suspend fun setDynamicColor(value: Boolean) = update { it[K.DYNAMIC] = value }
    suspend fun setSeedColor(value: Int) = update { it[K.SEED] = value }
    suspend fun setPageMode(mode: PageMode) = update { it[K.PAGE_MODE] = mode.name }
    suspend fun setVolumeKeys(value: Boolean) = update { it[K.VOLUME] = value }
    suspend fun setKeepScreenOn(value: Boolean) = update { it[K.KEEP_AWAKE] = value }
    suspend fun setTtsSpeed(value: Float) = update { it[K.TTS_SPEED] = value }
    suspend fun setViewMode(value: ViewMode) = update { it[K.VIEW] = value.name }
    suspend fun setSortMode(value: SortMode) = update { it[K.SORT] = value.name }
    suspend fun setDegrade(value: Boolean) = update { it[K.DEGRADE] = value }
    suspend fun setParagraphSpacing(value: Float) = update { it[K.PARAGRAPH_SPACING] = value }
    suspend fun setFirstLineIndent(value: Boolean) = update { it[K.FIRST_LINE_INDENT] = value }
    suspend fun setShowInlineImages(value: Boolean) = update { it[K.SHOW_IMAGES] = value }
    suspend fun setProgressOnRight(value: Boolean) = update { it[K.PROGRESS_RIGHT] = value }
    suspend fun setTurnSensitivity(value: Float) = update { it[K.TURN_SENSITIVITY] = value }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
