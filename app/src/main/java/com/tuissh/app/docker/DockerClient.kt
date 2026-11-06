package com.tuissh.app.docker

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.RemoteCommandRunner

/**
 * Docker engine access over an existing SSH session.
 *
 * Every call runs a `docker` command with `--format '{{json .}}'` and hands the
 * output to [DockerParsers]. Using the CLI rather than the engine API is a
 * deliberate trade: the socket protocol needs a client library and a forwarded unix
 * socket, while the CLI is already present on any host that runs containers, works
 * over the same SSH channel, and is trivial to reason about.
 *
 * Commands are executed with `exec`, so a Docker view never steals the interactive
 * shell's channel.
 */
class DockerClient(private val runner: RemoteCommandRunner) {

    /** Which binary answered. Podman is API-compatible for every command used here. */
    private var engineBinary: String? = null

    data class EngineInfo(
        val binary: String,
        val version: String,
        val serverVersion: String?,
        val rootless: Boolean,
    )

    // =====================================================================
    // Discovery
    // =====================================================================

    /**
     * Find a container engine and confirm it answers.
     *
     * Tries `docker`, then `podman`. A rootless or permission-denied failure is
     * reported as such instead of being flattened into "not available".
     */
    suspend fun detect(): NeoResult<EngineInfo> = runCatchingEngine { binary ->
        val version = run("$binary version --format '{{.Client.Version}}'")
        val server = run("$binary version --format '{{.Server.Version}}'")
        if (version.isFailure) return@runCatchingEngine version.problem()

        val rootless = run("$binary info --format '{{.SecurityOptions}}'")
            .stdout
            .contains("rootless", ignoreCase = true)

        NeoResult.Ok(
            EngineInfo(
                binary = binary,
                version = version.stdout.trim().ifEmpty { "unknown" },
                serverVersion = server.stdout.trim().ifBlank { null },
                rootless = rootless,
            ),
        )
    }

    /** Throws away the cached binary so the next call re-detects. */
    fun invalidate() {
        engineBinary = null
    }

    // =====================================================================
    // Containers
    // =====================================================================

    suspend fun listContainers(includeStopped: Boolean = true): NeoResult<List<DockerParsers.Container>> =
        withEngine { binary ->
            val flag = if (includeStopped) "-a" else ""
            val result = run("$binary ps $flag --no-trunc --format '{{json .}}'")
            if (result.isFailure) return@withEngine result.problem()
            NeoResult.Ok(DockerParsers.parseContainers(result.stdout))
        }

    suspend fun start(id: String): NeoResult<Unit> = requireId(id) { binary ->
        action("$binary start $id")
    }

    suspend fun stop(id: String): NeoResult<Unit> = requireId(id) { binary ->
        action("$binary stop $id")
    }

    suspend fun restart(id: String): NeoResult<Unit> = requireId(id) { binary ->
        action("$binary restart $id")
    }

    suspend fun pause(id: String): NeoResult<Unit> = requireId(id) { binary ->
        action("$binary pause $id")
    }

    suspend fun unpause(id: String): NeoResult<Unit> = requireId(id) { binary ->
        action("$binary unpause $id")
    }

    suspend fun remove(id: String, force: Boolean = false): NeoResult<Unit> = requireId(id) { binary ->
        action("$binary rm $id${if (force) " -f" else ""}")
    }

    /** Tail of `docker logs`, bounded so a huge log cannot exhaust memory. */
    suspend fun logs(id: String, lines: Int = 500): NeoResult<String> = requireId(id) { binary ->
        val result = run("$binary logs --tail $lines $id 2>&1")
        if (result.isFailure) result.problem() else NeoResult.Ok(result.stdout)
    }

    suspend fun stats(id: String): NeoResult<String> = requireId(id) { binary ->
        val result = run("$binary stats --no-stream --format '{{json .}}' $id")
        if (result.isFailure) result.problem() else NeoResult.Ok(result.stdout)
    }

    suspend fun inspect(id: String): NeoResult<String> = requireId(id) { binary ->
        val result = run("$binary inspect $id")
        if (result.isFailure) result.problem() else NeoResult.Ok(result.stdout)
    }

    /** `docker exec` with a single command string; the caller owns quoting. */
    suspend fun exec(id: String, command: String): NeoResult<String> = requireId(id) { binary ->
        val result = run("$binary exec $id $command 2>&1")
        if (result.isFailure) result.problem() else NeoResult.Ok(result.stdout)
    }

    // =====================================================================
    // Images, volumes, networks
    // =====================================================================

    suspend fun listImages(): NeoResult<List<DockerParsers.Image>> = withEngine { binary ->
        val result = run("$binary images --format '{{json .}}'")
        if (result.isFailure) return@withEngine result.problem()
        NeoResult.Ok(DockerParsers.parseImages(result.stdout))
    }

    suspend fun listVolumes(): NeoResult<List<DockerParsers.Volume>> = withEngine { binary ->
        val result = run("$binary volume ls --format '{{json .}}'")
        if (result.isFailure) return@withEngine result.problem()
        NeoResult.Ok(DockerParsers.parseVolumes(result.stdout))
    }

    suspend fun listNetworks(): NeoResult<List<DockerParsers.Network>> = withEngine { binary ->
        val result = run("$binary network ls --format '{{json .}}'")
        if (result.isFailure) return@withEngine result.problem()
        NeoResult.Ok(DockerParsers.parseNetworks(result.stdout))
    }

    // =====================================================================
    // Plumbing
    // =====================================================================

    private data class ShellRun(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
        val isFailure: Boolean get() = exitCode != 0

        fun problem(): NeoResult.Err = NeoResult.Err(
            when (val p = DockerParsers.classifyFailure(exitCode, "$stderr\n$stdout")) {
                DockerParsers.EngineProblem.NotInstalled ->
                    NeoError.EngineUnavailable("docker")
                DockerParsers.EngineProblem.PermissionDenied ->
                    NeoError.EnginePermissionDenied
                DockerParsers.EngineProblem.DaemonDown ->
                    NeoError.EngineDaemonDown
                is DockerParsers.EngineProblem.Other ->
                    NeoError.EngineCommandFailed(p.message)
            },
        )
    }

    private suspend fun run(command: String): ShellRun = when (val r = runner.run(command)) {
        is NeoResult.Ok -> ShellRun(r.value.exitCode, r.value.stdout, r.value.stderr)
        else -> {
            val err = (r as? NeoResult.Err)?.error
            ShellRun(1, "", err?.message ?: "exec failed")
        }
    }

    private suspend fun action(command: String): NeoResult<Unit> {
        val result = run(command)
        return if (result.isSuccess) NeoResult.Ok(Unit) else result.problem()
    }

    /**
     * Run [block] with a detected engine binary, detecting once and caching the
     * answer. A cached binary that stops working is invalidated so a later call can
     * re-detect instead of failing forever.
     */
    private suspend fun <T> withEngine(block: suspend (String) -> NeoResult<T>): NeoResult<T> {
        val cached = engineBinary
        if (cached != null) {
            return block(cached)
        }
        for (candidate in CANDIDATES) {
            val probe = run("command -v $candidate")
            if (probe.isSuccess && probe.stdout.isNotBlank()) {
                engineBinary = candidate
                return block(candidate)
            }
        }
        return NeoResult.Err(NeoError.EngineUnavailable("docker"))
    }

    private suspend fun <T> runCatchingEngine(
        block: suspend (String) -> NeoResult<T>,
    ): NeoResult<T> {
        for (candidate in CANDIDATES) {
            val probe = run("command -v $candidate")
            if (probe.isSuccess && probe.stdout.isNotBlank()) {
                return block(candidate)
            }
        }
        return NeoResult.Err(NeoError.EngineUnavailable("docker"))
    }

    /** Container ids come from the UI, so reject anything that could break the command. */
    private suspend fun <T> requireId(id: String, block: suspend (String) -> NeoResult<T>): NeoResult<T> {
        if (id.isBlank() || !id.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            return NeoResult.Err(NeoError.EngineCommandFailed("invalid container id"))
        }
        return withEngine(block)
    }

    private companion object {
        val CANDIDATES = listOf("docker", "podman")
    }
}
