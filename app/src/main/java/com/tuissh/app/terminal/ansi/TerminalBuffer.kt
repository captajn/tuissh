package com.tuissh.app.terminal.ansi

/**
 * Screen buffer with scrollback.
 *
 * Mirrors `VTFramebuffer` + `NeoTerminalBuffer`. Two independent screens are kept
 * (primary and alternate) for `vim`, `htop`, `less`. Scrollback belongs to the
 * primary screen only, matching xterm behaviour.
 */
class TerminalBuffer(
    rows: Int,
    cols: Int,
    private val scrollbackLimit: Int = 5000,
) {

    var rows: Int = rows
        private set
    var cols: Int = cols
        private set

    private var primary: Array<TerminalRow> = Array(rows) { TerminalRow(cols) }
    private var alternate: Array<TerminalRow> = Array(rows) { TerminalRow(cols) }

    private var altActive = false

    /** Scrollback for the primary screen; oldest first. */
    private val scrollback = ArrayDeque<TerminalRow>(scrollbackLimit)

    /** Exposed for the renderer. */
    val screen: Array<TerminalRow>
        get() = if (altActive) alternate else primary

    val isAlternate: Boolean get() = altActive

    val scrollbackSize: Int get() = scrollback.size

    fun scrollbackRow(index: Int): TerminalRow? = scrollback.getOrNull(index)

    // --- geometry ---------------------------------------------------------

    fun resize(newRows: Int, newCols: Int, fillFg: Int = 0, fillBg: Int = 0) {
        if (newRows == rows && newCols == cols) return
        primary = resizeScreen(primary, newRows, newCols, fillFg, fillBg)
        alternate = resizeScreen(alternate, newRows, newCols, fillFg, fillBg)
        rows = newRows
        cols = newCols
    }

    /**
     * Rebuild one screen at a new size.
     *
     * Rows keep their index when the grid grows: a terminal grows downward and the
     * cursor keeps its content row. When the grid shrinks, the **bottom** rows are
     * kept because that is where the newest output is; the evicted top rows belong
     * in scrollback, and the emulator pushes them there before calling this.
     */
    private fun resizeScreen(
        src: Array<TerminalRow>,
        newRows: Int,
        newCols: Int,
        fillFg: Int,
        fillBg: Int,
    ): Array<TerminalRow> {
        val out = Array(newRows) { TerminalRow(newCols) }
        return if (newRows >= src.size) {
            // Growing: keep every existing row at its index, append blank rows.
            for (i in src.indices) {
                val row = src[i]
                row.resize(newCols, Cell.EMPTY_CHAR, fillFg, fillBg)
                out[i] = row
            }
            out
        } else {
            // Shrinking: keep the bottom `newRows` rows, matching what the user sees.
            val srcStart = src.size - newRows
            for (i in 0 until newRows) {
                val row = src[srcStart + i]
                row.resize(newCols, Cell.EMPTY_CHAR, fillFg, fillBg)
                out[i] = row
            }
            out
        }
    }

    // --- line access ------------------------------------------------------

    operator fun get(row: Int): TerminalRow =
        screen[row.coerceIn(0, rows - 1)]

    fun rowOrNull(row: Int): TerminalRow? =
        if (row in 0 until rows) screen[row] else null

    // --- scrolling --------------------------------------------------------

    /**
     * Scroll the screen up by [lines], pushing evicted primary-screen rows into
     * scrollback.
     */
    fun scrollUp(lines: Int, top: Int = 0, bottom: Int = rows - 1, fillFg: Int = 0, fillBg: Int = 0) {
        if (lines <= 0) return
        val n = minOf(lines, bottom - top + 1)
        if (n <= 0) return
        val target = screen
        repeat(n) {
            val evicted = target[top]
            if (!altActive && top == 0) {
                pushScrollback(evicted)
            }
            System.arraycopy(target, top + 1, target, top, bottom - top)
            val blank = TerminalRow(cols)
            blank.clear(fillFg, fillBg)
            target[bottom] = blank
        }
    }

    /** Scroll down by [lines] (used by reverse index), discarding bottom rows. */
    fun scrollDown(lines: Int, top: Int = 0, bottom: Int = rows - 1, fillFg: Int = 0, fillBg: Int = 0) {
        if (lines <= 0) return
        val n = minOf(lines, bottom - top + 1)
        val target = screen
        repeat(n) {
            System.arraycopy(target, top, target, top + 1, bottom - top)
            val blank = TerminalRow(cols)
            blank.clear(fillFg, fillBg)
            target[top] = blank
        }
    }

    private fun pushScrollback(row: TerminalRow) {
        if (scrollback.size >= scrollbackLimit) scrollback.removeFirst()
        scrollback.addLast(row.clone())
    }

    // --- alternate screen -------------------------------------------------

    fun enterAlternateScreen() {
        if (altActive) return
        altActive = true
        for (r in alternate) r.clear()
    }

    fun exitAlternateScreen() {
        altActive = false
    }

    // --- erasing ----------------------------------------------------------

    fun eraseAll(fillFg: Int, fillBg: Int) {
        for (r in screen) r.clear(fillFg, fillBg)
    }

    fun eraseRows(from: Int, to: Int, fillFg: Int, fillBg: Int) {
        for (r in from..to) if (r in 0 until rows) screen[r].clear(fillFg, fillBg)
    }

    // --- text extraction --------------------------------------------------

    /** Full primary screen as text, bottom-trimmed. */
    fun screenText(): String = buildString {
        for (r in 0 until rows) {
            append(screen[r].trimmedText())
            if (r != rows - 1) append('\n')
        }
    }

    /** Screen plus scrollback. */
    fun fullText(): String = buildString {
        for (r in scrollback) {
            append(r.trimmedText())
            append('\n')
        }
        for (r in 0 until rows) {
            append(screen[r].trimmedText())
            if (r != rows - 1) append('\n')
        }
    }

    /** Extract a rectangular row range as text, for copy operations. */
    fun textRange(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
        if (startRow > endRow) return ""
        return buildString {
            for (row in startRow..endRow) {
                val line = screen.getOrNull(row) ?: continue
                val from = if (row == startRow) startCol.coerceIn(0, cols) else 0
                val to = if (row == endRow) (endCol + 1).coerceIn(0, cols) else cols
                if (from >= to) continue
                val sb = StringBuilder(to - from)
                var c = from
                while (c < to) {
                    if (line.isWideTail(c)) {
                        c++
                        continue
                    }
                    sb.append(line.cells[c])
                    line.combinator[c]?.let { sb.append(it) }
                    c++
                }
                append(sb.toString().trimEnd())
                if (row != endRow) append('\n')
            }
        }
    }

    fun clearScrollback() = scrollback.clear()

    fun clone(): TerminalBuffer {
        val copy = TerminalBuffer(rows, cols, scrollbackLimit)
        copy.primary = Array(rows) { primary[it].clone() }
        copy.alternate = Array(rows) { alternate[it].clone() }
        copy.altActive = altActive
        for (r in scrollback) copy.scrollback.addLast(r.clone())
        return copy
    }
}
