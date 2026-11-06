package com.tuissh.app.ssh

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.InteractiveProcessShellFactory
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * A real in-process SSH server for tests.
 *
 * Uses Apache MINA SSHD so the SSH and SFTP layers run against an actual protocol
 * implementation. A mock would not catch cipher negotiation, host-key handling or
 * channel semantic mistakes — the three things most likely to be wrong.
 *
 * The server exposes:
 *   - password auth (`tester` / `secret`)
 *   - an SFTP subsystem
 *   - a real interactive shell via the process shell factory
 *   - a process command factory for `exec`
 *
 * Bound to loopback on an ephemeral port.
 */
class TestSshServer(
    private val username: String = "tester",
    private val password: String = "secret",
    /**
     * When set, clients may authenticate with the matching private key.
     *
     * Key auth is opt-in so the existing password tests keep exercising exactly one
     * path, and a suite can prove the key path separately.
     */
    private val authorizedPublicKey: java.security.PublicKey? = null,
) {

    private var server: SshServer? = null

    lateinit var homeDir: Path
        private set

    val port: Int
        get() = server?.port ?: error("server is not running")

    fun start(): TestSshServer {
        homeDir = Files.createTempDirectory("tuissh-test-home")

        val sshd = SshServer.setUpDefaultServer()
        sshd.host = "127.0.0.1"
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(
            Files.createTempFile("tuissh-test-hostkey", ".ser"),
        )
        sshd.passwordAuthenticator = PasswordAuthenticator { user, pass, _: ServerSession? ->
            user == username && pass == password
        }
        // Public-key auth is enabled with a configurable authorised key so a
        // private-key login can be exercised offline. Without this the suite only ever
        // proved password auth, which is exactly how the missing
        // `jsch.addIdentity()` call went unnoticed.
        authorizedPublicKey?.let { key ->
            sshd.publickeyAuthenticator = PublickeyAuthenticator { user, key2, _: ServerSession? ->
                user == username && key2 != null && key2 == key
            }
        }

        sshd.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())

        // A real pty-backed shell and a real command executor. `UnknownCommandFactory`
        // would make `openShell` fail with "failed to send channel request" because
        // the server has no shell to offer.
        sshd.shellFactory = InteractiveProcessShellFactory.INSTANCE
        sshd.commandFactory = ProcessShellCommandFactory.INSTANCE

        sshd.start()
        server = sshd
        return this
    }

    fun stop() {
        runCatching { server?.stop(true) }
        server = null
        runCatching {
            if (::homeDir.isInitialized) {
                Files.walk(homeDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { runCatching { Files.delete(it) } }
            }
        }
    }
}
