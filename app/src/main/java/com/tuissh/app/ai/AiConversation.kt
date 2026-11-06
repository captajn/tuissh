package com.tuissh.app.ai

import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Runs the model-tool conversation.
 *
 * The loop is the whole feature: ask the model, and if it replies with tool calls,
 * execute them, append the results, and ask again. Stops when the model answers without
 * asking for a tool, when a round limit is hit, or when the user cancels.
 *
 * Three constraints shape the implementation:
 *
 * 1. **Bounded rounds.** A model that keeps calling a failing tool would otherwise loop
 *    forever and bill the user for it. [maxRounds] caps it.
 *
 * 2. **No tool runs without a declaration.** A call naming a tool that is not in the
 *    toolkit is answered with an error rather than being executed, so a prompt
 *    injection cannot conjure capability the app never shipped.
 *
 * 3. **Every step is observable.** The flow emits each event so the UI can render a card
 *    per tool call as it happens, the way the original app did.
 */
class AiConversation(
    private val http: AiHttpClient,
    private val runner: AiToolRunner,
    private val model: String,
    private val systemPrompt: String,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
) {

    /** What the loop reports as it goes. */
    sealed interface Event {
        /** Incremental assistant text, already stripped of framing. */
        data class Text(val delta: String) : Event

        /** The model asked for a tool; emitted before it runs. */
        data class ToolStarted(val name: String, val argumentsJson: String) : Event

        /** A tool finished. */
        data class ToolFinished(
            val name: String,
            val ok: Boolean,
            val display: String,
            val durationMs: Long,
        ) : Event

        /** The loop ended normally. */
        data class Done(val fullText: String, val rounds: Int) : Event

        /** The loop ended because something failed. */
        data class Failed(val error: NeoError) : Event

        /** The model asked for a tool that does not exist. */
        data class UnknownTool(val name: String) : Event
    }

    /** Append-only transcript, in the shape the provider expects. */
    private val history = mutableListOf<AiProtocol.WireMessage>()

    /** Seed the conversation. */
    fun seed(userMessage: String) {
        history += AiProtocol.WireMessage("user", userMessage)
    }

    fun history(): List<AiProtocol.WireMessage> = history.toList()

    /**
     * Run one turn of the conversation.
     *
     * Streaming is used for the assistant text so the user sees tokens arrive; tool
     * calls are collected from the stream and executed after it ends, because a
     * partially-streamed argument string is not valid JSON.
     */
    fun run(): Flow<Event> = flow {
        var fullText = StringBuilder()
        var round = 0

        while (round < maxRounds) {
            round++
            fullText = StringBuilder()

            // Accumulate streamed tool-call fragments by index: OpenAI sends the name
            // once and then the arguments in pieces, so they must be concatenated
            // rather than replaced.
            val partialCalls = linkedMapOf<Int, PartialCall>()
            var streamError: String? = null
            var finishReason: String? = null

            val messages = buildList {
                add(AiProtocol.WireMessage("system", systemPrompt))
                addAll(history)
            }

            try {
                http.stream(
                    model = model,
                    messages = messages,
                    tools = AiToolkit.specs,
                ).collect { event ->
                    if (event.error != null) {
                        streamError = event.error
                        return@collect
                    }
                    if (event.delta.isNotEmpty()) {
                        fullText.append(event.delta)
                        emit(Event.Text(event.delta))
                    }
                    event.toolCallDelta?.let { delta ->
                        val call = partialCalls.getOrPut(delta.index) { PartialCall() }
                        delta.id?.let { call.id = it }
                        delta.name?.let { call.name = it }
                        delta.argumentsFragment?.let { call.arguments.append(it) }
                    }
                    if (event.done) finishReason = "stop"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(Event.Failed(NeoError.AiProtocol(e.message ?: "stream failed")))
                return@flow
            }

            if (streamError != null) {
                emit(Event.Failed(NeoError.AiHttp(0, streamError!!)))
                return@flow
            }

            val text = fullText.toString()

            // No tool calls means the model answered; that ends the turn.
            if (partialCalls.isEmpty()) {
                if (text.isNotBlank()) {
                    history += AiProtocol.WireMessage("assistant", text)
                }
                emit(Event.Done(text, round))
                return@flow
            }

            // Record the assistant turn so the tool results have something to answer.
            // Some providers reject a tool result whose call id is not preceded by the
            // assistant message that produced it.
            if (text.isNotBlank()) {
                history += AiProtocol.WireMessage("assistant", text)
            }

            val calls = partialCalls.entries.sortedBy { it.key }.map { (_, partial) ->
                AiProtocol.ToolCallRequest(
                    id = partial.id.ifEmpty { "call_${partial.name}_${partialsCounter++}" },
                    name = partial.name,
                    argumentsJson = partial.arguments.toString().ifBlank { "{}" },
                )
            }
            NeoLog.d(LogTag.AI, "round $round requested ${calls.size} tool call(s)")

            for (call in calls) {
                // A hallucinated name must not reach the dispatcher as a capability.
                val definition = AiToolkit.definitionFor(call.name)
                if (definition == null) {
                    emit(Event.UnknownTool(call.name))
                    history += AiProtocol.WireMessage(
                        "user",
                        "tool_result(${call.name}): error: unknown tool",
                    )
                    continue
                }

                emit(Event.ToolStarted(call.name, call.argumentsJson))
                val result = runner.run(call.name, call.argumentsJson)
                emit(
                    Event.ToolFinished(
                        name = call.name,
                        ok = result.ok,
                        display = result.display,
                        durationMs = result.durationMs,
                    ),
                )
                history += AiProtocol.WireMessage(
                    "user",
                    "tool_result(${call.name}): ${toolResultPayload(result)}",
                )
            }

            // Loop back so the model can use the results.
            if (finishReason == null && round >= maxRounds) break
        }

        // Reaching the cap is not an error: the model simply had more to do than the
        // budget allowed, and the partial answer is still useful.
        emit(Event.Done(fullText.toString(), round))
    }

    /** Accumulates a streamed tool call. */
    private class PartialCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private var partialsCounter = 0

    companion object {
        /**
         * Rounds before the loop gives up.
         *
         * Four allows diagnose-fix-verify plus one spare. Higher values mainly increase
         * the bill when a tool keeps failing.
         */
        const val DEFAULT_MAX_ROUNDS = 4

        /** The assistant's standing instructions. */
        fun defaultSystemPrompt(hostLabel: String): String = """
            You are the assistant inside Tui SSH, an Android SSH client.
            You are connected to: $hostLabel.

            Rules:
            - Prefer the read-only tools (list_directory, read_file, system_info,
              docker_ps, docker_logs) over run_command. Running a shell command needs
              the user's explicit approval and should be a last resort.
            - Never claim you did something you did not do. If a tool failed, say so and
              quote the error.
            - Answer in the language the user writes in. Keep replies short; the screen
              is a phone.
            - When you are about to change anything on the host, state what will change
              before doing it.
        """.trimIndent()
    }
}
