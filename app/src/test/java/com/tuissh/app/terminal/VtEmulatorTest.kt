package com.tuissh.app.terminal

import com.tuissh.app.terminal.parse.VtEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for the VT emulator.
 *
 * Assertions are written against the visible grid. Where a test feeds escape
 * sequences, the expectation reflects what a real terminal does — including the
 * blank cells that a bare `\n` leaves behind, because LF moves the row but keeps
 * the column, which is why shells always emit `\r\n`.
 */
class VtEmulatorTest {

    private lateinit var term: VtEmulator

    private fun feed(text: String) = term.feed(text)

    /** Row content with trailing blanks removed. */
    private fun row(r: Int): String = term.buffer[r].trimmedText()

    @Before
    fun setUp() {
        term = VtEmulator(rows = 5, cols = 20)
    }

    // --- printing and cursor -------------------------------------------------

    @Test
    fun `plain text renders on first row`() {
        feed("hello")
        assertEquals("hello", row(0))
        assertEquals(0, term.cursorRow)
        assertEquals(5, term.cursorCol)
    }

    @Test
    fun `newline moves to next row and keeps column`() {
        feed("ab\ncd")
        assertEquals("ab", row(0))
        assertEquals("  cd", row(1))
        assertEquals(1, term.cursorRow)
    }

    @Test
    fun `carriage return newline starts a clean line`() {
        feed("ab\r\ncd")
        assertEquals("ab", row(0))
        assertEquals("cd", row(1))
    }

    @Test
    fun `carriage return resets column`() {
        feed("abc\rxy")
        assertEquals("xyc", row(0))
    }

    @Test
    fun `backspace moves left one cell`() {
        feed("abc\bZ")
        assertEquals("abZ", row(0))
    }

    @Test
    fun `tab advances to next tab stop`() {
        feed("a\tb")
        assertEquals("a       b", row(0))
        assertEquals(9, term.cursorCol)
    }

    @Test
    fun `autowrap writes to next row at the right edge`() {
        feed("x".repeat(25))
        assertEquals("x".repeat(20), row(0))
        assertEquals("xxxxx", row(1))
    }

    // --- scrolling -----------------------------------------------------------

    @Test
    fun `line feed at bottom scrolls and pushes to scrollback`() {
        for (i in 0 until 10) feed("line$i\r\n")
        assertTrue("scrollback should hold evicted rows", term.buffer.scrollbackSize >= 5)
    }

    @Test
    fun `reverse index scrolls down at top`() {
        feed("top")
        feed("\u001B[1;1H")
        feed("\u001B" + "M")
        assertEquals("", row(0))
    }

    // --- erase ---------------------------------------------------------------

    @Test
    fun `erase display clears the screen`() {
        feed("hello\r\nworld")
        feed("\u001B[2J")
        assertEquals("", row(0))
        assertEquals("", row(1))
    }

    @Test
    fun `erase to end of line keeps prefix`() {
        feed("abcdef")
        feed("\u001B[1;3H")
        feed("\u001B[K")
        assertEquals("ab", row(0))
    }

    @Test
    fun `erase to start of line keeps suffix`() {
        feed("abcdef")
        feed("\u001B[1;3H")
        feed("\u001B[1K")
        // EL 1 erases through the cursor cell, so the character under it goes too.
        assertEquals("   def", row(0))
    }

    // --- cursor addressing ---------------------------------------------------

    @Test
    fun `cursor position puts text at the requested cell`() {
        feed("\u001B[2;5HX")
        assertEquals("    X", row(1))
        assertEquals(1, term.cursorRow)
        assertEquals(5, term.cursorCol)
    }

    @Test
    fun `cursor forward and back move within the row`() {
        feed("\u001B[1;1Habc\u001B[2DZ")
        assertEquals("aZc", row(0))
    }

    @Test
    fun `save and restore cursor keeps the position`() {
        feed("\u001B[2;3H")
        feed("\u001B[s")
        feed("\u001B[1;1H")
        feed("\u001B[u")
        assertEquals(1, term.cursorRow)
        assertEquals(2, term.cursorCol)
    }

    // --- insert / delete -----------------------------------------------------

    @Test
    fun `delete characters shifts the tail left`() {
        feed("abcdef")
        feed("\u001B[1;2H")
        feed("\u001B[2P")
        assertEquals("adef", row(0))
    }

    @Test
    fun `insert characters shifts the tail right`() {
        feed("abcdef")
        feed("\u001B[1;2H")
        feed("\u001B[2@")
        assertEquals("a  bcdef", row(0))
    }

    @Test
    fun `delete line removes the current row`() {
        feed("one\r\ntwo\r\nthree")
        feed("\u001B[2;1H")
        feed("\u001B[M")
        assertEquals("one", row(0))
        assertEquals("three", row(1))
    }

    // --- scroll region -------------------------------------------------------

    @Test
    fun `scroll region confines scrolling to the region`() {
        feed("r0\r\nr1\r\nr2\r\nr3\r\nr4")
        feed("\u001B[2;4r")
        feed("\u001B[4;1H")
        feed("\r\n")
        assertEquals("r0", row(0))
    }

    // --- modes ---------------------------------------------------------------

    @Test
    fun `alternate screen saves and restores the primary buffer`() {
        feed("primary")
        feed("\u001B[?1049h")
        feed("\u001B[2J\u001B[Halt")
        assertEquals("alt", row(0))
        feed("\u001B[?1049l")
        assertEquals("primary", row(0))
    }

    @Test
    fun `origin mode confines cursor addressing to the scroll region`() {
        feed("\u001B[2;4r")
        feed("\u001B[?6h")
        feed("\u001B[1;1H")
        assertEquals(1, term.cursorRow)
    }

    // --- OSC -----------------------------------------------------------------

    @Test
    fun `title osc updates the window title`() {
        var seen: String? = null
        term.onTitleChanged = { seen = it }
        feed("\u001B]0;my title\u0007")
        assertEquals("my title", seen)
        assertEquals("my title", term.title)
    }

    @Test
    fun `bell osc reaches the callback`() {
        var rang = false
        term.onBell = { rang = true }
        feed("\u0007")
        assertTrue(rang)
    }

    // --- charset -------------------------------------------------------------

    @Test
    fun `dec special graphics maps line drawing`() {
        feed("\u001B(0lqk\u001B(B")
        assertEquals("┌─┐", row(0))
    }

    // --- wide characters -----------------------------------------------------

    @Test
    fun `wide characters occupy two cells`() {
        feed("日本")
        assertEquals(4, term.cursorCol)
        assertEquals("日本", row(0))
        assertTrue(term.buffer[0].isWideTail(1))
        assertTrue(term.buffer[0].isWideTail(3))
    }

    @Test
    fun `combining marks attach to the previous cell`() {
        feed("e\u0301")
        assertEquals(1, term.cursorCol)
        assertEquals("e\u0301", row(0))
    }

    // --- resize --------------------------------------------------------------

    @Test
    fun `resize keeps bottom rows visible`() {
        feed("a\r\nb\r\nc\r\nd\r\ne")
        term.resize(3, 20)
        assertEquals(3, term.rows)
        assertTrue(row(2).isNotEmpty())
    }

    @Test
    fun `resize wider keeps content`() {
        feed("hello")
        term.resize(5, 40)
        assertEquals("hello", row(0))
        assertEquals(40, term.cols)
    }

    // --- text extraction -----------------------------------------------------

    @Test
    fun `visible text joins rows`() {
        feed("one\r\ntwo")
        val text = term.visibleText()
        assertTrue(text.startsWith("one"))
        assertTrue(text.contains("two"))
    }
}
