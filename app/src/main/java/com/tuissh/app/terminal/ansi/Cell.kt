package com.tuissh.app.terminal.ansi

/**
 * A single terminal cell.
 *
 * Packed into one `Int` for speed: rows are `IntArray` so a 200x100 grid is
 * 80 KB and can be copied without allocation pressure during scrolling.
 *
 * Layout:
 *   bits  0..3   colour kind (0 = default, 1 = palette 0-255, 2 = 24-bit)
 *   bits  4..27  colour value
 *   bits 28..31  flags (bold, dim, italic, underline, reverse, blink, hidden, strike)
 *
 * Text is stored separately in a parallel `CharArray`; combining marks and wide
 * characters live in [TerminalRow]. This mirrors the split the original
 * `VTFramebuffer` / `NeoTerminalBuffer` used, which makes repaint cheap.
 */
object Cell {

    const val KIND_DEFAULT = 0
    const val KIND_PALETTE = 1
    const val KIND_RGB = 2

    // Style flags, packed into the top nibble (bits 28..31) of the fg/bg int.
    // Exactly FOUR bits fit, so FLAG_WIDE_TAIL cannot live here as well.
    const val FLAG_BOLD = 1 shl 0
    const val FLAG_DIM = 1 shl 1
    const val FLAG_ITALIC = 1 shl 2
    const val FLAG_UNDERLINE = 1 shl 3
    const val FLAG_BLINK = 1 shl 4
    const val FLAG_REVERSE = 1 shl 5
    const val FLAG_HIDDEN = 1 shl 6
    const val FLAG_STRIKE = 1 shl 7

    /**
     * Wide-glyph tail marker.
     *
     * Stored as a separate packing kind rather than a style flag: the fg/bg int
     * only has four flag bits (bits 28..31) and all four are already used by
     * bold/dim/italic/underline plus the upper style bits. Reusing bit 8 would be
     * silently truncated by [flagsOf], which is exactly the bug that made
     * half-erased wide glyphs survive.
     *
     * A tail cell is therefore encoded as `KIND_WIDE_TAIL` with value 0.
     */
    const val KIND_WIDE_TAIL = 3

    /** Default cell: space with default colours. */
    const val EMPTY_CHAR = ' '

    fun fg(kind: Int, value: Int, flags: Int): Int =
        (kind and 0xF) or ((value and 0xFFFFFF) shl 4) or ((flags and 0xF) shl 28)

    fun bg(kind: Int, value: Int, flags: Int): Int =
        (kind and 0xF) or ((value and 0xFFFFFF) shl 4) or ((flags and 0xF) shl 28)

    fun kindOf(packed: Int): Int = packed and 0xF

    fun valueOf(packed: Int): Int = (packed ushr 4) and 0xFFFFFF

    fun flagsOf(packed: Int): Int = (packed ushr 28) and 0xF

    fun withFlags(packed: Int, flags: Int): Int =
        (packed and 0x0FFFFFFF) or ((flags and 0xF) shl 28)

    /** Build a packed 24-bit colour. */
    fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    fun red(v: Int): Int = (v ushr 16) and 0xFF
    fun green(v: Int): Int = (v ushr 8) and 0xFF
    fun blue(v: Int): Int = v and 0xFF

    /** xterm 256-colour palette entry to 0xRRGGBB. */
    fun paletteToRgb(index: Int): Int = when {
        index < 0 -> 0x000000
        index < 16 -> XTERM_16[index]
        index < 232 -> {
            // 6x6x6 cube, levels 0/95/135/175/215/255.
            val i = index - 16
            val r = if (i / 36 == 0) 0 else 55 + (i / 36) * 40
            val g = if ((i % 36) / 6 == 0) 0 else 55 + ((i % 36) / 6) * 40
            val b = if (i % 6 == 0) 0 else 55 + (i % 6) * 40
            rgb(r, g, b)
        }
        index < 256 -> {
            val level = 8 + (index - 232) * 10
            rgb(level, level, level)
        }
        else -> 0xFFFFFF
    }

    /** The standard 16 ANSI colours (xterm defaults). */
    val XTERM_16 = intArrayOf(
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    )

    /** Resolve a packed colour int to a concrete RGB value. */
    fun resolve(packed: Int, palette: IntArray = XTERM_16): Int = when (kindOf(packed)) {
        KIND_PALETTE -> {
            val idx = valueOf(packed)
            if (idx < 16) palette[idx] else paletteToRgb(idx)
        }
        KIND_RGB -> valueOf(packed)
        KIND_WIDE_TAIL -> -1
        else -> -1
    }
}

/** Mouse / mode flags shared with the input layer. */
object TerminalMode {
    const val CURSOR_VISIBLE = 1 shl 0
    const val APPLICATION_CURSOR = 1 shl 1
    const val ORIGIN = 1 shl 2
    const val WRAPAROUND = 1 shl 3
    const val INSERT = 1 shl 4
    const val NEW_LINE = 1 shl 5
    const val AUTOWRAP = 1 shl 6
    const val BRACKETED_PASTE = 1 shl 7
    const val MOUSE_REPORT_BUTTON = 1 shl 8
    const val MOUSE_REPORT_DRAG = 1 shl 9
    const val MOUSE_REPORT_SGR = 1 shl 10
    const val FOCUS_EVENT = 1 shl 11
    const val ALTERNATE_SCREEN = 1 shl 12
    const val APP_KEYPAD = 1 shl 13
    const val CURSOR_BLINK = 1 shl 14
    const val SHOW_CURSOR = 1 shl 15

    const val DEFAULT = AUTOWRAP or CURSOR_VISIBLE or SHOW_CURSOR or CURSOR_BLINK
}

/** Cursor shape as requested by DECSCUSR. */
enum class CursorShape { BLOCK, BLOCK_BLINK, UNDERLINE, UNDERLINE_BLINK, BAR, BAR_BLINK }
