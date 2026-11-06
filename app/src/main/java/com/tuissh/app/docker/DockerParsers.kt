package com.tuissh.app.docker

/**
 * Parser for `docker` / `podman` CLI output.
 *
 * The original app talked to the Docker daemon over an SSH-tunnelled socket. That
 * needs a binary protocol this port cannot ship, so the CLI is used instead: every
 * list is obtained with `--format '{{json .}}'`, one JSON object per line.
 *
 * That choice matters for correctness. Parsing the default column output with regex
 * breaks whenever a container name or image contains a space, and the original app
 * had exactly that bug on `docker ps` output. JSON Lines is stable across versions
 * and locale settings, and the parser below never guesses.
 *
 * Pure functions only — no SSH, no Android, so everything here is unit-testable.
 */
object DockerParsers {

    /** One container as reported by `docker ps`. */
    data class Container(
        val id: String,
        val name: String,
        val image: String,
        val command: String,
        val state: String,
        val status: String,
        /** Seconds since start, parsed from `Status` when the engine reports it. */
        val runningForSeconds: Long?,
        val ports: List<PortMapping>,
        val createdAt: String,
        /** Only present in `docker ps -a`. */
        val exitCode: Int? = null,
    ) {
        val isRunning: Boolean get() = state.equals("running", ignoreCase = true)

        /** Short id used by the CLI and by the UI. */
        val shortId: String get() = id.take(12)

        /** `host:container/proto` pairs, or an empty list for a host-network container. */
        val portsLabel: String
            get() = ports.joinToString(", ") { p ->
                val host = if (p.hostPort != null) "${p.hostPort}->" else ""
                "$host${p.containerPort}/${p.protocol}"
            }.ifEmpty { "—" }
    }

    data class PortMapping(
        val hostIp: String?,
        val hostPort: Int?,
        val containerPort: Int,
        val protocol: String,
    )

    /** One image as reported by `docker images`. */
    data class Image(
        val id: String,
        val repository: String,
        val tag: String,
        val size: String,
        val createdSince: String,
    ) {
        val shortId: String get() = id.removePrefix("sha256:").take(12)
        val reference: String get() = if (tag.isBlank()) repository else "$repository:$tag"
        val isDangling: Boolean get() = repository == "<none>" || tag == "<none>"
    }

    /** One `docker volume ls` row. */
    data class Volume(val name: String, val driver: String, val mountPoint: String?)

    /** One `docker network ls` row. */
    data class Network(val id: String, val name: String, val driver: String, val scope: String)

    /** One entry from `docker inspect` state. */
    data class ContainerDetail(
        val id: String,
        val name: String,
        val image: String,
        val running: Boolean,
        val startedAt: String?,
        val finishedAt: String?,
        val restartCount: Int,
        val exitCode: Int,
        val health: String?,
        val ipAddress: String?,
        val mounts: List<Mount>,
    )

    data class Mount(val type: String, val source: String, val destination: String, val readOnly: Boolean)

    // =====================================================================
    // Minimal JSON reader
    // =====================================================================

    /**
     * A tiny JSON parser for the flat-ish objects Docker emits.
     *
     * Shipping a full JSON library for one use would be wasteful, and the project
     * already avoids extra dependencies. This handles objects, arrays, strings,
     * numbers, booleans and null — everything `--format '{{json .}}'` can produce.
     * It does not handle unpaired surrogates or duplicate keys, and does not need to.
     */
    private class Json(input: String) {
        private val s = input
        private var i = 0

        fun parse(): Any? {
            skipWs()
            val value = value()
            skipWs()
            return value
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, result: Any?): Any? {
            if (s.startsWith(word, i)) {
                i += word.length
                return result
            }
            throw IllegalArgumentException("bad literal at $i")
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (i < s.length) {
                skipWs()
                val key = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected : at $i")
                i++
                skipWs()
                out[key] = value()
                skipWs()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == '}') {
                    i++
                    return out
                }
                throw IllegalArgumentException("expected , or } at $i")
            }
            throw IllegalArgumentException("unterminated object")
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (i < s.length) {
                skipWs()
                out.add(value())
                skipWs()
                if (i < s.length && s[i] == ',') {
                    i++
                    continue
                }
                if (i < s.length && s[i] == ']') {
                    i++
                    return out
                }
                throw IllegalArgumentException("expected , or ] at $i")
            }
            throw IllegalArgumentException("unterminated array")
        }

        private fun str(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) throw IllegalArgumentException("bad escape")
                        when (s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw IllegalArgumentException("bad \\u")
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\${s[i]}")
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        private fun num(): Any {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            val text = s.substring(start, i)
            if (text.isEmpty()) throw IllegalArgumentException("expected value at $start")
            return text.toDoubleOrNull() ?: text.toLongOrNull() ?: text
        }
    }

    /** Parse one JSON Lines payload into maps; unparseable lines are skipped. */
    fun parseJsonLines(stdout: String): List<Map<String, Any?>> =
        stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line ->
                runCatching { Json(line).parse() as? Map<String, Any?> }.getOrNull()
            }
            .toList()

    // =====================================================================
    // Containers
    // =====================================================================

    /**
     * Parse `docker ps --format '{{json .}}'` (JSON Lines).
     *
     * @param includeStopped true when the caller used `-a`; the parser does not care,
     *        it reports whatever state the engine returned.
     */
    fun parseContainers(stdout: String): List<Container> =
        parseJsonLines(stdout).mapNotNull { row ->
            val id = row.string("ID") ?: row.string("Id") ?: return@mapNotNull null
            val statusText = row.string("Status").orEmpty()
            Container(
                id = id,
                name = row.string("Names") ?: row.string("Name").orEmpty(),
                image = row.string("Image").orEmpty(),
                command = row.string("Command").orEmpty(),
                state = row.string("State").orEmpty(),
                status = statusText,
                runningForSeconds = parseUptimeSeconds(statusText),
                ports = parsePorts(row.string("Ports")),
                createdAt = row.string("CreatedAt") ?: row.string("RunningFor").orEmpty(),
            )
        }

    /**
     * Parse the `Status` column, e.g. `Up 3 hours`, `Up 2 days (healthy)`,
     * `Exited (0) 5 minutes ago`. Returns null when the wording is not recognised —
     * a wrong uptime is worse than no uptime.
     */
    fun parseUptimeSeconds(status: String): Long? {
        val up = Regex("""^Up\s+(.+?)(?:\s*\(|$)""").find(status)?.groupValues?.get(1) ?: return null
        return parseDuration(up)
    }

    /** Parse `3 hours`, `2 days 4 minutes`, `45 seconds`. */
    private fun parseDuration(text: String): Long? {
        val parts = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)s?""")
            .findAll(text.lowercase())
            .toList()
        if (parts.isEmpty()) return null
        var seconds = 0L
        for (m in parts) {
            val n = m.groupValues[1].toLongOrNull() ?: return null
            seconds += n * when (m.groupValues[2]) {
                "second" -> 1L
                "minute" -> 60L
                "hour" -> 3_600L
                "day" -> 86_400L
                "week" -> 604_800L
                "month" -> 2_592_000L
                "year" -> 31_536_000L
                else -> return null
            }
        }
        return seconds
    }

    /**
     * Parse the `Ports` column.
     *
     * Docker emits `0.0.0.0:8080->80/tcp, :::8080->80/tcp, 9000/tcp`, and for ranges
     * both sides carry a span: `0.0.0.0:8000-8002->8000-8002/tcp`. The IPv6 form
     * duplicates the IPv4 mapping, so it is dropped rather than shown twice.
     *
     * A host range and a container range do not have to be the same length; when they
     * differ the host span is ignored rather than guessing a mapping.
     */
    fun parsePorts(text: String?): List<PortMapping> {
        if (text.isNullOrBlank()) return emptyList()
        val seen = LinkedHashSet<PortMapping>()
        val pattern = Regex(
            """^(?:(\[[^\]]*\]|[^:]*):(\d+(?:-\d+)?)->)?(\d+)(?:-(\d+))?/(tcp|udp|sctp)$""",
        )
        for (raw in text.split(",")) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val match = pattern.find(part) ?: continue
            val hostIp = match.groupValues[1].takeIf { it.isNotBlank() }
            if (hostIp == ":::") continue // duplicate of the IPv4 mapping

            val hostStart = match.groupValues[2].substringBefore('-').toIntOrNull()
            val hostEnd = match.groupValues[2].substringAfter('-', "")
                .takeIf { it.isNotEmpty() }?.toIntOrNull()
            val containerStart = match.groupValues[3].toIntOrNull() ?: continue
            val containerEnd = match.groupValues[4].toIntOrNull()
            val proto = match.groupValues[5]

            if (containerEnd != null && containerEnd > containerStart) {
                val span = containerEnd - containerStart
                val hostSpan = if (hostStart != null && hostEnd != null) hostEnd - hostStart else null
                for (offset in 0..span) {
                    // Only shift the host port when the two spans line up.
                    val host = if (hostStart != null && hostSpan == span) hostStart + offset else hostStart
                    seen.add(PortMapping(hostIp, host, containerStart + offset, proto))
                }
            } else {
                seen.add(PortMapping(hostIp, hostStart, containerStart, proto))
            }
        }
        return seen.toList()
    }

    /** Extract the exit code from `Exited (137) 2 hours ago`. */
    fun parseExitCode(status: String): Int? =
        Regex("""Exited\s+\((\d+)\)""").find(status)?.groupValues?.get(1)?.toIntOrNull()

    // =====================================================================
    // Images, volumes, networks
    // =====================================================================

    fun parseImages(stdout: String): List<Image> =
        parseJsonLines(stdout).mapNotNull { row ->
            val id = row.string("ID") ?: row.string("Id") ?: return@mapNotNull null
            Image(
                id = id,
                repository = row.string("Repository").orEmpty(),
                tag = row.string("Tag").orEmpty(),
                size = row.string("Size").orEmpty(),
                createdSince = row.string("CreatedSince") ?: row.string("CreatedAt").orEmpty(),
            )
        }

    fun parseVolumes(stdout: String): List<Volume> =
        parseJsonLines(stdout).mapNotNull { row ->
            val name = row.string("Name") ?: return@mapNotNull null
            Volume(
                name = name,
                driver = row.string("Driver").orEmpty(),
                mountPoint = row.string("Mountpoint") ?: row.string("MountPoint"),
            )
        }

    fun parseNetworks(stdout: String): List<Network> =
        parseJsonLines(stdout).mapNotNull { row ->
            val id = row.string("ID") ?: row.string("Id") ?: return@mapNotNull null
            Network(
                id = id,
                name = row.string("Name").orEmpty(),
                driver = row.string("Driver").orEmpty(),
                scope = row.string("Scope").orEmpty(),
            )
        }

    // =====================================================================
    // Errors
    // =====================================================================

    /** Why `docker` produced no output, as something the UI can say out loud. */
    sealed interface EngineProblem {
        data object NotInstalled : EngineProblem
        data object PermissionDenied : EngineProblem
        data object DaemonDown : EngineProblem
        data class Other(val message: String) : EngineProblem
    }

    /**
     * Classify a failed `docker` invocation.
     *
     * The three common failures are worth distinguishing: "not installed" needs a
     * different fix from "permission denied" (user not in the docker group) and from
     * "daemon not running".
     */
    fun classifyFailure(exitCode: Int, stderr: String): EngineProblem {
        val text = stderr.lowercase()
        return when {
            text.contains("command not found") ||
                text.contains("not recognized as an internal") ||
                text.contains("no such file or directory") && text.contains("docker") ->
                EngineProblem.NotInstalled

            text.contains("permission denied") ||
                text.contains("got permission denied while trying to connect") ->
                EngineProblem.PermissionDenied

            text.contains("cannot connect to the docker daemon") ||
                text.contains("is the docker daemon running") ->
                EngineProblem.DaemonDown

            else -> EngineProblem.Other(stderr.trim().ifEmpty { "exit $exitCode" })
        }
    }

    /** Read a string field, tolerating Docker's inconsistent casing. */
    private fun Map<String, Any?>.string(key: String): String? = when (val v = this[key]) {
        is String -> v
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> null
    }
}
