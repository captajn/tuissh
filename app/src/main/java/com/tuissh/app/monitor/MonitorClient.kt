package com.tuissh.app.monitor

import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.RemoteCommandRunner
import kotlinx.coroutines.delay

/**
 * Collects host metrics over an existing SSH session.
 *
 * The parsers in [MonitorParsers] are pure, so this class only has to run commands and
 * remember state between samples. Two design points matter:
 *
 * 1. CPU percentage needs **two** `/proc/stat` samples. `/proc/stat` reports
 *    monotonic jiffy counters since boot, not a percentage, so a single read can only
 *    tell you the average since the machine started. The first [sampleCpu] call
 *    therefore returns null and only establishes a baseline.
 *
 * 2. Every command is bundled into one `exec` round trip where possible. Three
 *    separate `cat` calls would triple the latency on a high-RTT link for data that is
 *    a few kilobytes.
 */
class MonitorClient(private val runner: RemoteCommandRunner) {

    /** Baseline for the next CPU percentage; null until the first sample. */
    private var previousCpu: MonitorParsers.CpuTotals? = null

    /** Everything one poll produced. Fields are null when that command failed. */
    data class Snapshot(
        val cpu: MonitorParsers.CpuSnapshot?,
        val memory: MonitorParsers.MemoryInfo?,
        val disks: List<MonitorParsers.DiskInfo>,
        val processes: List<MonitorParsers.ProcessInfo>,
        val networks: List<MonitorParsers.NetworkInfo>,
        val uptime: MonitorParsers.UptimeInfo?,
        /** Kernel and distribution identity, shown as the header line. */
        val hostLabel: String?,
    )

    // =====================================================================
    // Whole-host sample
    // =====================================================================

    /**
     * Read every metric in one round trip.
     *
     * Missing files are tolerated: `/proc/net/dev` is absent in some containers and
     * the CPU counters need two reads, but a host that answers `meminfo` and `df` is
     * still worth showing.
     */
    suspend fun sample(): NeoResult<Snapshot> {
        val command = buildString {
            append("cat /proc/stat 2>/dev/null; ")
            append("echo '---'; ")
            append("cat /proc/meminfo 2>/dev/null; ")
            append("echo '---'; ")
            append("cat /proc/loadavg 2>/dev/null; ")
            append("echo '---'; ")
            append("cat /proc/uptime 2>/dev/null; ")
            append("echo '---'; ")
            append("cat /proc/net/dev 2>/dev/null; ")
            append("echo '---'; ")
            append("df -kP 2>/dev/null; ")
            append("echo '---'; ")
            append("ps -eo pid,user,pcpu,pmem,rss,args --sort=-pcpu 2>/dev/null | head -n 16; ")
            append("echo '---'; ")
            append("uname -snr 2>/dev/null")
        }

        val result = runner.run(command, timeoutMs = COMMAND_TIMEOUT_MS)
        if (result is NeoResult.Err) return result

        val out = result.getOrThrow()
        // Non-zero exit is normal here: `2>/dev/null` hides absent files but the
        // shell still reports the last command's status. Only an empty payload is
        // treated as a failure.
        if (out.stdout.isBlank()) {
            return NeoResult.Err(NeoError.Io("the host returned no metrics"))
        }

        val sections = out.stdout.split(SECTION_SEPARATOR)
        fun section(index: Int): String = sections.getOrElse(index) { "" }

        val cpuTotals = MonitorParsers.parseCpuTotals(section(0))
        val cpu = cpuTotals?.let { totals ->
            val previous = previousCpu
            previousCpu = totals
            // First poll has no baseline; the caller shows "measuring" instead of a
            // fabricated zero.
            previous?.let { MonitorParsers.cpuPercentBetween(it, totals) }
        }

        val loads = MonitorParsers.parseLoadAvg(section(2))

        return NeoResult.Ok(
            Snapshot(
                cpu = cpu ?: loads?.let {
                    // Load average is available immediately even when CPU needs a
                    // second sample, so it is carried on a zeroed snapshot rather than
                    // withheld.
                    MonitorParsers.CpuSnapshot(
                        userPercent = 0.0,
                        systemPercent = 0.0,
                        idlePercent = 0.0,
                        iowaitPercent = 0.0,
                        load1 = it.first,
                        load5 = it.second,
                        load15 = it.third,
                    )
                },
                memory = MonitorParsers.parseMemInfo(section(1)),
                disks = MonitorParsers.parseDf(section(5)),
                processes = MonitorParsers.parseTop(section(6)),
                networks = MonitorParsers.parseProcNetDev(section(4)),
                uptime = MonitorParsers.parseUptime(section(3)),
                hostLabel = section(7).trim().ifBlank { null },
            ),
        )
    }

    /**
     * Drop the CPU baseline.
     *
     * Called when a view resumes: jiffy counters keep advancing while nothing is
     * watching, so a sample taken hours ago would make the next percentage an average
     * over that whole gap instead of over the poll interval.
     */
    fun resetCpuBaseline() {
        previousCpu = null
    }

    // =====================================================================
    // Individual reads
    // =====================================================================

    /** CPU only. Returns null on the first call, which establishes the baseline. */
    suspend fun sampleCpu(): NeoResult<MonitorParsers.CpuSnapshot?> {
        val result = runner.run("cat /proc/stat 2>/dev/null", timeoutMs = SHORT_TIMEOUT_MS)
        if (result is NeoResult.Err) return result

        val totals = MonitorParsers.parseCpuTotals(result.getOrThrow().stdout)
            ?: return NeoResult.Err(NeoError.Io("/proc/stat was unreadable"))

        val previous = previousCpu
        previousCpu = totals
        if (previous == null) return NeoResult.Ok(null)

        val loads = runner.run("cat /proc/loadavg 2>/dev/null", timeoutMs = SHORT_TIMEOUT_MS)
            .getOrNull()
            ?.let { MonitorParsers.parseLoadAvg(it.stdout) }

        val snapshot = MonitorParsers.cpuPercentBetween(previous, totals)
            ?: return NeoResult.Ok(null)

        return NeoResult.Ok(
            if (loads == null) {
                snapshot
            } else {
                snapshot.copy(load1 = loads.first, load5 = loads.second, load15 = loads.third)
            },
        )
    }

    /**
     * Wait [intervalMs] then take a CPU sample, so the caller gets a real percentage
     * on the second poll without tracking timers itself.
     */
    suspend fun sampleCpuAfter(intervalMs: Long = DEFAULT_INTERVAL_MS): NeoResult<MonitorParsers.CpuSnapshot?> {
        if (previousCpu == null) {
            // Establish the baseline immediately, then wait, so the first interval is
            // spent measuring rather than being wasted before the clock starts.
            val warmUp = sampleCpu()
            if (warmUp is NeoResult.Err) return warmUp
            delay(intervalMs)
            return sampleCpu()
        }
        delay(intervalMs)
        return sampleCpu()
    }

    /**
     * One snapshot with a CPU percentage, taking the two samples it needs.
     *
     * Exists for callers that cannot hold state between polls — a one-shot AI tool
     * call, for instance. The regular polling path uses [sample] twice instead, because
     * it can wait for the next tick rather than blocking for one second.
     */
    suspend fun sampleAfterWarmUp(intervalMs: Long = DEFAULT_INTERVAL_MS): NeoResult<Snapshot> {
        // Read the baseline first so the wait is spent measuring, not idling.
        sample()
        delay(intervalMs)
        return sample()
    }
    suspend fun memory(): NeoResult<MonitorParsers.MemoryInfo> {
        val result = runner.run("cat /proc/meminfo 2>/dev/null", timeoutMs = SHORT_TIMEOUT_MS)
        if (result is NeoResult.Err) return result
        val info = MonitorParsers.parseMemInfo(result.getOrThrow().stdout)
            ?: return NeoResult.Err(NeoError.Io("/proc/meminfo was unreadable"))
        return NeoResult.Ok(info)
    }

    suspend fun disks(): NeoResult<List<MonitorParsers.DiskInfo>> {
        val result = runner.run("df -kP 2>/dev/null", timeoutMs = SHORT_TIMEOUT_MS)
        if (result is NeoResult.Err) return result
        return NeoResult.Ok(MonitorParsers.parseDf(result.getOrThrow().stdout))
    }

    suspend fun processes(): NeoResult<List<MonitorParsers.ProcessInfo>> {
        val result = runner.run(
            "ps -eo pid,user,pcpu,pmem,rss,args --sort=-pcpu 2>/dev/null | head -n 16",
            timeoutMs = SHORT_TIMEOUT_MS,
        )
        if (result is NeoResult.Err) return result
        return NeoResult.Ok(MonitorParsers.parseTop(result.getOrThrow().stdout))
    }

    suspend fun networks(): NeoResult<List<MonitorParsers.NetworkInfo>> {
        val result = runner.run("cat /proc/net/dev 2>/dev/null", timeoutMs = SHORT_TIMEOUT_MS)
        if (result is NeoResult.Err) return result
        return NeoResult.Ok(MonitorParsers.parseProcNetDev(result.getOrThrow().stdout))
    }

    suspend fun uptime(): NeoResult<MonitorParsers.UptimeInfo> {
        val result = runner.run("cat /proc/uptime 2>/dev/null", timeoutMs = SHORT_TIMEOUT_MS)
        if (result is NeoResult.Err) return result
        val info = MonitorParsers.parseUptime(result.getOrThrow().stdout)
            ?: return NeoResult.Err(NeoError.Io("/proc/uptime was unreadable"))
        NeoLog.d(LogTag.METRICS, "uptime=${info.seconds}s")
        return NeoResult.Ok(info)
    }

    /**
     * Kill a process by pid.
     *
     * The pid is validated as a positive integer before it reaches the command line.
     * Pids come from parsed output, but a malformed line could still put arbitrary
     * text into that field, and `sh -c` would happily execute it.
     */
    suspend fun kill(pid: Int, force: Boolean = false): NeoResult<Unit> {
        if (pid <= 1) {
            return NeoResult.Err(NeoError.Io("refusing to signal pid $pid"))
        }
        val signal = if (force) "-9" else "-15"
        val result = runner.run("kill $signal $pid 2>&1", timeoutMs = SHORT_TIMEOUT_MS)
        if (result is NeoResult.Err) return result
        val out = result.getOrThrow()
        return if (out.exitCode == 0) {
            NeoResult.Ok(Unit)
        } else {
            NeoResult.Err(NeoError.Io(out.stdout.trim().ifEmpty { "kill failed" }))
        }
    }

    private companion object {
        /** Section delimiter emitted between `cat` calls. */
        const val SECTION_SEPARATOR = "---"

        /** Whole-host sample: eight commands in one round trip. */
        const val COMMAND_TIMEOUT_MS = 30_000

        /** A single small file read. */
        const val SHORT_TIMEOUT_MS = 15_000

        /** Default gap between CPU samples; one second matches what top shows. */
        const val DEFAULT_INTERVAL_MS = 1_000L
    }
}
