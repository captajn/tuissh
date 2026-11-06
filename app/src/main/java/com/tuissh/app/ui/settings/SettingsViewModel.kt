package com.tuissh.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuissh.app.data.dao.KvDao
import com.tuissh.app.ui.NeoActivity
import com.tuissh.app.i18n.LocaleEngine
import com.tuissh.app.i18n.NeoLocale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App settings.
 *
 * Stored in the existing key/value table rather than a new preference file: the app
 * already has one place for durable non-relational state, and adding a second would mean
 * two sources of truth for the same kind of data.
 *
 * Each setting is read once at startup and written when changed. Nothing here is
 * observed live by other screens, so a simple state object is enough.
 */
class SettingsViewModel(
    private val kv: KvDao,
    /** Needed only to mirror the language for the next activity start. */
    private val appContext: android.content.Context? = null,
) : ViewModel() {

    data class UiState(
        /** BCP-47 tag, or [FOLLOW_SYSTEM]. */
        val language: String = LocaleEngine.DEFAULT_LANGUAGE,
        val fontSize: Int = DEFAULT_FONT_SIZE,
        val cursorBlink: Boolean = true,
        val keepScreenOn: Boolean = false,
        /** Id of the terminal theme row, or null for the built-in palette. */
        val themeId: Long? = null,
        val themeName: String = "",
        val loaded: Boolean = false,
    ) {
        companion object {
            const val DEFAULT_FONT_SIZE = 13
            const val MIN_FONT_SIZE = 10
            const val MAX_FONT_SIZE = 22
        }
    }

    /** One selectable language. */
    data class LanguageOption(val tag: String, val label: String)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Languages the app ships resources for, plus "follow the system". */
    val languages: List<LanguageOption> = listOf(
        LanguageOption(FOLLOW_SYSTEM, "Theo hệ thống / System"),
    ) + NeoLocale.SUPPORTED.map { LanguageOption(it, languageLabel(it)) }

    fun load() {
        viewModelScope.launch {
            val language = kv.get(KEY_LANGUAGE)?.takeIf { it.isNotBlank() }
                ?: LocaleEngine.DEFAULT_LANGUAGE
            val font = kv.get(KEY_FONT_SIZE)?.toIntOrNull()
                ?.coerceIn(UiState.MIN_FONT_SIZE, UiState.MAX_FONT_SIZE)
                ?: UiState.DEFAULT_FONT_SIZE
            _state.value = UiState(
                language = language,
                fontSize = font,
                cursorBlink = kv.get(KEY_CURSOR_BLINK) != "false",
                keepScreenOn = kv.get(KEY_KEEP_AWAKE) == "true",
                themeId = kv.get(KEY_THEME_ID)?.toLongOrNull(),
                themeName = kv.get(KEY_THEME_NAME).orEmpty(),
                loaded = true,
            )
        }
    }

    /**
     * Change the language.
     *
     * Only the stored value changes here; the activity applies the locale on its next
     * creation. Forcing a re-creation from inside the ViewModel would lose whatever the
     * user was doing, so the screen offers an explicit restart instead.
     */
    fun setLanguage(tag: String) {
        _state.value = _state.value.copy(language = tag)
        viewModelScope.launch {
            kv.put(KEY_LANGUAGE, tag)
            // Mirror into shared preferences so the next activity start can apply the
            // locale before the database is open.
            mirrorLanguage(tag)
        }
    }

    /** Write the language somewhere readable during attachBaseContext. */
    fun mirrorLanguage(tag: String) {
        appContext
            ?.getSharedPreferences(NeoActivity.LANGUAGE_PREFS, android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(NeoActivity.LANGUAGE_KEY, tag)
            ?.apply()
    }

    fun setFontSize(size: Int) {
        val clamped = size.coerceIn(UiState.MIN_FONT_SIZE, UiState.MAX_FONT_SIZE)
        _state.value = _state.value.copy(fontSize = clamped)
        viewModelScope.launch { kv.put(KEY_FONT_SIZE, clamped.toString()) }
    }

    fun setCursorBlink(enabled: Boolean) {
        _state.value = _state.value.copy(cursorBlink = enabled)
        viewModelScope.launch { kv.put(KEY_CURSOR_BLINK, enabled.toString()) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _state.value = _state.value.copy(keepScreenOn = enabled)
        viewModelScope.launch { kv.put(KEY_KEEP_AWAKE, enabled.toString()) }
    }

    /** Reset the terminal look without touching servers, keys or history. */
    fun resetTerminalLook() {
        _state.value = _state.value.copy(
            fontSize = UiState.DEFAULT_FONT_SIZE,
            cursorBlink = true,
            themeId = null,
            themeName = "",
        )
        viewModelScope.launch {
            kv.remove(KEY_FONT_SIZE)
            kv.remove(KEY_CURSOR_BLINK)
            kv.remove(KEY_THEME_ID)
            kv.remove(KEY_THEME_NAME)
        }
    }

    private fun languageLabel(tag: String): String = when (tag.substringBefore('-')) {
        "vi" -> "Tiếng Việt"
        "en" -> "English"
        "zh" -> "中文"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "ru" -> "Русский"
        "de" -> "Deutsch"
        "fr" -> "Français"
        "es" -> "Español"
        "pt" -> "Português"
        "tr" -> "Türkçe"
        else -> tag
    }

    companion object {
        /** Stored value meaning "whatever the device is set to". */
        const val FOLLOW_SYSTEM = "system"

        const val KEY_LANGUAGE = "settings.language"
        const val KEY_FONT_SIZE = "settings.terminal.fontSize"
        const val KEY_CURSOR_BLINK = "settings.terminal.cursorBlink"
        const val KEY_KEEP_AWAKE = "settings.terminal.keepAwake"
        const val KEY_THEME_ID = "settings.terminal.themeId"
        const val KEY_THEME_NAME = "settings.terminal.themeName"

        /**
         * Read the language without a ViewModel.
         *
         * The activity needs this before any composition exists.
         */
        suspend fun readLanguage(kv: KvDao): String =
            kv.get(KEY_LANGUAGE)?.takeIf { it.isNotBlank() } ?: LocaleEngine.DEFAULT_LANGUAGE
    }
}
