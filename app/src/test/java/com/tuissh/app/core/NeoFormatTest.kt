package com.tuissh.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoFormatTest {

    @Test
    fun `bytes uses binary units`() {
        assertEquals("0 B", NeoFormat.bytes(0))
        assertEquals("512 B", NeoFormat.bytes(512))
        assertEquals("1.0 KiB", NeoFormat.bytes(1024))
        assertEquals("1.0 MiB", NeoFormat.bytes(1024L * 1024))
        assertEquals("1.0 GiB", NeoFormat.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun `bytes uses decimal units when asked`() {
        assertEquals("1.0 KB", NeoFormat.bytes(1000, UnitBase.DECIMAL))
        assertEquals("1.0 MB", NeoFormat.bytes(1_000_000, UnitBase.DECIMAL))
    }

    @Test
    fun `rate formats per second`() {
        assertTrue(NeoFormat.rate(1024.0).contains("/s"))
        assertTrue(NeoFormat.rate(1024.0).contains("KiB"))
    }

    @Test
    fun `parse size handles suffixes`() {
        assertEquals(1024L, NeoFormat.parseSizeToBytes("1K"))
        assertEquals(1024L * 1024, NeoFormat.parseSizeToBytes("1M"))
        assertEquals(1536L * 1024, NeoFormat.parseSizeToBytes("1.5M"))
        assertEquals(0L, NeoFormat.parseSizeToBytes("0"))
        assertEquals(512L, NeoFormat.parseSizeToBytes("512"))
    }

    @Test
    fun `parse size rejects junk`() {
        assertNull(NeoFormat.parseSizeToBytes(""))
        assertNull(NeoFormat.parseSizeToBytes("-"))
        assertNull(NeoFormat.parseSizeToBytes("abc"))
    }

    @Test
    fun `parse percent accepts comma decimal`() {
        assertEquals(12.5, NeoFormat.parsePercent("12.5%")!!, 0.001)
        assertEquals(12.5, NeoFormat.parsePercent("12,5%")!!, 0.001)
        assertEquals(3.0, NeoFormat.parsePercent("3")!!, 0.001)
    }

    @Test
    fun `duration reads naturally`() {
        assertEquals("0m", NeoFormat.duration(0))
        assertEquals("45s", NeoFormat.duration(45))
        assertEquals("2m 5s", NeoFormat.duration(125))
        assertEquals("1h 1m", NeoFormat.duration(3660))
        assertEquals("1d 0h 0m", NeoFormat.duration(86_400))
    }

    @Test
    fun `looks binary detects nul bytes`() {
        assertTrue(NeoFormat.looksBinary(byteArrayOf(0x41, 0x00, 0x42)))
        assertTrue(!NeoFormat.looksBinary("hello world".toByteArray()))
    }

    @Test
    fun `group separates thousands`() {
        assertEquals("1,234,567", NeoFormat.group(1_234_567))
    }
}

class PortRangeTest {

    @Test
    fun `single port parses`() {
        val r = PortRange.parse("22")!!
        assertEquals(22, r.start)
        assertEquals(22, r.end)
    }

    @Test
    fun `dash range parses`() {
        val r = PortRange.parse("60000-61000")!!
        assertEquals(60000, r.start)
        assertEquals(61000, r.end)
        assertEquals(1001, r.count)
    }

    @Test
    fun `colon range parses mosh style`() {
        val r = PortRange.parse("60000:61000")!!
        assertEquals(60000, r.start)
        assertEquals(61000, r.end)
    }

    @Test
    fun `comma list becomes a span`() {
        val r = PortRange.parse("60001,60003")!!
        assertEquals(60001, r.start)
        assertEquals(60003, r.end)
    }

    @Test
    fun `invalid input returns null`() {
        assertNull(PortRange.parse(""))
        assertNull(PortRange.parse("0"))
        assertNull(PortRange.parse("70000"))
        assertNull(PortRange.parse("10-5"))
        assertNull(PortRange.parse("abc"))
    }
}
