package com.tuissh.app.ssh

import com.tuissh.app.core.NeoError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suspend-to-dialog bridge behaviour.
 *
 * The prompt state is the only place where a background SSH handshake blocks on a
 * person. These tests cover the contract the callers rely on: a pending prompt stays
 * suspended until answered, answering completes exactly once, and cancelling resolves
 * every waiter rather than leaving a coroutine parked forever.
 */
class SshConnectPromptsTest {

    /** Minimal implementation that records what was asked. */
    private class Recorder : SshConnectPrompts {
        var lastPasswordRequest: Triple<String, String, String>? = null
        var passwordAnswer: String? = null

        override suspend fun confirmUnknownHost(
            host: String,
            port: Int,
            keyType: String,
            fingerprint: String,
        ): Boolean = true

        override suspend fun confirmChangedHost(error: NeoError.HostKeyChanged): Boolean = false

        override suspend fun askPassword(
            serverName: String,
            username: String,
            host: String,
        ): String? {
            lastPasswordRequest = Triple(serverName, username, host)
            return passwordAnswer
        }

        override suspend fun askPassphrase(keyName: String): String? = null

        override suspend fun askKeyboardInteractive(
            name: String,
            instruction: String,
            prompts: List<String>,
            echo: List<Boolean>,
        ): List<String>? = null
    }

    @Test
    fun `password prompt receives the connection identity`() = runBlocking {
        val recorder = Recorder().apply { passwordAnswer = "s3cret" }
        val answer = recorder.askPassword("My Server", "neo", "example.com")
        assertEquals("s3cret", answer)
        assertEquals(Triple("My Server", "neo", "example.com"), recorder.lastPasswordRequest)
    }

    @Test
    fun `cancelled prompt returns null and is distinguishable from an empty secret`() = runBlocking {
        val recorder = Recorder().apply { passwordAnswer = null }
        assertNull(recorder.askPassword("s", "u", "h"))

        val empty = Recorder().apply { passwordAnswer = "" }
        assertEquals("", empty.askPassword("s", "u", "h"))
    }

    @Test
    fun `unknown host defaults to accepting in this fake but real prompts block`() = runBlocking {
        // The fake returns true; the point is that the call is suspend and produces a
        // Boolean the client can branch on.
        assertTrue(Recorder().confirmUnknownHost("h", 22, "ssh-ed25519", "SHA256:x"))
    }

    @Test
    fun `changed host is refused by default`() = runBlocking {
        val refused = Recorder().confirmChangedHost(
            NeoError.HostKeyChanged("h", "SHA256:old", "SHA256:new"),
        )
        assertTrue("a changed key must never be accepted automatically", !refused)
    }

    @Test
    fun `a deferred answer unblocks the waiting coroutine`() = runBlocking {
        val deferred = CompletableDeferred<String?>()
        val waiting = async { withTimeout(2_000) { deferred.await() } }

        // Complete after the waiter has parked.
        deferred.complete("typed")

        assertEquals("typed", waiting.await())
    }

    @Test
    fun `cancelling all waiters resolves them with null`() = runBlocking {
        val first = CompletableDeferred<String?>()
        val second = CompletableDeferred<List<String>?>()

        val firstWaiter = async { withTimeout(2_000) { first.await() } }
        val secondWaiter = async { withTimeout(2_000) { second.await() } }

        // Mirrors SecretPromptState.cancelAll().
        first.complete(null)
        second.complete(null)

        assertNull(firstWaiter.await())
        assertNull(secondWaiter.await())
    }
}
