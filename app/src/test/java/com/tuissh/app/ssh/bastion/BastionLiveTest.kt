package com.tuissh.app.ssh.bastion

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.SshConnectPrompts
import com.tuissh.app.ssh.SshConnectSpec
import com.tuissh.app.ssh.SshSession
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
import java.nio.file.Files
import java.nio.file.Path

/**
 * Jump chains against a real host.
 *
 * A second machine to jump through is not something a test can assume, so the chain is
 * proved against the same host used twice: connect to it, ask it to forward a loopback
 * port back to its own SSH port, then connect through that port. That exercises exactly
 * the mechanism ProxyJump needs — a forward that carries a real SSH handshake — without
 * pretending a direct connection is a jump.
 *
 * Skipped unless `LIVE_HOST`, `LIVE_USER` and `LIVE_KEY_PATH` are set.
 */
class BastionLiveTest {

    private val host: String? = System.getenv("LIVE_HOST")?.takeIf { it.isNotBlank() }
    private val user: String? = System.getenv("LIVE_USER")?.takeIf { it.isNotBlank() }
    private val keyPath: String? = System.getenv("LIVE_KEY_PATH")?.takeIf { it.isNotBlank() }

    private fun ready() = host != null && user != null && keyPath != null

    private fun spec(name: String, port: Int = 22): SshConnectSpec {
        val pem = String(Files.readAllBytes(Path.of(keyPath!!)), Charsets.UTF_8)
        val key = (KeyParser.parse(pem) as? KeyParseResult.Success)?.key
            ?: error("the live key did not parse")
        return SshConnectSpec(
            serverId = name.hashCode().toLong(),
            name = name,
            host = host!!,
            port = port,
            username = user!!,
            auth = SshConnectSpec.Auth.PrivateKey(key.pem, key.passphrase),
            connectTimeoutSeconds = 25,
        )
    }

    private val connector = BastionConnector { dial ->
        SshSession(
            spec = dial,
            hostKeyVerifier = TrustLive,
            prompts = SilentPrompts,
        )
    }

    @Test
    fun `a chain with no bastion passes the target through`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", ready())

        val target = spec("target")
        val opened = withTimeout(20_000) { connector.open(emptyList(), target) }

        val reachable = (opened as NeoResult.Ok).getOrThrow()
        assertTrue("no sessions are opened without a hop", reachable.sessions.isEmpty())
        assertTrue("the target address is passed through", reachable.host == target.host)
        assertTrue("the target port is passed through", reachable.port == target.port)
    }

    @Test
    fun `one hop yields a loopback endpoint that carries ssh`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", ready())

        val bastion = spec("bastion")
        val target = spec("target")

        val opened = withTimeout(60_000) { connector.open(listOf(bastion), target) }
        assertTrue("the chain must open, got $opened", opened is NeoResult.Ok)
        val reachable = (opened as NeoResult.Ok).getOrThrow()

        try {
            assertTrue("the hop session is live", reachable.sessions.size == 1)
            assertTrue(
                "the endpoint must be loopback, got ${reachable.host}",
                reachable.host == "127.0.0.1",
            )
            assertTrue("the endpoint must have a real port", reachable.port > 0)

            // The strongest check: complete a full SSH handshake through the forward.
            // Nothing short of that proves the chain carries a session rather than just
            // accepting a socket.
            val through = SshSession(
                spec = target.copy(host = reachable.host, port = reachable.port),
                hostKeyVerifier = TrustLive,
                prompts = SilentPrompts,
            )
            try {
                val connected = withTimeout(40_000) { through.connect() }
                assertTrue(
                    "a session through the forward must authenticate, got $connected",
                    connected is NeoResult.Ok,
                )
                // And carry a command, which is what the chain exists for.
                val ran = withTimeout(20_000) { through.exec("echo through-the-jump") }
                assertTrue("the command must run, got $ran", ran is NeoResult.Ok)
                assertTrue(
                    "the output must come back over the chained session",
                    (ran as NeoResult.Ok).getOrThrow().stdout.contains("through-the-jump"),
                )
            } finally {
                through.disconnect()
            }
        } finally {
            reachable.close()
        }
    }

    @Test
    fun `an unreachable hop names itself in the error`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", ready())

        // A port nothing listens on: the failure must name the hop rather than
        // reporting a generic tunnel error.
        val deadHop = spec("dead").copy(port = 1, connectTimeoutSeconds = 5)
        val target = spec("target")

        val opened = withTimeout(40_000) { connector.open(listOf(deadHop), target) }

        assertTrue("a dead hop must fail the chain, got $opened", opened is NeoResult.Err)
        val error = (opened as NeoResult.Err).error
        assertTrue(
            "the error must name the hop, got $error",
            error is NeoError.BastionUnreachable && error.host == host,
        )
    }

    @Test
    fun `closing a chain releases its forward`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", ready())

        val opened = withTimeout(60_000) {
            connector.open(listOf(spec("bastion")), spec("target"))
        }
        val reachable = (opened as NeoResult.Ok).getOrThrow()
        val port = reachable.port

        reachable.close()

        // A leaked listener would keep accepting on a port nothing owns any more.
        val stillListening = runCatching {
            java.net.Socket("127.0.0.1", port).use { it.soTimeout = 700 }
        }
        assertTrue(
            "the forward must be gone after close(), port $port still accepts",
            stillListening.isFailure,
        )
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
