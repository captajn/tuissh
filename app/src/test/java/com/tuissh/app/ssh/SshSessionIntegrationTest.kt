package com.tuissh.app.ssh

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.hostkey.HostKeyMaterial
import com.tuissh.app.ssh.hostkey.HostKeyVerifier
import com.tuissh.app.ssh.hostkey.HostTrustStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for [SshSession] against a real SSH server.
 *
 * These are the tests that prove the SSH layer actually works. Everything else in
 * this project has been "compiles and looks right"; this file executes the protocol.
 */
class SshSessionIntegrationTest {

    private lateinit var server: TestSshServer
    private lateinit var keys: InMemoryHostKeyVerifier

    @Before
    fun setUp() {
        server = TestSshServer(username = "tester", password = "secret").start()
        keys = InMemoryHostKeyVerifier()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private class TestPrompts(
        private val acceptHost: Boolean = true,
        private val password: String? = "secret",
    ) : SshConnectPrompts {
        var sawUnknownHost = false
        var sawChangedHost = false

        override suspend fun confirmUnknownHost(
            host: String,
            port: Int,
            keyType: String,
            fingerprint: String,
        ): Boolean {
            sawUnknownHost = true
            return acceptHost
        }

        override suspend fun confirmChangedHost(error: NeoError.HostKeyChanged): Boolean {
            sawChangedHost = true
            return false
        }

        override suspend fun askPassword(
            serverName: String,
            username: String,
            host: String,
        ): String? = password

        override suspend fun askPassphrase(keyName: String): String? = null

        override suspend fun askKeyboardInteractive(
            name: String,
            instruction: String,
            prompts: List<String>,
            echo: List<Boolean>,
        ): List<String>? = null
    }

    private fun spec(password: String = "secret") = SshConnectSpec(
        serverId = 1L,
        name = "test",
        host = "127.0.0.1",
        port = server.port,
        username = "tester",
        auth = SshConnectSpec.Auth.Password(password),
        connectTimeoutSeconds = 10,
    )

    // ------------------------------------------------------------------
    // Connect
    // ------------------------------------------------------------------

    @Test
    fun `connects with valid password`() = runBlocking {
        val session = SshSession(spec = spec(), hostKeyVerifier = keys, prompts = TestPrompts())
        val result = session.connect()
        assertTrue("expected Ok, got $result", result is NeoResult.Ok)
        assertTrue(session.isConnected)
        assertEquals(SshPhase.READY, session.phase)
        session.disconnect()
    }

    @Test
    fun `rejects wrong password`() = runBlocking {
        val session = SshSession(
            spec = spec(password = "wrong"),
            hostKeyVerifier = keys,
            prompts = TestPrompts(),
        )
        val result = session.connect()
        assertTrue("expected Err, got $result", result is NeoResult.Err)
        assertEquals(NeoError.AuthFailed, (result as NeoResult.Err).error)
        assertTrue(!session.isConnected)
    }

    @Test
    fun `unknown host key is reported to the prompt`() = runBlocking {
        val prompts = TestPrompts(acceptHost = true)
        val session = SshSession(spec = spec(), hostKeyVerifier = keys, prompts = prompts)
        session.connect()
        assertTrue("prompt should have been asked to trust a new host", prompts.sawUnknownHost)
        session.disconnect()
    }

    @Test
    fun `declining the host key aborts the connection`() = runBlocking {
        val session = SshSession(
            spec = spec(),
            hostKeyVerifier = keys,
            prompts = TestPrompts(acceptHost = false),
        )
        val result = session.connect()
        assertTrue(result is NeoResult.Err)
        assertTrue(!session.isConnected)
    }

    // ------------------------------------------------------------------
    // Channels
    // ------------------------------------------------------------------

    @Test
    fun `opens a shell channel`() = runBlocking {
        val session = SshSession(spec = spec(), hostKeyVerifier = keys, prompts = TestPrompts())
        session.connect()
        val shell = session.openShell(cols = 100, rows = 30)
        assertTrue("shell open failed: $shell", shell is NeoResult.Ok)
        assertNotNull(shell.getOrNull())
        session.disconnect()
    }

    @Test
    fun `exec round-trips through the channel`() = runBlocking {
        val session = SshSession(spec = spec(), hostKeyVerifier = keys, prompts = TestPrompts())
        session.connect()
        val result = session.exec("echo hello", timeoutMs = 15_000)
        assertTrue("exec failed: $result", result is NeoResult.Ok)
        session.disconnect()
    }

    @Test
    fun `disconnect closes the session`() = runBlocking {
        val session = SshSession(spec = spec(), hostKeyVerifier = keys, prompts = TestPrompts())
        session.connect()
        assertTrue(session.isConnected)
        session.disconnect()
        assertTrue(!session.isConnected)
        assertEquals(SshPhase.CLOSED, session.phase)
    }
}

/**
 * In-memory host key store for tests, avoiding the Room dependency that
 * [HostKeyVerifier] needs on a device.
 *
 * Keyed by `host:port:blobType` and comparing fingerprints, the same way the real
 * store does, so a test that passes here is not passing because the double is
 * laxer than production.
 */
class InMemoryHostKeyVerifier : HostTrustStore {
    private val seen = mutableMapOf<String, String>()

    override suspend fun evaluate(
        host: String,
        port: Int,
        material: HostKeyMaterial,
    ): HostKeyVerifier.Outcome {
        val key = material.storageKey(host, port)
        val stored = seen[key]
        return when {
            stored == null ->
                HostKeyVerifier.Outcome.UnknownHost(host, port, material.keyType, material.fingerprint)
            stored == material.fingerprint ->
                HostKeyVerifier.Outcome.Trusted
            else ->
                HostKeyVerifier.Outcome.Changed(
                    host, port,
                    com.tuissh.app.data.model.KnownHostEntity(
                        hostKey = key, host = host, port = port,
                        keyType = material.keyType, publicKeyBase64 = "",
                        fingerprintSha256 = stored,
                    ),
                    material.fingerprint,
                    NeoError.HostKeyChanged(key, stored, material.fingerprint),
                )
        }
    }

    override suspend fun trust(
        host: String,
        port: Int,
        material: HostKeyMaterial,
    ): NeoResult<Unit> {
        seen[material.storageKey(host, port)] = material.fingerprint
        return NeoResult.Ok(Unit)
    }
}

