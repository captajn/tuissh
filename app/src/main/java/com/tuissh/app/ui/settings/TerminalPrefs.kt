package com.tuissh.app.ui.settings

import com.tuissh.app.data.dao.KvDao

/**
 * Terminal appearance read from settings.
 *
 * Kept separate from [SettingsViewModel] because the terminal screen needs these values
 * once when it appears, not as a ViewModel with its own lifecycle: creating a second
 * ViewModel just to read three numbers would outlive the screen and re-read on every
 * configuration change.
 */
data class TerminalPrefs(
    val fontSize: Int = DEFAULT_FONT_SIZE,
    val cursorBlink: Boolean = true,
    val keepScreenOn: Boolean = false,
) {
    companion object {
        const val DEFAULT_FONT_SIZE = 13

        /**
         * Read the stored values, falling back to the defaults.
         *
         * A stale or malformed value is clamped rather than trusted: a font size of 0
         * from an interrupted write would render an invisible terminal with no way back.
         */
        suspend fun load(kv: KvDao): TerminalPrefs {
            val size = kv.get(SettingsViewModel.KEY_FONT_SIZE)?.toIntOrNull()
                ?.coerceIn(
                    SettingsViewModel.UiState.MIN_FONT_SIZE,
                    SettingsViewModel.UiState.MAX_FONT_SIZE,
                )
                ?: DEFAULT_FONT_SIZE
            return TerminalPrefs(
                fontSize = size,
                cursorBlink = kv.get(SettingsViewModel.KEY_CURSOR_BLINK) != "false",
                keepScreenOn = kv.get(SettingsViewModel.KEY_KEEP_AWAKE) == "true",
            )
        }
    }
}
