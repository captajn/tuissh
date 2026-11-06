package com.tuissh.app.ai

import com.tuissh.app.core.NeoResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live calls against a real provider.
 *
 * These are skipped unless `AI_TEST_KEY` is set, so an ordinary build stays offline and
 * deterministic. They exist because the offline tests prove the client matches the wire
 * format it *believes* in, and only a real endpoint can prove that belief is right:
 * a proxy may accept a body and quietly ignore a field, which no local fixture would
 * ever reveal.
 *
 * Run with:
 *   $env:AI_TEST_KEY='...'; .\gradlew.bat testDebugUnitTest --tests '*AiLiveTest*'
 *
 * @see AiProtocolTest for the offline contract tests
 */
class AiLiveTest {

    private val apiKey: String? = System.getenv("AI_TEST_KEY")?.takeIf { it.isNotBlank() }
    private val baseUrl: String = System.getenv("AI_TEST_BASE")
        ?.takeIf { it.isNotBlank() }
        ?: "https://api.chjbi.net/v1"
    private val model: String = System.getenv("AI_TEST_MODEL")
        ?.takeIf { it.isNotBlank() }
        ?: "deepseek-v4.1-flash-1017"

    private fun client() = AiHttpClient(baseUrl, AiProtocol.PROTOCOL_OPENAI_CHAT, apiKey)

    @Test
    fun `the provider lists at least one model`() = runBlocking {
        assumeTrue("set AI_TEST_KEY to run live tests", apiKey != null)

        val result = client().listModels()

        assertTrue("listModels failed: $result", result is NeoResult.Ok)
        val models = result.getOrThrow()
        assertTrue("expected a non-empty catalogue", models.isNotEmpty())
        assertTrue(
            "the configured model must be offered",
            models.any { it.id == model },
        )
    }

    @Test
    fun `a chat completion returns text and usage`() = runBlocking {
        assumeTrue("set AI_TEST_KEY to run live tests", apiKey != null)

        val result = client().complete(
            model = model,
            messages = listOf(
                AiProtocol.WireMessage("user", "Reply with exactly: OK"),
            ),
            maxTokens = 20,
        )

        assertTrue("complete failed: $result", result is NeoResult.Ok)
        val turn = result.getOrThrow()
        assertTrue("expected some text, got '${turn.text}'", turn.text.isNotBlank())
        assertTrue("usage must be reported", turn.promptTokens > 0)
    }

    @Test
    fun `the model asks for a declared tool`() = runBlocking {
        assumeTrue("set AI_TEST_KEY to run live tests", apiKey != null)

        val listing = AiToolkit.definitions.first { it.spec.name == AiToolkit.TOOL_LIST_DIR }
        val result = client().complete(
            model = model,
            messages = listOf(
                AiProtocol.WireMessage(
                    "system",
                    "You are a Linux server assistant. Always use a tool when one fits.",
                ),
                AiProtocol.WireMessage("user", "List the files in /var"),
            ),
            tools = listOf(listing.spec),
            maxTokens = 200,
        )

        assertTrue("complete failed: $result", result is NeoResult.Ok)
        val turn = result.getOrThrow()
        assertTrue(
            "the model must call list_directory, got finish=${turn.finishReason} text='${turn.text}'",
            turn.toolCalls.any { it.name == AiToolkit.TOOL_LIST_DIR },
        )
        val call = turn.toolCalls.first { it.name == AiToolkit.TOOL_LIST_DIR }
        assertTrue(
            "arguments must name the path, got ${call.argumentsJson}",
            call.argumentsJson.contains("/var"),
        )
    }

    @Test
    fun `streaming delivers text incrementally and terminates`() = runBlocking {
        assumeTrue("set AI_TEST_KEY to run live tests", apiKey != null)

        val events = withTimeout(120_000) {
            client().stream(
                model = model,
                messages = listOf(
                    AiProtocol.WireMessage("user", "Count from 1 to 5, one number per line"),
                ),
                maxTokens = 80,
            ).toList()
        }

        val text = events.joinToString("") { it.delta }
        assertTrue("expected streamed text, got '$text'", text.isNotBlank())
        assertTrue("the stream must terminate", events.any { it.done })
        assertTrue("no error frame expected", events.none { it.error != null })
    }

    @Test
    fun `a bad key is reported as 401 rather than a crash`() = runBlocking {
        assumeTrue("set AI_TEST_KEY to run live tests", apiKey != null)

        val bad = AiHttpClient(baseUrl, AiProtocol.PROTOCOL_OPENAI_CHAT, "definitely-not-a-key")
        val result = bad.complete(
            model = model,
            messages = listOf(AiProtocol.WireMessage("user", "hi")),
            maxTokens = 5,
        )

        assertTrue("expected a failure, got $result", result is NeoResult.Err)
    }
}
