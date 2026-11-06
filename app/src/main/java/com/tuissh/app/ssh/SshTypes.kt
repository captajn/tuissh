package com.tuissh.app.ssh

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.model.ServerEntity

/**
 * Everything needed to open one SSH connection, resolved from storage.
 *
 * Credentials live in this object only for the duration of a connect attempt; they
 * are never written back to the database in plaintext and never logged.
 */
data class SshConnectSpec(
    val serverId: Long,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val auth: Auth,
    val terminalType: String = "xterm-256color",
    val keepaliveSeconds: Int = 30,
    val connectTimeoutSeconds: Int = 15,
    val startupScript: String? = null,
    val defaultWorkdir: String? = null,
    val agentForwarding: Boolean = false,
    val tcpNoDelay: Boolean = true,
) {
    sealed interface Auth {
        data class Password(val password: String) : Auth

        data class PrivateKey(
            val pem: String,
            val passphrase: String?,
        ) : Auth

        /** Server drives a challenge/response exchange (OTP, Duo, PAM). */
        data object KeyboardInteractive : Auth

        /** Ask the user at connect time; the UI supplies [Password] afterwards. */
        data object AskOnConnect : Auth

        data object None : Auth
    }

    init {
        require(port in 1..65535) { "invalid port" }
    }
}

/** Connection phase, surfaced to the UI so the terminal can show progress. */
enum class SshPhase {
    IDLE,
    RESOLVING,
    CONNECTING,
    HANDSHAKE,
    VERIFYING_HOST_KEY,
    AUTHENTICATING,
    OPENING_CHANNEL,
    READY,
    CLOSING,
    CLOSED,
    FAILED,
}

/** Snapshot of a live session, safe to read from any thread. */
data class SshSessionInfo(
    val serverId: Long,
    val serverName: String,
    val phase: SshPhase,
    val remoteVersion: String? = null,
    val cipherClientToServer: String? = null,
    val cipherServerToClient: String? = null,
    val macClientToServer: String? = null,
    val macServerToClient: String? = null,
    val kexAlgorithm: String? = null,
    val hostKeyAlgorithm: String? = null,
)

/**
 * Callbacks a terminal session supplies while the connection is being opened.
 *
 * `suspend` is deliberate: host-key confirmation and keyboard-interactive prompts
 * must be able to wait for the user without blocking the connect thread.
 */
interface SshConnectPrompts {

    /**
     * The host key is unknown. Return true to trust and connect.
     * Implementations should show the fingerprint from [SshError.HostKeyUnknown].
     */
    suspend fun confirmUnknownHost(
        host: String,
        port: Int,
        keyType: String,
        fingerprint: String,
    ): Boolean

    /** The host key changed. Return true only after the user explicitly accepted. */
    suspend fun confirmChangedHost(error: NeoError.HostKeyChanged): Boolean

    /** Password was not stored; collect it now. Return null to abort. */
    suspend fun askPassword(serverName: String, username: String, host: String): String?

    /** Private-key passphrase is missing. Return null to abort. */
    suspend fun askPassphrase(keyName: String): String?

    /** Keyboard-interactive round. Return answers in order, or null to abort. */
    suspend fun askKeyboardInteractive(
        name: String,
        instruction: String,
        prompts: List<String>,
        echo: List<Boolean>,
    ): List<String>?

    /** Progress updates for the connection banner. */
    fun onPhase(phase: SshPhase) {}

    /** Non-fatal diagnostics. */
    fun onDiagnostic(message: String) {}
}

/** Marker errors specific to establishing a session. */
sealed class SshError(message: String) : Exception(message) {
    data object HostKeyRejected : SshError("Host key was not accepted")

    data object UserAborted : SshError("Connection cancelled by the user")

    data class UnsupportedAuth(val detail: String) : SshError("Unsupported authentication: $detail")

    data class ExecFailed(val exitCode: Int, val stderr: String) :
        SshError("Command exited with status $exitCode")
}

/** Result wrapper used by session operations that only care about success. */
typealias SshResult<T> = NeoResult<T>
