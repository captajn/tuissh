package com.tuissh.app.ai

import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP transport for the AI providers.
 *
 * One instance per provider: the base URL, protocol and key are fixed at construction,
 * which keeps every call site from having to pass them around and makes it impossible
 * to send a request to the wrong host with another provider's key.
 *
 * Streaming is exposed as a [Flow] rather than a callback so cancellation works: a
 * user leaving the chat screen tears down the socket instead of continuing to burn
 * tokens in the background.
 */
class AiHttpClient(
    private val baseUrl: String,
    private val protocol: String,
    private val apiKey: String?,
    private val http: OkHttpClient = defaultHttp(),
) {

    /** Non-streaming completion; returns the whole assistant turn. */
    suspend fun complete(
        model: String,
        messages: List<AiProtocol.WireMessage>,
        tools: List<AiProtocol.ToolSpec> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        system: String? = null,
    ): NeoResult<AiProtocol.AssistantTurn> = withContext(Dispatchers.IO) {
        val body = when (protocol) {
            AiProtocol.PROTOCOL_ANTHROPIC -> AiProtocol.anthropicBody(
                model = model,
                messages = messages,
                system = system,
                tools = tools,
                temperature = temperature,
                maxTokens = maxTokens ?: DEFAULT_ANTHROPIC_MAX_TOKENS,
                stream = false,
            )
            else -> AiProtocol.openAiChatBody(
                model = model,
                messages = messages,
                tools = tools,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = false,
            )
        }

        val request = buildRequest(AiProtocol.chatPath(protocol, baseUrl), body)
            ?: return@withContext NeoResult.Err(NeoError.AiNotConfigured("invalid base URL: $baseUrl"))

        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    NeoLog.w(LogTag.AI, "chat failed ${response.code}: ${text.take(200)}")
                    return@withContext NeoResult.Err(
                        NeoError.AiHttp(response.code, AiProtocol.extractErrorMessage(text)),
                    )
                }
                val turn = AiProtocol.parseOpenAiResponse(text)
                    ?: return@withContext NeoResult.Err(
                        NeoError.AiProtocol("unparseable reply: ${text.take(200)}"),
                    )
                NeoResult.Ok(turn)
            }
        } catch (e: IOException) {
            NeoLog.e(LogTag.AI, "chat request failed", e)
            NeoResult.Err(NeoError.AiHttp(0, e.message ?: "network error"))
        }
    }

    /**
     * Streaming completion.
     *
     * Emits text deltas as they arrive. The flow completes after the terminal event or
     * with an error; cancelling the collector cancels the HTTP call because the
     * callback closes the response.
     */
    fun stream(
        model: String,
        messages: List<AiProtocol.WireMessage>,
        tools: List<AiProtocol.ToolSpec> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        system: String? = null,
    ): Flow<AiProtocol.StreamEvent> = callbackFlow {
        val body = when (protocol) {
            AiProtocol.PROTOCOL_ANTHROPIC -> AiProtocol.anthropicBody(
                model = model,
                messages = messages,
                system = system,
                tools = tools,
                temperature = temperature,
                maxTokens = maxTokens ?: DEFAULT_ANTHROPIC_MAX_TOKENS,
                stream = true,
            )
            else -> AiProtocol.openAiChatBody(
                model = model,
                messages = messages,
                tools = tools,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = true,
            )
        }

        val request = buildRequest(AiProtocol.chatPath(protocol, baseUrl), body)
        if (request == null) {
            close(IllegalStateException("invalid base URL: $baseUrl"))
            awaitClose { }
            return@callbackFlow
        }

        val call = http.newCall(request)
        var response: Response? = null

        try {
            response = call.execute()
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                trySend(
                    AiProtocol.StreamEvent(
                        error = AiProtocol.extractErrorMessage(text),
                        done = true,
                    ),
                )
                close()
                return@callbackFlow
            }

            // A gateway can answer 200 with an HTML page: Cloudflare interstitials and
            // rate-limit pages both do. Reading that as SSE yields an empty transcript
            // and no explanation, so the content type is checked before parsing.
            val contentType = response.header("Content-Type").orEmpty()
            if (contentType.isNotEmpty() && !contentType.contains("event-stream", ignoreCase = true) &&
                contentType.contains("html", ignoreCase = true)
            ) {
                val page = response.body?.string().orEmpty()
                trySend(
                    AiProtocol.StreamEvent(
                        error = "the provider returned an HTML page instead of a stream " +
                            "(status ${response.code}). This is usually a gateway block or " +
                            "rate limit, not a rejected request.",
                        done = true,
                    ),
                )
                NeoLog.w(LogTag.AI, "HTML instead of SSE (${response.code}): ${page.take(160)}")
                close()
                return@callbackFlow
            }

            val source = response.body?.source()
            if (source == null) {
                trySend(AiProtocol.StreamEvent(error = "empty stream", done = true))
                close()
                return@callbackFlow
            }

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val event = when (protocol) {
                    AiProtocol.PROTOCOL_ANTHROPIC -> AiProtocol.parseAnthropicStreamLine(line)
                    else -> AiProtocol.parseOpenAiStreamLine(line)
                }
                if (event != null) {
                    trySend(event)
                    if (event.done || event.error != null) break
                }
            }
            close()
        } catch (e: IOException) {
            NeoLog.e(LogTag.AI, "stream failed", e)
            trySend(AiProtocol.StreamEvent(error = e.message ?: "stream failed", done = true))
            close()
        }

        awaitClose {
            // Closing the call is what actually stops the socket; without it a
            // cancelled flow leaves a reader blocked on a live connection.
            runCatching { call.cancel() }
            runCatching { response?.close() }
        }
    }.flowOn(Dispatchers.IO)

    /** List available models, used by the provider settings screen. */
    suspend fun listModels(): NeoResult<List<AiProtocol.ModelEntry>> = withContext(Dispatchers.IO) {
        val request = buildRequest(AiProtocol.modelsPath(baseUrl), body = null, method = "GET")
            ?: return@withContext NeoResult.Err(NeoError.AiNotConfigured("invalid base URL: $baseUrl"))

        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext NeoResult.Err(
                        NeoError.AiHttp(response.code, AiProtocol.extractErrorMessage(text)),
                    )
                }
                NeoResult.Ok(AiProtocol.parseModels(text))
            }
        } catch (e: IOException) {
            NeoResult.Err(NeoError.AiHttp(0, e.message ?: "network error"))
        }
    }

    private fun buildRequest(
        url: String,
        body: String?,
        method: String = "POST",
    ): Request? {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return null
        val builder = Request.Builder().url(parsed)

        for ((name, value) in AiProtocol.headersFor(protocol, apiKey)) {
            // `accept: text/event-stream` only makes sense for a stream; a plain GET
            // that asks for SSE confuses some gateways into holding the response open.
            if (method == "GET" && name.equals("accept", ignoreCase = true)) continue
            builder.header(name, value)
        }

        when (method) {
            "GET" -> builder.get()
            else -> builder.post(
                (body ?: "{}").toRequestBody(JSON_MEDIA_TYPE),
            )
        }
        return builder.build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Anthropic requires `max_tokens`; this matches its own console default. */
        const val DEFAULT_ANTHROPIC_MAX_TOKENS = 4096

        /**
         * A client tuned for a chat stream.
         *
         * The read timeout is long because a slow model can think for a while between
         * tokens, but it is not infinite: a wedged socket must eventually fail rather
         * than leave the UI "loading" forever.
         */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** Build from a stored preset. */
        fun forProvider(
            baseUrl: String,
            protocol: String,
            apiKey: String?,
            http: OkHttpClient = defaultHttp(),
        ): AiHttpClient = AiHttpClient(baseUrl, protocol, apiKey, http)
    }
}
