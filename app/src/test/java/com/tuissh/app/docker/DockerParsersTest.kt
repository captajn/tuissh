package com.tuissh.app.docker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests for Docker CLI output.
 *
 * The cases are taken from real `docker ps --format '{{json .}}'` output, including
 * the awkward ones: a container name with a space, an image digest, an IPv6 port
 * duplicate, a range mapping, and a healthcheck suffix.
 */
class DockerParsersTest {

    // --- containers -------------------------------------------------------

    private val psJson = """
        {"Command":"\"nginx -g 'daemon of…\"","CreatedAt":"2026-11-05 10:00:00 +0000 UTC","ID":"a1b2c3d4e5f6a7b8c9d0","Image":"nginx:1.27","Labels":"","LocalVolumes":"0","Mounts":"","Names":"web","Networks":"bridge","Ports":"0.0.0.0:8080->80/tcp, :::8080->80/tcp","RunningFor":"3 hours ago","Size":"0B","State":"running","Status":"Up 3 hours"}
        {"Command":"\"/bin/sh -c sleep 300\"","CreatedAt":"2026-11-05 09:00:00 +0000 UTC","ID":"b2c3d4e5f6a7b8c9d0e1","Image":"alpine:3.20","Labels":"","LocalVolumes":"0","Mounts":"","Names":"my api worker","Networks":"bridge","Ports":"","RunningFor":"2 days ago","Size":"0B","State":"running","Status":"Up 2 days (healthy)"}
        {"Command":"\"bash\"","CreatedAt":"2026-11-04 08:00:00 +0000 UTC","ID":"c3d4e5f6a7b8c9d0e1f2","Image":"ubuntu:24.04","Labels":"","LocalVolumes":"0","Mounts":"","Names":"crashed","Networks":"bridge","Ports":"9000/tcp","RunningFor":"5 minutes ago","Size":"0B","State":"exited","Status":"Exited (137) 5 minutes ago"}
    """.trimIndent()

    @Test
    fun `parses running containers`() {
        val list = DockerParsers.parseContainers(psJson)
        assertEquals(3, list.size)

        val web = list.first { it.name == "web" }
        assertEquals("nginx:1.27", web.image)
        assertTrue(web.isRunning)
        assertEquals(3 * 3600L, web.runningForSeconds)
    }

    @Test
    fun `keeps a container name that contains spaces`() {
        // This is the case regex-on-columns parsing gets wrong.
        val list = DockerParsers.parseContainers(psJson)
        assertNotNull(list.firstOrNull { it.name == "my api worker" })
    }

    @Test
    fun `short id is twelve characters`() {
        val list = DockerParsers.parseContainers(psJson)
        assertEquals(12, list.first().shortId.length)
    }

    @Test
    fun `parses port mapping and drops the ipv6 duplicate`() {
        val web = DockerParsers.parseContainers(psJson).first { it.name == "web" }
        assertEquals(1, web.ports.size)
        assertEquals(8080, web.ports[0].hostPort)
        assertEquals(80, web.ports[0].containerPort)
        assertEquals("tcp", web.ports[0].protocol)
    }

    @Test
    fun `container with no published port has an empty mapping`() {
        val worker = DockerParsers.parseContainers(psJson).first { it.name == "my api worker" }
        assertTrue(worker.ports.isEmpty())
        assertEquals("—", worker.portsLabel)
    }

    @Test
    fun `exposed-only port has no host port`() {
        val crashed = DockerParsers.parseContainers(psJson).first { it.name == "crashed" }
        assertEquals(1, crashed.ports.size)
        assertNull(crashed.ports[0].hostPort)
        assertEquals(9000, crashed.ports[0].containerPort)
    }

    @Test
    fun `healthcheck suffix does not break the uptime`() {
        val worker = DockerParsers.parseContainers(psJson).first { it.name == "my api worker" }
        assertEquals(2 * 86_400L, worker.runningForSeconds)
    }

    @Test
    fun `exit code is read from the status`() {
        val crashed = DockerParsers.parseContainers(psJson).first { it.name == "crashed" }
        assertTrue(!crashed.isRunning)
        assertEquals(137, DockerParsers.parseExitCode(crashed.status))
    }

    @Test
    fun `unrecognised uptime is null rather than wrong`() {
        assertNull(DockerParsers.parseUptimeSeconds("Created"))
        assertNull(DockerParsers.parseUptimeSeconds(""))
        assertEquals(45L, DockerParsers.parseUptimeSeconds("Up 45 seconds"))
    }

    @Test
    fun `malformed lines are skipped, not fatal`() {
        val mixed = """
            {"ID":"good1","Names":"ok","Image":"x","State":"running","Status":"Up 1 hour","Ports":""}
            not json at all
            {"ID":"good2","Names":"ok2","Image":"y","State":"running","Status":"Up 2 hours","Ports":""}
        """.trimIndent()
        assertEquals(2, DockerParsers.parseContainers(mixed).size)
    }

    @Test
    fun `port range expands`() {
        val ports = DockerParsers.parsePorts("0.0.0.0:8000-8002->8000-8002/tcp")
        assertEquals(3, ports.size)
        assertEquals(8001, ports[1].hostPort)
        assertEquals(8001, ports[1].containerPort)
    }

    // --- images -----------------------------------------------------------

    @Test
    fun `parses images and flags dangling ones`() {
        val json = """
            {"Containers":"N/A","CreatedAt":"2026-11-01 00:00:00 +0000 UTC","CreatedSince":"5 days ago","Digest":"<none>","ID":"sha256:abc123def456","Repository":"nginx","SharedSize":"N/A","Size":"192MB","Tag":"latest","UniqueSize":"N/A","VirtualSize":"192MB"}
            {"Containers":"N/A","CreatedAt":"2026-10-01 00:00:00 +0000 UTC","CreatedSince":"1 month ago","Digest":"<none>","ID":"sha256:def456abc789","Repository":"<none>","SharedSize":"N/A","Size":"0B","Tag":"<none>","UniqueSize":"N/A","VirtualSize":"0B"}
        """.trimIndent()
        val images = DockerParsers.parseImages(json)
        assertEquals(2, images.size)
        assertEquals("nginx:latest", images[0].reference)
        assertEquals(12, images[0].shortId.length)
        assertTrue(images[1].isDangling)
    }

    // --- volumes and networks --------------------------------------------

    @Test
    fun `parses volumes`() {
        val json = """{"Availability":"N/A","Driver":"local","Group":"N/A","Labels":"","Links":"N/A","Mountpoint":"/var/lib/docker/volumes/data/_data","Name":"data","Scope":"local","Status":"N/A"}"""
        val volumes = DockerParsers.parseVolumes(json)
        assertEquals(1, volumes.size)
        assertEquals("data", volumes[0].name)
        assertEquals("/var/lib/docker/volumes/data/_data", volumes[0].mountPoint)
    }

    @Test
    fun `parses networks`() {
        val json = """{"CreatedAt":"2026-11-01 00:00:00","Driver":"bridge","ID":"aa11","IPv6":"false","Internal":"false","Labels":"","Name":"bridge","Scope":"local"}"""
        val networks = DockerParsers.parseNetworks(json)
        assertEquals(1, networks.size)
        assertEquals("bridge", networks[0].name)
        assertEquals("local", networks[0].scope)
    }

    // --- failures ---------------------------------------------------------

    @Test
    fun `classifies a missing binary`() {
        val problem = DockerParsers.classifyFailure(127, "sh: docker: command not found")
        assertEquals(DockerParsers.EngineProblem.NotInstalled, problem)
    }

    @Test
    fun `classifies permission denied`() {
        val problem = DockerParsers.classifyFailure(
            1,
            "permission denied while trying to connect to the Docker daemon socket",
        )
        assertEquals(DockerParsers.EngineProblem.PermissionDenied, problem)
    }

    @Test
    fun `classifies daemon down`() {
        val problem = DockerParsers.classifyFailure(
            1,
            "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?",
        )
        assertEquals(DockerParsers.EngineProblem.DaemonDown, problem)
    }

    @Test
    fun `unknown failure keeps the message`() {
        val problem = DockerParsers.classifyFailure(2, "something odd happened")
        assertTrue(problem is DockerParsers.EngineProblem.Other)
        assertEquals("something odd happened", (problem as DockerParsers.EngineProblem.Other).message)
    }

    // --- JSON reader edge cases ------------------------------------------

    @Test
    fun `json reader handles escapes`() {
        val json = """{"Names":"quote\"inside","Image":"back\\slash","Command":"tab\there"}"""
        val rows = DockerParsers.parseJsonLines(json)
        assertEquals(1, rows.size)
        assertEquals("quote\"inside", rows[0]["Names"])
        assertEquals("back\\slash", rows[0]["Image"])
        assertEquals("tab\there", rows[0]["Command"])
    }

    @Test
    fun `json reader handles unicode escape`() {
        val json = """{"Names":"caf\u00e9"}"""
        val rows = DockerParsers.parseJsonLines(json)
        assertEquals("café", rows[0]["Names"])
    }

    @Test
    fun `json reader handles nested arrays`() {
        val json = """{"Ports":[{"host":80}],"Names":"x"}"""
        val rows = DockerParsers.parseJsonLines(json)
        assertEquals(1, rows.size)
        assertTrue(rows[0]["Ports"] is List<*>)
    }

    @Test
    fun `empty input yields nothing`() {
        assertTrue(DockerParsers.parseContainers("").isEmpty())
        assertTrue(DockerParsers.parseContainers("   \n  \n").isEmpty())
    }
}
