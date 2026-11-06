package com.tuissh.app.ssh

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.model.ForwardEntity
import com.tuissh.app.ssh.hostkey.HostKeyMaterial
import com.tuissh.app.ssh.hostkey.HostKeyVerifier
import com.tuissh.app.ssh.hostkey.HostTrustStore
import com.tuissh.app.ssh.key.KeyParseResult
import com.tuissh.app.ssh.key.KeyParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

/**
 * Port forwarding against a real host.
 *
 * Storing a rule proves nothing; the thing that can be wrong is whether the tunnel
 * actually carries bytes. Each test opens a rule, connects through it, and checks that
 * the data came from the far side.
 *
 * Skipped unless `LIVE_HOST`, `LIVE_USER` and `LIVE_KEY_PATH` are set.
 */
class PortForwardLiveTest {

    private val host: String? = System.getenv("LIVE_HOST")?.takeIf { it.isNotBlank() }
    private val user: String? = System.getenv("LIVE_USER")?.takeIf { it.isNotBlank() }
    private val keyPath: String? = System.getenv("LIVE_KEY_PATH")?.takeIf { it.isNotBlank() }

    private fun ready() = host != null && user != null && keyPath != null

    private suspend fun connect(): SshSession {
        val pem = String(Files.readAllBytes(Path.of(keyPath!!)), Charsets.UTF_8)
        val key = (KeyParser.parse(pem) as? KeyParseResult.Success)?.key
            ?: error("the live key did not parse")

        val session = SshSession(
            spec = SshConnectSpec(
                serverId = 1L,
                name = "forward",
                host = host!!,
                port = 22,
                username = user!!,
                auth = SshConnectSpec.Auth.PrivateKey(key.pem, key.passphrase),
                connectTimeoutSeconds = 25,
            ),
            hostKeyVerifier = TrustLive,
            prompts = SilentPrompts,
        )
        assertTrue("connect failed", session.connect() is NeoResult.Ok)
        return session
    }

    /**
     * Read whatever the far end sends.
     *
     * The SSH banner is the cheapest thing to prove a tunnel end-to-end: if bytes come
     * back and start with "SSH-", the port forward really reached an SSH daemon rather
     * than a listener that accepts and hangs.
     */
    private fun banner(port: Int, timeoutMs: Int = 8_000): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = timeoutMs
            val buffer = ByteArray(64)
            val read = socket.getInputStream().read(buffer)
            return if (read > 0) String(buffer, 0, read) else ""
        }
    }

    @Test
    fun `a local forward actually reaches the server's ssh port`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", ready())
        val session = connect()
        try {
            val rule = ForwardEntity(
                serverId = 1L,
                name = "loopback",
                kind = ForwardEntity.KIND_LOCAL,
                bindHost = "127.0.0.1",
                bindPort = 18080,
                targetHost = "127.0.0.1",
                targetPort = 22,
            )

            val started = withTimeout(30_000) { session.startForwards(listOf(rule)) }
            val failures = (started as NeoResult.Ok).getOrThrow()
            assertTrue("the rule must bind, failures: $failures", failures.isEmpty())

            // Data through the tunnel is the only proof that it works.
            val text = banner(18080)
            assertTrue(
                "the tunnel must reach the SSH daemon, got '$text'",
                text.startsWith("SSH-"),
            )
        } finally {
            session.stopForwards()
            session.disconnect()
        }
    }

    @Test
    fun `closing the forwards releases the port`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", ready())
        val session = connect()
        try {
            val rule = ForwardEntity(
                serverId = 1L,
                kind = ForwardEntity.KIND_LOCAL,
                bindHost = "127.0.0.1",
                bindPort = 18081,
                targetHost = "127.0.0.1",
                targetPort = 22,
            )
            withTimeout(30_000) { session.startForwards(listOf(rule)) }
            assertTrue("the port must be open while forwarding", banner(18081).startsWith("SSH-"))

            session.stopForwards()

            // A stale listener would keep accepting connections and quietly leak a port.
            val stillOpen = runCatching { banner(18081, 600) }.getOrDefault("")
            assertTrue(
                "the port must be closed after stopForwards, got '$stillOpen'",
                !stillOpen.startsWith("SSH-"),
            )
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `one bad rule does not prevent the others`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", ready())
        val session = connect()
        try {
            // Bind a port first, then ask for it again: the second must fail and the
            // third must still come up.
            val first = ForwardEntity(
                serverId = 1L,
                kind = ForwardEntity.KIND_LOCAL,
                bindHost = "127.0.0.1",
                bindPort = 18082,
                targetHost = "127.0.0.1",
                targetPort = 22,
            )
            val duplicate = first.copy(id = 2L, bindPort = 18082)
            val other = first.copy(id = 3L, bindPort = 18083)

            val result = withTimeout(30_000) {
                session.startForwards(listOf(first, duplicate, other))
            }
            val failures = (result as NeoResult.Ok).getOrThrow()

            assertTrue("the duplicate must be reported", failures.any { it.first.id == duplicate.id })
            assertTrue(
                "the distinct rule must still work, got '${
                    banner(18083)
                }'",
                banner(18083).startsWith("SSH-"),
            )
        } finally {
            session.stopForwards()
            session.disconnect()
        }
    }

    @Test
    fun `forwarding is refused when the session is closed`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", ready())
        val session = connect()
        session.disconnect()

        val rule = ForwardEntity(
            serverId = 1L,
            kind = ForwardEntity.KIND_LOCAL,
            bindPort = 18084,
            targetHost = "127.0.0.1",
            targetPort = 22,
        )
        val result = withTimeout(15_000) { session.startForwards(listOf(rule)) }

        assertTrue("a dead session must refuse, got $result", result is NeoResult.Err)
    }

    private object TrustLive : HostTrustStore {
        override suspend fun evaluate(
            host: String,
            port: Int,
            material: HostKeyMaterial,
        ): HostKeyVerifier.Outcome = HostKeyVerifier.Outcome.Trusted

        override suspend fun trust(
            host: String,
            port: Int,
            material: HostKeyMaterial,
        ): NeoResult<Unit> = NeoResult.Ok(Unit)

        override suspend fun evaluateUnverifiable(
            host: String,
            port: Int,
            keyType: String,
        ): HostKeyVerifier.Outcome = HostKeyVerifier.Outcome.Trusted
    }

    private object SilentPrompts : SshConnectPrompts {
        override suspend fun confirmUnknownHost(
            host: String,
            port: Int,
            keyType: String,
            fingerprint: String,
        ): Boolean = true

        override suspend fun confirmChangedHost(error: NeoError.HostKeyChanged): Boolean = true

        override suspend fun askPassword(
            serverName: String,
            username: String,
            host: String,
        ): String? = null

        override suspend fun askPassphrase(keyName: String): String? = null

        override suspend fun askKeyboardInteractive(
            name: String,
            instruction: String,
            prompts: List<String>,
            echo: List<Boolean>,
        ): List<String>? = null
    }
}
