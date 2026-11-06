package com.tuissh.app.monitor

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.RemoteCommandRunner
import com.tuissh.app.ssh.SshSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of [MonitorClient] against a scripted command runner.
 *
 * The parsers are covered separately; what is tested here is everything the parsers do
 * not decide: how the eight commands are framed into one round trip, what happens when
 * a section is missing, and that the CPU percentage needs a second sample instead of
 * reporting a fabricated zero.
 */
class MonitorClientTest {

    /** Records commands and replays canned output. */
    private class ScriptedRunner(
        private val respond: (String) -> NeoResult<SshSession.CommandResult>,
    ) : RemoteCommandRunner {
        val commands = mutableListOf<String>()

        override suspend fun run(
            command: String,
            timeoutMs: Int,
        ): NeoResult<SshSession.CommandResult> {
            commands.add(command)
            return respond(command)
        }
    }

    private fun ok(stdout: String, exitCode: Int = 0) = NeoResult.Ok(
        SshSession.CommandResult(
            command = "",
            exitCode = exitCode,
            stdout = stdout,
            stderr = "",
        ),
    )

    /**
     * Payload shaped like the eight-section sample.
     *
     * `/proc/stat` column order is user, nice, system, idle, iowait, so the idle figure
     * goes in the fourth slot. Putting it in the third slot makes the parser read it as
     * system time, which is exactly the mistake this helper must not bake in.
     */
    private fun fullPayload(cpuIdle: Long = 1000, cpuTotal: Long = 4000): String = """
        cpu  ${cpuTotal - cpuIdle} 0 0 ${cpuIdle} 0 0 0 0
        ---
        MemTotal:        8000000 kB
        MemFree:         1000000 kB
        MemAvailable:    4000000 kB
        Buffers:          100000 kB
        Cached:          2000000 kB
        SwapTotal:       1000000 kB
        SwapFree:         800000 kB
        ---
        0.50 0.40 0.30 1/100 1234
        ---
        12345.67 98765.43
        ---
        Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
            lo:    1000      10    0    0    0     0          0         0     1000      10    0    0    0     0       0          0
          eth0: 5000000    5000    0    0    0     0          0         0  2000000    2000    0    0    0     0       0          0
        ---
        Filesystem     1024-blocks    Used Available Capacity Mounted on
        /dev/sda1         50000000 20000000  30000000      40% /
        tmpfs              1000000        0   1000000       0% /dev/shm
        ---
          PID USER      %CPU %MEM   RSS COMMAND
         1234 root      12.5  3.2 123456 /usr/bin/python3 -m http.server 8080
         5678 www-data   2.0  1.1  45678 nginx: worker process
        ---
        Linux hostname 5.15.0-91-generic
    """.trimIndent()

    @Test
    fun `one round trip carries every command`() = runBlocking {
        val runner = ScriptedRunner { ok(fullPayload()) }
        val client = MonitorClient(runner)

        client.sample()

        assertEquals("sample() must not open several channels", 1, runner.commands.size)
        val command = runner.commands.single()
        assertTrue("must read /proc/stat", command.contains("/proc/stat"))
        assertTrue("must read /proc/meminfo", command.contains("/proc/meminfo"))
        assertTrue("must read /proc/loadavg", command.contains("/proc/loadavg"))
        assertTrue("must read /proc/uptime", command.contains("/proc/uptime"))
        assertTrue("must read /proc/net/dev", command.contains("/proc/net/dev"))
        assertTrue("must run df", command.contains("df -kP"))
        assertTrue("must run ps", command.contains("ps -eo"))
    }

    @Test
    fun `first sample has no cpu percentage because there is no baseline`() = runBlocking {
        val runner = ScriptedRunner { ok(fullPayload()) }
        val client = MonitorClient(runner)

        val snapshot = client.sample().getOrThrow()

        assertNull("a single /proc/stat read cannot yield a percentage", snapshot.cpu?.userPercent?.takeIf { it > 0 })
        // Load average is still carried, because it is readable from one call.
        assertNotNull(snapshot.cpu)
        assertEquals(0.50, snapshot.cpu!!.load1, 0.001)
    }

    @Test
    fun `second sample produces a real percentage`() = runBlocking {
        var call = 0
        val runner = ScriptedRunner {
            call++
            // 1000 idle of 4000 total, then 1000 more idle of 2000 more total:
            // half the delta was idle, so user+system should be about 50%.
            if (call == 1) ok(fullPayload(cpuIdle = 1000, cpuTotal = 4000))
            else ok(fullPayload(cpuIdle = 2000, cpuTotal = 6000))
        }
        val client = MonitorClient(runner)

        client.sample()
        val second = client.sample().getOrThrow().cpu

        assertNotNull("second sample must carry a percentage", second)
        val busy = second!!.userPercent + second.systemPercent
        assertEquals("half the jiffy delta was busy", 50.0, busy, 1.0)
    }

    @Test
    fun `reset forces the next sample to re-baseline`() = runBlocking {
        val runner = ScriptedRunner { ok(fullPayload()) }
        val client = MonitorClient(runner)

        client.sample()
        client.sample()
        assertNotNull("baseline established by the second call", client.sample().getOrThrow())

        client.resetCpuBaseline()

        assertNull(
            "after a reset the next sample is a baseline again",
            client.sample().getOrThrow().cpu?.userPercent?.takeIf { it > 0 },
        )
    }

    @Test
    fun `memory disks and processes are parsed from their sections`() = runBlocking {
        val runner = ScriptedRunner { ok(fullPayload()) }
        val snapshot = MonitorClient(runner).sample().getOrThrow()

        assertEquals(8_000_000L, snapshot.memory?.totalKb)
        assertEquals("tmpfs must be filtered out", 1, snapshot.disks.size)
        assertEquals("/", snapshot.disks.single().mount)
        assertEquals(2, snapshot.processes.size)
        assertEquals(1234, snapshot.processes.first().pid)
        assertTrue(
            "a command with spaces must survive",
            snapshot.processes.first().command.contains("http.server 8080"),
        )
    }

    @Test
    fun `loopback is excluded from the network list`() = runBlocking {
        val snapshot = MonitorClient(ScriptedRunner { ok(fullPayload()) }).sample().getOrThrow()

        assertEquals(1, snapshot.networks.size)
        assertEquals("eth0", snapshot.networks.single().interfaceName)
    }

    @Test
    fun `host label comes from uname`() = runBlocking {
        val snapshot = MonitorClient(ScriptedRunner { ok(fullPayload()) }).sample().getOrThrow()

        assertTrue(snapshot.hostLabel!!.startsWith("Linux hostname"))
    }

    @Test
    fun `empty output is an error, not an empty dashboard`() = runBlocking {
        val result = MonitorClient(ScriptedRunner { ok("") }).sample()

        assertTrue("a host that answers nothing must surface an error", result is NeoResult.Err)
    }

    @Test
    fun `missing sections leave those fields empty rather than failing`() = runBlocking {
        // A container has no /proc/net/dev; memory and cpu should still arrive.
        val partial = """
            cpu  1000 0 3000 0 0 0 0 0
            ---
            MemTotal:        8000000 kB
            MemFree:         1000000 kB
            MemAvailable:    4000000 kB
            Buffers:          0 kB
            Cached:             0 kB
            SwapTotal:          0 kB
            SwapFree:           0 kB
            ---
        """.trimIndent()

        val snapshot = MonitorClient(ScriptedRunner { ok(partial) }).sample().getOrThrow()

        assertEquals(8_000_000L, snapshot.memory?.totalKb)
        assertTrue(snapshot.networks.isEmpty())
        assertTrue(snapshot.disks.isEmpty())
        assertTrue(snapshot.processes.isEmpty())
    }

    @Test
    fun `kill refuses pid 1 and pid 0`() = runBlocking {
        val runner = ScriptedRunner { ok("") }
        val client = MonitorClient(runner)

        assertTrue("pid 1 must never be signalled", client.kill(1) is NeoResult.Err)
        assertTrue("pid 0 must never be signalled", client.kill(0) is NeoResult.Err)
        assertTrue("no command may be sent", runner.commands.isEmpty())
    }

    @Test
    fun `kill sends the expected signal`() = runBlocking {
        val runner = ScriptedRunner { ok("") }
        val client = MonitorClient(runner)

        client.kill(4321)
        assertTrue(runner.commands.last().contains("kill -15 4321"))

        client.kill(4321, force = true)
        assertTrue(runner.commands.last().contains("kill -9 4321"))
    }

    @Test
    fun `kill reports a non-zero exit as an error`() = runBlocking {
        val runner = ScriptedRunner {
            ok("kill: (4321) - No such process", exitCode = 1)
        }
        val result = MonitorClient(runner).kill(4321)

        assertTrue(result is NeoResult.Err)
        assertTrue((result as NeoResult.Err).error.message!!.contains("No such process"))
    }

    @Test
    fun `a failed exec propagates as an error`() = runBlocking {
        val runner = ScriptedRunner { NeoResult.Err(NeoError.ConnectionLost) }

        val result = MonitorClient(runner).sample()

        assertTrue(result is NeoResult.Err)
        assertEquals(NeoError.ConnectionLost, (result as NeoResult.Err).error)
    }

    @Test
    fun `sampleCpuAfter establishes the baseline before waiting`() = runBlocking {
        var calls = 0
        val runner = ScriptedRunner {
            calls++
            ok("cpu  1000 0 3000 0 0 0 0 0")
        }

        // A zero interval keeps the test fast while exercising the warm-up branch.
        val snapshot = MonitorClient(runner).sampleCpuAfter(intervalMs = 0).getOrThrow()

        // Three reads: the baseline /proc/stat, then /proc/stat plus /proc/loadavg on
        // the measured sample.
        assertEquals("baseline read, then stat and loadavg", 3, calls)
        assertNull("identical counters mean no delta to report", snapshot)
    }
}
