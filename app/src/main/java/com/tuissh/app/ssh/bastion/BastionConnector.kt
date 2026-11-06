package com.tuissh.app.ssh.bastion

import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.SshConnectSpec
import com.tuissh.app.ssh.SshSession

/**
 * Reaches a server through one or more bastions.
 *
 * ## How the jump works
 *
 * SSH has no "connect through" verb, so ProxyJump is emulated the way `ssh -J` does it:
 * connect to the first bastion normally, ask it to open a local port that forwards to
 * the next host, then connect the next session to that local port. Each hop repeats,
 * and the chain collapses into one socket per hop that all live on this device.
 *
 * That is why every hop needs its own authenticated session rather than a single
 * tunnel: each one is an independent SSH connection, and if any hop's credentials are
 * wrong the chain stops there with that hop named.
 *
 * ## Why not a SOCKS proxy
 *
 * A SOCKS proxy would need a listener too, and it would hide which hop failed. The
 * forward chain keeps each hop addressable, so a failure can name the machine that
 * refused rather than reporting "the tunnel failed".
 */
class BastionConnector(
    /** Opens a session for one hop. Injected so tests can substitute a fake. */
    private val openSession: (SshConnectSpec) -> SshSession,
) {

    /** A live chain: the connected sessions plus the last port to dial. */
    data class Reachable(
        /** Authenticated sessions, outermost first. Closed in reverse on teardown. */
        val sessions: List<SshSession>,
        /** Where the final host is reachable from this device. */
        val host: String,
        val port: Int,
    ) {
        suspend fun close() {
            // Reverse order: the innermost forward sits on the outermost socket, so
            // closing from the outside would cut the forward it depends on.
            for (session in sessions.asReversed()) {
                runCatching { session.disconnect() }
            }
        }
    }

    /**
     * Open a chain to [target].
     *
     * @param hops bastions in connect order; index 0 must be directly reachable
     * @param specs the connection details for each hop, including [target]
     */
    suspend fun open(
        hops: List<SshConnectSpec>,
        target: SshConnectSpec,
    ): NeoResult<Reachable> {
        if (hops.isEmpty()) {
            // No bastion: nothing to do, the caller connects directly.
            return NeoResult.Ok(Reachable(emptyList(), target.host, target.port))
        }

        val sessions = mutableListOf<SshSession>()

        // Each step points at where the *next* thing is reachable from.
        var currentHost = hops.first().host
        var currentPort = hops.first().port

        try {
            for ((index, spec) in hops.withIndex()) {
                // The spec carries the bastion's own address; the socket has to go to
                // wherever the previous forward put it, so the address is replaced
                // while the credentials stay the bastion's.
                val dial = spec.copy(
                    host = currentHost,
                    port = currentPort,
                    // Host-key trust is per real host: the forward is loopback but the
                    // identity being verified is still the bastion's own.
                )

                val session = openSession(dial)
                val connected = session.connect()
                if (connected is NeoResult.Err) {
                    sessions.asReversed().forEach { runCatching { it.disconnect() } }
                    return NeoResult.Err(
                        NeoError.BastionUnreachable(
                            host = spec.host,
                            detail = connected.error.message ?: "connection refused",
                        ),
                    )
                }
                sessions.add(session)

                // Forward a local port through this hop to the next destination.
                val next = hops.getOrNull(index + 1) ?: target
                val localPort = session.openTunnelTo(next.host, next.port)
                if (localPort is NeoResult.Err) {
                    sessions.asReversed().forEach { runCatching { it.disconnect() } }
                    return NeoResult.Err(localPort.error)
                }

                NeoLog.i(LogTag.SSH, "hop ${spec.host} tunneled to ${next.host}:${next.port}")
                currentHost = LOOPBACK
                currentPort = localPort.getOrThrow()
            }

            return NeoResult.Ok(Reachable(sessions, currentHost, currentPort))
        } catch (e: Exception) {
            NeoLog.e(LogTag.SSH, "bastion chain failed", e)
            sessions.asReversed().forEach { runCatching { it.disconnect() } }
            return NeoResult.Err(NeoError.Io(e.message ?: "bastion chain failed"))
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
