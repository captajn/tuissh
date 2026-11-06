package com.tuissh.app.monitor

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.docker.DockerClient
import com.tuissh.app.ssh.RemoteCommandRunner
import com.tuissh.app.ssh.SshConnectPrompts
import com.tuissh.app.ssh.SshConnectSpec
import com.tuissh.app.ssh.SshSession
import com.tuissh.app.ssh.hostkey.HostKeyMaterial
import com.tuissh.app.ssh.hostkey.HostKeyVerifier
import com.tuissh.app.ssh.hostkey.HostTrustStore
import com.tuissh.app.ssh.key.KeyParseResult
import com.tuissh.app.ssh.key.KeyParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Real-host verification for the metrics and container clients.
 *
 * Skipped unless `LIVE_HOST`, `LIVE_USER` and `LIVE_KEY_PATH` are set. These exist
 * because a parser tested only against hand-written fixtures proves it matches my
 * assumptions, not that it matches a real machine. A two-core ARM host with Docker
 * behaves differently from x86 in ways that matter: `/proc/stat` field counts differ,
 * `df` header spacing differs, and container JSON carries a `Platform` object that the
 * fixtures never had.
 *
 * The key is read from a path rather than embedded so no credential lands in the repo.
 */
class MonitorLiveTest {

    private val host: String? = System.getenv("LIVE_HOST")?.takeIf { it.isNotBlank() }
    private val user: String? = System.getenv("LIVE_USER")?.takeIf { it.isNotBlank() }
    private val keyPath: String? = System.getenv("LIVE_KEY_PATH")?.takeIf { it.isNotBlank() }

    private fun available() = host != null && user != null && keyPath != null

    /** Connect exactly the way the app does, from the stored key to a shell. */
    private suspend fun connect(): SshSession {
        val pem = String(Files.readAllBytes(Path.of(keyPath!!)), Charsets.UTF_8)
        val parsed = KeyParser.parse(pem)
        val key = when (parsed) {
            is KeyParseResult.Success -> parsed.key
            else -> error("the live key did not parse: $parsed")
        }

        val session = SshSession(
            spec = SshConnectSpec(
                serverId = 1L,
                name = "live",
                host = host!!,
                port = 22,
                username = user!!,
                auth = SshConnectSpec.Auth.PrivateKey(key.pem, key.passphrase),
                connectTimeoutSeconds = 25,
            ),
            hostKeyVerifier = TrustLive,
            prompts = SilentPrompts,
        )
        val connected = session.connect()
        assertTrue("live connect failed: $connected", connected is NeoResult.Ok)
        return session
    }

    @Test
    fun `metrics come back from a real Linux host`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", available())
        val session = connect()
        try {
            val client = MonitorClient(RemoteCommandRunner.of(session))

            val snapshot = withTimeout(90_000) { client.sampleAfterWarmUp(intervalMs = 1_200) }

            assertTrue("sample failed: $snapshot", snapshot is NeoResult.Ok)
            val s = snapshot.getOrThrow()

            // Memory must be a real figure, not zeros from an unparsed file.
            assertNotNull("memory must parse on a real host", s.memory)
            assertTrue("total memory must be positive", s.memory!!.totalKb > 0)
            assertTrue(
                "used memory must not exceed total",
                s.memory!!.usedKb <= s.memory!!.totalKb,
            )

            // A percentage needs two samples; after the warm-up it must exist and be
            // a plausible number rather than the 0.0 the first read would produce.
            assertNotNull("cpu percentage must be measured after the warm-up", s.cpu)
            val busy = s.cpu!!.userPercent + s.cpu!!.systemPercent
            assertTrue("cpu must be within 0..100, got $busy", busy in 0.0..100.5)
            assertTrue("load average must parse", s.cpu!!.load1 >= 0.0)

            assertTrue("uptime must parse", (s.uptime?.seconds ?: 0) > 0)
            assertTrue("at least one real disk must survive the tmpfs filter", s.disks.isNotEmpty())
            assertTrue("the root mount must be present", s.disks.any { it.mount == "/" })
            assertTrue("processes must parse", s.processes.isNotEmpty())
            assertTrue("host label must parse", !s.hostLabel.isNullOrBlank())
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `docker containers come back from a real host`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", available())
        val session = connect()
        try {
            val docker = DockerClient(RemoteCommandRunner.of(session))

            val detected = withTimeout(60_000) { docker.detect() }
            assertTrue("engine detection failed: $detected", detected is NeoResult.Ok)
            assertTrue("docker must be found", detected.getOrThrow().binary == "docker")

            val listed = withTimeout(60_000) { docker.listContainers(includeStopped = true) }
            assertTrue("container listing failed: $listed", listed is NeoResult.Ok)
            val containers = listed.getOrThrow()

            assertTrue("the host runs containers", containers.isNotEmpty())
            for (c in containers) {
                assertTrue("id must not be blank", c.id.isNotBlank())
                assertTrue("name must not be blank", c.name.isNotBlank())
                assertTrue("image must not be blank", c.image.isNotBlank())
                assertEquals("short id is twelve characters", 12, c.shortId.length)
            }
            assertTrue("at least one container is running", containers.any { it.isRunning })

            // A published port must survive parsing; the host maps 8081.
            val withPorts = containers.filter { it.ports.isNotEmpty() }
            assertTrue("some container publishes a port", withPorts.isNotEmpty())
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `docker logs come back for a running container`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", available())
        val session = connect()
        try {
            val docker = DockerClient(RemoteCommandRunner.of(session))
            val containers = withTimeout(60_000) {
                docker.listContainers(includeStopped = false)
            }.getOrThrow()
            // Prefer a container whose status says healthy: a running container marked
            // for removal still refuses logs, and picking it would make the test look
            // broken for an environment reason.
            val running = containers.firstOrNull { it.isRunning && it.status.contains("healthy") }
                ?: containers.firstOrNull { it.isRunning }
            assumeTrue("no running container to read logs from", running != null)

            val logs = withTimeout(60_000) { docker.logs(running!!.id, lines = 20) }

            assertTrue("logs must be readable: $logs", logs is NeoResult.Ok)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `docker images and volumes parse from a real host`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", available())
        val session = connect()
        try {
            val docker = DockerClient(RemoteCommandRunner.of(session))

            val images = withTimeout(60_000) { docker.listImages() }
            assertTrue("image listing failed: $images", images is NeoResult.Ok)
            assertTrue("the host has images", images.getOrThrow().isNotEmpty())
            for (i in images.getOrThrow()) {
                assertTrue("repository must not be blank", i.repository.isNotBlank())
            }

            val volumes = withTimeout(60_000) { docker.listVolumes() }
            assertTrue("volume listing failed: $volumes", volumes is NeoResult.Ok)

            val networks = withTimeout(60_000) { docker.listNetworks() }
            assertTrue("network listing failed: $networks", networks is NeoResult.Ok)
            assertTrue("the default bridge network exists", networks.getOrThrow().any { it.name == "bridge" })
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `a kill of a non-existent pid is refused before touching the host`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", available())
        val session = connect()
        try {
            val client = MonitorClient(RemoteCommandRunner.of(session))

            // pid 1 is init; the client must refuse locally rather than signal it.
            assertTrue(client.kill(1) is NeoResult.Err)
        } finally {
            session.disconnect()
        }
    }

    private object TrustLive : HostTrustStore {
        override suspend fun evaluate(
            host: String,
            port: Int,
            material: HostKeyMaterial,
        ): HostKeyVerifier.Outcome = HostKeyVerifier.Outcome.Trusted

        override suspend fun trust(
            host: String,
            port: Int,
            material: HostKeyMaterial,
        ): NeoResult<Unit> = NeoResult.Ok(Unit)

        override suspend fun evaluateUnverifiable(
            host: String,
            port: Int,
            keyType: String,
        ): HostKeyVerifier.Outcome = HostKeyVerifier.Outcome.Trusted
    }

    private object SilentPrompts : SshConnectPrompts {
        override suspend fun confirmUnknownHost(
            host: String,
            port: Int,
            keyType: String,
            fingerprint: String,
        ): Boolean = true

        override suspend fun confirmChangedHost(error: NeoError.HostKeyChanged): Boolean = true

        override suspend fun askPassword(
            serverName: String,
            username: String,
            host: String,
        ): String? = null

        override suspend fun askPassphrase(keyName: String): String? = null

        override suspend fun askKeyboardInteractive(
            name: String,
            instruction: String,
            prompts: List<String>,
            echo: List<Boolean>,
        ): List<String>? = null
    }
}
