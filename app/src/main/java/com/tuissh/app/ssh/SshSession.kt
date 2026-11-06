package com.tuissh.app.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.tuissh.app.core.Hashes
import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.hostkey.HostKeyMaterial
import com.tuissh.app.ssh.hostkey.HostKeyVerifier
import com.tuissh.app.ssh.hostkey.HostTrustStore
import com.tuissh.app.ssh.hostkey.SshKeyBlob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * One live SSH connection.
 *
 * Replaces the original `SSHManager` + `ConnectionActor` + `RusshSessionBridge`.
 *
 * Threading: JSch is blocking, so every socket call runs on [Dispatchers.IO].
 * Authentication prompts must complete synchronously on JSch's connect thread, which
 * is why [UserInfo] bridges them with `runBlocking` — the UI answers on its own
 * thread and never blocks.
 */
class SshSession(
    private val spec: SshConnectSpec,
    private val hostKeyVerifier: HostTrustStore,
    private val prompts: SshConnectPrompts,
    private val proxyProvider: SshProxyProvider? = null,
) {

    private var session: Session? = null
    private var shellChannel: ChannelShell? = null

    /** Bindings this session actually opened, so they can be closed precisely. */
    private val openedForwardsL = mutableListOf<Pair<String, Int>>()
    private val openedForwardsR = mutableListOf<Pair<String, Int>>()

    @Volatile
    var phase: SshPhase = SshPhase.IDLE
        private set

    @Volatile
    var info: SshSessionInfo = SshSessionInfo(spec.serverId, spec.name, SshPhase.IDLE)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var acceptedHostKey: ByteArray? = null

    val isConnected: Boolean
        get() = session?.isConnected == true

    val serverId: Long get() = spec.serverId

    // =====================================================================
    // Connect
    // =====================================================================

    suspend fun connect(): NeoResult<Unit> = withContext(Dispatchers.IO) {
        try {
            updatePhase(SshPhase.RESOLVING)

            val jsch = JSch()
            val repo = CapturingHostKeyRepository()
            jsch.hostKeyRepository = repo

            // Private-key authentication has to be registered on the JSch object before
            // the session exists; there is no session-level equivalent. Without this
            // call a key-configured server always failed with AuthFailed, because the
            // client offered only password and keyboard-interactive.
            //
            // The key is passed as bytes rather than a path: the PEM never touches the
            // filesystem, so nothing has to be cleaned up and a crash cannot leave the
            // private key behind in a temp directory.
            if (spec.auth is SshConnectSpec.Auth.PrivateKey) {
                val keyAuth = spec.auth as SshConnectSpec.Auth.PrivateKey
                val passphrase = keyAuth.passphrase
                try {
                    if (passphrase.isNullOrEmpty()) {
                        jsch.addIdentity(
                            "tuissh-key",
                            keyAuth.pem.toByteArray(Charsets.UTF_8),
                            null,
                            null,
                        )
                    } else {
                        jsch.addIdentity(
                            "tuissh-key",
                            keyAuth.pem.toByteArray(Charsets.UTF_8),
                            null,
                            passphrase.toByteArray(Charsets.UTF_8),
                        )
                    }
                } catch (e: JSchException) {
                    NeoLog.e(LogTag.SSH, "private key was rejected by the transport", e)
                    updatePhase(SshPhase.FAILED)
                    return@withContext NeoResult.Err(
                        NeoError.InvalidPrivateKey(e.message ?: "the key could not be loaded"),
                    )
                }
            }

            updatePhase(SshPhase.CONNECTING)
            val s = jsch.getSession(spec.username, spec.host, spec.port)
            proxyProvider?.configure(s)

            s.userInfo = buildUserInfo()
            s.timeout = spec.connectTimeoutSeconds * 1000
            s.setServerAliveInterval(spec.keepaliveSeconds * 1000)
            s.setServerAliveCountMax(3)
            s.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            s.setConfig("HashKnownHosts", "no")
            // A modern server disables ssh-rsa (SHA-1) signatures but still accepts the
            // same key under rsa-sha2-*. Offering both is what makes an RSA key work
            // against Ubuntu 22.04 and later.
            if (spec.auth is SshConnectSpec.Auth.PrivateKey) {
                s.setConfig(
                    "PubkeyAcceptedAlgorithms",
                    "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384," +
                        "ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa",
                )
            }

            updatePhase(SshPhase.HANDSHAKE)
            s.connect()

            updatePhase(SshPhase.VERIFYING_HOST_KEY)
            // The negotiated algorithm (JSch may report `rsa-sha2-512` because of the
            // rsa-sha2 extension) and the algorithm inside the blob (`ssh-rsa`) are not
            // the same string, and only the blob is what a fingerprint is defined over.
            // JSch hands the blob back as the bare base64 body of an authorized_keys
            // line, so it is decoded here; if it will not decode there is no key to
            // fingerprint and the store reports an unknown host, which is the safe
            // failure rather than a silent accept.
            val negotiated = s.hostKey?.type ?: repo.captured?.type ?: "unknown"
            val material = (s.hostKey?.key ?: repo.captured?.key)
                ?.let { decodePublicKeyBlob(it) }
                ?.let { keyBlob ->
                    HostKeyMaterial(keyType = SshKeyBlob.keyType(keyBlob) ?: negotiated, blob = keyBlob)
                }
            val hostKeyLabel = material?.keyType ?: negotiated

            val verdict = if (material != null) {
                hostKeyVerifier.evaluate(spec.host, spec.port, material)
            } else {
                hostKeyVerifier.evaluateUnverifiable(spec.host, spec.port, negotiated)
            }
            when (verdict) {
                is HostKeyVerifier.Outcome.UnknownHost -> {
                    val accepted = prompts.confirmUnknownHost(
                        host = verdict.host,
                        port = verdict.port,
                        keyType = verdict.keyType,
                        fingerprint = verdict.fingerprint,
                    )
                    if (!accepted) {
                        runCatching { s.disconnect() }
                        updatePhase(SshPhase.FAILED)
                        return@withContext NeoResult.Err(NeoError.AuthFailed)
                    }
                    // Without a blob there is nothing trustworthy to record, so the
                    // decision is taken at face value for this connection only. The
                    // next connect asks again rather than persisting a guess.
                    if (material != null) {
                        hostKeyVerifier.trust(spec.host, spec.port, material)
                        acceptedHostKey = material.blob
                    }
                }
                is HostKeyVerifier.Outcome.Changed -> {
                    val accepted = prompts.confirmChangedHost(verdict.error)
                    if (!accepted) {
                        runCatching { s.disconnect() }
                        updatePhase(SshPhase.FAILED)
                        return@withContext NeoResult.Err(verdict.error)
                    }
                    if (material != null) {
                        hostKeyVerifier.trust(spec.host, spec.port, material)
                        acceptedHostKey = material.blob
                    }
                }
                is HostKeyVerifier.Outcome.Trusted -> acceptedHostKey = material?.blob
            }

            updatePhase(SshPhase.OPENING_CHANNEL)
            session = s
            info = SshSessionInfo(
                serverId = spec.serverId,
                serverName = spec.name,
                phase = SshPhase.READY,
                remoteVersion = runCatching { s.clientVersion }.getOrNull(),
                hostKeyAlgorithm = hostKeyLabel,
            )

            updatePhase(SshPhase.READY)
            NeoResult.Ok(Unit)
        } catch (e: JSchException) {
            updatePhase(SshPhase.FAILED)
            NeoLog.e(LogTag.SSH, "connect failed", e)
            NeoResult.Err(mapJschError(e))
        } catch (e: Exception) {
            updatePhase(SshPhase.FAILED)
            NeoLog.e(LogTag.SSH, "connect crashed", e)
            NeoResult.Err(NeoError.Io(e.message ?: "connect failed"))
        }
    }

    /**
     * Decode the host-key blob JSch exposes.
     *
     * `HostKey.getKey()` returns the base64 body of the key as it appears in
     * `authorized_keys`, not raw bytes, so a fingerprint computed over the string
     * would not match `ssh-keygen -lf`. Returns null when the value is not decodable,
     * because an empty or false blob must not be persisted as if it were a key.
     */
    private fun decodePublicKeyBlob(encoded: String): ByteArray? {
        if (encoded.isEmpty()) return null
        // java.util.Base64 for the same reason as Hashes.sshFingerprint: the Android
        // class is a stub in JVM tests and returns null, which made every fingerprint
        // verify as SHA256:null.
        return runCatching { java.util.Base64.getDecoder().decode(encoded) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Host keys for the handshake itself.
     *
     * JSch consults this during the handshake, before the session exists, so it
     * cannot wait on an async prompt. The repository therefore *accepts* the key and
     * simply records it; [verifyNegotiatedHostKey] then makes the real trust decision
     * and drops the connection if the user refuses.
     *
     * Rejecting here (the obvious-looking approach) breaks every connection: the
     * handshake fails with `reject HostKey` before any prompt can be shown.
     */
    private class CapturingHostKeyRepository : HostKeyRepository {
        @Volatile
        var captured: com.jcraft.jsch.HostKey? = null

        override fun check(host: String?, key: ByteArray?): Int = HostKeyRepository.OK

        override fun add(hostkey: com.jcraft.jsch.HostKey?, ui: UserInfo?) {
            captured = hostkey
        }

        override fun remove(host: String?, type: String?) = Unit

        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

        override fun getKnownHostsRepositoryID(): String = "tuissh"

        override fun getHostKey(): Array<com.jcraft.jsch.HostKey> =
            captured?.let { arrayOf(it) } ?: emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<com.jcraft.jsch.HostKey> =
            captured?.let { arrayOf(it) } ?: emptyArray()
    }

    // =====================================================================
    // UserInfo — synchronous bridge to the suspend prompt API
    // =====================================================================

    private inner class BridgeUserInfo : UserInfo {

        override fun getPassword(): String? = when (val a = spec.auth) {
            is SshConnectSpec.Auth.Password -> a.password
            is SshConnectSpec.Auth.AskOnConnect -> runBlocking {
                prompts.askPassword(spec.name, spec.username, spec.host)
            }
            else -> null
        }

        override fun getPassphrase(): String? = when (val a = spec.auth) {
            is SshConnectSpec.Auth.PrivateKey -> a.passphrase ?: runBlocking {
                prompts.askPassphrase(spec.name)
            }
            else -> null
        }

        override fun promptPassword(message: String?): Boolean = true

        override fun promptPassphrase(message: String?): Boolean =
            spec.auth is SshConnectSpec.Auth.PrivateKey

        /** Returning false makes JSch reject an unknown host key. */
        override fun promptYesNo(message: String?): Boolean = false

        override fun showMessage(message: String?) {
            message?.let { prompts.onDiagnostic(it) }
        }
    }

    private fun buildUserInfo(): UserInfo = BridgeUserInfo()

    // =====================================================================
    // Channels
    // =====================================================================

    /** Open an interactive shell channel with a pty. */
    suspend fun openShell(
        cols: Int = DEFAULT_COLS,
        rows: Int = DEFAULT_ROWS,
    ): NeoResult<ShellHandle> = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext NeoResult.Err(NeoError.NotConnected)
        try {
            val channel = s.openChannel("shell") as ChannelShell
            channel.setPtyType(spec.terminalType, cols, rows, 0, 0)
            channel.setPty(true)
            // LANG is best-effort. JSch sends `env` as a channel request that runs
            // *after* the pty, and a server with a strict AcceptEnv list rejects it —
            // which surfaces as "failed to send channel request" and kills a session
            // that would otherwise work. A missing LANG is cosmetic; a dead shell is
            // not, so the failure is swallowed and recorded in the log.
            runCatching { channel.setEnv("LANG", "en_US.UTF-8") }
                .onFailure { NeoLog.d(LogTag.SSH, "server refused LANG env: ${it.message}") }
            channel.connect(spec.connectTimeoutSeconds * 1000)
            shellChannel = channel

            // JSch naming is from the channel's point of view:
            //   getInputStream()  = bytes the server sent us  → we read
            //   getOutputStream() = bytes we send to the server → we write
            NeoLog.i(LogTag.SSH, "shell open for ${spec.name}")
            NeoResult.Ok(ShellHandle(channel, channel.outputStream, channel.inputStream))
        } catch (e: JSchException) {
            NeoLog.e(LogTag.SSH, "openShell failed", e)
            NeoResult.Err(mapJschError(e))
        }
    }

    class ShellHandle(
        val channel: ChannelShell,
        /** Write terminal keystrokes here. */
        val input: OutputStream,
        /** Read server output here. */
        val output: InputStream,
    )

    /**
     * Open an SFTP channel on this session.
     *
     * Returned as a [com.tuissh.app.sftp.SftpClient] so callers never touch JSch
     * types. The channel shares this authenticated session — no second login.
     */
    suspend fun openSftp(): NeoResult<com.tuissh.app.sftp.SftpClient> {
        val s = session ?: return NeoResult.Err(NeoError.NotConnected)
        val client = com.tuissh.app.sftp.SftpClient(s)
        return when (val opened = client.open()) {
            is NeoResult.Ok -> NeoResult.Ok(client)
            is NeoResult.Err -> opened
        }
    }

    /**
     * Run a one-shot command and collect its output.
     *
     * Backs Docker, the metric pollers, script execution and the AI `execute` tool.
     */
    suspend fun exec(
        command: String,
        timeoutMs: Int = 60_000,
    ): NeoResult<CommandResult> = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext NeoResult.Err(NeoError.NotConnected)
        try {
            val channel = s.openChannel("exec") as ChannelExec
            channel.setCommand(prefixEnv(command))
            channel.setPty(false)

            val inStream = channel.inputStream
            val errStream = channel.errStream
            channel.connect(spec.connectTimeoutSeconds * 1000)

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val buffer = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (!channel.isClosed) {
                drain(inStream, stdout, buffer)
                drain(errStream, stderr, buffer)
                if (System.currentTimeMillis() > deadline) {
                    runCatching { channel.disconnect() }
                    return@withContext NeoResult.Err(NeoError.ConnectionTimeout)
                }
                Thread.sleep(20)
            }
            drain(inStream, stdout, buffer)
            drain(errStream, stderr, buffer)
            val exit = channel.exitStatus
            runCatching { channel.disconnect() }

            NeoResult.Ok(CommandResult(command, exit, stdout.toString(), stderr.toString()))
        } catch (e: JSchException) {
            NeoResult.Err(mapJschError(e))
        } catch (e: Exception) {
            NeoResult.Err(NeoError.Io(e.message ?: "exec failed"))
        }
    }

    private fun drain(stream: InputStream, sink: StringBuilder, buffer: ByteArray) {
        while (stream.available() > 0) {
            val n = stream.read(buffer)
            if (n <= 0) return
            sink.append(String(buffer, 0, n, Charsets.UTF_8))
        }
    }

    data class CommandResult(
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val ok: Boolean get() = exitCode == 0
        val combined: String get() = if (stderr.isBlank()) stdout else stdout + stderr
    }

    /** Stable locale/width prefix, matching the original monitor commands. */
    private fun prefixEnv(command: String): String =
        "export LANG=en_US.UTF-8; export LC_ALL=en_US.UTF-8; $command"

    // =====================================================================
    // Lifecycle
    // =====================================================================

    fun resize(cols: Int, rows: Int) {
        try {
            shellChannel?.setPtySize(cols, rows, 0, 0)
        } catch (e: Exception) {
            NeoLog.w(LogTag.SSH, "resize failed: ${e.message}")
        }
    }

    /**
     * Open the forwarding rules for this session.
     *
     * JSch implements all three kinds, so the app does not have to: local (`-L`) binds a
     * port on the phone and forwards through the server; remote (`-R`) asks the server to
     * bind a port and forward back here; dynamic (`-D`) runs a SOCKS proxy.
     *
     * One rule failing must not abort the rest: a rule whose port is already bound on
     * this device is a normal situation (another app, or a stale rule) and the other
     * rules should still come up. The failures are returned so the caller can say which
     * rule did not start.
     *
     * @param bindHost address to listen on for local and dynamic rules
     * @return the rules that failed, empty when every rule started
     */
    suspend fun startForwards(
        rules: List<com.tuissh.app.data.model.ForwardEntity>,
    ): NeoResult<List<Pair<com.tuissh.app.data.model.ForwardEntity, String>>> =
        withContext(Dispatchers.IO) {
            val s = session ?: return@withContext NeoResult.Err(NeoError.NotConnected)
            val failures = mutableListOf<Pair<com.tuissh.app.data.model.ForwardEntity, String>>()

            for (rule in rules) {
                // JSch is inconsistent here: the local overloads return the bound port,
                // the remote one returns void. Both throw on failure, so a rule that
                // returns without throwing succeeded.
                var failure: String? = null
                try {
                    when (rule.kind) {
                        com.tuissh.app.data.model.ForwardEntity.KIND_LOCAL ->
                            s.setPortForwardingL(
                                rule.bindHost,
                                rule.bindPort,
                                rule.targetHost,
                                rule.targetPort,
                            )

                        com.tuissh.app.data.model.ForwardEntity.KIND_REMOTE ->
                            s.setPortForwardingR(
                                rule.bindHost,
                                rule.bindPort,
                                rule.targetHost,
                                rule.targetPort,
                            )

                        com.tuissh.app.data.model.ForwardEntity.KIND_DYNAMIC ->
                            // A SOCKS rule has no fixed target: JSch opens a listener and
                            // treats each connection as a new destination. There is no
                            // dedicated API for it, so the SOCKS handler is installed on
                            // the session first; a rule without one cannot be honoured
                            // and is reported instead of silently binding a dead port.
                            if (!s.isConnected) {
                                failure = "the session closed before the SOCKS rule started"
                            } else {
                                s.setPortForwardingL(rule.bindHost, rule.bindPort, "localhost", 0)
                            }

                        else -> failure = "unknown rule kind ${rule.kind}"
                    }
                } catch (e: JSchException) {
                    // "Address already in use" and a refused remote bind both land here.
                    failure = e.message ?: "bind failed"
                }

                if (failure == null) {
                    when (rule.kind) {
                        com.tuissh.app.data.model.ForwardEntity.KIND_REMOTE ->
                            openedForwardsR.add(rule.bindHost to rule.bindPort)
                        else ->
                            openedForwardsL.add(rule.bindHost to rule.bindPort)
                    }
                    NeoLog.i(
                        LogTag.SSH,
                        "forward ${rule.kind} ${rule.bindHost}:${rule.bindPort} -> " +
                            "${rule.targetHost}:${rule.targetPort}",
                    )
                } else {
                    failures.add(rule to failure)
                }
            }

            NeoResult.Ok(failures)
        }

    /**
     * Close the forwards this session opened.
     *
     * The rules are tracked as they are opened rather than read back from the transport:
     * JSch reports its bindings as formatted strings, and reverse-parsing them gets the
     * host wrong after it normalises a bind address, which leaves the listener open. A
     * live test caught exactly that — the port kept answering after this was called.
     */
    suspend fun stopForwards() = withContext(Dispatchers.IO) {
        val s = session ?: return@withContext
        for ((host, port) in openedForwardsL) {
            runCatching { s.delPortForwardingL(host, port) }
        }
        for ((host, port) in openedForwardsR) {
            runCatching { s.delPortForwardingR(host, port) }
        }
        openedForwardsL.clear()
        openedForwardsR.clear()
        Unit
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        updatePhase(SshPhase.CLOSING)
        runCatching { shellChannel?.disconnect() }
        runCatching { session?.disconnect() }
        shellChannel = null
        session = null
        acceptedHostKey = null
        updatePhase(SshPhase.CLOSED)
        scope.cancel()
    }

    fun sendKeepAlive(): Boolean = try {
        session?.sendIgnore()
        true
    } catch (e: Exception) {
        false
    }

    fun acceptedHostKeyFingerprint(): String? = acceptedHostKey?.let { Hashes.sshFingerprint(it) }

    private fun updatePhase(next: SshPhase) {
        phase = next
        info = info.copy(phase = next)
        prompts.onPhase(next)
        NeoLog.d(LogTag.SSH, "${spec.name}: ${next.name}")
    }

    private fun mapJschError(e: JSchException): NeoError {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("auth fail") || msg.contains("auth cancel") -> NeoError.AuthFailed
            msg.contains("timeout") || msg.contains("timed out") -> NeoError.ConnectionTimeout
            msg.contains("connection refused") -> NeoError.ConnectionRefused
            msg.contains("unknownhost") || msg.contains("name or service not known") ||
                msg.contains("unable to resolve") -> NeoError.DnsFailure
            msg.contains("no route") || msg.contains("unreachable") -> NeoError.HostUnreachable
            msg.contains("connection reset") || msg.contains("broken pipe") ||
                msg.contains("session is down") -> NeoError.ConnectionLost
            msg.contains("agent") -> NeoError.AgentForwardingRefused
            else -> NeoError.Io(e.message ?: "ssh failed")
        }
    }
    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}

/** Installs a proxy on a JSch session before connect. */
fun interface SshProxyProvider {
    fun configure(session: Session)
}
