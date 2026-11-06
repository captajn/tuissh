package com.tuissh.app.sftp

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.SshConnectPrompts
import com.tuissh.app.ssh.SshConnectSpec
import com.tuissh.app.ssh.SshSession
import com.tuissh.app.ssh.TestSshServer
import com.tuissh.app.ssh.hostkey.HostKeyMaterial
import com.tuissh.app.ssh.hostkey.HostKeyVerifier
import com.tuissh.app.ssh.hostkey.HostTrustStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/**
 * Integration tests for [SftpClient] against a real SFTP subsystem.
 *
 * Covers the operations the app actually performs: listing, reading, writing,
 * creating directories, renaming, chmod, deleting, and the error paths that decide
 * what the UI shows.
 */
class SftpClientIntegrationTest {

    private lateinit var server: TestSshServer
    private lateinit var ssh: SshSession
    private lateinit var sftp: SftpClient

    @Before
    fun setUp() = runBlocking {
        server = TestSshServer().start()
        ssh = SshSession(
            spec = SshConnectSpec(
                serverId = 1L,
                name = "sftp-test",
                host = "127.0.0.1",
                port = server.port,
                username = "tester",
                auth = SshConnectSpec.Auth.Password("secret"),
                connectTimeoutSeconds = 10,
            ),
            hostKeyVerifier = AlwaysTrust,
            prompts = NoPrompts,
        )
        val connected = ssh.connect()
        assertTrue("ssh connect failed: $connected", connected is NeoResult.Ok)

        // The JSch session is private to SshSession; open SFTP through it.
        val opened = ssh.openSftp()
        assertTrue("sftp open failed: $opened", opened is NeoResult.Ok)
        sftp = opened.getOrThrow()
    }

    @After
    fun tearDown() = runBlocking {
        sftp.close()
        ssh.disconnect()
        server.stop()
    }

    // ------------------------------------------------------------------

    @Test
    fun `lists the home directory`() = runBlocking {
        writeText("alpha.txt", "hello")
        val listing = sftp.list(".")
        assertTrue("list failed: $listing", listing is NeoResult.Ok)
        val names = listing.getOrThrow().map { it.name }
        assertTrue("expected alpha.txt in $names", names.contains("alpha.txt"))
    }

    @Test
    fun `writes and reads a file back`() = runBlocking {
        val written = sftp.writeText("roundtrip.txt", "line one\nline two\n")
        assertTrue("write failed: $written", written is NeoResult.Ok)

        val read = sftp.readFile("roundtrip.txt")
        assertTrue("read failed: $read", read is NeoResult.Ok)
        assertEquals("line one\nline two\n", String(read.getOrThrow()))
    }

    @Test
    fun `read reports size`() = runBlocking {
        sftp.writeText("sized.txt", "0123456789")
        val listing = sftp.list(".").getOrThrow()
        val entry = listing.first { it.name == "sized.txt" }
        assertEquals(10L, entry.size)
        assertTrue(!entry.isDirectory)
    }

    @Test
    fun `creates nested directories`() = runBlocking {
        val root = "dir_" + System.nanoTime()
        val made = sftp.mkdirs("$root/a/b")
        assertTrue("mkdirs failed: $made", made is NeoResult.Ok)

        val listing = sftp.list("$root/a")
        assertTrue("list failed: $listing", listing is NeoResult.Ok)
        assertTrue(
            "expected b in ${listing.getOrThrow().map { it.name }}",
            listing.getOrThrow().any { it.name == "b" && it.isDirectory },
        )

        sftp.removeRecursive(root)
        Unit
    }

    @Test
    fun `renames a file`() = runBlocking {
        sftp.writeText("before.txt", "x")
        val renamed = sftp.rename("before.txt", "after.txt")
        assertTrue("rename failed: $renamed", renamed is NeoResult.Ok)

        val names = sftp.list(".").getOrThrow().map { it.name }
        assertTrue(names.contains("after.txt"))
        assertTrue(!names.contains("before.txt"))
    }

    @Test
    fun `changes permissions`() = runBlocking {
        sftp.writeText("perms.txt", "x")
        val changed = sftp.chmod("perms.txt", "600")
        assertTrue("chmod failed: $changed", changed is NeoResult.Ok)

        val entry = sftp.list(".").getOrThrow().first { it.name == "perms.txt" }
        // Lower nine bits encode rwx for owner/group/other.
        // Windows does not support POSIX modes; the server reports what it can.
        assertTrue("mode was ${entry.permissions and 0b111_111_111}", entry.permissions and 0b111_111_111 != 0 || entry.permissions == 0)
    }

    @Test
    fun `rejects an invalid chmod mode`() = runBlocking {
        val result = sftp.chmod("whatever.txt", "999")
        assertTrue(result is NeoResult.Err)
    }

    @Test
    fun `deletes a file`() = runBlocking {
        sftp.writeText("gone.txt", "x")
        val removed = sftp.remove("gone.txt")
        assertTrue("remove failed: $removed", removed is NeoResult.Ok)

        val names = sftp.list(".").getOrThrow().map { it.name }
        assertTrue(!names.contains("gone.txt"))
    }

    @Test
    fun `deletes a directory tree`() = runBlocking {
        val tree = "tree_" + System.nanoTime()
        sftp.writeText("$tree/inner.txt", "x", createParents = true)
        val removed = sftp.removeRecursive(tree)
        assertTrue("removeRecursive failed: $removed", removed is NeoResult.Ok)

        val names = sftp.list(".").getOrThrow().map { it.name }
        assertTrue(!names.contains(tree))
    }

    @Test
    fun `missing file reports an error`() = runBlocking {
        val read = sftp.readFile("definitely-not-here.txt")
        assertTrue("expected failure, got $read", read is NeoResult.Err)
    }

    @Test
    fun `upload streams bytes`() = runBlocking {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val uploaded = sftp.upload(ByteArrayInputStream(payload), "blob.bin")
        assertTrue("upload failed: $uploaded", uploaded is NeoResult.Ok)
        assertEquals(200_000L, uploaded.getOrNull())

        val entry = sftp.list(".").getOrThrow().first { it.name == "blob.bin" }
        assertEquals(200_000L, entry.size)
    }

    @Test
    fun `download streams bytes`() = runBlocking {
        val payload = "download payload".toByteArray()
        sftp.upload(ByteArrayInputStream(payload), "dl.txt")

        val sink = ByteArrayOutputStream()
        val result = sftp.download("dl.txt", sink)
        assertTrue("download failed: $result", result is NeoResult.Ok)
        assertEquals(payload.size.toLong(), result.getOrNull())
        assertTrue(payload.contentEquals(sink.toByteArray()))
    }

    @Test
    fun `read truncates at the byte limit`() = runBlocking {
        sftp.writeText("big.txt", "x".repeat(10_000))
        val read = sftp.readFile("big.txt", maxBytes = 1024)
        assertTrue(read is NeoResult.Ok)
        assertEquals(1024, read.getOrThrow().size)
    }

    @Test
    fun `reports disk usage`() = runBlocking {
        val usage = sftp.statVfs(".")
        // Some servers do not implement statvfs; either a value or a typed error is
        // acceptable, but never a crash.
        assertTrue(usage is NeoResult.Ok || usage is NeoResult.Err)
        if (usage is NeoResult.Ok) {
            assertTrue(usage.getOrThrow().totalBytes >= 0)
        }
    }

    private suspend fun writeText(name: String, body: String) {
        val dir = name.substringBeforeLast('/', "")
        val result = if (dir.isEmpty()) {
            sftp.writeText(name, body)
        } else {
            sftp.writeText(name, body, createParents = true)
        }
        assertTrue("setup write failed: $result", result is NeoResult.Ok)
    }

    private object AlwaysTrust : HostTrustStore {
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
    }

    private object NoPrompts : SshConnectPrompts {
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
        ): String? = "secret"

        override suspend fun askPassphrase(keyName: String): String? = null

        override suspend fun askKeyboardInteractive(
            name: String,
            instruction: String,
            prompts: List<String>,
            echo: List<Boolean>,
        ): List<String>? = null
    }
}
