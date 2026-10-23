package com.joe.novelreader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.joe.novelreader.core.net.DnsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ReaderTheme(val label: String, val background: Long, val text: Long) {
    LIGHT("Light", 0xFFFFFFFF, 0xFF1F1F1F),
    PAPER("Paper", 0xFFF6F0E3, 0xFF3B3226),
    SEPIA("Sepia", 0xFFEAD9B9, 0xFF4A3B26),
    GREEN("Green", 0xFFD9EAD3, 0xFF22311E),
    DARK("Dark", 0xFF1E2126, 0xFFC9CDD3),
    BLACK("Black", 0xFF000000, 0xFFA8A8A8),
}

enum class ReaderFont(val label: String) { SERIF("Serif"), SANS("Sans"), SYSTEM("System"), MONO("Mono") }

enum class LibrarySort(val label: String) { LAST_READ("Last read"), TITLE("Title"), ADDED("Date added"), UNREAD("Unread chapters") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val dnsEnabled: Boolean = true,
    val dnsServers: List<String> = DnsConfig.DEFAULT_SERVERS,
    val dnsFallbackToSystem: Boolean = true,
    val readerFontSize: Float = 18f,
    val readerLineHeight: Float = 1.6f,
    val readerFont: ReaderFont = ReaderFont.SERIF,
    val readerTheme: ReaderTheme = ReaderTheme.DARK,
    val readerMargin: Int = 20,
    val readerParagraphSpacing: Int = 14,
    val readerJustify: Boolean = false,
    val keepScreenOn: Boolean = true,
    val continuousScroll: Boolean = true,
    val volumeKeysScroll: Boolean = true,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val disabledSources: Set<String> = emptySet(),
    val baseUrls: Map<String, String> = emptyMap(),
    val librarySort: LibrarySort = LibrarySort.LAST_READ,
    val libraryColumns: Int = 3,
    val autoUpdate: Boolean = true,
    val downloadAhead: Int = 0,
    val onboarded: Boolean = false,
) {
    val dnsConfig: DnsConfig get() = DnsConfig(dnsEnabled, dnsServers, dnsFallbackToSystem)
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val dnsEnabled = booleanPreferencesKey("dns_enabled")
        val dnsServers = stringPreferencesKey("dns_servers")
        val dnsFallback = booleanPreferencesKey("dns_fallback")
        val fontSize = floatPreferencesKey("reader_font_size")
        val lineHeight = floatPreferencesKey("reader_line_height")
        val font = stringPreferencesKey("reader_font")
        val readerTheme = stringPreferencesKey("reader_theme")
        val margin = intPreferencesKey("reader_margin")
        val paragraphSpacing = intPreferencesKey("reader_paragraph_spacing")
        val justify = booleanPreferencesKey("reader_justify")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val continuous = booleanPreferencesKey("continuous_scroll")
        val volumeKeys = booleanPreferencesKey("volume_keys")
        val ttsRate = floatPreferencesKey("tts_rate")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val disabledSources = stringSetPreferencesKey("disabled_sources")
        val baseUrls = stringPreferencesKey("base_urls")
        val librarySort = stringPreferencesKey("library_sort")
        val libraryColumns = intPreferencesKey("library_columns")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val downloadAhead = intPreferencesKey("download_ahead")
        val onboarded = booleanPreferencesKey("onboarded")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> read(p) }

    /** Latest settings, available instantly (needed by the DNS resolver on network threads). */
    val state: StateFlow<AppSettings> = flow.stateIn(
        scope,
        SharingStarted.Eagerly,
        runBlocking { flow.first() },
    )

    val current: AppSettings get() = state.value

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = p[K.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            dynamicColor = p[K.dynamicColor] ?: d.dynamicColor,
            dnsEnabled = p[K.dnsEnabled] ?: d.dnsEnabled,
            dnsServers = p[K.dnsServers]?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: d.dnsServers,
            dnsFallbackToSystem = p[K.dnsFallback] ?: d.dnsFallbackToSystem,
            readerFontSize = p[K.fontSize] ?: d.readerFontSize,
            readerLineHeight = p[K.lineHeight] ?: d.readerLineHeight,
            readerFont = p[K.font]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: d.readerFont,
            readerTheme = p[K.readerTheme]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: d.readerTheme,
            readerMargin = p[K.margin] ?: d.readerMargin,
            readerParagraphSpacing = p[K.paragraphSpacing] ?: d.readerParagraphSpacing,
            readerJustify = p[K.justify] ?: d.readerJustify,
            keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn,
            continuousScroll = p[K.continuous] ?: d.continuousScroll,
            volumeKeysScroll = p[K.volumeKeys] ?: d.volumeKeysScroll,
            ttsRate = p[K.ttsRate] ?: d.ttsRate,
            ttsPitch = p[K.ttsPitch] ?: d.ttsPitch,
            disabledSources = p[K.disabledSources] ?: d.disabledSources,
            baseUrls = p[K.baseUrls]?.split('\n')?.mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
            }?.toMap() ?: d.baseUrls,
            librarySort = p[K.librarySort]?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() } ?: d.librarySort,
            libraryColumns = p[K.libraryColumns] ?: d.libraryColumns,
            autoUpdate = p[K.autoUpdate] ?: d.autoUpdate,
            downloadAhead = p[K.downloadAhead] ?: d.downloadAhead,
            onboarded = p[K.onboarded] ?: d.onboarded,
        )
    }

    suspend fun update(change: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val n = change(read(p))
            p[K.themeMode] = n.themeMode.name
            p[K.dynamicColor] = n.dynamicColor
            p[K.dnsEnabled] = n.dnsEnabled
            p[K.dnsServers] = n.dnsServers.joinToString("\n")
            p[K.dnsFallback] = n.dnsFallbackToSystem
            p[K.fontSize] = n.readerFontSize
            p[K.lineHeight] = n.readerLineHeight
            p[K.font] = n.readerFont.name
            p[K.readerTheme] = n.readerTheme.name
            p[K.margin] = n.readerMargin
            p[K.paragraphSpacing] = n.readerParagraphSpacing
            p[K.justify] = n.readerJustify
            p[K.keepScreenOn] = n.keepScreenOn
            p[K.continuous] = n.continuousScroll
            p[K.volumeKeys] = n.volumeKeysScroll
            p[K.ttsRate] = n.ttsRate
            p[K.ttsPitch] = n.ttsPitch
            p[K.disabledSources] = n.disabledSources
            p[K.baseUrls] = n.baseUrls.entries.joinToString("\n") { "${it.key}=${it.value}" }
            p[K.librarySort] = n.librarySort.name
            p[K.libraryColumns] = n.libraryColumns
            p[K.autoUpdate] = n.autoUpdate
            p[K.downloadAhead] = n.downloadAhead
            p[K.onboarded] = n.onboarded
        }
    }
}
