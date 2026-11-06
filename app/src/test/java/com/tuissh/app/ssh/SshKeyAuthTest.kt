package com.tuissh.app.ssh

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.hostkey.HostKeyMaterial
import com.tuissh.app.ssh.hostkey.HostKeyVerifier
import com.tuissh.app.ssh.hostkey.HostTrustStore
import com.tuissh.app.ssh.key.KeyParseResult
import com.tuissh.app.ssh.key.KeyParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * Offline regression test for private-key authentication.
 *
 * Exists because a key-only login silently failed for the whole life of the project:
 * `SshSession.connect()` never registered the key with JSch, so every key-configured
 * server returned `AuthFailed`, while 271 tests stayed green because the in-process test
 * server only offered password auth.
 *
 * The server here accepts a generated RSA key, so the test fails if `addIdentity` is ever
 * removed again.
 */
class SshKeyAuthTest {

    private var server: TestSshServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun `a private key authenticates against a key-only server`() = runBlocking {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()

        val running = TestSshServer(authorizedPublicKey = pair.public).start()
        server = running

        val pem = encodeRsaPrivateKeyPem(pair.private.encoded)

        val session = SshSession(
            spec = SshConnectSpec(
                serverId = 1L,
                name = "key-auth",
                host = "127.0.0.1",
                port = running.port,
                username = "tester",
                auth = SshConnectSpec.Auth.PrivateKey(pem = pem, passphrase = null),
                connectTimeoutSeconds = 15,
            ),
            hostKeyVerifier = AlwaysTrust,
            prompts = NoPrompts,
        )

        val result = session.connect()

        assertTrue(
            "key auth must succeed; got $result. A failure here means the key was never " +
                "registered with the transport.",
            result is NeoResult.Ok,
        )
        session.disconnect()
    }

    @Test
    fun `a key the server does not know is refused`() = runBlocking {
        val accepted = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val running = TestSshServer(authorizedPublicKey = accepted.public).start()
        server = running

        val session = SshSession(
            spec = SshConnectSpec(
                serverId = 1L,
                name = "wrong-key",
                host = "127.0.0.1",
                port = running.port,
                username = "tester",
                auth = SshConnectSpec.Auth.PrivateKey(
                    pem = encodeRsaPrivateKeyPem(stranger.private.encoded),
                    passphrase = null,
                ),
                connectTimeoutSeconds = 15,
            ),
            hostKeyVerifier = AlwaysTrust,
            prompts = NoPrompts,
        )

        val result = session.connect()

        assertTrue("an unlisted key must not authenticate, got $result", result is NeoResult.Err)
        session.disconnect()
    }

    @Test
    fun `a garbage key reports an invalid key rather than a generic auth failure`() = runBlocking {
        val session = SshSession(
            spec = SshConnectSpec(
                serverId = 1L,
                name = "bad-key",
                host = "127.0.0.1",
                port = 1,
                username = "tester",
                auth = SshConnectSpec.Auth.PrivateKey(
                    pem = "-----BEGIN RSA PRIVATE KEY-----\nnot a key\n-----END RSA PRIVATE KEY-----",
                    passphrase = null,
                ),
                connectTimeoutSeconds = 5,
            ),
            hostKeyVerifier = AlwaysTrust,
            prompts = NoPrompts,
        )

        val result = session.connect()

        assertTrue("a broken key must fail, got $result", result is NeoResult.Err)
        val error = (result as NeoResult.Err).error
        assertTrue(
            "the error must name the key, not the network; got $error",
            error is NeoError.InvalidPrivateKey,
        )
    }

    @Test
    fun `the parser produces a pem the transport accepts`() = runBlocking {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = encodeRsaPrivateKeyPem(pair.private.encoded)

        val parsed = KeyParser.parse(pem)

        assertTrue("the generated PKCS#1 key must parse, got $parsed", parsed is KeyParseResult.Success)
        val key = (parsed as KeyParseResult.Success).key
        assertTrue("the parsed pem must keep the armour", key.pem.contains("PRIVATE KEY"))
    }

    /**
     * Wrap a PKCS#8 payload as PEM.
     *
     * The JDK's `PrivateKey.getEncoded()` returns PKCS#8 (`BEGIN PRIVATE KEY`), and that
     * is what is emitted here directly. An earlier version tried to re-wrap it as
     * PKCS#1 by hand-parsing the DER, produced a truncated body, and made the test fail
     * for a reason that had nothing to do with the code under test.
     */
    private fun encodeRsaPrivateKeyPem(pkcs8: ByteArray): String {
        val base64 = java.util.Base64.getEncoder().encodeToString(pkcs8)
        return buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            base64.chunked(64).forEach { appendLine(it) }
            append("-----END PRIVATE KEY-----")
        }
    }
    private object AlwaysTrust : HostTrustStore {
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

    private object NoPrompts : SshConnectPrompts {
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
