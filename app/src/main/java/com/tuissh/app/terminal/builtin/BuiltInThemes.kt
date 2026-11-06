package com.tuissh.app.terminal.builtin

import com.tuissh.app.data.model.TerminalThemeEntity

/**
 * Default plus the popular themes the original app shipped and allowed importing
 * (Ghostty / Warp `.yaml` / iTerm2 `.itermcolors`).
 *
 * Colours are stored as packed 0xRRGGBB so they can be written into a Room row and
 * rehydrated into Compose `Color(0xFF000000 or value)`.
 */
object BuiltInThemes {

    /** 16 ANSI slots: black, red, green, yellow, blue, magenta, cyan, white, then bright. */
    private fun ansi(vararg colours: Int): String =
        colours.joinToString(",") { String.format("%06x", it and 0xFFFFFF) }

    private val ANSI_XTERM = ansi(
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    )

    private val ANSI_DRACULA = ansi(
        0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
        0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF,
    )

    private val ANSI_ONEDARK = ansi(
        0x282C34, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF,
        0x5C6370, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xFFFFFF,
    )

    private val ANSI_NORD = ansi(
        0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
        0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81AEC1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
    )

    private val ANSI_GRUVBOX = ansi(
        0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
        0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
    )

    private val ANSI_TOKYO = ansi(
        0x15161E, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xA9B1D6,
        0x414868, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xC0CAF5,
    )

    private val ANSI_SOLARIZED_DARK = ansi(
        0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
        0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
    )

    private val ANSI_SOLARIZED_LIGHT = ansi(
        0xEEE8D5, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0x073642,
        0xFDF6E3, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0x002B36,
    )

    private val ANSI_GITHUB_LIGHT = ansi(
        0x24292E, 0xD73A49, 0x28A745, 0xDBAB09, 0x0366D6, 0x6F42C1, 0x1B7C83, 0x6A737D,
        0x959DA5, 0xCB2431, 0x22863A, 0xB08800, 0x005CC5, 0x5A32A3, 0x3192AA, 0xFFFFFF,
    )

    private val ANSI_CAMPBELL = ansi(
        0x0C0C0C, 0xC50F1F, 0x13A10E, 0xC19C00, 0x0037DA, 0x881798, 0x3A96DD, 0xCCCCCC,
        0x767676, 0xE74856, 0x16C60C, 0xF9F1A5, 0x3B78FF, 0xB4009E, 0x61D6D6, 0xF2F2F2,
    )

    fun all(): List<TerminalThemeEntity> = listOf(
        theme("xterm", true, 0xE5E5E5, 0x000000, 0xFFFFFF, 0x4D4D4D, ANSI_XTERM),
        theme("Dracula", true, 0xF8F8F2, 0x282A36, 0xF8F8F2, 0x44475A, ANSI_DRACULA),
        theme("One Dark", true, 0xABB2BF, 0x282C34, 0x528BFF, 0x3E4451, ANSI_ONEDARK),
        theme("Nord", true, 0xD8DEE9, 0x2E3440, 0xD8DEE9, 0x434C5E, ANSI_NORD),
        theme("Gruvbox Dark", true, 0xEBDBB2, 0x282828, 0xEBDBB2, 0x504945, ANSI_GRUVBOX),
        theme("Tokyo Night", true, 0xA9B1D6, 0x1A1B26, 0xC0CAF5, 0x33467C, ANSI_TOKYO),
        theme("Solarized Dark", true, 0x839496, 0x002B36, 0x93A1A1, 0x073642, ANSI_SOLARIZED_DARK),
        theme("Solarized Light", false, 0x657B83, 0xFDF6E3, 0x586E75, 0xEEE8D5, ANSI_SOLARIZED_LIGHT),
        theme("GitHub Light", false, 0x24292E, 0xFFFFFF, 0x0366D6, 0xB3D5FF, ANSI_GITHUB_LIGHT),
        theme("Windows Campbell", true, 0xCCCCCC, 0x0C0C0C, 0xFFFFFF, 0x3A3A3A, ANSI_CAMPBELL),
    )

    private fun theme(
        name: String,
        dark: Boolean,
        fg: Int,
        bg: Int,
        cursor: Int,
        selection: Int,
        ansiColors: String,
    ) = TerminalThemeEntity(
        name = name,
        isDark = dark,
        foreground = fg,
        background = bg,
        cursor = cursor,
        selection = selection,
        ansiColors = ansiColors,
        source = "builtin",
        builtIn = true,
        createdAt = 0,
    )
}
