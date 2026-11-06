package com.tuissh.app.terminal.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.tuissh.app.data.model.TerminalThemeEntity

/**
 * Resolved palette used by the renderer.
 *
 * The database stores colours as packed 0xRRGGBB ints; Compose wants `Color`.
 * Resolution happens once per theme change, not per cell.
 */
data class TerminalPalette(
    val foreground: Color,
    val background: Color,
    val cursor: Color,
    val selection: Color,
    /** 256 resolved entries: the 16 theme colours first, then the xterm cube/greys. */
    val ansi: IntArray,
    val isDark: Boolean,
) {
    /**
     * Resolve a packed foreground/background cell value to a display colour.
     *
     * @param packed the cell's packed int
     * @param default what to use when the cell has no explicit colour
     */
    fun resolve(packed: Int, default: Color): Color {
        val kind = com.tuissh.app.terminal.ansi.Cell.kindOf(packed)
        return when (kind) {
            com.tuissh.app.terminal.ansi.Cell.KIND_PALETTE -> {
                val idx = com.tuissh.app.terminal.ansi.Cell.valueOf(packed)
                Color(0xFF000000.toInt() or ansi[idx.coerceIn(0, 255)])
            }
            com.tuissh.app.terminal.ansi.Cell.KIND_RGB ->
                Color(0xFF000000.toInt() or com.tuissh.app.terminal.ansi.Cell.valueOf(packed))
            else -> default
        }
    }

    companion object {

        /** Build a palette from a stored theme row. */
        fun from(theme: TerminalThemeEntity): TerminalPalette {
            val ansi = IntArray(256)
            val custom = parseAnsi(theme.ansiColors)
            for (i in 0 until 16) {
                ansi[i] = if (i < custom.size) custom[i]
                else com.tuissh.app.terminal.ansi.Cell.XTERM_16[i]
            }
            for (i in 16 until 256) {
                ansi[i] = com.tuissh.app.terminal.ansi.Cell.paletteToRgb(i)
            }
            return TerminalPalette(
                foreground = Color(0xFF000000.toInt() or (theme.foreground and 0xFFFFFF)),
                background = Color(0xFF000000.toInt() or (theme.background and 0xFFFFFF)),
                cursor = Color(0xFF000000.toInt() or (theme.cursor and 0xFFFFFF)),
                selection = Color(0xFF000000.toInt() or (theme.selection and 0xFFFFFF)),
                ansi = ansi,
                isDark = theme.isDark,
            )
        }

        /** Fallback palette when no theme row is available yet. */
        fun default(dark: Boolean = true): TerminalPalette = if (dark) {
            TerminalPalette(
                foreground = Color(0xFFF5F5F7),
                background = Color(0xFF0D0E12),
                cursor = Color(0xFF0A84FF),
                selection = Color(0x660A84FF),
                ansi = IntArray(256) { i ->
                    if (i < 16) com.tuissh.app.terminal.ansi.Cell.XTERM_16[i]
                    else com.tuissh.app.terminal.ansi.Cell.paletteToRgb(i)
                },
                isDark = true,
            )
        } else {
            TerminalPalette(
                foreground = Color(0xFF1D1D1F),
                background = Color(0xFFFAFAFC),
                cursor = Color(0xFF007AFF),
                selection = Color(0x40007AFF),
                ansi = IntArray(256) { i ->
                    if (i < 16) com.tuissh.app.terminal.ansi.Cell.XTERM_16[i]
                    else com.tuissh.app.terminal.ansi.Cell.paletteToRgb(i)
                },
                isDark = false,
            )
        }

        /** `ansiColors` is `rrggbb` hex joined with commas. */
        fun parseAnsi(text: String): List<Int> =
            text.split(',')
                .mapNotNull { it.trim().toIntOrNull(16) }
                .filter { it in 0..0xFFFFFF }
    }
}

/** Font metrics the renderer needs to build the grid. */
data class TerminalTypography(
    val fontSize: TextUnit = 13.sp,
    val fontFamily: FontFamily = FontFamily.Monospace,
    /** Cell advance in px, measured once by the view. */
    val charWidth: Float = 0f,
    /** Cell height in px. */
    val lineHeight: Float = 0f,
    val fontWeightBold: Int = 700,
) {
    val cellValid: Boolean get() = charWidth > 0f && lineHeight > 0f
}
