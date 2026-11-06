package com.tuissh.app.terminal.session

/**
 * Translates key events into the byte sequences a pty expects.
 *
 * Mirrors `VTUserInputProcessor` and the `TerminalFixedKeySettings` /
 * `TerminalKeyTriggerSettings` behaviour from the original app.
 *
 * Rules that matter in practice:
 *   - arrow / Home / End / Delete switch between normal and "application cursor"
 *     mode (`ESC O A` vs `ESC [ A`) driven by DECCKM
 *   - Enter sends `\r`; the pty layer translates to newline
 *   - Ctrl+letter maps to 0x01..0x1A, with Ctrl+Space = 0x00 and Ctrl+[ = 0x1B
 *   - Alt+key is prefixed with ESC
 *   - F1..F12 follow xterm-256color
 */
object TerminalInputEncoder {

    /** Keys the UI can send without a character payload. */
    enum class Key {
        UP, DOWN, RIGHT, LEFT,
        HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
        BACKSPACE, TAB, ENTER, ESCAPE,
        F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    }

    const val ESC = "\u001B"

    // --- modifiers ---------------------------------------------------------

    data class Modifiers(
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    ) {
        val any: Boolean get() = ctrl || alt || shift

        /** xterm modifier parameter: 1 + bitmask(shift=1, alt=2, ctrl=4). */
        fun param(): Int {
            var n = 1
            if (shift) n += 1
            if (alt) n += 2
            if (ctrl) n += 4
            return n
        }
    }

    // --- printable text ----------------------------------------------------

    /**
     * Encode a text character with modifiers.
     *
     * @return bytes to send, or empty when the key produces nothing
     */
    fun encodeChar(ch: Char, mods: Modifiers, appCursor: Boolean = false): ByteArray {
        // Ctrl+Space is NUL, Ctrl+[ is ESC, Ctrl+] is GS, Ctrl+^ is RS, Ctrl+_ is US.
        if (mods.ctrl) {
            val ctrl = ctrlCode(ch)
            if (ctrl != null) return wrapAlt(byteArrayOf(ctrl.toByte()), mods)
        }

        val base = when {
            mods.alt && ch.code in 'a'.code..'z'.code -> ch.uppercaseChar()
            else -> ch
        }
        val raw = base.toString().toByteArray(Charsets.UTF_8)
        return wrapAlt(raw, mods)
    }

    /** Ctrl mapping for the printable range. */
    fun ctrlCode(ch: Char): Int? {
        val c = ch.uppercaseChar()
        return when {
            c in '@'..'_' -> c.code - 0x40      // @ A-Z [ \ ] ^ _  → 0x00..0x1F
            c == '?' -> 0x7F
            c == '2' -> 0x00                     // Ctrl+2 = NUL
            c == '3' -> 0x1B                     // Ctrl+3 = ESC
            c == '4' -> 0x1C
            c == '5' -> 0x1D
            c == '6' -> 0x1E
            c == '7' -> 0x1F
            c == '8' -> 0x7F
            else -> null
        }
    }

    private fun wrapAlt(raw: ByteArray, mods: Modifiers): ByteArray =
        if (mods.alt && !mods.ctrl) {
            byteArrayOf(0x1B) + raw
        } else {
            raw
        }

    // --- special keys ------------------------------------------------------

    /**
     * Encode a non-printable key.
     *
     * @param appCursor true when DECCKM is set (`ESC O` prefix for cursor keys)
     */
    fun encodeKey(key: Key, mods: Modifiers, appCursor: Boolean): ByteArray {
        // Modified cursor keys always use the CSI-parameter form, never SS3.
        if (mods.any && key in CURSOR_KEYS) {
            return csi("1;${mods.param()}", cursorFinal(key)).toByteArray()
        }

        return when (key) {
            Key.UP -> (if (appCursor) "${ESC}OA" else "${ESC}[A").toByteArray()
            Key.DOWN -> (if (appCursor) "${ESC}OB" else "${ESC}[B").toByteArray()
            Key.RIGHT -> (if (appCursor) "${ESC}OC" else "${ESC}[C").toByteArray()
            Key.LEFT -> (if (appCursor) "${ESC}OD" else "${ESC}[D").toByteArray()

            Key.HOME -> (if (appCursor) "${ESC}OH" else "${ESC}[H").toByteArray()
            Key.END -> (if (appCursor) "${ESC}OF" else "${ESC}[F").toByteArray()

            Key.INSERT -> "${ESC}[2~".toByteArray()
            Key.DELETE -> "${ESC}[3~".toByteArray()
            Key.PAGE_UP -> "${ESC}[5~".toByteArray()
            Key.PAGE_DOWN -> "${ESC}[6~".toByteArray()

            Key.BACKSPACE -> byteArrayOf(0x7F)
            Key.TAB -> if (mods.shift) "${ESC}[Z".toByteArray() else byteArrayOf(0x09)
            Key.ENTER -> byteArrayOf(0x0D)
            Key.ESCAPE -> byteArrayOf(0x1B)

            Key.F1 -> "${ESC}OP".toByteArray()
            Key.F2 -> "${ESC}OQ".toByteArray()
            Key.F3 -> "${ESC}OR".toByteArray()
            Key.F4 -> "${ESC}OS".toByteArray()
            Key.F5 -> "${ESC}[15~".toByteArray()
            Key.F6 -> "${ESC}[17~".toByteArray()
            Key.F7 -> "${ESC}[18~".toByteArray()
            Key.F8 -> "${ESC}[19~".toByteArray()
            Key.F9 -> "${ESC}[20~".toByteArray()
            Key.F10 -> "${ESC}[21~".toByteArray()
            Key.F11 -> "${ESC}[23~".toByteArray()
            Key.F12 -> "${ESC}[24~".toByteArray()
        }
    }

    private val CURSOR_KEYS = setOf(Key.UP, Key.DOWN, Key.RIGHT, Key.LEFT, Key.HOME, Key.END)

    private fun cursorFinal(key: Key): Char = when (key) {
        Key.UP -> 'A'
        Key.DOWN -> 'B'
        Key.RIGHT -> 'C'
        Key.LEFT -> 'D'
        Key.HOME -> 'H'
        Key.END -> 'F'
        else -> 'A'
    }

    private fun csi(params: String, final: Char): String = "$ESC[$params$final"

    // --- mouse -------------------------------------------------------------

    /**
     * SGR mouse report (`ESC [ < b ; x ; y M|m`).
     *
     * @param button 0 left, 1 middle, 2 right, 64/65 wheel
     * @param pressed true for press, false for release
     */
    fun encodeMouseSgr(
        button: Int,
        col: Int,
        row: Int,
        pressed: Boolean,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
    ): ByteArray {
        var b = button
        if (shift) b += 4
        if (alt) b += 8
        if (ctrl) b += 16
        val suffix = if (pressed) 'M' else 'm'
        return "$ESC[<${b};${col + 1};${row + 1}$suffix".toByteArray()
    }

    /** X10 mouse report, used by older apps that never negotiated SGR. */
    fun encodeMouseX10(button: Int, col: Int, row: Int): ByteArray = byteArrayOf(
        0x1B, '['.code.toByte(),
        'M'.code.toByte(),
        (32 + button).toByte(),
        (33 + col).coerceIn(33, 255).toByte(),
        (33 + row).coerceIn(33, 255).toByte(),
    )

    /** Focus in/out events, only when the app enabled mode 1004. */
    fun encodeFocus(focused: Boolean): ByteArray =
        (if (focused) "${ESC}[I" else "${ESC}[O").toByteArray()
}
