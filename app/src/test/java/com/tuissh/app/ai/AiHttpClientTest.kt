package com.tuissh.app.ai

import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Transport tests against a local HTTP server.
 *
 * A real socket is used rather than a mocked OkHttp client because the parts most
 * likely to be wrong are the ones a mock skips: URL construction, header placement,
 * and whether an SSE body is read line by line and terminated. The server here speaks
 * just enough HTTP to exercise those.
 */
class AiHttpClientTest {

    /**
     * Serves one canned response then stops.
     *
     * Hand-rolled instead of pulling in MockWebServer: the project has no test-server
     * dependency, and one response per case is all these assertions need.
     */
    private fun serveOnce(
        statusLine: String,
        headers: Map<String, String>,
        body: String,
        onRequest: (String) -> Unit = {},
    ): Pair<Int, Thread> {
        val socket = ServerSocket(0)
        val latch = CountDownLatch(1)

        val thread = Thread {
            try {
                socket.use { server ->
                    server.soTimeout = 10_000
                    server.accept().use { client ->
                        client.soTimeout = 10_000
                        val input = client.getInputStream().bufferedReader()
                        val requestLine = input.readLine().orEmpty()

                        // Read headers up to the blank line, then the body by
                        // Content-Length. Without draining the body the client can
                        // block writing it while we are already replying.
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                        }
                        val bodyText = if (contentLength > 0) {
                            val buffer = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val n = input.read(buffer, read, contentLength - read)
                                if (n < 0) break
                                read += n
                            }
                            String(buffer, 0, read)
                        } else {
                            ""
                        }
                        onRequest(requestLine + "\n" + bodyText)

                        val payload = body.toByteArray(Charsets.UTF_8)
                        val out = client.getOutputStream()
                        val headerText = buildString {
                            append("HTTP/1.1 ").append(statusLine).append("\r\n")
                            for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
                            append("Content-Length: ").append(payload.size).append("\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(headerText.toByteArray(Charsets.US_ASCII))
                        out.write(payload)
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                // A client that closes early is a valid outcome in the error cases.
            } finally {
                latch.countDown()
            }
        }
        thread.isDaemon = true
        thread.start()
        return socket.localPort to thread
    }

    @Test
    fun `a successful completion returns the assistant text`() = runBlocking {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":"xin chao"},
             "finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}
        """.trimIndent()
        var seen = ""
        val (port, thread) = serveOnce(
            "200 OK",
            mapOf("Content-Type" to "application/json"),
            body,
        ) { seen = it }

        val client = AiHttpClient("http://127.0.0.1:$port/v1", AiProtocol.PROTOCOL_OPENAI_CHAT, "sk-test")
        val result = client.complete("m", listOf(AiProtocol.WireMessage("user", "hi")))

        assertTrue("expected ok, got $result", result is NeoResult.Ok)
        assertEquals("xin chao", result.getOrThrow().text)
        assertTrue("request must hit the chat path", seen.startsWith("POST /v1/chat/completions"))
        assertTrue("body must carry the model", seen.contains("\"model\":\"m\""))
        thread.join(2_000)
    }

    @Test
    fun `a 401 becomes a typed AiHttp error carrying the provider message`() = runBlocking {
        val body = """{"error":{"message":"invalid api key"}}"""
        val (port, thread) = serveOnce(
            "401 Unauthorized",
            mapOf("Content-Type" to "application/json"),
            body,
        )

        val client = AiHttpClient("http://127.0.0.1:$port/v1", AiProtocol.PROTOCOL_OPENAI_CHAT, "bad")
        val result = client.complete("m", listOf(AiProtocol.WireMessage("user", "hi")))

        assertTrue("expected an error, got $result", result is NeoResult.Err)
        val error = (result as NeoResult.Err).error
        assertTrue(error is NeoError.AiHttp)
        assertEquals(401, (error as NeoError.AiHttp).status)
        assertEquals("invalid api key", error.body)
        thread.join(2_000)
    }

    @Test
    fun `a 429 quota body keeps the provider explanation`() = runBlocking {
        val body = """{"error":{"message":"quota exhausted","type":"daily_limit_exceeded"}}"""
        val (port, thread) = serveOnce(
            "429 Too Many Requests",
            mapOf("Content-Type" to "application/json"),
            body,
        )

        val result = AiHttpClient(
            "http://127.0.0.1:$port/v1",
            AiProtocol.PROTOCOL_OPENAI_CHAT,
            "k",
        ).complete("m", listOf(AiProtocol.WireMessage("user", "hi")))

        val error = (result as NeoResult.Err).error as NeoError.AiHttp
        assertEquals(429, error.status)
        assertTrue(error.body.contains("quota exhausted"))
        thread.join(2_000)
    }

    @Test
    fun `an HTML error page is reported instead of a parse crash`() = runBlocking {
        val (port, thread) = serveOnce(
            "502 Bad Gateway",
            mapOf("Content-Type" to "text/html"),
            "<html><body>502 Bad Gateway</body></html>",
        )

        val result = AiHttpClient(
            "http://127.0.0.1:$port/v1",
            AiProtocol.PROTOCOL_OPENAI_CHAT,
            "k",
        ).complete("m", listOf(AiProtocol.WireMessage("user", "hi")))

        val error = (result as NeoResult.Err).error as NeoError.AiHttp
        assertEquals(502, error.status)
        assertTrue("the raw body must survive so the user can see it", error.body.contains("Bad Gateway"))
        thread.join(2_000)
    }

    @Test
    fun `streaming emits each delta in order and stops at DONE`() = runBlocking {
        val sse = buildString {
            append("""data: {"choices":[{"delta":{"content":"Xin "}}]}""").append("\n\n")
            append("""data: {"choices":[{"delta":{"content":"chao"}}]}""").append("\n\n")
            append("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""").append("\n\n")
            append("data: [DONE]").append("\n\n")
        }
        val (port, thread) = serveOnce(
            "200 OK",
            mapOf("Content-Type" to "text/event-stream"),
            sse,
        )

        val client = AiHttpClient("http://127.0.0.1:$port/v1", AiProtocol.PROTOCOL_OPENAI_CHAT, "k")
        val events = withTimeout(20_000) {
            client.stream("m", listOf(AiProtocol.WireMessage("user", "hi"))).toList()
        }

        val text = events.joinToString("") { it.delta }
        assertEquals("Xin chao", text)
        assertTrue("the stream must terminate", events.any { it.done })
        thread.join(2_000)
    }

    @Test
    fun `streaming surfaces an inline error frame`() = runBlocking {
        val sse = """data: {"error":{"message":"backend down"}}""" + "\n\n"
        val (port, thread) = serveOnce(
            "200 OK",
            mapOf("Content-Type" to "text/event-stream"),
            sse,
        )

        val events = withTimeout(20_000) {
            AiHttpClient("http://127.0.0.1:$port/v1", AiProtocol.PROTOCOL_OPENAI_CHAT, "k")
                .stream("m", listOf(AiProtocol.WireMessage("user", "hi")))
                .toList()
        }

        assertTrue(events.any { it.error == "backend down" })
        thread.join(2_000)
    }

    @Test
    fun `an unreachable host is a network error not a crash`() = runBlocking {
        // Port 1 is reserved and never listening, so the connect fails immediately.
        val result = AiHttpClient(
            "http://127.0.0.1:1/v1",
            AiProtocol.PROTOCOL_OPENAI_CHAT,
            "k",
        ).complete("m", listOf(AiProtocol.WireMessage("user", "hi")))

        assertTrue(result is NeoResult.Err)
        assertTrue((result as NeoResult.Err).error is NeoError.AiHttp)
    }

    @Test
    fun `an invalid base url is rejected before any socket work`() = runBlocking {
        val result = AiHttpClient("not a url", AiProtocol.PROTOCOL_OPENAI_CHAT, "k")
            .complete("m", listOf(AiProtocol.WireMessage("user", "hi")))

        assertTrue(result is NeoResult.Err)
        assertTrue((result as NeoResult.Err).error is NeoError.AiNotConfigured)
    }

    @Test
    fun `model listing reads the data array`() = runBlocking {
        val body = """{"object":"list","data":[{"id":"gemini-2.5-flash","owned_by":"google"}]}"""
        var seen = ""
        val (port, thread) = serveOnce(
            "200 OK",
            mapOf("Content-Type" to "application/json"),
            body,
        ) { seen = it }

        val result = AiHttpClient("http://127.0.0.1:$port/v1", AiProtocol.PROTOCOL_OPENAI_CHAT, "k")
            .listModels()

        assertTrue(result is NeoResult.Ok)
        assertEquals("gemini-2.5-flash", result.getOrThrow().single().id)
        assertTrue("models is a GET", seen.startsWith("GET /v1/models"))
        thread.join(2_000)
    }
}
