package com.tuissh.app.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for the server monitor.
 *
 * The samples are captured from real hosts. The awkward cases are the point of the
 * suite: a French-locale `df`, a Chinese `df -kP` header, a frozen `/proc/stat` on a
 * paused VM, a command line full of spaces, and an old kernel with no
 * `MemAvailable`.
 */
class MonitorParsersTest {

    // --- A) CPU -----------------------------------------------------------

    private val procStatSample = """
        cpu  3357 0 4313 1362393 0 0 0 0 0 0
        cpu0 1687 0 2183 681138 0 0 0 0 0 0
        cpu1 1670 0 2130 681255 0 0 0 0 0 0
        intr 1234567 0 0 0
        ctxt 9876543
        btime 1735689600
        processes 12345
    """.trimIndent()

    @Test
    fun `parses the aggregate cpu line and ignores per-core lines`() {
        val totals = MonitorParsers.parseCpuTotals(procStatSample)
        assertNotNull(totals)
        // Only the `cpu ` line counts; `cpu0`/`cpu1` would triple the total.
        assertEquals(3_357L + 0L + 4_313L + 1_362_393L, totals!!.totalJiffies)
        // `idle` and `iowait` are both quiet time.
        assertEquals(1_362_393L, totals.idleJiffies)
        assertEquals(3_357L, totals.userJiffies)
        assertEquals(4_313L, totals.systemJiffies)
        assertEquals(0L, totals.iowaitJiffies)
    }

    @Test
    fun `computes utilisation between two samples`() {
        val prev = MonitorParsers.CpuTotals(idleJiffies = 1000, totalJiffies = 2000, userJiffies = 600, systemJiffies = 400)
        val curr = MonitorParsers.CpuTotals(idleJiffies = 1300, totalJiffies = 3000, userJiffies = 1100, systemJiffies = 600)

        val snapshot = MonitorParsers.cpuPercentBetween(prev, curr, Triple(0.5, 0.4, 0.3))
        assertNotNull(snapshot)
        // Delta total = 1000 jiffies: 500 user, 200 system, 300 idle.
        assertEquals(50.0, snapshot!!.userPercent, 0.001)
        assertEquals(20.0, snapshot.systemPercent, 0.001)
        assertEquals(30.0, snapshot.idlePercent, 0.001)
        assertEquals(0.0, snapshot.otherPercent, 0.001)
    }

    @Test
    fun `carries the load averages into the snapshot`() {
        val prev = MonitorParsers.CpuTotals(idleJiffies = 0, totalJiffies = 0)
        val curr = MonitorParsers.CpuTotals(idleJiffies = 100, totalJiffies = 400)
        val snapshot = MonitorParsers.cpuPercentBetween(prev, curr, Triple(1.5, 2.5, 3.5))!!
        assertEquals(1.5, snapshot.load1, 0.001)
        assertEquals(2.5, snapshot.load5, 0.001)
        assertEquals(3.5, snapshot.load15, 0.001)
        assertEquals(25.0, snapshot.idlePercent, 0.001)
    }

    @Test
    fun `zero delta between samples yields null instead of zero percent`() {
        // A paused VM or a cached read: the counters did not move, so there is no
        // measurement to report.
        val same = MonitorParsers.CpuTotals(idleJiffies = 500, totalJiffies = 1000)
        assertNull(MonitorParsers.cpuPercentBetween(same, same))
    }

    @Test
    fun `totals that move backwards yield null`() {
        val prev = MonitorParsers.CpuTotals(idleJiffies = 800, totalJiffies = 1000)
        val curr = MonitorParsers.CpuTotals(idleJiffies = 100, totalJiffies = 200)
        assertNull(MonitorParsers.cpuPercentBetween(prev, curr))
    }

    @Test
    fun `a single zeroed sample still parses but cannot be diffed`() {
        // Real on tiny VMs: the line is present and all counters are zero.
        val frozen = "cpu  0 0 0 0 0 0 0 0 0 0\n"
        val totals = MonitorParsers.parseCpuTotals(frozen)!!
        assertEquals(0L, totals.totalJiffies)
        assertNull(MonitorParsers.cpuPercentBetween(totals, totals))
    }

    @Test
    fun `percentages never exceed a hundred`() {
        // One field jumped backwards while the total advanced; the clamp keeps the
        // other buckets usable and the stack bar inside its track.
        val prev = MonitorParsers.CpuTotals(idleJiffies = 900, totalJiffies = 1000, userJiffies = 100)
        val curr = MonitorParsers.CpuTotals(idleJiffies = 1000, totalJiffies = 1100, userJiffies = 50)
        val snapshot = MonitorParsers.cpuPercentBetween(prev, curr)!!
        val sum = snapshot.userPercent + snapshot.systemPercent + snapshot.idlePercent +
            snapshot.iowaitPercent + snapshot.otherPercent
        assertTrue("sum was $sum", sum in 99.9..100.05)
        assertTrue(snapshot.otherPercent >= 0.0)
    }

    @Test
    fun `old kernel line without steal parses`() {
        val totals = MonitorParsers.parseCpuTotals("cpu  10 20 30 40")!!
        assertEquals(100L, totals.totalJiffies)
        assertEquals(40L, totals.idleJiffies)
    }

    @Test
    fun `junk instead of cpu stats returns null`() {
        assertNull(MonitorParsers.parseCpuTotals(""))
        assertNull(MonitorParsers.parseCpuTotals("not a cpu line"))
        assertNull(MonitorParsers.parseCpuTotals("cpu 1 2 3"))
        assertNull(MonitorParsers.parseCpuTotals("cpu  1 x 3 4"))
        // A per-core line alone is not the aggregate.
        assertNull(MonitorParsers.parseCpuTotals("cpu0 1 2 3 4"))
    }

    @Test
    fun `parses load average`() {
        val load = MonitorParsers.parseLoadAvg("0.52 0.58 0.59 1/234 5678\n")
        assertNotNull(load)
        assertEquals(0.52, load!!.first, 0.0001)
        assertEquals(0.58, load.second, 0.0001)
        assertEquals(0.59, load.third, 0.0001)
    }

    @Test
    fun `malformed load average returns null`() {
        assertNull(MonitorParsers.parseLoadAvg(""))
        assertNull(MonitorParsers.parseLoadAvg("0.52 0.58"))
        assertNull(MonitorParsers.parseLoadAvg("a b c"))
    }

    // --- B) Memory --------------------------------------------------------

    private val memInfoSample = """
        MemTotal:        8174324 kB
        MemFree:          204580 kB
        MemAvailable:    3221320 kB
        Buffers:          142120 kB
        Cached:          2841332 kB
        SwapCached:            0 kB
        SwapTotal:       2097148 kB
        SwapFree:        1998604 kB
        Dirty:               256 kB
        Writeback:             0 kB
    """.trimIndent()

    @Test
    fun `parses meminfo and prefers available over free`() {
        val mem = MonitorParsers.parseMemInfo(memInfoSample)
        assertNotNull(mem)
        assertEquals(8_174_324L, mem!!.totalKb)
        assertEquals(204_580L, mem.freeKb)
        assertEquals(3_221_320L, mem.availableKb)
        assertEquals(142_120L, mem.buffersKb)
        assertEquals(2_841_332L, mem.cachedKb)
        assertEquals(2_097_148L, mem.swapTotalKb)
        assertEquals(1_998_604L, mem.swapFreeKb)
    }

    @Test
    fun `used is total minus available, not total minus free`() {
        val mem = MonitorParsers.parseMemInfo(memInfoSample)!!
        assertEquals(8_174_324L - 3_221_320L, mem.usedKb)
        assertTrue(mem.usedFraction > 0.6 && mem.usedFraction < 0.61)
        assertEquals(98_544L, mem.swapUsedKb)
    }

    @Test
    fun `old kernel without MemAvailable falls back to free plus buffers plus cached`() {
        val old = """
            MemTotal:        1000000 kB
            MemFree:          100000 kB
            Buffers:           50000 kB
            Cached:           350000 kB
            SwapTotal:             0 kB
            SwapFree:              0 kB
        """.trimIndent()
        val mem = MonitorParsers.parseMemInfo(old)!!
        assertEquals(500_000L, mem.availableKb)
        assertEquals(500_000L, mem.usedKb)
        // Swap disabled: the fraction is meaningless rather than zero-filled nonsense.
        assertEquals(0L, mem.swapTotalKb)
        assertEquals(0.0, mem.swapUsedFraction, 0.0001)
    }

    @Test
    fun `meminfo without MemTotal returns null`() {
        assertNull(MonitorParsers.parseMemInfo(""))
        assertNull(MonitorParsers.parseMemInfo("HugePages_Total: 0\nHugePages_Free: 0\n"))
        assertNull(MonitorParsers.parseMemInfo("garbage line without a colon"))
    }

    // --- C) Disk ----------------------------------------------------------

    private val dfGnuSample = """
        Filesystem     1024-blocks      Used Available Capacity Mounted on
        /dev/sda1         102687672  41234568  56214544      43% /
        tmpfs               4087164         0   4087164       0% /dev/shm
        /dev/sdb1        1026876720 512000000 462000000      53% /data
        devtmpfs            4087160         0   4087160       0% /dev
        overlay           102687672  41234568  56214544      43% /var/lib/docker/overlay2/abc
        /dev/loop3            65536     65536         0     100% /snap/core20/1974
        /dev/sda15           106832     6187    100645       6% /boot/efi
    """.trimIndent()

    @Test
    fun `parses df and keeps only real filesystems`() {
        val disks = MonitorParsers.parseDf(dfGnuSample)
        assertEquals(2, disks.size)
        assertEquals(checkNotNull(disks.firstOrNull { it.mount == "/" }).filesystem, "/dev/sda1")
        assertEquals("/data", disks[1].mount)
    }

    @Test
    fun `df values are converted from 1K blocks to bytes`() {
        val root = MonitorParsers.parseDf(dfGnuSample).first { it.mount == "/" }
        assertEquals(102_687_672L * 1024L, root.totalBytes)
        assertEquals(41_234_568L * 1024L, root.usedBytes)
        assertEquals(56_214_544L * 1024L, root.availableBytes)
        // 41234568 / (41234568 + 56214544) ~= 42.3%, which differs from df's 43%
        // because the reserved-block maths is df's own.
        assertEquals(42.3, root.usedPercent, 0.2)
    }

    @Test
    fun `chinese df header is not mistaken for a mount`() {
        // Real Android / zh-CN host header: every word is translated, but the shape of
        // the rows is identical and the header must not become a row.
        val chinese = """
            文件系统        1024-块      已用      可用   已用% 挂载点
            /dev/block/dm-0 57421896  22104840  35317056     39% /
            /dev/block/dm-1 11264000    512000  10752000      5% /data
            /dev/block/dm-2   524288     10240    514048       2% /vendor
        """.trimIndent()
        val disks = MonitorParsers.parseDf(chinese)
        assertEquals(3, disks.size)
        assertEquals("/", disks[0].mount)
        assertEquals("/data", disks[1].mount)
        assertEquals("/vendor", disks[2].mount)
        assertTrue(disks.none { it.filesystem.contains("文件系统") })
    }

    @Test
    fun `french df header and thousands separators are handled`() {
        // Numbers as reported by a French host. Both headers are skipped and neither the
        // separator nor the translated header breaks parsing.
        val french = """
            Système de fichiers    blocs de 1K   Utilisé Disponible Uti% Monté sur
            /dev/sda1          102687672  41234568   56214544   43% /
            /mnt/store/espace disque 1 024 000  500000  524000  49% /mnt/store/espace disque
        """.trimIndent()
        val disks = MonitorParsers.parseDf(french)
        // The second data row has a space in the mount name; it is not layout-stable
        // under whitespace splitting, so the mount point itself is rejected rather than
        // silently truncated to "disque".
        assertEquals(1, disks.size)
        assertEquals("/", disks[0].mount)
        assertEquals(42.3, disks[0].usedPercent, 0.2)
    }

    @Test
    fun `df rows with a dash instead of a size are skipped`() {
        val output = """
            Filesystem 1K-blocks Used Available Use% Mounted on
            /dev/sda1 1000 500 500 50% /
            proc - - - - /proc
        """.trimIndent()
        val disks = MonitorParsers.parseDf(output)
        assertEquals(1, disks.size)
        assertEquals("/", disks[0].mount)
    }

    @Test
    fun `empty or headerless df yields no rows`() {
        assertTrue(MonitorParsers.parseDf("").isEmpty())
        assertTrue(MonitorParsers.parseDf("Filesystem 1K-blocks Used Available Use% Mounted on").isEmpty())
        assertTrue(MonitorParsers.parseDf("garbage\nmore garbage\n").isEmpty())
    }

    @Test
    fun `snap subdirectory mounts are skipped`() {
        val output = """
            Filesystem     1K-blocks  Used Available Use% Mounted on
            /dev/loop1            4096  1024      3072  25% /snap/foo/1
            /dev/sda1          1000000 50000    950000   5% /home
        """.trimIndent()
        val disks = MonitorParsers.parseDf(output)
        assertEquals(1, disks.size)
        assertEquals("/home", disks[0].mount)
    }

    // --- D) Processes -----------------------------------------------------

    @Test
    fun `parses ps rows with a header present`() {
        val output = """
            PID USER     %CPU %MEM   RSS COMMAND
              1 root      0.0  0.1  3456 /sbin/init
            839 www-data  1.4  2.3 48210 nginx: worker process
            1201 postgres  3.2  8.4 204800 postgres: checkpointer
        """.trimIndent()
        val list = MonitorParsers.parseTop(output)
        assertEquals(3, list.size)
        assertEquals(1, list[0].pid)
        assertEquals("root", list[0].user)
        assertEquals(3.2, list[2].cpuPercent, 0.001)
        assertEquals(8.4, list[2].memPercent, 0.001)
        assertEquals(204_800L, list[2].rssKb)
    }

    @Test
    fun `command keeps its spaces`() {
        val output = """
            PID USER     %CPU %MEM   RSS COMMAND
            912 root      0.7  0.4  18240 /usr/bin/java -Xmx512m -jar /opt/app/server.jar --spring.profiles.active=prod
        """.trimIndent()
        val process = MonitorParsers.parseTop(output).single()
        assertEquals("/usr/bin/java -Xmx512m -jar /opt/app/server.jar --spring.profiles.active=prod", process.command)
        assertEquals(18_240L, process.rssKb)
    }

    @Test
    fun `malformed ps lines are skipped`() {
        val output = """
            PID USER     %CPU %MEM   RSS COMMAND
            not a row at all
            12 root      0.2  0.1  9000 /bin/ok
            abc root    1.0  1.0  1000 /bin/bad-pid
            77 root     n/a  0.1  1000 /bin/bad-cpu
            88 root      0.1  0.1  1000
        """.trimIndent()
        val list = MonitorParsers.parseTop(output)
        assertEquals(1, list.size)
        assertEquals(12, list[0].pid)
    }

    @Test
    fun `process table is capped at ten rows`() {
        val header = "PID USER %CPU %MEM RSS COMMAND\n"
        val body = (1..25).joinToString("\n") { i -> "$i root 0.1 0.1 1000 /bin/proc$i" }
        val list = MonitorParsers.parseTop(header + body)
        assertEquals(10, list.size)
        assertEquals(1, list.first().pid)
        assertEquals(10, list.last().pid)
    }

    @Test
    fun `empty ps output yields an empty list`() {
        assertTrue(MonitorParsers.parseTop("").isEmpty())
        assertTrue(MonitorParsers.parseTop("PID USER %CPU %MEM RSS COMMAND").isEmpty())
    }

    // --- E) Network -------------------------------------------------------

    private val netDevSample = """
        Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
            lo: 5000000    4000    0    0    0     0          0         0  5000000    4000    0    0    0     0       0          0
          eth0: 123456789  90000    0    0    0     0          0         0 987654321  80000    0    0    0     0       0          0
        docker0:   1200      14    0    0    0     0          0         0     2400      20    0    0    0     0       0          0
    """.trimIndent()

    @Test
    fun `parses net dev and skips loopback`() {
        val list = MonitorParsers.parseProcNetDev(netDevSample)
        assertEquals(2, list.size)
        assertEquals("eth0", list[0].interfaceName)
        assertEquals(123_456_789L, list[0].rxBytes)
        assertEquals(987_654_321L, list[0].txBytes)
        assertEquals("docker0", list[1].interfaceName)
        assertTrue(list.none { it.interfaceName == "lo" })
    }

    @Test
    fun `net dev without the header lines still parses`() {
        val bare = "eth0: 100 1 0 0 0 0 0 0 200 2 0 0 0 0 0 0\n"
        val list = MonitorParsers.parseProcNetDev(bare)
        assertEquals(1, list.size)
        assertEquals(100L, list[0].rxBytes)
        assertEquals(200L, list[0].txBytes)
    }

    @Test
    fun `truncated net dev lines are skipped`() {
        val broken = """
            Inter-|   Receive |  Transmit
             face |bytes packets
            eth0: 100 1 0 0 0 0 0 0 200
            wlan0: 300 1 0 0 0 0 0 0 400 2 0 0 0 0 0 0
        """.trimIndent()
        val list = MonitorParsers.parseProcNetDev(broken)
        assertEquals(1, list.size)
        assertEquals("wlan0", list[0].interfaceName)
    }

    @Test
    fun `empty net dev yields nothing`() {
        assertTrue(MonitorParsers.parseProcNetDev("").isEmpty())
        assertTrue(MonitorParsers.parseProcNetDev("Inter-|   Receive   |  Transmit").isEmpty())
    }

    // --- F) Uptime --------------------------------------------------------

    @Test
    fun `keeps two decimals of uptime`() {
        val up = MonitorParsers.parseUptime("98765.43 1234567.89\n")
        assertNotNull(up)
        assertEquals(98_765L, up!!.seconds)
        assertEquals(1_234_567.89, up.idleSeconds, 0.0001)
    }

    @Test
    fun `idle seconds may exceed uptime on a multi-core host`() {
        // Sum of per-CPU idle time; not an error and not normalised away here.
        val up = MonitorParsers.parseUptime("100.50 800.25")!!
        assertTrue(up.idleSeconds > up.seconds)
    }

    @Test
    fun `malformed uptime returns null`() {
        assertNull(MonitorParsers.parseUptime(""))
        assertNull(MonitorParsers.parseUptime("   "))
        assertNull(MonitorParsers.parseUptime("abc 123"))
        assertNull(MonitorParsers.parseUptime("-5.0 1.0"))
    }

    @Test
    fun `uptime without the idle field still reports seconds`() {
        val up = MonitorParsers.parseUptime("3600.00")!!
        assertEquals(3_600L, up.seconds)
        assertEquals(0.0, up.idleSeconds, 0.0001)
    }

    // --- G) Formatting ----------------------------------------------------

    @Test
    fun `formats bytes with one decimal and 1024 base`() {
        assertEquals("-", MonitorParsers.formatBytes(-1))
        assertEquals("0 B", MonitorParsers.formatBytes(0))
        assertEquals("512 B", MonitorParsers.formatBytes(512))
        assertEquals("1.0 KB", MonitorParsers.formatBytes(1024))
        assertEquals("1.5 KB", MonitorParsers.formatBytes(1536))
        assertEquals("1.0 MB", MonitorParsers.formatBytes(1024L * 1024))
        assertEquals(("1.2 GB"), MonitorParsers.formatBytes((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1.0 TB", MonitorParsers.formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `rounding up at the unit boundary promotes the unit`() {
        // 1023.99 KB must not print as "1024.0 KB".
        assertEquals("1.0 MB", MonitorParsers.formatBytes(1_048_570L))
    }

    @Test
    fun `byte formatting uses a dot regardless of device locale`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.5 KB", MonitorParsers.formatBytes(1536))
        } finally {
            java.util.Locale.setDefault(default)
        }
    }
}
