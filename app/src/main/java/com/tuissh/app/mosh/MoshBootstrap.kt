package com.tuissh.app.mosh

import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.RemoteCommandRunner

/**
 * Starts `mosh-server` on the remote host and reads back the connection details.
 *
 * Mosh works by running a server process over an ordinary SSH session, which prints one
 * line describing a UDP port and a shared key; the client then talks to that port
 * directly and SSH is dropped. This class covers that handshake only.
 *
 * ## What is and is not implemented
 *
 * Implemented: launching the server, reading its `MOSH CONNECT` line, and validating it.
 * Not implemented: the State Synchronisation Protocol that the UDP side speaks — SSP
 * needs a specific datagram format, AES-OCB and a protobuf schema. Without it a session
 * can be *started* but not *driven*, so [MoshSession] refuses rather than pretending.
 *
 * This is stated plainly because a half-working Mosh is worse than none: the failure
 * would surface as a blank terminal mid-session rather than at connect time.
 */
class MoshBootstrap(private val runner: RemoteCommandRunner) {

    /** What `mosh-server new` reported. */
    data class Connection(
        val host: String,
        val port: Int,
        /** Base64 key, exactly as the server printed it. */
        val key: String,
    ) {
        /**
         * The `MOSH_KEY` export a user would paste into a terminal.
         *
         * Kept because it is the one piece of this handshake that is useful even without
         * the UDP client: it lets the app show what was negotiated.
         */
        val keyExport: String get() = "MOSH_KEY=$key"
    }

    /**
     * Ask the host to start a server.
     *
     * `-c` sets the colour capability and `-l` the locale, which is what the reference
     * client sends; without them the server may start without 256-colour support.
     *
     * @param bind the address family to bind; `0.0.0.0` keeps both stacks open
     */
    suspend fun start(
        host: String,
        cols: Int = 80,
        rows: Int = 24,
        bind: String = "0.0.0.0",
    ): NeoResult<Connection> {
        val command = "mosh-server new -s -c 256 -i $bind -l LANG=en_US.UTF-8 " +
            "-p $portRangePlaceholder -- $cols $rows 2>&1"

        val result = runner.run(command, timeoutMs = STARTUP_TIMEOUT_MS)
        if (result is NeoResult.Err) {
            // A connect-level failure is not a Mosh problem; report it as itself.
            return result
        }

        val output = result.getOrThrow()
        val combined = output.stdout + "\n" + output.stderr

        // The three failures below are distinguishable from the server's own output, so
        // they are reported separately rather than as one "Mosh failed".
        if (combined.contains("command not found") || combined.contains("No such file")) {
            NeoLog.w(LogTag.MOSH, "mosh-server is missing on $host")
            return NeoResult.Err(NeoError.MoshServerMissing)
        }
        if (combined.contains("Permission denied", ignoreCase = true)) {
            return NeoResult.Err(NeoError.MoshPermissionDenied)
        }

        val connection = parseStartup(combined, host)
            ?: return NeoResult.Err(NeoError.MoshBadStartupOutput)

        NeoLog.i(LogTag.MOSH, "mosh-server started on ${connection.host}:${connection.port}")
        return NeoResult.Ok(connection)
    }

    companion object {
        /** Port range the server may choose from; empty lets it pick. */
        private const val portRangePlaceholder = "60000:61000"

        /** The server prints its line quickly or not at all. */
        const val STARTUP_TIMEOUT_MS = 20_000

        /** Marker the server prefixes its connection line with. */
        const val CONNECT_MARKER = "MOSH CONNECT "

        /**
         * Parse `MOSH CONNECT <port> <key>` out of the server's output.
         *
         * The line may be surrounded by locale warnings, a shell banner or an SSH
         * message-of-the-day, so the whole payload is scanned rather than only its first
         * line. A malformed port or a missing key is rejected instead of being guessed
         * at, because a wrong port would silently talk UDP at something else.
         */
        fun parseStartup(output: String, host: String): Connection? {
            val index = output.indexOf(CONNECT_MARKER)
            if (index < 0) return null

            val rest = output.substring(index + CONNECT_MARKER.length)
            val line = rest.lineSequence().firstOrNull()?.trim().orEmpty()
            if (line.isEmpty()) return null

            val parts = line.split(Regex("""\s+"""))
            if (parts.size < 2) return null

            val port = parts[0].toIntOrNull() ?: return null
            if (port !in 1..65535) return null

            val key = parts[1]
            // The key is base64 of the AES key; anything much shorter means the line was
            // truncated in transit.
            if (key.length < MIN_KEY_LENGTH) return null

            return Connection(host = host, port = port, key = key)
        }

        /** Shortest plausible base64 key; the real one is 22 characters. */
        const val MIN_KEY_LENGTH = 16
    }
}
