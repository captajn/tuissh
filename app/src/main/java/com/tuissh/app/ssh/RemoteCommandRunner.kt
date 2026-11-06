package com.tuissh.app.ssh

import com.tuissh.app.core.NeoResult

/**
 * Runs one command on a connected host.
 *
 * [MonitorClient] and [com.tuissh.app.docker.DockerClient] only need this, not the
 * whole session: extracting it keeps them testable without a live server, and it makes
 * the dependency they actually have explicit rather than "everything SshSession can
 * do".
 *
 * Implementations must be safe to call from several coroutines. [SshSession] satisfies
 * that because each call opens its own channel.
 */
interface RemoteCommandRunner {

    /**
     * Execute [command] and collect its output.
     *
     * @param timeoutMs wall-clock budget for the whole command, after which the
     *        implementation must give up and return
     *        [com.tuissh.app.core.NeoError.ConnectionTimeout] rather than blocking
     *        forever on a wedged channel
     */
    suspend fun run(command: String, timeoutMs: Int): NeoResult<SshSession.CommandResult>

    /** Run with the default budget, so callers do not repeat the constant. */
    suspend fun run(command: String): NeoResult<SshSession.CommandResult> =
        run(command, DEFAULT_TIMEOUT_MS)

    companion object {
        /** Matches [SshSession.exec]'s own default. */
        const val DEFAULT_TIMEOUT_MS = 60_000

        /** Adapt a live session. */
        fun of(session: SshSession): RemoteCommandRunner =
            object : RemoteCommandRunner {
                override suspend fun run(
                    command: String,
                    timeoutMs: Int,
                ): NeoResult<SshSession.CommandResult> = session.exec(command, timeoutMs)
            }
    }
}
