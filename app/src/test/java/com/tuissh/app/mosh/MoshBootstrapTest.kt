package com.tuissh.app.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bootstrap parsing.
 *
 * The server's connection line shares stdout with whatever the login shell printed, so
 * almost every case here is about extracting one line from a noisy payload without
 * accepting a truncated or malformed one.
 */
class MoshBootstrapTest {

    private val key = "abcdefghijklmnopqrstuv"

    @Test
    fun `parses a clean connection line`() {
        val output = "MOSH CONNECT 60001 $key"

        val parsed = MoshBootstrap.parseStartup(output, "example.com")

        assertNotNull(parsed)
        assertEquals("example.com", parsed!!.host)
        assertEquals(60001, parsed.port)
        assertEquals(key, parsed.key)
    }

    @Test
    fun `finds the line after a login banner`() {
        val output = """
            Welcome to Ubuntu 24.04 LTS
            Last login: Thu Nov  6 20:00:00 2026
            MOSH CONNECT 60042 $key
        """.trimIndent()

        val parsed = MoshBootstrap.parseStartup(output, "h")

        assertEquals(60042, parsed?.port)
    }

    @Test
    fun `ignores trailing warnings after the line`() {
        val output = "MOSH CONNECT 60010 $key\nbash: warning: setlocale: LC_ALL: cannot change locale"

        assertEquals(key, MoshBootstrap.parseStartup(output, "h")?.key)
    }

    @Test
    fun `a payload without the marker yields nothing`() {
        assertNull(MoshBootstrap.parseStartup("mosh-server: not found", "h"))
        assertNull(MoshBootstrap.parseStartup("", "h"))
    }

    @Test
    fun `a malformed port is rejected rather than guessed`() {
        // A wrong port would send UDP packets at an unrelated service.
        assertNull(MoshBootstrap.parseStartup("MOSH CONNECT notaport $key", "h"))
        assertNull(MoshBootstrap.parseStartup("MOSH CONNECT 0 $key", "h"))
        assertNull(MoshBootstrap.parseStartup("MOSH CONNECT 70000 $key", "h"))
    }

    @Test
    fun `a truncated key is rejected`() {
        // The line can be cut short by a pty closing mid-write; a short key would fail
        // later with a confusing authentication error.
        assertNull(MoshBootstrap.parseStartup("MOSH CONNECT 60001 abc", "h"))
    }

    @Test
    fun `a line with only a port is rejected`() {
        assertNull(MoshBootstrap.parseStartup("MOSH CONNECT 60001", "h"))
    }

    @Test
    fun `the key export is in the shape a user can paste`() {
        val parsed = MoshBootstrap.parseStartup("MOSH CONNECT 60001 $key", "h")!!

        assertEquals("MOSH_KEY=$key", parsed.keyExport)
        assertTrue(parsed.keyExport.startsWith("MOSH_KEY="))
    }

    @Test
    fun `extra whitespace between fields does not break the parse`() {
        val parsed = MoshBootstrap.parseStartup("MOSH CONNECT   60001    $key", "h")

        assertEquals(60001, parsed?.port)
        assertEquals(key, parsed?.key)
    }
}
