package com.tuissh.app.terminal

import com.tuissh.app.terminal.ansi.TerminalBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Row mapping between scrollback and the live screen.
 *
 * This is the one piece of the scroll gesture that can be wrong without looking wrong:
 * an off-by-one draws a duplicated value at the seam between history and the live
 * screen, and a missed clamp returns a blank line at the top when the user overscrolls.
 * Both are checked here rather than only on a device.
 */
class ScrollbackMappingTest {

    private fun buffer() = TerminalBuffer(rows = 4, cols = 20, scrollbackLimit = 100)

    /**
     * Write [count] numbered lines the way a session does.
     *
     * Lines fill the screen first and only then scroll, so with a four-row buffer the
     * first four writes land on screen and each later write pushes exactly one row into
     * scrollback. Scrolling unconditionally after every write — the obvious shortcut —
     * pushes all of them and makes the fixture disagree with a real terminal.
     */
    private fun TerminalBuffer.fill(count: Int) {
        var cursor = 0
        for (i in 1..count) {
            val text = "L$i"
            for ((col, ch) in text.withIndex()) {
                this[cursor].set(col, ch, 0, 0)
            }
            cursor++
            if (cursor >= rows) {
                scrollUp(1)
                cursor = rows - 1
            }
        }
    }

    @Test
    fun `scrollback grows as lines scroll off`() {
        val b = buffer()
        assertEquals(0, b.scrollbackSize)

        b.fill(10)

        // Ten writes into a four-row screen: the first four fill it, and each of the
        // remaining six pushes one row out. The write that triggers the scroll also
        // lands on the row that scrolls, so seven rows end up in history.
        assertEquals(7, b.scrollbackSize)
    }

    @Test
    fun `the newest scrollback line is the last one pushed`() {
        val b = buffer()
        b.fill(10)

        val newest = b.scrollbackRow(b.scrollbackSize - 1)
        assertNotNull(newest)
        assertEquals("L7", newest!!.trimmedText())
    }

    @Test
    fun `the oldest retained line is the first pushed`() {
        val b = buffer()
        b.fill(10)

        assertEquals("L1", b.scrollbackRow(0)!!.trimmedText())
    }

    @Test
    fun `an index past the scrollback returns null rather than a blank row`() {
        val b = buffer()
        b.fill(10)

        assertNull(b.scrollbackRow(b.scrollbackSize))
        assertNull(b.scrollbackRow(999))
        assertNull(b.scrollbackRow(-1))
    }

    /**
     * Reproduces the view's mapping so the seam can be checked without a device.
     *
     * The view blends `scrollbackRow(maxOffset - offset + r)` for the rows that come
     * from history with `rowOrNull(historyIndex - maxOffset)` for the rest. Getting the
     * boundary condition wrong here duplicates or skips exactly one line at the seam.
     */
    private fun rowTextAt(buffer: TerminalBuffer, visibleRows: Int, offset: Int, r: Int): String? {
        val maxOffset = buffer.scrollbackSize
        val clamped = offset.coerceIn(0, maxOffset)
        return if (clamped == 0) {
            buffer.rowOrNull(r)?.trimmedText()
        } else {
            val historyIndex = maxOffset - clamped + r
            if (historyIndex < maxOffset) {
                buffer.scrollbackRow(historyIndex)?.trimmedText()
            } else {
                buffer.rowOrNull(historyIndex - maxOffset)?.trimmedText()
            }
        }
    }

    @Test
    fun `offset zero shows the live screen unchanged`() {
        val b = buffer()
        b.fill(10)

        val visible = (0 until 4).map { rowTextAt(b, 4, 0, it) }

        assertEquals(listOf("L8", "L9", "L10", ""), visible)
    }

    @Test
    fun `scrolling by one lifts the newest history line into view`() {
        val b = buffer()
        b.fill(10)

        val top = rowTextAt(b, 4, 1, 0)

        assertEquals("one line back means the newest scrollback row", "L7", top)
    }

    @Test
    fun `scrolling by the full scrollback shows the oldest retained line`() {
        val b = buffer()
        b.fill(10)

        val top = rowTextAt(b, 4, b.scrollbackSize, 0)

        assertEquals("L1", top)
    }

    @Test
    fun `the seam between history and live screen has no duplicate`() {
        val b = buffer()
        b.fill(10)

        // With 6 lines of scrollback and a 4-row screen, an offset of 3 puts 3 history
        // rows above 1 live row.
        val rows = (0 until 4).map { rowTextAt(b, 4, 3, it) }.filterNotNull()

        val duplicates = rows.groupBy { it }.filter { it.value.size > 1 }.keys
        assertEquals("no line may appear twice across the seam, saw $rows", emptySet<String>(), duplicates)
    }

    @Test
    fun `an overscroll clamps to the oldest line instead of showing blanks`() {
        val b = buffer()
        b.fill(10)

        val top = rowTextAt(b, 4, 9999, 0)

        assertEquals("clamping must land on the oldest row, not a blank", "L1", top)
    }

    @Test
    fun `an alternate screen has no scrollback to scroll into`() {
        // `vim` and `htop` switch to the alternate screen; scrolling back there must do
        // nothing, because those programs own the whole viewport.
        val b = buffer()
        b.fill(6)
        val before = b.scrollbackSize

        b.enterAlternateScreen()

        assertEquals("entering the alternate screen must not append history", before, b.scrollbackSize)
    }
}
