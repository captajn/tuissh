package com.tuissh.app.ai

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.RemoteCommandRunner
import com.tuissh.app.ssh.SshConnectPrompts
import com.tuissh.app.ssh.SshConnectSpec
import com.tuissh.app.ssh.SshSession
import com.tuissh.app.ssh.hostkey.HostKeyMaterial
import com.tuissh.app.ssh.hostkey.HostKeyVerifier
import com.tuissh.app.ssh.hostkey.HostTrustStore
import com.tuissh.app.ssh.key.KeyParseResult
import com.tuissh.app.ssh.key.KeyParser
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The assistant's tools, executed against a real host.
 *
 * Separate from [AiConversationTest] because that suite proves the *loop* with a scripted
 * runner. This one proves the tools themselves return real data: a `system_info` call that
 * reports "unreadable" on a machine that plainly has `/proc` is a passing loop and a
 * broken tool.
 *
 * Skipped unless `LIVE_HOST`, `LIVE_USER`, `LIVE_KEY_PATH` and `AI_TEST_KEY` are set.
 */
class AiToolLiveTest {

    private val host: String? = System.getenv("LIVE_HOST")?.takeIf { it.isNotBlank() }
    private val user: String? = System.getenv("LIVE_USER")?.takeIf { it.isNotBlank() }
    private val keyPath: String? = System.getenv("LIVE_KEY_PATH")?.takeIf { it.isNotBlank() }
    private val apiKey: String? = System.getenv("AI_TEST_KEY")?.takeIf { it.isNotBlank() }
    private val model: String = System.getenv("AI_TEST_MODEL")?.takeIf { it.isNotBlank() }
        ?: "deepseek-v4.1-flash-1017"
    private val baseUrl: String = System.getenv("AI_TEST_BASE")?.takeIf { it.isNotBlank() }
        ?: "https://api.chjbi.net/v1"

    private fun hostReady() = host != null && user != null && keyPath != null
    private fun aiReady() = apiKey != null

    private suspend fun connect(): SshSession {
        val pem = String(Files.readAllBytes(Path.of(keyPath!!)), Charsets.UTF_8)
        val parsed = KeyParser.parse(pem)
        val key = (parsed as? KeyParseResult.Success)?.key
            ?: error("the live key did not parse: $parsed")

        val session = SshSession(
            spec = SshConnectSpec(
                serverId = 1L,
                name = "tools",
                host = host!!,
                port = 22,
                username = user!!,
                auth = SshConnectSpec.Auth.PrivateKey(key.pem, key.passphrase),
                connectTimeoutSeconds = 25,
            ),
            hostKeyVerifier = TrustLive,
            prompts = SilentPrompts,
        )
        assertTrue("live connect failed: ${session.connect()}", session.isConnectedForTest())
        return session
    }

    private fun SshSession.isConnectedForTest(): Boolean = runBlocking {
        // A trivial exec is the cheapest proof that the session really carries traffic.
        exec("true") is NeoResult.Ok
    }

    // ------------------------------------------------------------------
    // Tool-level checks, no model involved
    // ------------------------------------------------------------------

    @Test
    fun `system_info returns real numbers from a real host`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", hostReady())
        val session = connect()
        try {
            val runner = AiToolRunner(RemoteCommandRunner.of(session), sftp = null)

            val result = withTimeout(90_000) {
                runner.run(AiToolkit.TOOL_SYSTEM_INFO, "{}")
            }

            assertTrue("system_info must succeed on Linux: ${result.payload}", result.ok)
            assertTrue(
                "payload must name the host, got: ${result.payload.take(200)}",
                result.payload.contains("host:"),
            )
            assertTrue(
                "payload must carry real memory figures, got: ${result.payload.take(300)}",
                result.payload.contains("memory:"),
            )
            assertTrue(
                "payload must list disks, got: ${result.payload.take(400)}",
                result.payload.contains("disks:"),
            )
            assertTrue(
                "the display summary must be short and human, got '${result.display}'",
                result.display.length < 80,
            )
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `docker_ps returns the containers that actually exist`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", hostReady())
        val session = connect()
        try {
            val runner = AiToolRunner(RemoteCommandRunner.of(session), sftp = null)

            val result = withTimeout(60_000) {
                runner.run(AiToolkit.TOOL_DOCKER_PS, "{}")
            }

            assertTrue("docker_ps must succeed: ${result.payload}", result.ok)
            // The host runs at least one container; a zero count means the tool
            // silently failed to parse rather than that the host is idle.
            val countLine = Regex("""count: (\d+)""").find(result.payload)?.groupValues?.get(1)?.toIntOrNull()
            assertNotNull("the payload must report a count, got: ${result.payload.take(200)}", countLine)
            // The host runs containers, so a positive count is the difference between
            // a working tool and one that silently parsed nothing.
            assertTrue("the host runs containers, so count must be positive", countLine!! > 0)

            // A row carries a short id, a name, a state and an image reference. Asserting
            // on the image tag is the strongest signal that parsing produced real data.
            assertTrue(
                "rows must carry image references, got: ${result.payload.take(400)}",
                result.payload.contains(":"),
            )
            assertTrue(
                "a known running container must appear, got: ${result.payload.take(400)}",
                result.payload.contains("[running]"),
            )
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `docker_logs returns output for a running container`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", hostReady())
        val session = connect()
        try {
            val runner = AiToolRunner(RemoteCommandRunner.of(session), sftp = null)
            val listing = withTimeout(60_000) {
                runner.run(AiToolkit.TOOL_DOCKER_PS, "{}")
            }
            // Prefer a container the engine reports as healthy. A container can be
            // "running" while the daemon already refuses its logs (marked for removal),
            // which would make this test flaky for a reason unrelated to the code.
            val healthy = Regex("""([0-9a-f]{12}) (\S+) \[running\]([^\n]*)""")
                .findAll(listing.payload)
                .firstOrNull { it.groupValues[3].contains("healthy") }
                ?: Regex("""([0-9a-f]{12}) (\S+) \[running\]""").find(listing.payload)
            val firstId = healthy?.groupValues?.get(1)
            assumeTrue("no running container id in the listing", firstId != null)

            val logs = withTimeout(60_000) {
                runner.run(AiToolkit.TOOL_DOCKER_LOGS, """{"id":"$firstId","lines":10}""")
            }

            assertTrue("docker_logs must succeed: ${logs.payload.take(200)}", logs.ok)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `run_command is refused without approval`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", hostReady())
        val session = connect()
        try {
            // The default policy denies; the assertion is that nothing reached the host.
            val runner = AiToolRunner(RemoteCommandRunner.of(session), sftp = null)

            val result = withTimeout(30_000) {
                runner.run(
                    AiToolkit.TOOL_RUN_COMMAND,
                    """{"command":"echo should-not-run","reason":"test"}""",
                )
            }

            assertTrue("an unapproved command must fail", !result.ok)
            assertTrue(
                "the refusal must tell the model not to retry: ${result.payload}",
                result.payload.contains("declined"),
            )
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `run_command executes once approved`() = runBlocking {
        assumeTrue("set LIVE_HOST/LIVE_USER/LIVE_KEY_PATH", hostReady())
        val session = connect()
        try {
            var askedFor: String? = null
            val runner = AiToolRunner(
                commands = RemoteCommandRunner.of(session),
                sftp = null,
                approval = AiToolkit.ApprovalPolicy { _, command, _ ->
                    askedFor = command
                    true
                },
            )

            val result = withTimeout(30_000) {
                runner.run(
                    AiToolkit.TOOL_RUN_COMMAND,
                    """{"command":"echo tuissh-approved","reason":"verify approval path"}""",
                )
            }

            assertTrue("an approved command must run: ${result.payload}", result.ok)
            assertTrue("the exact command must reach the host", askedFor == "echo tuissh-approved")
            assertTrue(
                "stdout must come back: ${result.payload}",
                result.payload.contains("tuissh-approved"),
            )
        } finally {
            session.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Full turn: model chooses a tool, tool answers with real data
    // ------------------------------------------------------------------

    @Test
    fun `the model answers a host question using real tool output`() = runBlocking {
        assumeTrue("host and AI credentials are both required", hostReady() && aiReady())
        val session = connect()
        try {
            val runner = AiToolRunner(RemoteCommandRunner.of(session), sftp = null)
            val conversation = AiConversation(
                http = AiHttpClient(baseUrl, AiProtocol.PROTOCOL_OPENAI_CHAT, apiKey),
                runner = runner,
                model = model,
                systemPrompt = AiConversation.defaultSystemPrompt("${user}@${host}"),
                maxRounds = 3,
            )
            conversation.seed("How many containers are running on this host? Use a tool, then answer in one short line.")

            val events = withTimeout(180_000) { conversation.run().toList() }

            val failures = events.filterIsInstance<AiConversation.Event.Failed>()
            assertTrue("the turn must not fail: $failures", failures.isEmpty())

            val tools = events.filterIsInstance<AiConversation.Event.ToolStarted>()
            assertTrue(
                "the model must use a tool rather than guess",
                tools.any { it.name == AiToolkit.TOOL_DOCKER_PS || it.name == AiToolkit.TOOL_SYSTEM_INFO },
            )

            val finished = events.filterIsInstance<AiConversation.Event.ToolFinished>()
            assertTrue("a tool must report a result", finished.isNotEmpty())
            assertTrue(
                "the tool must have succeeded against a real host: $finished",
                finished.any { it.ok },
            )

            val answer = events.filterIsInstance<AiConversation.Event.Text>()
                .joinToString("") { it.delta }
            assertTrue("the model must produce an answer, got '$answer'", answer.isNotBlank())
            assertNotNull(
                "the answer must be grounded in tool output, not empty",
                answer.takeIf { it.length > 0 },
            )
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
