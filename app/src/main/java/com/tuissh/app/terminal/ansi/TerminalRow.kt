package com.tuissh.app.terminal.ansi

/**
 * Growable row of terminal cells.
 *
 * Mirrors `VTRow` from the original `MoshTerminal` module. Wide characters occupy
 * two cells: the head holds the codepoint, the tail is marked with
 * [Cell.FLAG_WIDE_TAIL] so rendering and cursor movement treat it as one glyph.
 */
class TerminalRow(cols: Int) {

    var cells: CharArray = CharArray(cols) { Cell.EMPTY_CHAR }
        private set

    var fg: IntArray = IntArray(cols) { Cell.fg(Cell.KIND_DEFAULT, 0, 0) }
        private set

    var bg: IntArray = IntArray(cols) { Cell.bg(Cell.KIND_DEFAULT, 0, 0) }
        private set

    /**
     * Extra codepoints for a cell: variation selectors, ZWJ sequences, combining
     * marks. Keyed by column; value is the appended string.
     */
    var combinator: Array<String?> = arrayOfNulls(cols)
        private set

    val size: Int get() = cells.size

    fun resize(cols: Int, fill: Char = Cell.EMPTY_CHAR, fillFg: Int = 0, fillBg: Int = 0) {
        if (cols == cells.size) return
        val newCells = CharArray(cols) { fill }
        val newFg = IntArray(cols) { fillFg }
        val newBg = IntArray(cols) { fillBg }
        val newComb = arrayOfNulls<String>(cols)
        val keep = minOf(cols, cells.size)
        System.arraycopy(cells, 0, newCells, 0, keep)
        System.arraycopy(fg, 0, newFg, 0, keep)
        System.arraycopy(bg, 0, newBg, 0, keep)
        System.arraycopy(combinator, 0, newComb, 0, keep)
        cells = newCells
        fg = newFg
        bg = newBg
        combinator = newComb
    }

    fun clear(fillFg: Int = 0, fillBg: Int = 0) {
        cells.fill(Cell.EMPTY_CHAR)
        fg.fill(fillFg)
        bg.fill(fillBg)
        combinator.fill(null)
    }

    fun copyFrom(other: TerminalRow) {
        if (cells.size != other.cells.size) resize(other.cells.size)
        System.arraycopy(other.cells, 0, cells, 0, cells.size)
        System.arraycopy(other.fg, 0, fg, 0, fg.size)
        System.arraycopy(other.bg, 0, bg, 0, bg.size)
        for (i in combinator.indices) combinator[i] = other.combinator[i]
    }

    fun clone(): TerminalRow = TerminalRow(cells.size).also { it.copyFrom(this) }

    fun charAt(col: Int): Char = if (col in cells.indices) cells[col] else Cell.EMPTY_CHAR

    fun set(
        col: Int,
        ch: Char,
        packedFg: Int,
        packedBg: Int,
        combining: String? = null,
    ) {
        if (col !in cells.indices) return
        cells[col] = ch
        fg[col] = packedFg
        bg[col] = packedBg
        combinator[col] = combining
    }

    /**
     * Mark [col] as the trailing half of a wide glyph.
     *
     * The marker is a dedicated packing kind ([Cell.KIND_WIDE_TAIL]) carrying the
     * head cell's background value, so the tail still paints the same background
     * while being identifiable and skippable during text extraction.
     */
    fun markWideTail(col: Int) {
        if (col !in cells.indices) return
        cells[col] = Cell.EMPTY_CHAR
        bg[col] = Cell.bg(
            Cell.KIND_WIDE_TAIL,
            Cell.valueOf(bg[col]),
            Cell.flagsOf(bg[col]),
        )
        combinator[col] = null
    }

    fun isWideTail(col: Int): Boolean =
        col in bg.indices && Cell.kindOf(bg[col]) == Cell.KIND_WIDE_TAIL

    /**
     * Erase `count` cells starting at [from].
     *
     * Wide glyphs span two cells. If the erase window starts on a tail cell, the
     * head cell to its left must be cleared too, otherwise the left half of a
     * half-erased glyph survives and renders as a stray blank or box.
     */
    fun erase(from: Int, count: Int, packedFg: Int, packedBg: Int) {
        if (from !in cells.indices) return
        var start = from
        if (from > 0 && isWideTail(from)) {
            start = from - 1
            cells[start] = Cell.EMPTY_CHAR
            fg[start] = packedFg
            bg[start] = packedBg
            combinator[start] = null
        }
        val end = minOf(start + count, cells.size)
        for (i in (start + 1) until end) {
            cells[i] = Cell.EMPTY_CHAR
            fg[i] = packedFg
            bg[i] = packedBg
            combinator[i] = null
        }
    }

    /**
     * Erase an inclusive `[fromBounded, toBounded]` span.
     *
     * Used by EL 0/1 and ED, where the requested range has to be widened to cover
     * both halves of any wide glyph that straddles a boundary.
     */
    fun eraseRange(fromBounded: Int, toBounded: Int, packedFg: Int, packedBg: Int) {
        var from = fromBounded.coerceIn(0, cells.size)
        var to = toBounded.coerceIn(-1, cells.size - 1)
        if (to < from) return
        if (from > 0 && isWideTail(from)) from--
        if (to + 1 < cells.size && isWideTail(to + 1)) to++
        for (i in from..to) {
            cells[i] = Cell.EMPTY_CHAR
            fg[i] = packedFg
            bg[i] = packedBg
            combinator[i] = null
        }
    }

    /** Insert `count` blanks at [col], pushing the tail off the right edge. */
    fun insertCells(col: Int, count: Int, packedFg: Int, packedBg: Int) {
        if (col !in cells.indices || count <= 0) return
        val n = minOf(count, cells.size - col)
        System.arraycopy(cells, col, cells, col + n, cells.size - col - n)
        System.arraycopy(fg, col, fg, col + n, fg.size - col - n)
        System.arraycopy(bg, col, bg, col + n, bg.size - col - n)
        for (i in col until col + n) {
            cells[i] = Cell.EMPTY_CHAR
            fg[i] = packedFg
            bg[i] = packedBg
            combinator[i] = null
        }
    }

    /** Delete `count` cells at [col], pulling the tail left and padding the end. */
    fun deleteCells(col: Int, count: Int, packedFg: Int, packedBg: Int) {
        if (col !in cells.indices || count <= 0) return
        val n = minOf(count, cells.size - col)
        System.arraycopy(cells, col + n, cells, col, cells.size - col - n)
        System.arraycopy(fg, col + n, fg, col, fg.size - col - n)
        System.arraycopy(bg, col + n, bg, col, bg.size - col - n)
        for (i in cells.size - n until cells.size) {
            cells[i] = Cell.EMPTY_CHAR
            fg[i] = packedFg
            bg[i] = packedBg
            combinator[i] = null
        }
    }

    /** True when the row contains no printable characters. */
    fun isBlank(): Boolean = cells.all { it == Cell.EMPTY_CHAR }

    /**
     * Trim trailing blanks — used when copying a selection.
     *
     * Wide characters occupy two cells: the head holds the codepoint and the tail
     * carries [Cell.FLAG_WIDE_TAIL] with a blank glyph. The tail must not be emitted,
     * and trimming has to walk backwards over tail cells so a trailing wide glyph is
     * not mistaken for a space.
     */
    fun trimmedText(): String {
        var end = cells.size
        while (end > 0) {
            val isTail = isWideTail(end - 1)
            if (!isTail && cells[end - 1] != Cell.EMPTY_CHAR) break
            end--
        }
        if (end == 0) return ""
        val sb = StringBuilder(end)
        var i = 0
        while (i < end) {
            if (isWideTail(i)) {
                i++
                continue
            }
            sb.append(cells[i])
            combinator[i]?.let { sb.append(it) }
            i++
        }
        return sb.toString()
    }
}
