package com.tuissh.app.ai

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.docker.DockerClient
import com.tuissh.app.monitor.MonitorClient
import com.tuissh.app.monitor.MonitorParsers
import com.tuissh.app.sftp.SftpClient
import com.tuissh.app.ssh.RemoteCommandRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The tools the assistant may call, and their implementations.
 *
 * Two rules decide what is in this list:
 *
 * 1. **Read-only by default.** Every tool here only observes the host. Anything that
 *    mutates state is gated behind [ApprovalPolicy], because a model that can run
 *    arbitrary commands is a remote code execution hole with extra steps. The original
 *    app learned this the hard way and shipped a confirmation step.
 *
 * 2. **Declared once.** The JSON schema handed to the model and the code that runs the
 *    call live next to each other. Splitting them is how a schema drifts from its
 *    implementation and the model starts sending arguments nobody reads.
 */
object AiToolkit {

    /** Names are stable: the model echoes them back and the dispatcher matches on them. */
    const val TOOL_LIST_DIR = "list_directory"
    const val TOOL_READ_FILE = "read_file"
    const val TOOL_SYSTEM_INFO = "system_info"
    const val TOOL_DOCKER_PS = "docker_ps"
    const val TOOL_DOCKER_LOGS = "docker_logs"
    const val TOOL_RUN_COMMAND = "run_command"

    private val json = Json { ignoreUnknownKeys = true }

    /** Everything the model is told about a tool. */
    data class Definition(
        val spec: AiProtocol.ToolSpec,
        /** True when running this tool changes the host and needs confirmation. */
        val mutating: Boolean,
    )

    /**
     * The full set.
     *
     * `run_command` is the one mutating tool. It is declared so the model can ask for
     * it, but [AiToolRunner] refuses to execute it without approval — the declaration
     * alone never grants capability.
     */
    val definitions: List<Definition> = listOf(
        Definition(
            spec = AiProtocol.ToolSpec(
                name = TOOL_LIST_DIR,
                description = "List files and folders in a directory on the connected server. " +
                    "Use this before reading or editing a file so you know what exists.",
                parametersJson = """
                    {"type":"object","properties":{
                      "path":{"type":"string","description":"Absolute path. Use ~ for the home directory."}
                    },"required":["path"]}
                """.trimIndent(),
            ),
            mutating = false,
        ),
        Definition(
            spec = AiProtocol.ToolSpec(
                name = TOOL_READ_FILE,
                description = "Read a text file from the connected server. Output is truncated " +
                    "for large files; check the truncated flag.",
                parametersJson = """
                    {"type":"object","properties":{
                      "path":{"type":"string","description":"Absolute path of the file"},
                      "max_bytes":{"type":"integer","description":"Upper bound, default 65536"}
                    },"required":["path"]}
                """.trimIndent(),
            ),
            mutating = false,
        ),
        Definition(
            spec = AiProtocol.ToolSpec(
                name = TOOL_SYSTEM_INFO,
                description = "Read CPU, memory, disk and top-process metrics for the server.",
                parametersJson = """{"type":"object","properties":{}}""",
            ),
            mutating = false,
        ),
        Definition(
            spec = AiProtocol.ToolSpec(
                name = TOOL_DOCKER_PS,
                description = "List containers on the server, including stopped ones.",
                parametersJson = """{"type":"object","properties":{}}""",
            ),
            mutating = false,
        ),
        Definition(
            spec = AiProtocol.ToolSpec(
                name = TOOL_DOCKER_LOGS,
                description = "Read the most recent log lines of a container.",
                parametersJson = """
                    {"type":"object","properties":{
                      "id":{"type":"string","description":"Container id or name"},
                      "lines":{"type":"integer","description":"How many lines, default 100, max 500"}
                    },"required":["id"]}
                """.trimIndent(),
            ),
            mutating = false,
        ),
        Definition(
            spec = AiProtocol.ToolSpec(
                name = TOOL_RUN_COMMAND,
                description = "Run a shell command on the server. Requires user approval for " +
                    "every call. Prefer the read-only tools when they answer the question.",
                parametersJson = """
                    {"type":"object","properties":{
                      "command":{"type":"string","description":"The command to run"},
                      "reason":{"type":"string","description":"Why this command is needed"}
                    },"required":["command","reason"]}
                """.trimIndent(),
            ),
            mutating = true,
        ),
    )

    /** Schemas only, for the request body. */
    val specs: List<AiProtocol.ToolSpec> get() = definitions.map { it.spec }

    fun definitionFor(name: String): Definition? = definitions.firstOrNull { it.spec.name == name }

    /**
     * Decides whether a mutating call may proceed.
     *
     * A suspend function so the UI can show a dialog and wait. Returning false must
     * cancel the call, not silently skip it: the model needs to be told the user
     * declined so it can choose another approach.
     */
    fun interface ApprovalPolicy {
        suspend fun approve(name: String, command: String, reason: String): Boolean
    }

    /** Refuses every mutating call; the default when no UI is attached. */
    val denyAll = ApprovalPolicy { _, _, _ -> false }
}

/**
 * Runs tool calls against a live host.
 *
 * Kept separate from the toolkit's declarations so the surface the model sees and the
 * code that executes it can be reviewed independently. Every entry point returns a
 * string because that is what goes back into the conversation — a structured result
 * would have to be re-serialised anyway and errors would lose their wording.
 */
open class AiToolRunner(
    private val commands: RemoteCommandRunner,
    private val sftp: SftpClient?,
    private val approval: AiToolkit.ApprovalPolicy = AiToolkit.denyAll,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Result of one call.
     *
     * `display` is a short human summary for the chat card; `payload` is the full text
     * handed back to the model. Separating them keeps the UI readable without starving
     * the model of detail.
     */
    data class ToolResult(
        val ok: Boolean,
        val display: String,
        val payload: String,
        val durationMs: Long = 0,
    )

    /** Dispatch one call by name. Unknown names are reported, never ignored. */
    open suspend fun run(name: String, argumentsJson: String): ToolResult {
        val started = System.currentTimeMillis()
        val result = when (name) {
            AiToolkit.TOOL_LIST_DIR -> listDirectory(argumentsJson)
            AiToolkit.TOOL_READ_FILE -> readFile(argumentsJson)
            AiToolkit.TOOL_SYSTEM_INFO -> systemInfo()
            AiToolkit.TOOL_DOCKER_PS -> dockerPs()
            AiToolkit.TOOL_DOCKER_LOGS -> dockerLogs(argumentsJson)
            AiToolkit.TOOL_RUN_COMMAND -> runCommand(argumentsJson)
            else -> ToolResult(
                ok = false,
                display = "unknown tool",
                payload = "error: no tool named \"$name\". Available: " +
                    AiToolkit.definitions.joinToString(", ") { it.spec.name },
            )
        }
        return result.copy(durationMs = System.currentTimeMillis() - started)
    }

    // =====================================================================
    // Read-only tools
    // =====================================================================

    private suspend fun listDirectory(argumentsJson: String): ToolResult {
        val client = sftp
            ?: return ToolResult(false, "SFTP unavailable", "error: SFTP is not available on this session")

        val args = parse(argumentsJson)
        val path = args.string("path") ?: return missingArgument("path")

        return when (val listing = client.list(path)) {
            is NeoResult.Ok -> {
                val entries = listing.value
                val body = buildString {
                    append("path: ").append(path).append('\n')
                    append("count: ").append(entries.size).append('\n')
                    for (entry in entries) {
                        append(if (entry.isDirectory) "dir  " else "file ")
                        append(entry.name)
                        if (entry.isFile) append("  ").append(entry.size).append("B")
                        if (entry.isSymlink) append("  -> ").append(entry.linkTarget ?: "?")
                        append('\n')
                    }
                }
                ToolResult(
                    ok = true,
                    display = "${entries.size} mục trong $path",
                    payload = body,
                )
            }
            is NeoResult.Err -> ToolResult(
                ok = false,
                display = "không đọc được $path",
                payload = "error: ${listing.error.message}",
            )
        }
    }

    private suspend fun readFile(argumentsJson: String): ToolResult {
        val client = sftp
            ?: return ToolResult(false, "SFTP unavailable", "error: SFTP is not available on this session")

        val args = parse(argumentsJson)
        val path = args.string("path") ?: return missingArgument("path")
        val maxBytes = (args.int("max_bytes") ?: DEFAULT_READ_BYTES)
            .coerceIn(1, MAX_READ_BYTES)

        return when (val read = client.readFile(path, maxBytes)) {
            is NeoResult.Ok -> {
                val bytes = read.value
                val truncated = bytes.size >= maxBytes
                val text = String(bytes, Charsets.UTF_8)
                ToolResult(
                    ok = true,
                    display = "$path · ${bytes.size}B" + if (truncated) " (cắt bớt)" else "",
                    payload = buildString {
                        if (truncated) {
                            append("note: truncated at ").append(maxBytes)
                                .append(" bytes; the file may be longer.\n")
                        }
                        append(text)
                    },
                )
            }
            is NeoResult.Err -> ToolResult(
                ok = false,
                display = "không đọc được $path",
                payload = "error: ${read.error.message}",
            )
        }
    }

    private suspend fun systemInfo(): ToolResult {
        val monitor = MonitorClient(commands)
        return when (val sampled = monitor.sampleAfterWarmUp()) {
            is NeoResult.Ok -> {
                val s = sampled.value
                ToolResult(
                    ok = true,
                    display = "CPU " + (s.cpu?.let { "${it.userPercent.toInt()}%" } ?: "?") +
                        " · RAM " + (s.memory?.let {
                            "${((it.usedKb * 100) / it.totalKb.coerceAtLeast(1))}%"
                        } ?: "?"),
                    payload = buildString {
                        append("host: ").append(s.hostLabel ?: "unknown").append('\n')
                        s.cpu?.let {
                            append("cpu: user ").append(fmt(it.userPercent))
                                .append("% system ").append(fmt(it.systemPercent))
                                .append("% iowait ").append(fmt(it.iowaitPercent))
                                .append("%\n")
                            append("load: ").append(fmt(it.load1)).append(' ')
                                .append(fmt(it.load5)).append(' ')
                                .append(fmt(it.load15)).append('\n')
                        } ?: append("cpu: unavailable\n")
                        s.memory?.let {
                            append("memory: ").append(it.usedKb).append("kB used of ")
                                .append(it.totalKb).append("kB\n")
                        }
                        s.uptime?.let { append("uptime: ").append(it.seconds).append("s\n") }
                        if (s.disks.isNotEmpty()) {
                            append("disks:\n")
                            for (d in s.disks) {
                                append("  ").append(d.mount).append(' ')
                                    .append(fmt(d.usedPercent)).append("% of ")
                                    .append(MonitorParsers.bytesForTool(d.totalBytes)).append('\n')
                            }
                        }
                        if (s.processes.isNotEmpty()) {
                            append("top processes:\n")
                            for (p in s.processes.take(8)) {
                                append("  ").append(p.pid).append(' ').append(p.command)
                                    .append(" cpu=").append(fmt(p.cpuPercent))
                                    .append("% mem=").append(fmt(p.memPercent)).append("%\n")
                            }
                        }
                    },
                )
            }
            is NeoResult.Err -> ToolResult(
                ok = false,
                display = "không đọc được số liệu",
                payload = "error: ${sampled.error.message}",
            )
        }
    }

    private suspend fun dockerPs(): ToolResult {
        val docker = DockerClient(commands)
        return when (val listed = docker.listContainers(includeStopped = true)) {
            is NeoResult.Ok -> {
                val containers = listed.value
                ToolResult(
                    ok = true,
                    display = "${containers.size} container",
                    payload = buildString {
                        append("count: ").append(containers.size).append('\n')
                        for (c in containers) {
                            append(c.shortId).append(' ').append(c.name)
                                .append(" [").append(c.state).append("] ")
                                .append(c.image)
                                .append(" ports=").append(c.portsLabel)
                                .append('\n')
                        }
                    },
                )
            }
            is NeoResult.Err -> ToolResult(
                ok = false,
                display = "không đọc được container",
                payload = "error: ${listed.error.message}",
            )
        }
    }

    private suspend fun dockerLogs(argumentsJson: String): ToolResult {
        val args = parse(argumentsJson)
        val id = args.string("id") ?: return missingArgument("id")
        val lines = (args.int("lines") ?: DEFAULT_LOG_LINES).coerceIn(1, MAX_LOG_LINES)

        val docker = DockerClient(commands)
        return when (val logs = docker.logs(id, lines)) {
            is NeoResult.Ok -> ToolResult(
                ok = true,
                display = "log $id · $lines dòng",
                payload = logs.value.ifBlank { "(no output)" },
            )
            is NeoResult.Err -> ToolResult(
                ok = false,
                display = "không đọc được log",
                payload = "error: ${logs.error.message}",
            )
        }
    }

    // =====================================================================
    // Mutating tool
    // =====================================================================

    /**
     * Run a shell command, but only after the user approves.
     *
     * The approval happens **before** the command reaches the host, and a refusal is
     * returned to the model as a normal tool result so it can pick another route
     * instead of retrying blindly.
     */
    private suspend fun runCommand(argumentsJson: String): ToolResult {
        val args = parse(argumentsJson)
        val command = args.string("command") ?: return missingArgument("command")
        val reason = args.string("reason") ?: "(no reason given)"

        if (!approval.approve(AiToolkit.TOOL_RUN_COMMAND, command, reason)) {
            return ToolResult(
                ok = false,
                display = "người dùng từ chối",
                payload = "error: the user declined to run this command. " +
                    "Do not retry it; ask what they would prefer instead.",
            )
        }

        return when (val executed = commands.run(command, timeoutMs = COMMAND_TIMEOUT_MS)) {
            is NeoResult.Ok -> {
                val r = executed.value
                ToolResult(
                    ok = r.exitCode == 0,
                    display = "exit ${r.exitCode}",
                    payload = buildString {
                        append("exit_code: ").append(r.exitCode).append('\n')
                        if (r.stdout.isNotBlank()) {
                            append("stdout:\n").append(r.stdout.take(MAX_OUTPUT_CHARS))
                            if (r.stdout.length > MAX_OUTPUT_CHARS) append("\n(truncated)")
                        }
                        if (r.stderr.isNotBlank()) {
                            append("\nstderr:\n").append(r.stderr.take(MAX_OUTPUT_CHARS))
                        }
                    },
                )
            }
            is NeoResult.Err -> ToolResult(
                ok = false,
                display = "lệnh thất bại",
                payload = "error: ${executed.error.message}",
            )
        }
    }

    // =====================================================================
    // Parsing helpers
    // =====================================================================

    private fun parse(argumentsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }

    private fun missingArgument(name: String) = ToolResult(
        ok = false,
        display = "thiếu tham số",
        payload = "error: missing required argument \"$name\"",
    )

    private fun fmt(value: Double): String = String.format("%.1f", value)

    private companion object {
        const val DEFAULT_READ_BYTES = 65_536
        const val MAX_READ_BYTES = 512 * 1_024
        const val DEFAULT_LOG_LINES = 100
        const val MAX_LOG_LINES = 500
        const val COMMAND_TIMEOUT_MS = 60_000
        const val MAX_OUTPUT_CHARS = 8_000
    }
}

/** Serialise a tool result back into the conversation as a JSON string. */
fun toolResultPayload(result: AiToolRunner.ToolResult): String = buildJsonObject {
    put("ok", result.ok)
    put("output", result.payload)
}.toString()

/** Format a duration for the chat card, e.g. `131.2s` like the original app showed. */
fun formatToolDuration(ms: Long): String = String.format("%.1fs", ms / 1000.0)

/** Build the arguments object the model expects when replaying an assistant tool call. */
fun toolCallMessage(calls: List<AiProtocol.ToolCallRequest>): String = buildString {
    append("""{"tool_calls":[""")
    calls.forEachIndexed { index, call ->
        if (index > 0) append(',')
        append("""{"id":""").append(quote(call.id))
        append(""","type":"function","function":{"name":""").append(quote(call.name))
        append(""","arguments":""").append(quote(call.argumentsJson)).append("}}")
    }
    append("]}")
}

/**
 * Minimal JSON string quoting.
 *
 * Used instead of a serialiser because the argument payload is already a JSON string
 * produced by the model and must be embedded verbatim; re-parsing it would reformat it
 * and lose the exact bytes the assistant emitted, which some providers reject.
 */
private fun quote(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}
