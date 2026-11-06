package com.tuissh.app.terminal

import com.tuissh.app.terminal.input.TerminalKeyBar
import com.tuissh.app.terminal.input.TerminalKeyBar.BarKey
import com.tuissh.app.terminal.session.TerminalInputEncoder.Key
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sticky-modifier and encoding behaviour of the terminal key bar.
 *
 * These are the cases that decide whether a phone can drive vim: a latched Ctrl must
 * be consumed by exactly one following key, Ctrl-C must be the single byte 0x03, and
 * a modifier must never leak into a key press two taps later.
 */
class TerminalKeyBarTest {

    private val none = TerminalKeyBar.ModifierState()

    @Test
    fun `ctrl latches then clears after one key`() {
        val (nothing, latched) = TerminalKeyBar.press(BarKey.Ctrl(), none, appCursor = false)
        assertEquals("modifier keys send nothing", 0, nothing.size)
        assertTrue(latched.ctrl)

        val (bytes, after) = TerminalKeyBar.press(
            BarKey.ControlByte(0x03, "^C"),
            latched,
            appCursor = false,
        )
        assertArrayEquals(byteArrayOf(0x03), bytes)
        assertFalse("ctrl must be consumed", after.ctrl)
    }

    @Test
    fun `ctrl-c is a single byte`() {
        val latched = none.toggleCtrl()
        val (bytes, _) = TerminalKeyBar.press(BarKey.ControlByte(0x03, "^C"), latched, false)
        assertEquals(1, bytes.size)
        assertEquals(3, bytes[0].toInt())
    }

    @Test
    fun `alt prefixes a control byte with escape`() {
        val latched = none.toggleAlt()
        val (bytes, _) = TerminalKeyBar.press(BarKey.ControlByte(0x03, "^C"), latched, false)
        assertArrayEquals(byteArrayOf(0x1B, 0x03), bytes)
    }

    @Test
    fun `alt prefixes literal text with escape`() {
        val latched = none.toggleAlt()
        val (bytes, _) = TerminalKeyBar.press(BarKey.Literal("|"), latched, false)
        assertEquals("\u001B|", String(bytes))
    }

    @Test
    fun `plain literal sends its UTF-8 bytes`() {
        val (bytes, after) = TerminalKeyBar.press(BarKey.Literal("~"), none, false)
        assertEquals("~", String(bytes))
        assertFalse(after.any)
    }

    @Test
    fun `arrow without modifier uses the normal form`() {
        val (bytes, _) = TerminalKeyBar.press(
            BarKey.Special(Key.UP, "↑"),
            none,
            appCursor = false,
        )
        assertEquals("\u001B[A", String(bytes))
    }

    @Test
    fun `arrow respects application cursor mode`() {
        val (bytes, _) = TerminalKeyBar.press(
            BarKey.Special(Key.UP, "↑"),
            none,
            appCursor = true,
        )
        assertEquals("\u001BOA", String(bytes))
    }

    @Test
    fun `ctrl is dropped from arrow keys`() {
        // Ctrl+Arrow is not a sequence most servers understand; sending it would
        // produce garbage in vim, so the modifier is discarded.
        val latched = none.toggleCtrl()
        val (bytes, after) = TerminalKeyBar.press(
            BarKey.Special(Key.LEFT, "←"),
            latched,
            appCursor = false,
        )
        assertEquals("\u001B[D", String(bytes))
        assertFalse(after.any)
    }

    @Test
    fun `shift arrow keeps the modifier parameter`() {
        val latched = none.toggleShift()
        val (bytes, _) = TerminalKeyBar.press(
            BarKey.Special(Key.RIGHT, "→"),
            latched,
            appCursor = false,
        )
        assertEquals("\u001B[1;2C", String(bytes))
    }

    @Test
    fun `escape key is a single ESC`() {
        val (bytes, _) = TerminalKeyBar.press(
            BarKey.Special(Key.ESCAPE, "Esc"),
            none,
            appCursor = false,
        )
        assertEquals("\u001B", String(bytes))
    }

    @Test
    fun `tab sends a tab byte`() {
        val (bytes, _) = TerminalKeyBar.press(
            BarKey.Special(Key.TAB, "Tab"),
            none,
            appCursor = false,
        )
        assertEquals("\t", String(bytes))
    }

    @Test
    fun `default layout has no empty rows`() {
        assertTrue(TerminalKeyBar.DefaultRows.isNotEmpty())
        for (row in TerminalKeyBar.DefaultRows) {
            assertTrue("row must not be empty", row.isNotEmpty())
        }
    }

    @Test
    fun `layout round trips through encode and decode`() {
        val encoded = TerminalKeyBar.encodeLayout(TerminalKeyBar.DefaultRows)
        val decoded = TerminalKeyBar.decodeLayout(encoded)
        assertEquals(TerminalKeyBar.DefaultRows.size, decoded.size)
        assertEquals(
            TerminalKeyBar.DefaultRows.flatten().size,
            decoded.flatten().size,
        )
    }

    @Test
    fun `empty stored layout falls back to the default`() {
        assertEquals(TerminalKeyBar.DefaultRows, TerminalKeyBar.decodeLayout(""))
    }

    @Test
    fun `control labels cover the common bytes`() {
        assertEquals("^C", TerminalKeyBar.controlLabel(0x03))
        assertEquals("^D", TerminalKeyBar.controlLabel(0x04))
        assertEquals("^[", TerminalKeyBar.controlLabel(0x1B))
        assertEquals("^?", TerminalKeyBar.controlLabel(0x7F))
    }
}
