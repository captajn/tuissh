package com.tuissh.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests.
 *
 * These are the cases that silently break a chat UI: a base URL that already ends in
 * `/v1` gaining a second one, a system prompt left inside `messages` where Anthropic
 * rejects it, and a stream whose final chunk carries only `finish_reason` with no text.
 */
class AiProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- request bodies ---------------------------------------------------

    @Test
    fun `openai body carries model messages and stream flag`() {
        val body = AiProtocol.openAiChatBody(
            model = "gemini-2.5-flash",
            messages = listOf(
                AiProtocol.WireMessage("system", "be brief"),
                AiProtocol.WireMessage("user", "hi"),
            ),
            stream = true,
        )
        val obj = json.parseToJsonElement(body).jsonObject

        assertEquals("gemini-2.5-flash", obj["model"]?.jsonPrimitive?.content)
        assertEquals("true", obj["stream"]?.jsonPrimitive?.content)
        val messages = obj["messages"]?.jsonArray!!
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `openai body includes tools in the function shape`() {
        val body = AiProtocol.openAiChatBody(
            model = "m",
            messages = listOf(AiProtocol.WireMessage("user", "list files")),
            tools = listOf(
                AiProtocol.ToolSpec(
                    name = "sftp_list",
                    description = "List a directory",
                    parametersJson = """{"type":"object","properties":{"path":{"type":"string"}}}""",
                ),
            ),
        )
        val obj = json.parseToJsonElement(body).jsonObject
        val tools = obj["tools"]?.jsonArray!!
        assertEquals(1, tools.size)

        val function = tools[0].jsonObject["function"]?.jsonObject!!
        assertEquals("sftp_list", function["name"]?.jsonPrimitive?.content)
        assertNotNull("parameters must be the OpenAI field name", function["parameters"])
    }

    @Test
    fun `anthropic body hoists the system prompt and uses input_schema`() {
        val body = AiProtocol.anthropicBody(
            model = "claude-sonnet-4-5",
            messages = listOf(
                AiProtocol.WireMessage("system", "be brief"),
                AiProtocol.WireMessage("user", "hi"),
            ),
            system = "be brief",
            tools = listOf(
                AiProtocol.ToolSpec("sftp_list", "List a directory", """{"type":"object"}"""),
            ),
            maxTokens = 512,
        )
        val obj = json.parseToJsonElement(body).jsonObject

        assertEquals("be brief", obj["system"]?.jsonPrimitive?.content)
        assertEquals("512", obj["max_tokens"]?.jsonPrimitive?.content)

        // The system message must NOT survive inside messages.
        val messages = obj["messages"]?.jsonArray!!
        assertEquals("only the user turn remains", 1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)

        val tools = obj["tools"]?.jsonArray!!
        val tool = tools[0].jsonObject
        assertNotNull("Anthropic names this input_schema", tool["input_schema"])
        assertNull("`parameters` is the OpenAI name and must not appear", tool["parameters"])
    }

    @Test
    fun `anthropic body always sends max_tokens because the API requires it`() {
        val body = AiProtocol.anthropicBody(
            model = "m",
            messages = listOf(AiProtocol.WireMessage("user", "x")),
        )
        val obj = json.parseToJsonElement(body).jsonObject

        assertNotNull(obj["max_tokens"])
    }

    // --- response parsing -------------------------------------------------

    @Test
    fun `parses a plain assistant reply`() {
        val body = """
            {"id":"x","choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
        """.trimIndent()

        val turn = AiProtocol.parseOpenAiResponse(body)

        assertNotNull(turn)
        assertEquals("hello", turn!!.text)
        assertEquals("stop", turn.finishReason)
        assertEquals(10, turn.promptTokens)
        assertTrue(turn.toolCalls.isEmpty())
    }

    @Test
    fun `parses tool calls with their raw argument json`() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":null,
              "tool_calls":[{"id":"call_1","type":"function",
                "function":{"name":"sftp_list","arguments":"{\"path\":\"/var\"}"}}]},
              "finish_reason":"tool_calls"}]}
        """.trimIndent()

        val turn = AiProtocol.parseOpenAiResponse(body)!!

        assertEquals(1, turn.toolCalls.size)
        assertEquals("call_1", turn.toolCalls[0].id)
        assertEquals("sftp_list", turn.toolCalls[0].name)
        assertEquals("""{"path":"/var"}""", turn.toolCalls[0].argumentsJson)
        assertEquals("tool_calls", turn.finishReason)
    }

    @Test
    fun `an error envelope is not mistaken for an empty reply`() {
        val body = """{"error":{"message":"quota exhausted","type":"daily_limit_exceeded"}}"""

        assertNull("an error must not parse as an assistant turn", AiProtocol.parseOpenAiResponse(body))
        assertEquals("quota exhausted", AiProtocol.extractErrorMessage(body))
    }

    @Test
    fun `extractErrorMessage handles a bare message object`() {
        assertEquals("bad key", AiProtocol.extractErrorMessage("""{"message":"bad key"}"""))
    }

    @Test
    fun `extractErrorMessage falls back to the raw body`() {
        val html = "<html>502 Bad Gateway</html>"
        assertTrue(AiProtocol.extractErrorMessage(html).contains("502"))
    }

    @Test
    fun `unparseable body returns null rather than throwing`() {
        assertNull(AiProtocol.parseOpenAiResponse("not json"))
        assertNull(AiProtocol.parseOpenAiResponse(""))
    }

    // --- streaming --------------------------------------------------------

    @Test
    fun `openai stream line yields a delta`() {
        val line = """data: {"choices":[{"delta":{"content":"Hel"},"index":0}]}"""

        val event = AiProtocol.parseOpenAiStreamLine(line)!!

        assertEquals("Hel", event.delta)
        assertTrue(!event.done)
    }

    @Test
    fun `a null finish_reason is not a terminal event`() {
        // Real providers send `"finish_reason":null` on every intermediate chunk.
        // Reading that as "done" cut the stream off after the first token, which the
        // offline fixtures missed because they used the literal "stop".
        val intermediate = """data: {"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}"""

        val event = AiProtocol.parseOpenAiStreamLine(intermediate)!!

        assertEquals("Hel", event.delta)
        assertTrue("a null finish_reason must not end the stream", !event.done)
    }

    @Test
    fun `a null content does not produce the string null`() {
        val line = """data: {"choices":[{"delta":{"content":null},"finish_reason":null}]}"""

        val event = AiProtocol.parseOpenAiStreamLine(line)!!

        assertEquals("", event.delta)
    }

    @Test
    fun `a real terminal chunk ends the stream`() {
        val line = """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""

        assertTrue(AiProtocol.parseOpenAiStreamLine(line)!!.done)
    }
    @Test
    fun `openai stream recognises the DONE sentinel`() {
        val event = AiProtocol.parseOpenAiStreamLine("data: [DONE]")!!
        assertTrue(event.done)
    }

    @Test
    fun `openai stream reports an error frame`() {
        val line = """data: {"error":{"message":"rate limited"}}"""

        val event = AiProtocol.parseOpenAiStreamLine(line)!!

        assertEquals("rate limited", event.error)
    }

    @Test
    fun `non-data lines are ignored`() {
        assertNull(AiProtocol.parseOpenAiStreamLine(""))
        assertNull(AiProtocol.parseOpenAiStreamLine(": keepalive"))
        assertNull(AiProtocol.parseOpenAiStreamLine("event: message"))
    }

    @Test
    fun `openai stream surfaces tool-call fragments`() {
        val line = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"sftp_list","arguments":"{\"pa"}}]}}]}"""

        val event = AiProtocol.parseOpenAiStreamLine(line)!!

        assertNotNull(event.toolCallDelta)
        assertEquals(0, event.toolCallDelta!!.index)
        assertEquals("sftp_list", event.toolCallDelta!!.name)
        assertEquals("""{"pa""", event.toolCallDelta!!.argumentsFragment)
    }

    @Test
    fun `anthropic stream yields text from content_block_delta`() {
        val line = """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}"""

        assertEquals("Hi", AiProtocol.parseAnthropicStreamLine(line)!!.delta)
    }

    @Test
    fun `anthropic stream ends on message_stop`() {
        val line = """data: {"type":"message_stop"}"""
        assertTrue(AiProtocol.parseAnthropicStreamLine(line)!!.done)
    }

    @Test
    fun `anthropic stream reports an error event`() {
        val line = """data: {"type":"error","error":{"message":"overloaded"}}"""
        assertEquals("overloaded", AiProtocol.parseAnthropicStreamLine(line)!!.error)
    }

    // --- URLs and headers -------------------------------------------------

    @Test
    fun `chat path does not double the v1 segment`() {
        assertEquals(
            "https://dc-ai.dabeecao.org/v1/chat/completions",
            AiProtocol.chatPath(AiProtocol.PROTOCOL_OPENAI_CHAT, "https://dc-ai.dabeecao.org/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiProtocol.chatPath(AiProtocol.PROTOCOL_OPENAI_CHAT, "https://api.openai.com"),
        )
        assertEquals(
            "https://api.chjbi.net/v1/chat/completions",
            AiProtocol.chatPath(AiProtocol.PROTOCOL_OPENAI_CHAT, "https://api.chjbi.net/v1/"),
        )
    }

    @Test
    fun `anthropic chat path handles both base shapes`() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AiProtocol.chatPath(AiProtocol.PROTOCOL_ANTHROPIC, "https://api.anthropic.com"),
        )
        assertEquals(
            "https://dc-ai.dabeecao.org/v1/messages",
            AiProtocol.chatPath(AiProtocol.PROTOCOL_ANTHROPIC, "https://dc-ai.dabeecao.org/v1"),
        )
    }

    @Test
    fun `openai headers use a bearer token`() {
        val headers = AiProtocol.headersFor(AiProtocol.PROTOCOL_OPENAI_CHAT, "sk-123")

        assertEquals("Bearer sk-123", headers["authorization"])
        assertNull("Anthropic fields must not leak into an OpenAI request", headers["x-api-key"])
    }

    @Test
    fun `anthropic headers use x-api-key and a version`() {
        val headers = AiProtocol.headersFor(AiProtocol.PROTOCOL_ANTHROPIC, "sk-ant")

        assertEquals("sk-ant", headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        assertNull(headers["authorization"])
    }

    @Test
    fun `a null key sends no auth header at all`() {
        val headers = AiProtocol.headersFor(AiProtocol.PROTOCOL_OPENAI_CHAT, null)
        assertNull(headers["authorization"])
    }

    // --- model list -------------------------------------------------------

    @Test
    fun `parses the model list`() {
        val body = """{"object":"list","data":[{"id":"gemini-2.5-flash","owned_by":"google"},{"id":"gpt-4o"}]}"""

        val models = AiProtocol.parseModels(body)

        assertEquals(2, models.size)
        assertEquals("gemini-2.5-flash", models[0].id)
        assertEquals("google", models[0].ownedBy)
        assertNull(models[1].ownedBy)
    }

    @Test
    fun `a malformed model list yields nothing rather than throwing`() {
        assertTrue(AiProtocol.parseModels("not json").isEmpty())
        assertTrue(AiProtocol.parseModels("""{"object":"list"}""").isEmpty())
    }
}
