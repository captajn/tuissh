package com.tuissh.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wire format for the three protocols the app speaks.
 *
 * DC AI and CHJBI both accept the OpenAI chat-completions shape, so most of the work
 * is one adapter. Anthropic differs enough in auth header, body and stream events
 * that it gets its own encoder rather than a translation layer over the OpenAI one —
 * translating would lose fields and produce requests that look right but are subtly
 * wrong.
 *
 * Everything here is pure: it builds JSON and parses JSON. No HTTP, so it is all
 * unit-testable.
 */
object AiProtocol {

    /** Protocols the preset list can select. */
    const val PROTOCOL_OPENAI_CHAT = "openai_chat"
    const val PROTOCOL_ANTHROPIC = "anthropic_messages"

    /** A message in the conversation, already flattened to the wire shape. */
    data class WireMessage(val role: String, val content: String)

    /** One tool the model may call. */
    data class ToolSpec(
        val name: String,
        val description: String,
        /** JSON Schema object for the parameters. */
        val parametersJson: String,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val parser: Json get() = json

    // =====================================================================
    // Request bodies
    // =====================================================================

    /**
     * OpenAI chat-completions body.
     *
     * `stream` is a parameter rather than a constant because the caller decides; the
     * non-streaming path is still needed for tool-calling rounds where the whole
     * message must be validated before use.
     */
    fun openAiChatBody(
        model: String,
        messages: List<WireMessage>,
        tools: List<ToolSpec> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        stream: Boolean = false,
    ): String = buildJsonObject {
        put("model", model)
        put(
            "messages",
            buildJsonArray {
                for (m in messages) {
                    add(
                        buildJsonObject {
                            put("role", m.role)
                            put("content", m.content)
                        },
                    )
                }
            },
        )
        put("stream", stream)
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        if (tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    for (t in tools) {
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", t.name)
                                        put("description", t.description)
                                        put("parameters", json.parseToJsonElement(t.parametersJson))
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }.toString()

    /**
     * Anthropic messages body.
     *
     * Three differences that matter and are easy to get wrong:
     *  - the system prompt is a top-level field, not a message with role `system`;
     *  - `max_tokens` is required;
     *  - tools use `input_schema`, not `parameters`.
     */
    fun anthropicBody(
        model: String,
        messages: List<WireMessage>,
        system: String? = null,
        tools: List<ToolSpec> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int = 4096,
        stream: Boolean = false,
    ): String = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("stream", stream)
        system?.takeIf { it.isNotBlank() }?.let { put("system", it) }
        temperature?.let { put("temperature", it) }
        put(
            "messages",
            buildJsonArray {
                // Anthropic rejects a system role inside messages, so it is filtered
                // here rather than trusting callers to keep it out.
                for (m in messages.filter { it.role != "system" }) {
                    add(
                        buildJsonObject {
                            put("role", m.role)
                            put("content", m.content)
                        },
                    )
                }
            },
        )
        if (tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    for (t in tools) {
                        add(
                            buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("input_schema", json.parseToJsonElement(t.parametersJson))
                            },
                        )
                    }
                },
            )
        }
    }.toString()

    // =====================================================================
    // Response parsing
    // =====================================================================

    @Serializable
    private data class OpenAiResponse(
        val choices: List<Choice> = emptyList(),
        val error: ApiError? = null,
        val usage: Usage? = null,
    )

    @Serializable
    private data class Choice(
        val message: ChoiceMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    private data class ChoiceMessage(
        val role: String? = null,
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    )

    @Serializable
    private data class ToolCall(
        val id: String? = null,
        val type: String? = null,
        val function: ToolFunction? = null,
    )

    @Serializable
    private data class ToolFunction(val name: String? = null, val arguments: String? = null)

    @Serializable
    private data class ApiError(val message: String? = null, val type: String? = null, val code: String? = null)

    @Serializable
    private data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0,
    )

    /** What one completed assistant turn produced. */
    data class AssistantTurn(
        val text: String,
        val toolCalls: List<ToolCallRequest>,
        val finishReason: String?,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    )

    data class ToolCallRequest(
        val id: String,
        val name: String,
        /** Raw JSON string of the arguments, exactly as the model produced it. */
        val argumentsJson: String,
    )

    /**
     * Parse a non-streaming OpenAI response.
     *
     * Returns null for unparseable bodies so the caller can surface the raw text: a
     * gateway that returns HTML on failure is common enough that a hard parse error
     * would hide the real cause.
     */
    fun parseOpenAiResponse(body: String): AssistantTurn? = runCatching {
        val parsed = json.decodeFromString<OpenAiResponse>(body)
        // An error envelope has no choices; surfacing "empty reply" would hide the
        // reason the provider actually gave.
        parsed.error?.message?.let { return null }

        val choice = parsed.choices.firstOrNull() ?: return null
        AssistantTurn(
            text = choice.message?.content.orEmpty(),
            toolCalls = choice.message?.toolCalls.orEmpty().mapNotNull { call ->
                val name = call.function?.name ?: return@mapNotNull null
                ToolCallRequest(
                    id = call.id ?: name,
                    name = name,
                    argumentsJson = call.function?.arguments ?: "{}",
                )
            },
            finishReason = choice.finishReason,
            promptTokens = parsed.usage?.promptTokens ?: 0,
            completionTokens = parsed.usage?.completionTokens ?: 0,
        )
    }.getOrNull()

    /**
     * Read the provider's error message out of a failed response.
     *
     * Handles both envelopes: OpenAI nests it under `error.message`, and a proxy may
     * return a bare `{"message": "..."}`. Falls back to the raw body truncated, so the
     * user sees something rather than "request failed".
     */
    fun extractErrorMessage(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "empty response"

        runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            obj["error"]?.let { element ->
                val nested = element as? JsonObject
                nested?.get("message")?.jsonPrimitive?.content?.let { return it }
                element.jsonPrimitiveOrNull()?.let { return it }
            }
            obj["message"]?.jsonPrimitive?.content?.let { return it }
        }

        return trimmed.take(300)
    }

    private fun JsonElement.jsonPrimitiveOrNull(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()

    // =====================================================================
    // Streaming
    // =====================================================================

    /**
     * One event from an SSE stream.
     *
     * `delta` is the incremental text; [done] marks the terminal event. Tool-call
     * fragments are surfaced as [toolCallDelta] because OpenAI streams them in pieces
     * that must be concatenated by index.
     */
    data class StreamEvent(
        val delta: String = "",
        val done: Boolean = false,
        val error: String? = null,
        val toolCallDelta: ToolCallDelta? = null,
    )

    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsFragment: String?,
    )

    /**
     * Parse one SSE line from an OpenAI stream.
     *
     * The line still carries its `data: ` prefix because the caller reads raw lines;
     * stripping it here keeps the framing knowledge in one place.
     */
    fun parseOpenAiStreamLine(line: String): StreamEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "data: [DONE]") return StreamEvent(done = true)
        if (!trimmed.startsWith("data:")) return null

        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null

        return runCatching {
            val obj = json.parseToJsonElement(payload).jsonObject
            (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.content?.let {
                return StreamEvent(error = it)
            }

            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val delta = choice["delta"] as? JsonObject
            // contentOrNull, not content: a JSON null yields the literal string "null"
            // through `content`, which is how a null finish_reason was being read as a
            // terminal event and cut every stream off after its first chunk.
            val content = delta?.get("content")?.let { element ->
                runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            }.orEmpty()

            val toolCalls = delta?.get("tool_calls")?.jsonArray?.mapNotNull { element ->
                val call = element.jsonObject
                ToolCallDelta(
                    index = call["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    id = call["id"]?.jsonPrimitive?.contentOrNull,
                    name = (call["function"] as? JsonObject)
                        ?.get("name")?.jsonPrimitive?.contentOrNull,
                    argumentsFragment = (call["function"] as? JsonObject)
                        ?.get("arguments")?.jsonPrimitive?.contentOrNull,
                )
            }.orEmpty()

            val finished = choice["finish_reason"]?.let { element ->
                runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            } != null

            StreamEvent(
                delta = content,
                done = finished,
                toolCallDelta = toolCalls.firstOrNull(),
            )
        }.getOrNull()
    }

    /**
     * Parse one SSE line from an Anthropic stream.
     *
     * Anthropic sends named events, so the type is read from the `event:` line's
     * payload rather than inferred. `content_block_delta` carries text; a
     * `message_stop` ends the turn.
     */
    fun parseAnthropicStreamLine(line: String): StreamEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null

        return runCatching {
            val obj = json.parseToJsonElement(payload).jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "content_block_delta" -> {
                    val text = (obj["delta"] as? JsonObject)
                        ?.get("text")?.jsonPrimitive?.content.orEmpty()
                    StreamEvent(delta = text)
                }
                "message_stop" -> StreamEvent(done = true)
                "error" -> StreamEvent(
                    error = (obj["error"] as? JsonObject)
                        ?.get("message")?.jsonPrimitive?.content ?: "stream error",
                )
                else -> null
            }
        }.getOrNull()
    }

    // =====================================================================
    // Model list
    // =====================================================================

    /** One entry from `GET /v1/models`. */
    data class ModelEntry(val id: String, val ownedBy: String?)

    fun parseModels(body: String): List<ModelEntry> = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        obj["data"]?.jsonArray?.mapNotNull { element ->
            val item = element.jsonObject
            val id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            ModelEntry(id = id, ownedBy = item["owned_by"]?.jsonPrimitive?.content)
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** Headers for each protocol. Returning a map keeps OkHttp out of this file. */
    fun headersFor(protocol: String, apiKey: String?): Map<String, String> = buildMap {
        put("content-type", "application/json")
        put("accept", "text/event-stream")
        when (protocol) {
            PROTOCOL_ANTHROPIC -> {
                apiKey?.let { put("x-api-key", it) }
                put("anthropic-version", "2023-06-01")
            }
            else -> apiKey?.let { put("authorization", "Bearer $it") }
        }
    }

    /** Chat path for a base URL, honouring a base that already ends in `/v1`. */
    fun chatPath(protocol: String, baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return when (protocol) {
            PROTOCOL_ANTHROPIC -> "$base/v1/messages".let {
                // A base such as https://api.anthropic.com must become
                // https://api.anthropic.com/v1/messages, while a proxy base that
                // already includes /v1 must not gain a second one.
                if (base.endsWith("/v1")) "$base/messages" else it
            }
            else -> if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        }
    }

    /** Models path with the same `/v1` rule as [chatPath]. */
    fun modelsPath(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
    }
}
