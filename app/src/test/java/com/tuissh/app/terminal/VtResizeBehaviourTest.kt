package com.tuissh.app.terminal

import com.tuissh.app.terminal.parse.VtEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cursor and content position across resizes.
 *
 * The bug these cover: a session that grows from 24 rows to 50 kept the cursor at
 * row 23, so a short banner rendered in the middle of the screen. A terminal grows
 * **downward** — existing content keeps its row and new rows are appended — so the
 * cursor keeps its own row index.
 */
class VtResizeBehaviourTest {

    private lateinit var term: VtEmulator

    @Before
    fun setUp() {
        term = VtEmulator(rows = 24, cols = 80)
    }

    @Test
    fun `growing the grid keeps the cursor on its content row`() {
        term.feed("hello\r\n")
        assertEquals(1, term.cursorRow)

        term.resize(50, 80)

        // Content stays at the top; the cursor stays right after it.
        assertEquals("hello", term.buffer[0].trimmedText())
        assertEquals(1, term.cursorRow)
    }

    @Test
    fun `content after growing starts at the top of the grid`() {
        term.resize(50, 80)
        term.feed("Microsoft Windows\r\n")
        val firstNonEmpty = (0 until term.rows).firstOrNull {
            term.buffer[it].trimmedText().isNotEmpty()
        }
        assertEquals("text must start at row 0", 0, firstNonEmpty)
    }

    @Test
    fun `shrinking the grid keeps the cursor visible`() {
        for (i in 0 until 20) term.feed("line$i\r\n")
        term.resize(10, 80)
        assertTrue("cursor must stay inside the grid", term.cursorRow in 0..9)
        assertEquals(9, term.scrollBottom)
    }

    @Test
    fun `resize is idempotent`() {
        term.feed("abc")
        term.resize(30, 100)
        val cursorAfter = term.cursorRow
        term.resize(30, 100)
        assertEquals(cursorAfter, term.cursorRow)
    }

    @Test
    fun `scroll bounds track the new row count`() {
        term.resize(40, 120)
        assertEquals(40, term.rows)
        assertEquals(120, term.cols)
        assertEquals(39, term.scrollBottom)
        assertEquals(0, term.scrollTop)
    }

    @Test
    fun `line feed at the last row scrolls instead of moving past the grid`() {
        term.resize(3, 20)
        term.feed("a\r\nb\r\nc\r\n")
        // Fourth line forces a scroll: "a" leaves, "d" arrives at the bottom.
        term.feed("d")
        assertEquals("b", term.buffer[0].trimmedText())
        assertEquals("d", term.buffer[2].trimmedText())
        assertEquals(2, term.cursorRow)
    }
}
