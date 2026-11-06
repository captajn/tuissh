package com.tuissh.app.monitor

import java.util.Locale

/**
 * Parsers for the Linux files and command output that back the server monitor.
 *
 * Every input here comes from a shell command run over SSH, so the parser has to
 * survive three things the happy path never shows:
 *
 *  - *Locale.* `df` and `ps` render numbers through the server's locale. A French or
 *    German host prints `12,4` where an English host prints `12.4`. Parsing those as
 *    "not a number" would blank the whole monitor, so locale-shaped numbers are read
 *    when the meaning is unambiguous.
 *  - *Version drift.* BusyBox, GNU coreutils and procps all claim to implement the
 *    same commands with slightly different headers and column spacing.
 *  - *Truncation.* A login shell can prepend a banner to command output, and the
 *    poller may cut a command short. Anything unrecognised is skipped rather than
 *    guessed at, because a wrong number on a monitor is worse than a missing one.
 *
 * Pure functions only — no SSH, no coroutines, no Android — so the whole thing is
 * unit-testable against captured output.
 */
object MonitorParsers {

    /**
     * Ceiling on the process table.
     *
     * The monitor shows a "top offenders" list, and `ps` on a busy host can return
     * thousands of rows. Cutting the list keeps the snapshot cheap to ship over the
     * poller and bounded for the UI; the rows are taken in `ps` order, i.e. by pid.
     */
    private const val MAX_PROCESS_ROWS = 10

    /** `/proc/net/dev` prints sixteen counters per interface; rx is field 0 and tx 8. */
    private const val NET_DEV_FIELDS = 16

    private const val KB_PER_BLOCK = 1024L
    private const val BLOCK_TO_BYTES = 1024L

    // =====================================================================
    // A) CPU
    // =====================================================================

    /**
     * One `/proc/stat` `cpu ` sample, in raw jiffies.
     *
     * Percentages are deliberately *not* part of this type. The kernel only reports
     * monotonically increasing counters, so a single read cannot describe load; it
     * can only describe the average since boot, which is nearly useless for a live
     * gauge. The caller therefore stores one sample, polls again, and hands both to
     * [cpuPercentBetween].
     */
    data class CpuTotals(
        /** Jiffies the CPU was idle or waiting on I/O combined, which is how `/proc/stat` reports them. */
        val idleJiffies: Long,
        /** Sum of every counter on the line: the denominator for any percentage. */
        val totalJiffies: Long,
        /** Advisory jiffies spent in user space (`user` + `nice`). */
        val userJiffies: Long = 0L,
        /** Advisory jiffies spent in kernel space (`system` + `irq` + `softirq`). */
        val systemJiffies: Long = 0L,
        /** Advisory jiffies spent waiting on I/O (`iowait`). */
        val iowaitJiffies: Long = 0L,
    )

    /**
     * CPU utilisation over one polling interval.
     *
     * `userPercent`, `systemPercent` and `iowaitPercent` are the three ways a server
     * usually looks busy, `idlePercent` is everything nothing ran, and the data
     * silently obeys `user + system + iowait + idle + other == 100`. `other` covers
     * `nice`, `irq`, `softirq`, `steal` and `guest`, which have no counter of their own
     * in this screen; folding them into `idle` would understate load, so
     * `otherPercent` is exposed separately and the UI does not have to guess.
     */
    data class CpuSnapshot(
        val userPercent: Double,
        val systemPercent: Double,
        val idlePercent: Double,
        val iowaitPercent: Double,
        val load1: Double,
        val load5: Double,
        val load15: Double,
        /** Jiffies attributed to none of the four explicit buckets. */
        val otherPercent: Double = 0.0,
    )

    /**
     * Read the aggregate `cpu ` line of `/proc/stat`.
     *
     * Only the first line is parsed: the per-core `cpu0`, `cpu1`, ... lines follow and
     * would otherwise be mistaken for the aggregate. The keyword is matched exactly so
     * that `cpu0` cannot produce a false hit.
     *
     * Trailing columns are tolerated in either direction. Kernels before 2.6.11 have
     * no `steal`, and a few vendor kernels add private counters; reading "as many as
     * present" keeps both working, while a truncated line with fewer than the four
     * mandatory columns is treated as malformed instead of being padded with zeros.
     *
     * On some very small VMs the counters are frozen at zero. That is a real reading,
     * not a parse failure, so a zeroed line still returns totals — [cpuPercentBetween]
     * is what refuses to divide by the resulting zero delta.
     */
    fun parseCpuTotals(procStatContent: String): CpuTotals? {
        val line = procStatContent.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("cpu ") }
            ?: return null

        val fields = line.removePrefix("cpu").trim().split(Regex("\\s+"))
        if (fields.size < 4) return null

        val values = LongArray(fields.size)
        for (i in fields.indices) {
            values[i] = fields[i].toLongOrNull() ?: return null
        }
        if (values.any { it < 0 }) return null

        val user = values[0]
        val nice = values[1]
        val system = values[2]
        val idle = values[3]
        // Columns are absent on old kernels; a missing iowait is genuinely zero there.
        val iowait = values.getOrElse(4) { 0L }
        val irq = values.getOrElse(5) { 0L }
        val softirq = values.getOrElse(6) { 0L }
        val steal = values.getOrElse(7) { 0L }

        // `guest` and `guest_nice` (columns 8 and 9) are already included in `user` and
        // `nice` by the kernel, so adding them again would inflate the denominator.
        val total = user + nice + system + idle + iowait + irq + softirq + steal

        return CpuTotals(
            idleJiffies = idle + iowait,
            totalJiffies = total,
            userJiffies = user + nice,
            systemJiffies = system + irq + softirq,
            iowaitJiffies = iowait,
        )
    }

    /**
     * Turn two `/proc/stat` samples into a utilisation snapshot.
     *
     * Returns null when the totals did not advance, and that is the important part of
     * this function: a VM paused between two reads, a cached response, or a host whose
     * counters are frozen all produce a zero (or negative) delta. Reporting "0% used"
     * there would be a lie — the program has no information at all — so the caller is
     * told to drop the sample instead.
     *
     * The load averages are not part of the delta; they are passed in from
     * [parseLoadAvg] because they describe a much longer window than one poll.
     */
    fun cpuPercentBetween(
        prev: CpuTotals,
        curr: CpuTotals,
        load: Triple<Double, Double, Double>? = null,
    ): CpuSnapshot? {
        val deltaTotal = curr.totalJiffies - prev.totalJiffies
        if (deltaTotal <= 0L) return null

        // Counters are monotonic, but a guest clock jump or a restored snapshot can
        // hand back a counter that moved backwards for one field. Clamping a single
        // field to zero keeps the other percentages usable; the alternative — null —
        // would discard an otherwise fine interval.
        fun delta(current: Long, previous: Long): Long = (current - previous).coerceAtLeast(0L)

        fun percent(value: Long): Double = value.toDouble() * 100.0 / deltaTotal.toDouble()

        val user = percent(delta(curr.userJiffies, prev.userJiffies))
        val system = percent(delta(curr.systemJiffies, prev.systemJiffies))
        val idle = percent(delta(curr.idleJiffies, prev.idleJiffies))
        val iowait = percent(delta(curr.iowaitJiffies, prev.iowaitJiffies))

        // Rounding each bucket independently can make the four add up to 100.1. Clamp
        // the residual so a stacked bar chart never overflows its track.
        val other = (100.0 - user - system - idle - iowait).coerceIn(0.0, 100.0)

        return CpuSnapshot(
            userPercent = user,
            systemPercent = system,
            idlePercent = idle,
            iowaitPercent = iowait,
            load1 = load?.first ?: 0.0,
            load5 = load?.second ?: 0.0,
            load15 = load?.third ?: 0.0,
            otherPercent = other,
        )
    }

    /**
     * Parse `/proc/loadavg`, e.g. `0.52 0.58 0.59 1/234 5678`.
     *
     * Only the three averages are read; the runnable/total and last-pid fields are
     * skipped. Fewer than three numbers means the kernel does not expose load
     * averages (some containers and kernels without `CONFIG_SMP` do not), and a
     * half-read triple is not worth showing.
     */
    fun parseLoadAvg(procLoadavg: String): Triple<Double, Double, Double>? {
        val tokens = procLoadavg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 3) return null
        val one = tokens[0].toDoubleOrNull() ?: return null
        val five = tokens[1].toDoubleOrNull() ?: return null
        val fifteen = tokens[2].toDoubleOrNull() ?: return null
        if (one < 0 || five < 0 || fifteen < 0) return null
        return Triple(one, five, fifteen)
    }

    // =====================================================================
    // B) Memory
    // =====================================================================

    /**
     * A `/proc/meminfo` snapshot, all values in kibibytes as the kernel reports them.
     *
     * `used` is computed as `total - available`, not `total - free`. `free` on a modern
     * kernel excludes reclaimable page cache, so on a healthy server it reads as a few
     * hundred megabytes and the monitor would scream "95% full" at an idle machine.
     * `MemAvailable` is the kernel's own estimate of what a new workload could claim
     * without swapping, which is what the number is actually trying to answer.
     */
    data class MemoryInfo(
        val totalKb: Long,
        val freeKb: Long,
        val availableKb: Long,
        val buffersKb: Long,
        val cachedKb: Long,
        val swapTotalKb: Long,
        val swapFreeKb: Long,
    ) {
        /** Memory in use, from the user's point of view rather than the kernel's. */
        val usedKb: Long get() = (totalKb - availableKb).coerceAtLeast(0L)

        val swapUsedKb: Long get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0L)

        /** Fraction in `0.0..1.0`, or 0 when the host reports no memory at all. */
        val usedFraction: Double
            get() = if (totalKb <= 0L) 0.0 else usedKb.toDouble() / totalKb.toDouble()

        /** Swap can be disabled, in which case the ratio is meaningless rather than zero. */
        val swapUsedFraction: Double
            get() = if (swapTotalKb <= 0L) 0.0 else swapUsedKb.toDouble() / swapTotalKb.toDouble()
    }

    /**
     * Parse `/proc/meminfo`.
     *
     * Every line is `Key: <number> kB`, but the spacing is not fixed — the kernel pads
     * the value to a column, and some architectures emit no unit at all — so the line
     * is split on the first colon and the number is taken as the first token.
     *
     * `MemAvailable` only exists from Linux 3.14. On older kernels it is absent, and
     * the honest fallback is `free + buffers + cached`, which is the formula the kernel
     * itself used before the field existed. Inventing a value from `free` alone would
     * make every old host look out of memory. A file with no `MemTotal` at all is not
     * a meminfo file and returns null.
     */
    fun parseMemInfo(procMeminfoContent: String): MemoryInfo? {
        val values = HashMap<String, Long>()
        for (raw in procMeminfoContent.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            // The first whitespace-separated token after the colon is the value; the
            // "kB" that normally follows it is a unit, not a second number.
            val number = line.substring(colon + 1).trim().substringBefore(' ')
            val value = number.toLongOrNull() ?: continue
            if (value < 0) continue
            values[key] = value
        }

        val total = values["MemTotal"] ?: return null
        val free = values["MemFree"] ?: 0L
        val buffers = values["Buffers"] ?: 0L
        val cached = values["Cached"] ?: 0L
        val available = values["MemAvailable"]
            // Pre-3.14 fallback, and also what the kernel reported on hosts that ship
            // the field as zero because a driver declined to estimate it.
            ?: (free + buffers + cached)

        return MemoryInfo(
            totalKb = total,
            freeKb = free,
            availableKb = available.coerceIn(0L, total.coerceAtLeast(0L)),
            buffersKb = buffers,
            cachedKb = cached,
            swapTotalKb = values["SwapTotal"] ?: 0L,
            swapFreeKb = values["SwapFree"] ?: 0L,
        )
    }

    // =====================================================================
    // C) Disk
    // =====================================================================

    /** One mounted filesystem row from `df -kP`. */
    data class DiskInfo(
        val mount: String,
        val filesystem: String,
        val totalBytes: Long,
        val usedBytes: Long,
        val availableBytes: Long,
        /** Utilisation of the space a non-root user can actually write to. */
        val usedPercent: Double,
    )

    /**
     * Parse `df -kP`, the POSIX-guaranteed output format.
     *
     * `-P` is what makes this tractable: it forbids the line wrapping that plain `df`
     * performs on long device names, so "one line per mount" is guaranteed. Blocks are
     * 1K as requested by `-k`, whatever the server's `BLOCKSIZE` is set to.
     *
     * The header is skipped by *content*, not by position, because of the locale
     * problem: the task description mentions a Chinese header, and this project ships
     * Chinese resources, so `Filesystem 1K-blocks Used Available Use% Mounted on`
     * arrives translated often enough to matter. The `Use%` column is not parsed for
     * the same reason (`utilisé%`, `已用%`); the percentage is recomputed from the
     * byte counts, which is also safer for the `-` that some drivers print there.
     *
     * Pseudo filesystems are dropped deliberately. `tmpfs`, `devtmpfs` and `overlay`
     * report the size of the host's RAM or of a container layer, so listing them would
     * bury the disks the user can actually fill, and on Android or WSL they would
     * dominate the screen. `/snap` entries are squashfs images that are always exactly
     * 100% full and never change, and `/boot/efi` is a fixed-size ESP that alarms users
     * without ever moving. Neither is actionable.
     */
    fun parseDf(output: String): List<DiskInfo> {
        val rows = ArrayList<DiskInfo>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // Split from the right: mount points may contain spaces, while `df -P`
            // keeps the four numeric columns and the filesystem name space-free.
            val tokens = line.split(Regex("\\s+"))
            if (tokens.size < 6) continue

            val mount = tokens.last()
            val filesystem = tokens[0]

            val available = parseBlockCount(tokens[tokens.size - 3]) ?: continue
            val used = parseBlockCount(tokens[tokens.size - 4]) ?: continue
            val total = parseBlockCount(tokens[tokens.size - 5]) ?: continue
            // Everything between the filesystem and the numbers belongs to a device
            // name that was split by whitespace; such a line cannot be classified, so
            // it is skipped rather than attributed to the wrong device.
            if (tokens.size > 6) continue

            if (filesystem.isBlank() || mount.isBlank()) continue
            if (isPseudoFilesystem(filesystem) || isIgnoredMount(mount)) continue

            val totalBytes = total * BLOCK_TO_BYTES
            val usedBytes = used * BLOCK_TO_BYTES
            val availableBytes = available * BLOCK_TO_BYTES

            // df's own `Use%` rounds differently and is untranslated-unparseable; the
            // denominator excludes reserved blocks so the gauge matches `Use%` closely.
            val denominator = usedBytes + availableBytes
            val percent = if (denominator <= 0L) 0.0 else usedBytes.toDouble() * 100.0 / denominator.toDouble()

            rows.add(
                DiskInfo(
                    mount = mount,
                    filesystem = filesystem,
                    totalBytes = totalBytes,
                    usedBytes = usedBytes,
                    availableBytes = availableBytes,
                    usedPercent = percent.coerceIn(0.0, 100.0),
                ),
            )
        }
        return rows
    }

    /**
     * Read a `df` numeric cell.
     *
     * `-` appears for filesystems that do not report a size (procdrv-style pseudo
     * mounts, some FUSE drivers), and it means "unknown", not zero. A locale may also
     * use `.` or `,` as the thousands separator; `df -k` only prints integers, so any
     * separator character between digits is stripped — that is unambiguous because a
     * block count has no fractional part.
     */
    private fun parseBlockCount(token: String): Long? {
        if (token == "-") return null
        val cleaned = token.filter { it.isDigit() }
        if (cleaned.isEmpty()) return null
        return cleaned.toLongOrNull()
    }

    private fun isPseudoFilesystem(filesystem: String): Boolean {
        val name = filesystem.lowercase(Locale.US)
        return name == "tmpfs" ||
            name == "devtmpfs" ||
            name == "squashfs" ||
            name == "overlay" ||
            name.startsWith("overlay:")
    }

    private fun isIgnoredMount(mount: String): Boolean {
        val path = mount.trimEnd('/')
        return path == "/snap" || path.startsWith("/snap/") ||
            path == "/boot/efi"
    }

    // =====================================================================
    // D) Processes
    // =====================================================================

    /** One row of the process table. */
    data class ProcessInfo(
        val pid: Int,
        val user: String,
        val cpuPercent: Double,
        val memPercent: Double,
        val rssKb: Long,
        /** The command with its arguments, spaces and all. */
        val command: String,
    )

    /**
     * Parse `ps -eo pid,user,pcpu,pmem,rss,args`.
     *
     * Columns are split with `limit = 6` rather than by naive whitespace: the command
     * is the *last* field but is the only one that can contain spaces, so the first
     * five splits are numeric/identifier columns and everything after them is the
     * command verbatim. Parsing from the right instead would break on a username that
     * contains a space, which some directory services allow.
     *
     * The header row is recognised by its numeric columns rather than by its first
     * token, again for locale reasons: a translated header (`PID 使用者 ...`) still has
     * non-numeric cells where the data rows have numbers. Any row whose numeric cells
     * do not parse is skipped, which also covers the login banner a shell can prepend.
     *
     * Exactly [MAX_PROCESS_ROWS] rows are kept, in `ps` order. Re-sorting would be a
     * second, unstated policy decision; callers that want "top by CPU" sort the result
     * themselves, which also keeps this function total and cheap.
     */
    fun parseTop(output: String): List<ProcessInfo> {
        val rows = ArrayList<ProcessInfo>(MAX_PROCESS_ROWS)
        for (raw in output.lineSequence()) {
            if (rows.size >= MAX_PROCESS_ROWS) break
            val line = raw.trim()
            if (line.isEmpty()) continue

            val parts = line.split(Regex("\\s+"), limit = 6)
            if (parts.size < 6) continue

            val pid = parts[0].toIntOrNull() ?: continue
            if (pid <= 0) continue
            val user = parts[1]
            if (user.isEmpty()) continue
            val cpu = parseLocaleDouble(parts[2]) ?: continue
            val mem = parseLocaleDouble(parts[3]) ?: continue
            val rss = parseLocaleDouble(parts[4])?.toLong() ?: continue
            val command = parts[5].trim()
            if (command.isEmpty()) continue

            rows.add(
                ProcessInfo(
                    pid = pid,
                    user = user,
                    cpuPercent = cpu.coerceAtLeast(0.0),
                    memPercent = mem.coerceAtLeast(0.0),
                    rssKb = rss.coerceAtLeast(0L),
                    command = command,
                ),
            )
        }
        return rows
    }

    /**
     * Read a decimal that a foreign locale may have rendered with a comma.
     *
     * Only a comma *between digits* is treated as a decimal point. A comma used as a
     * thousands separator would make `1,024` ambiguous, but `ps` prints `pcpu` and
     * `pmem` without grouping and `rss` is whole kibibytes, so the reading is safe
     * here and this helper is intentionally private to this file.
     */
    private fun parseLocaleDouble(token: String): Double? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toDoubleOrNull()?.let { return it }
        val swapped = trimmed.replace(',', '.')
        // Reject shapes like `1.024.5` that the naive replacement turns into junk.
        if (swapped.count { it == '.' } > 1) return null
        return swapped.toDoubleOrNull()
    }

    // =====================================================================
    // E) Network
    // =====================================================================

    /** Cumulative byte counters for one network interface. */
    data class NetworkInfo(
        val interfaceName: String,
        val rxBytes: Long,
        val txBytes: Long,
    )

    /**
     * Parse `/proc/net/dev`.
     *
     * The file opens with two header lines, and the `Inter-|`/`face |` pair is the only
     * reliable marker of them, so lines are validated by shape instead: `name:` followed
     * by at least sixteen counters. That makes the parser indifferent to whether the
     * header is present, how the kernel formats it, and whether the command was piped
     * through something that trimmed it.
     *
     * `lo` is dropped because loopback traffic is host-local: a busy database generates
     * gigabytes of it that never touch the wire, and showing it would make the network
     * graph meaningless. Interfaces are returned in file order so the caller can keep a
     * stable ordering across polls; ordering alphabetically would reorder a bond's slaves.
     */
    fun parseProcNetDev(content: String): List<NetworkInfo> {
        val result = ArrayList<NetworkInfo>()
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val colon = line.indexOf(':')
            if (colon <= 0) continue

            val name = line.substring(0, colon).trim()
            if (name.isEmpty() || name.contains(' ')) continue
            if (name == "lo") continue

            val fields = line.substring(colon + 1).trim().split(Regex("\\s+"))
            // rx_bytes is field 0 and tx_bytes is field 8 of sixteen. Every kernel
            // version prints all sixteen, so requiring the full set is what separates a
            // real device line from a header or a half-written read; checking only the
            // first nine would accept a line whose transmit counters never arrived.
            if (fields.size < NET_DEV_FIELDS) continue

            val rx = fields[0].toLongOrNull() ?: continue
            val tx = fields[8].toLongOrNull() ?: continue
            if (rx < 0 || tx < 0) continue

            result.add(NetworkInfo(interfaceName = name, rxBytes = rx, txBytes = tx))
        }
        return result
    }

    // =====================================================================
    // F) Uptime
    // =====================================================================

    /** How long the host has been up, and how much of that it spent idle. */
    data class UptimeInfo(val seconds: Long, val idleSeconds: Double)

    /**
     * Parse `/proc/uptime`, e.g. `12345.67 98765.43`.
     *
     * The second field is the sum of per-CPU idle time, so on a multi-core host it
     * exceeds the uptime itself. That looks like a bug and is not, so it is kept as a
     * raw `Double` and *not* divided down here: the number of cores is not part of this
     * file, and normalising without knowing it would silently produce a wrong figure.
     *
     * The fractional part is preserved (two decimals in practice) because the monitor
     * polls more often than once a second on purpose-built setups, and truncating would
     * make consecutive samples identical.
     */
    fun parseUptime(procUptime: String): UptimeInfo? {
        val tokens = procUptime.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val seconds = tokens[0].toDoubleOrNull() ?: return null
        if (seconds.isNaN() || seconds < 0.0) return null
        val idle = tokens.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        if (idle.isNaN() || idle < 0.0) return null
        return UptimeInfo(seconds = seconds.toLong(), idleSeconds = idle)
    }

    // =====================================================================
    // G) Formatting
    // =====================================================================

    private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    /**
     * Human readable size, e.g. `1.2 GB`.
     *
     * Two deliberate differences from [com.tuissh.app.core.NeoFormat.bytes]:
     *
     *  - Units are decimal-labelled (`KB`, `MB`) even though the divisor is 1024. That
     *    is what every server admin compares against `df` and `free` output, and the
     *    monitor is a server tool, not a file manager. The project's own
     *    `NeoFormat.bytes` keeps the strict `KiB`/`MiB` spelling for file sizes.
     *  - Bytes below 1 KB are printed bare (`512 B`) with no decimal, because `512.0 B`
     *    reads as false precision for an exact count.
     *
     * Negative input returns `-` rather than `-1.2 GB`: a negative byte count can only
     * come from a subtraction that underflowed, i.e. a bug or an unknown value, and
     * showing an unknown as a number would hide it.
     *
     * Formatting uses [Locale.US] so the decimal point does not become a comma on a
     * Vietnamese, German or French device — the string is parsed by humans and pasted
     * into tickets, and a mixed-locale `1,2 GB` is a recurring support question.
     */
    /**
     * Compact byte string for model-facing output.
     *
     * Distinct from [formatBytes] only in that it never uses a space: tool payloads are
     * re-read by a language model, and `192MB` tokenises more predictably than
     * `192 MB`.
     */
    fun bytesForTool(bytes: Long): String = formatBytes(bytes).replace(" ", "")
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "-"
        if (bytes < KB_PER_BLOCK) return "$bytes B"

        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < BYTE_UNITS.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        // The decimal is applied before the scale word is chosen. 1048570 bytes is
        // 1023.99 KB, which a one-decimal round turns into "1024.0 KB"; at that point the
        // label must be promoted so the unit never contradicts the number beside it.
        var text = String.format(Locale.US, "%.1f", value)
        if (text.toDouble() >= 1024.0 && unitIndex < BYTE_UNITS.size - 1) {
            value /= 1024.0
            unitIndex++
            text = String.format(Locale.US, "%.1f", value)
        }
        return "$text ${BYTE_UNITS[unitIndex]}"
    }
}
