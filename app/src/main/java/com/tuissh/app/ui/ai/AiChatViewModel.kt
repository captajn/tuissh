package com.tuissh.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuissh.app.ai.AiConversation
import com.tuissh.app.ai.AiHttpClient
import com.tuissh.app.ai.AiToolkit
import com.tuissh.app.ai.AiToolRunner
import com.tuissh.app.core.NeoError
import com.tuissh.app.data.model.AiMessageEntity
import com.tuissh.app.data.repo.AiConversationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chat state.
 *
 * Replaces the original app's AI chat view model. Two things are deliberately not
 * modelled as plain state:
 *
 * 1. **The transcript is append-only.** Streaming rewrites only the last assistant
 *    bubble, so a delta does not rebuild the whole list.
 *
 * 2. **Tool cards are first-class entries.** The original app rendered each tool call as
 *    a card with its own status and duration; flattening them into the assistant text
 *    would lose that, and a long tool run would look like a hang.
 */
class AiChatViewModel(
    private val http: AiHttpClient,
    private val runner: AiToolRunner,
    private val model: String,
    private val hostLabel: String,
    private val systemPrompt: String = AiConversation.defaultSystemPrompt(hostLabel),
    /**
     * Durable copy of the transcript. Null in tests and in previews where a database
     * would be noise; the chat still works, it just forgets on restart.
     */
    private val history: AiConversationRepository? = null,
    private val modelIdForHistory: String = model,
    /** Scopes history to one host, so two servers do not share a transcript. */
    private val serverIdForHistory: Long? = null,
) : ViewModel() {

    /** Conversation row this chat is writing to, once [restore] has run. */
    private var conversationId: Long? = null

    /** One row in the transcript. */
    sealed interface Row {
        val id: Long

        data class User(override val id: Long, val text: String) : Row

        data class Assistant(
            override val id: Long,
            val text: String,
            /** True while tokens are still arriving, so the cursor can be drawn. */
            val streaming: Boolean = false,
        ) : Row

        data class Tool(
            override val id: Long,
            val name: String,
            val argumentsJson: String,
            val state: ToolState,
            val display: String = "",
            val durationMs: Long = 0,
            val expanded: Boolean = false,
        ) : Row

        data class Notice(override val id: Long, val text: String) : Row
    }

    enum class ToolState { RUNNING, OK, FAILED }

    data class UiState(
        val rows: List<Row> = emptyList(),
        val input: String = "",
        val sending: Boolean = false,
        val error: NeoError? = null,
        /** Pending mutation waiting for the user's decision. */
        val awaitingApproval: ApprovalRequest? = null,
        /** Empty until the session is live; the screen shows the model name when set. */
        val model: String = "",
    ) {
        val canSend: Boolean get() = input.isNotBlank() && !sending
    }

    data class ApprovalRequest(
        val command: String,
        val reason: String,
        val answer: kotlinx.coroutines.CompletableDeferred<Boolean>,
    )

    private val _state = MutableStateFlow(UiState(model = model))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var nextRowId = 1L
    private var runJob: Job? = null

    /** Conversation is recreated per send so the history stays consistent. */
    private var conversation: AiConversation? = null

    fun setInput(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    /**
     * Send the current input.
     *
     * A fresh [AiConversation] is built each time from [history], because the loop owns
     * its transcript and reusing one instance across sends would replay the previous
     * turn's tool results.
     */
    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.sending) return

        append(Row.User(nextRowId++, text))
        _state.value = _state.value.copy(input = "", sending = true, error = null)

        viewModelScope.launch {
            val id = conversationId ?: history?.resumeOrCreate(modelIdForHistory, serverIdForHistory)?.also {
                conversationId = it
            }
            if (id != null) {
                history?.append(id, AiMessageEntity.ROLE_USER, text)
            }
        }

        val assistantId = nextRowId++
        append(Row.Assistant(assistantId, "", streaming = true))

        runJob = viewModelScope.launch {
            val conv = AiConversation(
                http = http,
                runner = runner,
                model = model,
                systemPrompt = systemPrompt,
            )
            // Replay the transcript so the model sees the whole conversation, not just
            // the newest message.
            for (row in _state.value.rows) {
                when (row) {
                    is Row.User -> conv.seed(row.text)
                    else -> Unit
                }
            }
            conversation = conv

            try {
                conv.run().collect { event ->
                    when (event) {
                        is AiConversation.Event.Text ->
                            appendDelta(assistantId, event.delta)

                        is AiConversation.Event.ToolStarted -> {
                            // Close the streaming bubble first so the card appears below
                            // the text that prompted it, matching the original layout.
                            finishStreaming(assistantId)
                            append(
                                Row.Tool(
                                    id = nextRowId++,
                                    name = event.name,
                                    argumentsJson = event.argumentsJson,
                                    state = ToolState.RUNNING,
                                ),
                            )
                        }

                        is AiConversation.Event.ToolFinished -> {
                            updateLastTool { tool ->
                                tool.copy(
                                    state = if (event.ok) ToolState.OK else ToolState.FAILED,
                                    display = event.display,
                                    durationMs = event.durationMs,
                                ).also { persistTool(it) }
                            }
                        }

                        is AiConversation.Event.UnknownTool -> {
                            append(
                                Row.Notice(nextRowId++, "Công cụ \"${event.name}\" không tồn tại."),
                            )
                        }

                        is AiConversation.Event.Done -> {
                            finishStreaming(assistantId)
                            persistLastAssistant()
                        }

                        is AiConversation.Event.Failed -> {
                            finishStreaming(assistantId)
                            _state.value = _state.value.copy(error = event.error)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } finally {
                finishStreaming(assistantId)
                _state.value = _state.value.copy(sending = false, awaitingApproval = null)
            }
        }
    }

    /** Stop the in-flight turn; the socket closes with the flow. */
    fun stop() {
        runJob?.cancel()
        runJob = null
        _state.value = _state.value.copy(sending = false, awaitingApproval = null)
    }

    fun toggleTool(id: Long) {
        _state.value = _state.value.copy(
            rows = _state.value.rows.map { row ->
                if (row is Row.Tool && row.id == id) row.copy(expanded = !row.expanded) else row
            },
        )
    }

    /**
     * The approval policy handed to [AiToolRunner].
     *
     * Suspends until the user answers, which blocks the tool loop — that is the point:
     * the command must not reach the host before the decision is made.
     */
    suspend fun approve(command: String, reason: String): Boolean {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        _state.value = _state.value.copy(
            awaitingApproval = ApprovalRequest(command, reason, deferred),
        )
        return deferred.await()
    }

    fun answerApproval(accept: Boolean) {
        _state.value.awaitingApproval?.answer?.complete(accept)
        _state.value = _state.value.copy(awaitingApproval = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Load the stored transcript for this server, if there is one.
     *
     * Called once when the screen appears. Rows are rebuilt from the database so a
     * restart shows what was said rather than an empty chat.
     */
    fun restore() {
        val store = history ?: return
        viewModelScope.launch {
            val id = store.resumeOrCreate(modelIdForHistory, serverIdForHistory)
            conversationId = id
            val stored = store.messages(id)
            if (stored.isEmpty()) return@launch
            val rows = stored.mapNotNull { message ->
                when (message.role) {
                    AiMessageEntity.ROLE_USER -> Row.User(nextRowId++, message.content)
                    AiMessageEntity.ROLE_ASSISTANT ->
                        Row.Assistant(nextRowId++, message.content).takeIf { message.content.isNotBlank() }
                    AiMessageEntity.ROLE_TOOL -> Row.Tool(
                        id = nextRowId++,
                        name = message.toolName ?: "tool",
                        argumentsJson = "",
                        state = ToolState.OK,
                        display = message.content.take(120),
                    )
                    else -> null
                }
            }
            _state.value = _state.value.copy(rows = rows)
        }
    }

    /** Persist the assistant text that just finished streaming. */
    private fun persistLastAssistant() {
        val store = history ?: return
        val id = conversationId ?: return
        val text = _state.value.rows
            .filterIsInstance<Row.Assistant>()
            .lastOrNull()
            ?.text
            ?.takeIf { it.isNotBlank() }
            ?: return
        viewModelScope.launch {
            store.append(id, AiMessageEntity.ROLE_ASSISTANT, text)
        }
    }

    /** Persist one finished tool card so the transcript survives a restart. */
    private fun persistTool(row: Row.Tool) {
        val store = history ?: return
        val id = conversationId ?: return
        viewModelScope.launch {
            store.append(
                conversationId = id,
                role = AiMessageEntity.ROLE_TOOL,
                content = row.display,
                toolName = row.name,
            )
        }
    }
    private fun append(row: Row) {
        _state.value = _state.value.copy(rows = _state.value.rows + row)
    }

    /** Append streamed text to one assistant bubble without touching the others. */
    private fun appendDelta(id: Long, delta: String) {
        _state.value = _state.value.copy(
            rows = _state.value.rows.map { row ->
                if (row is Row.Assistant && row.id == id) row.copy(text = row.text + delta) else row
            },
        )
    }

    private fun finishStreaming(id: Long) {
        _state.value = _state.value.copy(
            rows = _state.value.rows
                .map { row ->
                    if (row is Row.Assistant && row.id == id) row.copy(streaming = false) else row
                }
                // An assistant bubble that never received a token is noise.
                .filterNot { it is Row.Assistant && it.text.isBlank() && !it.streaming },
        )
    }

    private fun updateLastTool(transform: (Row.Tool) -> Row.Tool) {
        val rows = _state.value.rows
        val index = rows.indexOfLast { it is Row.Tool }
        if (index < 0) return
        val updated = rows.toMutableList().also { it[index] = transform(rows[index] as Row.Tool) }
        _state.value = _state.value.copy(rows = updated)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

/** Build a runner whose mutating calls route through [chat]'s approval UI. */
fun aiRunnerFor(
    commands: com.tuissh.app.ssh.RemoteCommandRunner,
    sftp: com.tuissh.app.sftp.SftpClient?,
    approve: suspend (String, String, String) -> Boolean,
): AiToolRunner = AiToolRunner(
    commands = commands,
    sftp = sftp,
    approval = AiToolkit.ApprovalPolicy { name, command, reason ->
        approve(name, command, reason)
    },
)
