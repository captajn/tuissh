package com.tuissh.app.core

/**
 * Explicit result type. The original Swift code used throwing functions plus
 * `Result<T, Error>` in the Rust bridge; Kotlin call sites read better with a
 * sealed type that carries a typed failure instead of a stack trace.
 */
sealed interface NeoResult<out T> {

    data class Ok<T>(val value: T) : NeoResult<T>

    data class Err(
        val error: NeoError,
    ) : NeoResult<Nothing>

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Err -> throw error.toException()
    }

    fun <R> map(transform: (T) -> R): NeoResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    companion object {
        fun <T> ok(value: T): NeoResult<T> = Ok(value)
        fun <T> err(error: NeoError): NeoResult<T> = Err(error)
    }
}

/** Typed failure surface shared by SSH, SFTP, Docker and the AI client. */
sealed class NeoError(message: String) : Exception(message) {

    // --- transport / connection ------------------------------------------------

    data object ConnectionTimeout : NeoError("Connection timeout")

    data object HostUnreachable : NeoError("Host unreachable")

    data object ConnectionRefused : NeoError("Connection refused")

    data object ConnectionLost : NeoError("Connection lost")

    data object NotConnected : NeoError("SSH session is not connected")

    data object DnsFailure : NeoError("Failed to resolve hostname")

    // --- authentication --------------------------------------------------------

    data object AuthFailed : NeoError("Authentication failed")

    data object AuthCancelled : NeoError("Authentication cancelled")

    data object AuthTimedOut : NeoError("Authentication timed out waiting for the password")

    data object NoMatchingAuthMethod : NeoError("No supported authentication method")

    data object AgentForwardingRefused :
        NeoError("SSH agent forwarding was refused by the server")

    data class HostKeyChanged(
        val host: String,
        val previousSha256: String,
        val currentSha256: String,
    ) : NeoError("The SSH host key for $host no longer matches the trusted key")

    // --- keys ------------------------------------------------------------------

    data class InvalidPrivateKey(val detail: String) : NeoError("Invalid private key: $detail")

    data class KeyPassphraseRequired(val keyId: Long) : NeoError("Private key passphrase required")

    // --- sftp ------------------------------------------------------------------

    data object SftpUnavailable : NeoError("Open SFTP failed, please check that SFTP is enabled on the server")

    data class SftpFailure(val operation: String, val detail: String) :
        NeoError("SFTP $operation failed: $detail")

    /**
     * Engine (Docker/Podman) failures.
     *
     * Kept distinct from a generic command failure because each one has a different
     * fix the user can act on: install the binary, join the docker group, or start
     * the daemon.
     */
    data class EngineUnavailable(val binary: String) :
        NeoError("$binary is not installed on the server")

    data object EnginePermissionDenied :
        NeoError("Permission denied talking to the container engine. Add the user to the docker group.")

    data object EngineDaemonDown :
        NeoError("The container engine is installed but not running.")

    data class EngineCommandFailed(val detail: String) : NeoError(detail)
    data class PathNotFound(val path: String) : NeoError("$path no longer exists")

    data class IsDirectory(val path: String) : NeoError("$path is a directory")

    // --- forwarding ------------------------------------------------------------

    data class BastionNotFound(val name: String) : NeoError("Bastion server not found: $name")

    /** A hop in the jump chain refused or could not be reached. */
    data class BastionUnreachable(val host: String, val detail: String) :
        NeoError("Could not connect through $host: $detail")

    data class CyclicBastion(val chain: String) : NeoError("Cyclic bastion configuration: $chain")

    data class InvalidProxy(val detail: String) : NeoError("Invalid SOCKS5 proxy: $detail")

    data class PortBindFailed(val port: Int, val detail: String) :
        NeoError("Cannot bind local port $port: $detail")

    // --- mosh ------------------------------------------------------------------

    data object MoshServerMissing :
        NeoError("mosh-server is not installed on the remote server")

    data object MoshPermissionDenied :
        NeoError("mosh-server permission denied on the remote server")

    data object MoshStartupTimeout : NeoError("mosh-server startup timed out")

    data object MoshBadStartupOutput : NeoError("Invalid mosh-server startup output")

    data object MoshProtocolMismatch : NeoError("Mosh protocol mismatch")

    data class MoshFailure(val detail: String) : NeoError("Mosh session failed: $detail")

    // --- docker ----------------------------------------------------------------

    data object DockerUnavailable : NeoError("Docker is not reachable on this server")

    data class DockerFailure(val detail: String) : NeoError("Docker command failed: $detail")

    data class UnsupportedRunCommand(val detail: String) :
        NeoError("Only docker run or podman run commands are supported")

    // --- ai --------------------------------------------------------------------

    data class AiNotConfigured(val detail: String) : NeoError("AI provider not configured: $detail")

    data class AiHttp(val status: Int, val body: String) :
        NeoError("AI request failed with HTTP $status: $body")

    data class AiProtocol(val detail: String) : NeoError("AI protocol error: $detail")

    // --- generic ---------------------------------------------------------------

    data class Io(val detail: String) : NeoError("I/O error: $detail")

    data class Unknown(val detail: String) : NeoError(detail)

    fun toException(): Exception = if (this is Exception) this else Exception(message)
}

/** Small helper so call sites can wrap a throwing block without noisy try/catch. */
inline fun <T> neoRun(block: () -> T): NeoResult<T> = try {
    NeoResult.Ok(block())
} catch (e: NeoError) {
    NeoResult.Err(e)
} catch (e: Exception) {
    NeoResult.Err(NeoError.Io(e.message ?: e.javaClass.simpleName))
}
