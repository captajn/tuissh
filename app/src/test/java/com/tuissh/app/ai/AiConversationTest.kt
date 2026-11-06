package com.tuissh.app.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end behaviour of the model-tool loop.
 *
 * The server here scripts a *sequence* of replies, because every interesting property of
 * the loop only shows up across rounds: the model asks for a tool, the app runs it,
 * sends the result back, and the model answers. A single canned response cannot test
 * that, so these tests drive a real socket that answers differently each time.
 */
class AiConversationTest {

    /**
     * Serves successive SSE responses, one per connection.
     *
     * Each entry is the full body for that round. The nth request gets the nth body;
     * extra requests get an empty stream that ends immediately, so a runaway loop
     * fails fast instead of hanging the test.
     */
    private class ScriptedStreamServer(private val bodies: List<String>) {
        private val counter = AtomicInteger(0)
        private val socket = ServerSocket(0)
        val requests = mutableListOf<String>()
        val port: Int get() = socket.localPort

        private val thread = Thread {
            try {
                socket.use { server ->
                    server.soTimeout = 15_000
                    while (true) {
                        val client = try {
                            server.accept()
                        } catch (_: Exception) {
                            break
                        }
                        client.use { c ->
                            c.soTimeout = 15_000
                            val input = c.getInputStream().bufferedReader()
                            val requestLine = input.readLine().orEmpty()
                            var contentLength = 0
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                                }
                            }
                            val body = if (contentLength > 0) {
                                val buf = CharArray(contentLength)
                                var read = 0
                                while (read < contentLength) {
                                    val n = input.read(buf, read, contentLength - read)
                                    if (n < 0) break
                                    read += n
                                }
                                String(buf, 0, read)
                            } else {
                                ""
                            }
                            synchronized(requests) {
                                requests.add(requestLine + "\n" + body)
                            }

                            val index = counter.getAndIncrement()
                            val payload = bodies.getOrElse(index) { "data: [DONE]\n\n" }
                                .toByteArray(Charsets.UTF_8)

                            val out = c.getOutputStream()
                            out.write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/event-stream\r\n" +
                                        "Content-Length: ${payload.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                    ).toByteArray(Charsets.US_ASCII),
                            )
                            out.write(payload)
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {
                // Closing the server ends the accept loop; that is the normal exit.
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        fun close() {
            runCatching { socket.close() }
            runCatching { thread.join(2_000) }
        }
    }

    private fun textBody(text: String, finish: Boolean = true): String = buildString {
        if (text.isNotEmpty()) {
            append("""data: {"choices":[{"delta":{"content":${quoteForTest(text)}}}]}""").append("\n\n")
        }
        if (finish) {
            append("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""").append("\n\n")
            append("data: [DONE]\n\n")
        }
    }

    private fun toolCallBody(name: String, arguments: String, id: String = "call_1"): String = buildString {
        append(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"$id","function":{"name":"$name","arguments":${quoteForTest(arguments)}}}]}}]}""",
        ).append("\n\n")
        append("""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""").append("\n\n")
        append("data: [DONE]\n\n")
    }

    private fun quoteForTest(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(ch)
            }
        }
        append('"')
    }

    /** Records what the loop asked the runner to do. */
    private class RecordingRunner(
        private val result: AiToolRunner.ToolResult = AiToolRunner.ToolResult(
            ok = true,
            display = "ok",
            payload = "listed 2 files",
        ),
    ) : AiToolRunner(StubRunner, sftp = null) {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun run(name: String, argumentsJson: String): ToolResult {
            calls.add(name to argumentsJson)
            return result
        }
    }

    /** A transport that fails loudly if the scripted runner ever delegates to it. */
    private object StubRunner : com.tuissh.app.ssh.RemoteCommandRunner {
        override suspend fun run(
            command: String,
            timeoutMs: Int,
        ): com.tuissh.app.core.NeoResult<com.tuissh.app.ssh.SshSession.CommandResult> =
            error("the scripted runner must not reach the transport")
    }

    private fun conversation(
        port: Int,
        runner: AiToolRunner,
        maxRounds: Int = 4,
    ): AiConversation = AiConversation(
        http = AiHttpClient(
            baseUrl = "http://127.0.0.1:$port/v1",
            protocol = AiProtocol.PROTOCOL_OPENAI_CHAT,
            apiKey = "k",
        ),
        runner = runner,
        model = "m",
        systemPrompt = "be helpful",
        maxRounds = maxRounds,
    )

    @Test
    fun `a plain answer ends after one round`() = runBlocking {
        val server = ScriptedStreamServer(listOf(textBody("Xin chao")))
        try {
            val runner = RecordingRunner()
            val conv = conversation(server.port, runner)
            conv.seed("hi")

            val events = withTimeout(30_000) { conv.run().toList() }

            val text = events.filterIsInstance<AiConversation.Event.Text>()
                .joinToString("") { it.delta }
            assertEquals("Xin chao", text)
            assertTrue(events.any { it is AiConversation.Event.Done })
            assertTrue("no tool may run for a plain answer", runner.calls.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun `a tool call is executed and the result is fed back for a second round`() = runBlocking {
        val server = ScriptedStreamServer(
            listOf(
                toolCallBody("list_directory", """{"path":"/var"}"""),
                textBody("Co 2 muc trong /var"),
            ),
        )
        try {
            val runner = RecordingRunner()
            val conv = conversation(server.port, runner)
            conv.seed("liet ke /var")

            val events = withTimeout(30_000) { conv.run().toList() }

            assertEquals(1, runner.calls.size)
            assertEquals("list_directory", runner.calls[0].first)
            assertTrue(runner.calls[0].second.contains("/var"))

            assertTrue(events.any { it is AiConversation.Event.ToolStarted })
            val finished = events.filterIsInstance<AiConversation.Event.ToolFinished>()
            assertEquals(1, finished.size)
            assertTrue(finished[0].ok)

            val text = events.filterIsInstance<AiConversation.Event.Text>()
                .joinToString("") { it.delta }
            assertEquals("Co 2 muc trong /var", text)

            val done = events.filterIsInstance<AiConversation.Event.Done>().single()
            assertEquals("two rounds: tool, then answer", 2, done.rounds)

            // The second request must carry the tool result, otherwise the model is
            // answering blind and will repeat the same call forever.
            val secondBody = server.requests.getOrNull(1).orEmpty()
            assertTrue(
                "tool result must be in the follow-up request, saw: ${secondBody.take(400)}",
                secondBody.contains("tool_result"),
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `an unknown tool is reported and never executed`() = runBlocking {
        val server = ScriptedStreamServer(
            listOf(
                toolCallBody("rm_rf_everything", "{}"),
                textBody("Xin loi, toi khong co cong cu do."),
            ),
        )
        try {
            val runner = RecordingRunner()
            val conv = conversation(server.port, runner)
            conv.seed("xoa het")

            val events = withTimeout(30_000) { conv.run().toList() }

            assertTrue(
                "a name outside the toolkit must be surfaced",
                events.any { it is AiConversation.Event.UnknownTool },
            )
            assertTrue("it must not reach the runner", runner.calls.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun `the round cap stops a model that keeps calling tools`() = runBlocking {
        // Every round asks for a tool and never answers, which is what a model stuck in
        // a retry loop looks like.
        val loop = List(12) { toolCallBody("list_directory", """{"path":"/"}""") }
        val server = ScriptedStreamServer(loop)
        try {
            val runner = RecordingRunner(
                AiToolRunner.ToolResult(ok = false, display = "failed", payload = "error: nope"),
            )
            val conv = conversation(server.port, runner, maxRounds = 3)
            conv.seed("go")

            val events = withTimeout(40_000) { conv.run().toList() }

            val done = events.filterIsInstance<AiConversation.Event.Done>().single()
            assertEquals("must stop at the cap", 3, done.rounds)
            assertEquals("one tool call per round", 3, runner.calls.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `an inline stream error ends the turn with a failure`() = runBlocking {
        val server = ScriptedStreamServer(
            listOf("""data: {"error":{"message":"backend down"}}""" + "\n\n"),
        )
        try {
            val conv = conversation(server.port, RecordingRunner())
            conv.seed("hi")

            val events = withTimeout(30_000) { conv.run().toList() }

            assertTrue(
                "an error frame must surface as Failed",
                events.any { it is AiConversation.Event.Failed },
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `a failed tool result is still fed back so the model can react`() = runBlocking {
        val server = ScriptedStreamServer(
            listOf(
                toolCallBody("read_file", """{"path":"/nope"}"""),
                textBody("File khong ton tai."),
            ),
        )
        try {
            val runner = RecordingRunner(
                AiToolRunner.ToolResult(ok = false, display = "khong doc duoc", payload = "error: no such file"),
            )
            val conv = conversation(server.port, runner)
            conv.seed("doc file")

            val events = withTimeout(30_000) { conv.run().toList() }

            val finished = events.filterIsInstance<AiConversation.Event.ToolFinished>().single()
            assertTrue("the failure must be reported, not hidden", !finished.ok)

            val secondBody = server.requests.getOrNull(1).orEmpty()
            assertTrue(
                "the error text must reach the model",
                secondBody.contains("no such file"),
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `the system prompt is sent on every round`() = runBlocking {
        val server = ScriptedStreamServer(
            listOf(
                toolCallBody("system_info", "{}"),
                textBody("done"),
            ),
        )
        try {
            val conv = conversation(server.port, RecordingRunner())
            conv.seed("status?")

            withTimeout(30_000) { conv.run().toList() }

            // Instructions in the first request only would be lost after a tool round.
            for ((index, request) in server.requests.withIndex()) {
                assertTrue(
                    "request $index must carry the system prompt",
                    request.contains("be helpful"),
                )
            }
        } finally {
            server.close()
        }
    }
}
