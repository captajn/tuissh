package com.tuissh.app.terminal.parse

import com.tuissh.app.terminal.ansi.Cell
import com.tuissh.app.terminal.ansi.CursorShape
import com.tuissh.app.terminal.ansi.TerminalBuffer
import com.tuissh.app.terminal.ansi.TerminalMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full VT emulator: parser + buffer + cursor + modes + charmaps + tabs.
 *
 * Direct port of `VTEmulator` / `VTSequenceDispatcher` / `VTFunctionRegistry` /
 * `VTUserInputProcessor` semantics. Everything the app's own terminal depends on
 * (vim, htop, tmux, mosh, SSH) is covered here.
 */
class VtEmulator(
    rows: Int,
    cols: Int,
    scrollbackLimit: Int = 5000,
) : VtParser.Handler {

    val buffer = TerminalBuffer(rows, cols, scrollbackLimit)
    private val parser = VtParser(this)

    var rows: Int = rows
        private set
    var cols: Int = cols
        private set

    // --- cursor -----------------------------------------------------------
    var cursorRow: Int = 0
    var cursorCol: Int = 0
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedPackedFg = 0
    private var savedPackedBg = 0

    // --- attributes -------------------------------------------------------
    private var packedFg = Cell.fg(Cell.KIND_DEFAULT, 0, 0)
    private var packedBg = Cell.bg(Cell.KIND_DEFAULT, 0, 0)
    private var flags = 0

    // --- modes ------------------------------------------------------------
    var modes: Int = TerminalMode.DEFAULT
        private set
    var cursorShape: CursorShape = CursorShape.BLOCK
        private set

    /** Scroll region, inclusive. Read-only outside the emulator. */
    var scrollTop = 0
        private set
    var scrollBottom = rows - 1
        private set

    /** Tab stops, column indices. Six-line UI rarely needs more than 200. */
    private val tabStops = HashSet<Int>()

    /** G0-G3 character sets; 'B' = ASCII, '0' = DEC graphics, 'A' = UK. */
    private val charsets = CharArray(4) { 'B' }
    private var glIndex = 0
    private var shiftOut = false // SO / SI

    /** Last printed char, for REP (repeat). */
    private var lastPrinted = ' '

    /** Pending wrap flag, xterm deferred-wrap semantics. */
    private var wrapPending = false

    // --- title / notifications -------------------------------------------
    var title: String = ""
        private set
    var iconName: String = ""
        private set

    /** Callbacks for higher layers (OSC 9 / 777 notifications, bell, clipboard). */
    var onBell: (() -> Unit)? = null
    var onTitleChanged: ((String) -> Unit)? = null
    var onNotification: ((title: String, body: String) -> Unit)? = null
    var onClipboardWrite: ((String) -> Unit)? = null
    var onResponse: ((String) -> Unit)? = null

    /** Kitty / iTerm inline image payloads, forwarded to the renderer. */
    var onImage: ((protocol: String, payload: String) -> Unit)? = null

    var damageListener: (() -> Unit)? = null

    init {
        resetTabStops()
    }

    // =====================================================================
    // Public API
    // =====================================================================

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        parser.feed(bytes, offset, length)
        damageListener?.invoke()
    }

    /** Lines held above the visible screen, for the scroll gesture. */
    val scrollbackSize: Int get() = buffer.scrollbackSize

    /** One historical row, 0 being the oldest line still retained. */
    fun scrollbackRow(index: Int) = buffer.scrollbackRow(index)

    fun feed(text: String) {
        parser.feed(text)
        damageListener?.invoke()
    }

    /**
     * Apply a new grid size.
     *
     * A terminal grows **downward**: existing content keeps its row index and the
     * new rows are appended at the bottom. The cursor therefore stays exactly where
     * it was (clamped to the new bounds), which is what xterm does and what a shell
     * expects after a window resize.
     *
     * Anchoring the cursor a fixed distance above the bottom edge is wrong: a
     * 24-row session resized to 50 rows would push a one-line banner to row 48 and
     * render it in the middle of the view.
     */
    fun resize(newRows: Int, newCols: Int) {
        if (newRows == rows && newCols == cols) return
        val fillFg = packedFg
        val fillBg = packedBg

        buffer.resize(newRows, newCols, fillFg, fillBg)
        rows = newRows
        cols = newCols
        scrollTop = 0
        scrollBottom = newRows - 1

        // Keep the cursor on the same content row.
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
        resetTabStops()
        damageListener?.invoke()
    }

    fun reset() {
        parser.reset()
        buffer.eraseAll(packedFg, packedBg)
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        flags = 0
        packedFg = Cell.fg(Cell.KIND_DEFAULT, 0, 0)
        packedBg = Cell.bg(Cell.KIND_DEFAULT, 0, 0)
        modes = TerminalMode.DEFAULT
        resetTabStops()
        damageListener?.invoke()
    }

    fun isModeSet(mode: Int): Boolean = (modes and mode) != 0

    /** Current selection-friendly text of the visible screen. */
    fun visibleText(): String = buffer.screenText()

    /** Screen + scrollback. */
    fun allText(): String = buffer.fullText()

    // =====================================================================
    // Parser.Handler — printing
    // =====================================================================

    override fun print(codepoint: Int) {
        if (codepoint < 0x20) return

        val ch = when {
            codepoint < 0x80 -> {
                val cs = activeCharset()
                if (cs == '0') decSpecial(codepoint) else codepoint.toChar()
            }
            else -> codepointToChar(codepoint)
        }
        putChar(ch)
        lastPrinted = ch
    }

    private fun codepointToChar(cp: Int): Char = when {
        cp <= 0xFFFF -> cp.toChar()
        else -> {
            // Supplementary plane: surrogate pair. The buffer stores one Char per
            // cell, so we store a replacement and push the real codepoint into the
            // combinator string of the head cell.
            Char(0xFFFD)
        }
    }

    private fun putChar(ch: Char) {
        if (wrapPending && isModeSet(TerminalMode.AUTOWRAP)) {
            wrapPending = false
            cursorCol = 0
            lineFeedInternal()
        }

        val width = charWidth(ch)
        if (width == 0) {
            // Combining mark: attach to the previous cell.
            val col = if (cursorCol > 0) cursorCol - 1 else 0
            val row = buffer[cursorRow]
            if (col in 0 until row.size) {
                val existing = row.combinator[col]
                row.combinator[col] = (existing ?: "") + ch
            }
            return
        }

        // A wide glyph needs two free cells; if only one remains before the right
        // edge, wrap first rather than splitting the glyph across rows.
        if (width == 2 && cursorCol == cols - 1) {
            if (isModeSet(TerminalMode.AUTOWRAP)) {
                cursorCol = 0
                lineFeedInternal()
            } else {
                return
            }
        }

        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeedInternal()
        }

        val row = buffer[cursorRow]

        if (isModeSet(TerminalMode.INSERT)) {
            row.insertCells(cursorCol, width, packedFg, packedBg)
        }

        row.set(cursorCol, ch, packedFg, packedBg)
        if (width == 2 && cursorCol + 1 < cols) {
            // The tail cell shares the head's colours and carries the tail flag so
            // text extraction and rendering skip it.
            row.set(cursorCol + 1, Cell.EMPTY_CHAR, packedFg, packedBg)
            row.markWideTail(cursorCol + 1)
        }

        cursorCol += width
        if (cursorCol >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        }
    }

    private fun activeCharset(): Char {
        val index = if (shiftOut) 1 else glIndex
        return charsets[index.coerceIn(0, 3)]
    }

    /** DEC Special Graphics (line drawing) mapping. */
    private fun decSpecial(cp: Int): Char = when (cp) {
        '`'.code -> '◆'
        'a'.code -> '▒'
        'b'.code -> '␉'
        'c'.code -> '␌'
        'd'.code -> '␍'
        'e'.code -> '␊'
        'f'.code -> '°'
        'g'.code -> '±'
        'h'.code -> '␤'
        'i'.code -> '␋'
        'j'.code -> '┘'
        'k'.code -> '┐'
        'l'.code -> '┌'
        'm'.code -> '└'
        'n'.code -> '┼'
        'o'.code -> '⎺'
        'p'.code -> '⎻'
        'q'.code -> '─'
        'r'.code -> '⎼'
        's'.code -> '⎽'
        't'.code -> '├'
        'u'.code -> '┤'
        'v'.code -> '┴'
        'w'.code -> '┬'
        'x'.code -> '│'
        'y'.code -> '≤'
        'z'.code -> '≥'
        '{'.code -> 'π'
        '|'.code -> '≠'
        '}'.code -> '£'
        '~'.code -> '·'
        else -> cp.toChar()
    }

    // =====================================================================
    // Parser.Handler — C0 controls
    // =====================================================================

    override fun execute(control: Int) {
        when (control) {
            0x07 -> onBell?.invoke()                                    // BEL
            0x08 -> {                                                   // BS
                if (cursorCol > 0) cursorCol--
                wrapPending = false
            }
            0x09 -> tab(1)                                             // HT
            0x0A, 0x0B, 0x0C -> lineFeedInternal()                      // LF VT FF
            0x0D -> {                                                   // CR
                cursorCol = 0
                wrapPending = false
            }
            0x0E -> shiftOut = true                                     // SO
            0x0F -> shiftOut = false                                    // SI
            else -> Unit
        }
    }

    // =====================================================================
    // Parser.Handler — ESC
    // =====================================================================

    override fun esc(intermediates: String, final: Char) {
        when {
            intermediates.isEmpty() -> when (final) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'D' -> lineFeedInternal()                               // IND
                'E' -> { lineFeedInternal(); cursorCol = 0 }            // NEL
                'H' -> tabStops.add(cursorCol)                          // HTS
                'M' -> reverseIndex()                                   // RI
                'N' -> Unit                                             // SS2 (single shift, ignored)
                'O' -> Unit                                             // SS3
                'Z' -> {                                                // DECID -> respond as VT100
                    onResponse?.invoke("\u001B[?6c")
                }
                'c' -> reset()                                          // RIS
                '=' -> modes = modes or TerminalMode.APP_KEYPAD
                '>' -> modes = modes and TerminalMode.APP_KEYPAD.inv()
                else -> Unit
            }
            intermediates == "(" -> charsets[0] = final
            intermediates == ")" -> charsets[1] = final
            intermediates == "*" -> charsets[2] = final
            intermediates == "+" -> charsets[3] = final
            intermediates == "#" && final == '8' -> screenAlignmentPattern()
            else -> Unit
        }
    }

    private fun screenAlignmentPattern() {
        for (r in 0 until rows) {
            val row = buffer[r]
            for (c in 0 until cols) row.set(c, 'E', packedFg, packedBg)
        }
    }

    // =====================================================================
    // Parser.Handler — CSI
    // =====================================================================

    override fun csi(
        prefix: Char,
        params: IntArray,
        paramCount: Int,
        privateMarker: Char,
        intermediates: String,
        final: Char,
        subParams: Array<IntArray>?,
    ) {
        fun p(i: Int, def: Int = 1): Int {
            if (i >= paramCount) return def
            val v = params[i]
            return if (v < 0) def else v
        }

        // Private modes: CSI ? Pm h/l
        if (privateMarker == '?' && (final == 'h' || final == 'l')) {
            val enable = final == 'h'
            for (i in 0 until max(paramCount, 1)) {
                val mode = if (paramCount == 0) 0 else params[i]
                setPrivateMode(mode, enable)
            }
            return
        }

        // DECSCUSR: CSI Ps SP q
        if (intermediates == " " && final == 'q') {
            cursorShape = when (p(0)) {
                0, 1 -> CursorShape.BLOCK_BLINK
                2 -> CursorShape.BLOCK
                3 -> CursorShape.UNDERLINE_BLINK
                4 -> CursorShape.UNDERLINE
                5 -> CursorShape.BAR_BLINK
                6 -> CursorShape.BAR
                else -> CursorShape.BLOCK_BLINK
            }
            return
        }

        when (final) {
            '@' -> buffer[cursorRow].insertCells(cursorCol, p(0), packedFg, packedBg)   // ICH
            'A' -> cursorRow = max(scrollTop, cursorRow - p(0))                        // CUU
            'B' -> cursorRow = min(scrollBottom, cursorRow + p(0))                     // CUD
            'C' -> cursorCol = min(cols - 1, cursorCol + p(0))                         // CUF
            'D' -> cursorCol = max(0, cursorCol - p(0))                                // CUB
            'E' -> { cursorRow = min(scrollBottom, cursorRow + p(0)); cursorCol = 0 }  // CNL
            'F' -> { cursorRow = max(scrollTop, cursorRow - p(0)); cursorCol = 0 }     // CPL
            'G', '`' -> cursorCol = (p(0) - 1).coerceIn(0, cols - 1)                   // CHA / HPA
            'H', 'f' -> {                                                              // CUP / HVP
                val row = p(0) - 1
                val col = p(1) - 1
                cursorRow = if (isModeSet(TerminalMode.ORIGIN)) {
                    (scrollTop + row).coerceIn(scrollTop, scrollBottom)
                } else {
                    row.coerceIn(0, rows - 1)
                }
                cursorCol = col.coerceIn(0, cols - 1)
                wrapPending = false
            }
            'I' -> repeat(p(0)) { tab(1) }                                             // CHT
            // ED, EL and TBC default to parameter 0, not 1. Passing the generic
            // default of 1 turned every bare `ESC[K` into `ESC[1K` and erased the
            // wrong half of the line.
            'J' -> eraseInDisplay(p(0, def = 0))                                       // ED
            'K' -> eraseInLine(p(0, def = 0))                                          // EL
            'L' -> insertLines(p(0))                                                   // IL
            'M' -> deleteLines(p(0))                                                   // DL
            'P' -> buffer[cursorRow].deleteCells(cursorCol, p(0), packedFg, packedBg)  // DCH
            'S' -> buffer.scrollUp(p(0), scrollTop, scrollBottom, packedFg, packedBg)  // SU
            'T' -> buffer.scrollDown(p(0), scrollTop, scrollBottom, packedFg, packedBg) // SD
            'X' -> buffer[cursorRow].erase(cursorCol, p(0), packedFg, packedBg)        // ECH
            'Z' -> repeat(p(0)) { tab(-1) }                                            // CBT
            'a' -> cursorCol = min(cols - 1, cursorCol + p(0))                         // HPR
            'b' -> {                                                                   // REP
                val count = p(0)
                val ch = lastPrinted
                repeat(count) { putChar(ch) }
            }
            'c' -> onResponse?.invoke("\u001B[?62;22c")                                // DA1 (VT220)
            'd' -> cursorRow = (p(0) - 1).coerceIn(0, rows - 1)                        // VPA
            'e' -> cursorRow = min(scrollBottom, cursorRow + p(0))                     // VPR
            'g' -> clearTabStops(p(0, def = 0))                                        // TBC
            'h' -> {                                                                   // SM
                for (i in 0 until max(paramCount, 1)) {
                    setAnsiMode(if (paramCount == 0) 0 else params[i], true)
                }
            }
            'l' -> {                                                                   // RM
                for (i in 0 until max(paramCount, 1)) {
                    setAnsiMode(if (paramCount == 0) 0 else params[i], false)
                }
            }
            'm' -> selectGraphicRendition(params, paramCount, subParams)               // SGR
            'n' -> {                                                                   // DSR
                when (p(0)) {
                    5 -> onResponse?.invoke("\u001B[0n")
                    6 -> onResponse?.invoke("\u001B[${cursorRow + 1};${cursorCol + 1}R")
                }
            }
            'q' -> Unit                                                                // DECLL
            'r' -> {                                                                   // DECSTBM
                val top = p(0) - 1
                val bottom = if (paramCount >= 2) p(1) - 1 else rows - 1
                if (top in 0 until bottom && bottom < rows) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorRow = if (isModeSet(TerminalMode.ORIGIN)) scrollTop else 0
                    cursorCol = 0
                }
            }
            's' -> saveCursor()                                                        // SCOSC
            'u' -> restoreCursor()                                                     // SCORC
            't' -> handleWindowOps(p(0))                                               // window ops
            'x' -> Unit                                                                // DECREQTPARM
            else -> Unit
        }
    }

    // =====================================================================
    // Modes
    // =====================================================================

    private fun setPrivateMode(mode: Int, enable: Boolean) {
        val bit = when (mode) {
            1 -> TerminalMode.APPLICATION_CURSOR
            6 -> TerminalMode.ORIGIN
            7 -> TerminalMode.AUTOWRAP
            12 -> TerminalMode.CURSOR_BLINK
            25 -> TerminalMode.SHOW_CURSOR
            47, 1047, 1049 -> {
                if (enable) {
                    if (mode == 1049) saveCursor()
                    buffer.enterAlternateScreen()
                } else {
                    buffer.exitAlternateScreen()
                    if (mode == 1049) restoreCursor()
                }
                TerminalMode.ALTERNATE_SCREEN
            }
            2004 -> TerminalMode.BRACKETED_PASTE
            1000 -> TerminalMode.MOUSE_REPORT_BUTTON
            1002 -> TerminalMode.MOUSE_REPORT_DRAG
            1006 -> TerminalMode.MOUSE_REPORT_SGR
            1004 -> TerminalMode.FOCUS_EVENT
            else -> 0
        }
        if (bit == 0) return
        modes = if (enable) modes or bit else modes and bit.inv()
    }

    private fun setAnsiMode(mode: Int, enable: Boolean) {
        val bit = when (mode) {
            4 -> TerminalMode.INSERT
            20 -> TerminalMode.NEW_LINE
            else -> 0
        }
        if (bit == 0) return
        modes = if (enable) modes or bit else modes and bit.inv()
    }

    // =====================================================================
    // SGR
    // =====================================================================

    private fun selectGraphicRendition(params: IntArray, count: Int, subParams: Array<IntArray>?) {
        if (count == 0) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < count) {
            when (val p = params[i]) {
                0 -> resetAttributes()
                1 -> flags = flags or Cell.FLAG_BOLD
                2 -> flags = flags or Cell.FLAG_DIM
                3 -> flags = flags or Cell.FLAG_ITALIC
                4 -> flags = flags or Cell.FLAG_UNDERLINE
                5, 6 -> flags = flags or Cell.FLAG_BLINK
                7 -> flags = flags or Cell.FLAG_REVERSE
                8 -> flags = flags or Cell.FLAG_HIDDEN
                9 -> flags = flags or Cell.FLAG_STRIKE
                21 -> flags = flags and Cell.FLAG_BOLD.inv()
                22 -> flags = flags and (Cell.FLAG_BOLD or Cell.FLAG_DIM).inv()
                23 -> flags = flags and Cell.FLAG_ITALIC.inv()
                24 -> flags = flags and Cell.FLAG_UNDERLINE.inv()
                25 -> flags = flags and Cell.FLAG_BLINK.inv()
                27 -> flags = flags and Cell.FLAG_REVERSE.inv()
                28 -> flags = flags and Cell.FLAG_HIDDEN.inv()
                29 -> flags = flags and Cell.FLAG_STRIKE.inv()
                in 30..37 -> packedFg = Cell.fg(Cell.KIND_PALETTE, p - 30, Cell.flagsOf(packedFg))
                38 -> {
                    val consumed = applyExtendedColor(p, params, count, subParams, true, i)
                    i += consumed
                }
                39 -> packedFg = Cell.fg(Cell.KIND_DEFAULT, 0, Cell.flagsOf(packedFg))
                in 40..47 -> packedBg = Cell.bg(Cell.KIND_PALETTE, p - 40, Cell.flagsOf(packedBg))
                48 -> {
                    val consumed = applyExtendedColor(p, params, count, subParams, false, i)
                    i += consumed
                }
                49 -> packedBg = Cell.bg(Cell.KIND_DEFAULT, 0, Cell.flagsOf(packedBg))
                in 90..97 -> packedFg = Cell.fg(Cell.KIND_PALETTE, p - 90 + 8, Cell.flagsOf(packedFg))
                in 100..107 -> packedBg = Cell.bg(Cell.KIND_PALETTE, p - 100 + 8, Cell.flagsOf(packedBg))
                else -> Unit
            }
            i++
        }
    }

    /** Handles `38`/`48` extended colours; returns how many params were consumed. */
    private fun applyExtendedColor(
        code: Int,
        params: IntArray,
        count: Int,
        subParams: Array<IntArray>?,
        foreground: Boolean,
        index: Int,
    ): Int {
        // Colon form arrives in subParams: 38:5:n or 38:2:r:g:b
        if (subParams != null) {
            for (sp in subParams) {
                if (sp.isEmpty() || sp[0] != code) continue
                when {
                    sp.size >= 3 && sp[1] == 5 -> {
                        setColor(foreground, Cell.KIND_PALETTE, sp[2])
                        return 0
                    }
                    sp.size >= 5 && sp[1] == 2 -> {
                        setColor(foreground, Cell.KIND_RGB, rgb(sp[2], sp[3], sp[4]))
                        return 0
                    }
                }
            }
        }
        // Semicolon form: 38;5;n or 38;2;r;g;b
        if (index + 1 >= count) return 0
        val mode = params[index + 1]
        return when {
            mode == 5 && index + 2 < count -> {
                setColor(foreground, Cell.KIND_PALETTE, params[index + 2])
                2
            }
            mode == 2 && index + 4 < count -> {
                setColor(foreground, Cell.KIND_RGB, rgb(params[index + 2], params[index + 3], params[index + 4]))
                4
            }
            else -> 0
        }
    }

    private fun setColor(foreground: Boolean, kind: Int, value: Int) {
        if (foreground) {
            packedFg = Cell.fg(kind, value, Cell.flagsOf(packedFg))
        } else {
            packedBg = Cell.bg(kind, value, Cell.flagsOf(packedBg))
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun resetAttributes() {
        flags = 0
        packedFg = Cell.fg(Cell.KIND_DEFAULT, 0, 0)
        packedBg = Cell.bg(Cell.KIND_DEFAULT, 0, 0)
    }

    // =====================================================================
    // Erase / insert / delete
    // =====================================================================

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                buffer[cursorRow].eraseRange(cursorCol, cols - 1, packedFg, packedBg)
                buffer.eraseRows(cursorRow + 1, rows - 1, packedFg, packedBg)
            }
            1 -> {
                buffer.eraseRows(0, cursorRow - 1, packedFg, packedBg)
                buffer[cursorRow].eraseRange(0, cursorCol, packedFg, packedBg)
            }
            2 -> buffer.eraseAll(packedFg, packedBg)
            3 -> buffer.clearScrollback()
        }
    }

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> buffer[cursorRow].eraseRange(cursorCol, cols - 1, packedFg, packedBg)
            1 -> buffer[cursorRow].eraseRange(0, cursorCol, packedFg, packedBg)
            2 -> buffer[cursorRow].eraseRange(0, cols - 1, packedFg, packedBg)
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        buffer.scrollDown(count, cursorRow, scrollBottom, packedFg, packedBg)
        cursorCol = 0
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        buffer.scrollUp(count, cursorRow, scrollBottom, packedFg, packedBg)
        cursorCol = 0
    }

    // =====================================================================
    // Cursor movement helpers
    // =====================================================================

    private fun lineFeedInternal() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            buffer.scrollUp(1, scrollTop, scrollBottom, packedFg, packedBg)
        } else {
            cursorRow = min(rows - 1, cursorRow + 1)
        }
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorRow == scrollTop) {
            buffer.scrollDown(1, scrollTop, scrollBottom, packedFg, packedBg)
        } else {
            cursorRow = max(0, cursorRow - 1)
        }
    }

    private fun tab(direction: Int) {
        if (direction > 0) {
            var next = cols - 1
            for (stop in tabStops.sorted()) if (stop > cursorCol) { next = stop; break }
            cursorCol = min(cols - 1, next)
        } else {
            var prev = 0
            for (stop in tabStops.sortedDescending()) if (stop < cursorCol) { prev = stop; break }
            cursorCol = prev
        }
        wrapPending = false
    }

    private fun resetTabStops() {
        tabStops.clear()
        var c = 8
        while (c < cols) {
            tabStops.add(c)
            c += 8
        }
    }

    private fun clearTabStops(mode: Int) {
        when (mode) {
            0 -> tabStops.remove(cursorCol)
            3 -> tabStops.clear()
        }
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        savedPackedFg = packedFg
        savedPackedBg = packedBg
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
        packedFg = savedPackedFg
        packedBg = savedPackedBg
        wrapPending = false
    }

    private fun handleWindowOps(op: Int) {
        when (op) {
            8 -> Unit // resize request from app; ignored unless the view can comply
            18 -> onResponse?.invoke("\u001B[8;${rows};${cols}t")
            19 -> onResponse?.invoke("\u001B[9;${rows};${cols}t")
            20 -> onResponse?.invoke("\u001B]L${title}\u0007")
            21 -> onResponse?.invoke("\u001B]l${iconName}\u0007")
            22 -> Unit
        }
    }

    // =====================================================================
    // OSC / DCS
    // =====================================================================

    override fun osc(command: Int, payload: String) {
        when (command) {
            0 -> {
                title = payload
                iconName = payload
                onTitleChanged?.invoke(payload)
            }
            1 -> {
                iconName = payload
            }
            2 -> {
                title = payload
                onTitleChanged?.invoke(payload)
            }
            4 -> Unit                                                            // colour palette set
            7 -> Unit                                                            // cwd (file://host/path)
            8 -> Unit                                                            // hyperlink
            9 -> onNotification?.invoke("", payload)                             // iTerm2 growl
            10 -> Unit                                                           // fg colour query
            11 -> Unit                                                           // bg colour query
            52 -> {                                                              // clipboard
                val parts = payload.split(';', limit = 2)
                if (parts.size == 2) {
                    val data = parts[1]
                    if (data.isNotEmpty() && data != "?") {
                        runCatching {
                            val decoded = String(java.util.Base64.getDecoder().decode(data))
                            onClipboardWrite?.invoke(decoded)
                        }
                    }
                }
            }
            133 -> Unit                                                          // shell integration marks
            1337 -> onImage?.invoke("iterm", payload)                            // iTerm2 inline image
            777 -> {                                                             // notifications
                val parts = payload.split(';')
                if (parts.size >= 4 && parts[0] == "notify") {
                    onNotification?.invoke(parts[2], parts[3])
                }
            }
            5522 -> onImage?.invoke("kitty", payload)                            // Kitty graphics
            else -> Unit
        }
    }

    override fun dcs(kind: Char, payload: String) {
        when (kind) {
            'P' -> {
                // Kitty graphics also arrives as `ESC P <control> ESC \`.
                if (payload.startsWith("q")) onImage?.invoke("kitty-dcs", payload)
                // Sixel starts with a numeric parameter then 'q'.
                else if (payload.length > 1 && payload[1] == 'q') onImage?.invoke("sixel", payload)
            }
            '_' -> onImage?.invoke("apc", payload)
            else -> Unit
        }
    }

    // =====================================================================
    // Character width
    // =====================================================================

    companion object {

        /**
         * East-Asian width, condensed. 2 for wide/fullwidth, 0 for combining,
         * 1 otherwise. Emoji presentation sequences are treated as wide when the
         * base codepoint is in a known emoji block.
         */
        fun charWidth(ch: Char): Int {
            val cp = ch.code
            return when {
                cp < 0x20 -> 0
                cp in 0x0300..0x036F -> 0  // combining diacritics
                cp in 0x1AB0..0x1AFF -> 0
                cp in 0x1DC0..0x1DFF -> 0
                cp in 0x20D0..0x20FF -> 0
                cp in 0xFE00..0xFE0F -> 0  // variation selectors
                cp in 0xFE20..0xFE2F -> 0
                cp in 0x200B..0x200F -> 0
                cp == 0x00AD -> 0          // soft hyphen
                cp in 0x1100..0x115F -> 2  // Hangul Jamo
                cp in 0x2E80..0x303E -> 2  // CJK radicals / punctuation
                cp in 0x3041..0x33FF -> 2  // Hiragana..CJK compat
                cp in 0x3400..0x4DBF -> 2  // CJK ext A
                cp in 0x4E00..0x9FFF -> 2  // CJK unified
                cp in 0xA000..0xA4CF -> 2  // Yi
                cp in 0xAC00..0xD7A3 -> 2  // Hangul syllables
                cp in 0xF900..0xFAFF -> 2  // CJK compat ideographs
                cp in 0xFE30..0xFE6F -> 2  // CJK compat forms
                cp in 0xFF00..0xFF60 -> 2  // fullwidth forms
                cp in 0xFFE0..0xFFE6 -> 2
                cp in 0x1F300..0x1F64F -> 2 // emoji
                cp in 0x1F900..0x1F9FF -> 2
                cp in 0x1F680..0x1F6FF -> 2
                cp in 0x20000..0x2FFFD -> 2
                cp in 0x30000..0x3FFFD -> 2
                else -> 1
            }
        }

        fun charWidth(cp: Int): Int =
            if (cp <= 0xFFFF) charWidth(cp.toChar()) else 2
    }
}
