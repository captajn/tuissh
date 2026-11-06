package com.tuissh.app.data.repo

import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.dao.AiConversationDao
import com.tuissh.app.data.model.AiConversationEntity
import com.tuissh.app.data.model.AiMessageEntity

/**
 * Persists assistant conversations.
 *
 * The chat screen owns a transient list of rows for rendering; this is the durable copy.
 * Keeping them separate matters because the render rows carry UI state (a tool card
 * being expanded) that must not be written to the database, while the database rows
 * carry fields the screen never shows (token counts).
 *
 * Every write is fire-and-forget from the caller's point of view: losing one message to
 * a failed write is annoying, but blocking the chat on disk I/O is worse.
 */
class AiConversationRepository(private val dao: AiConversationDao) {

    /** The conversation list, newest activity first. */
    fun conversations() = dao.conversations()

    /**
     * Resume the most recent conversation, or start one.
     *
     * Returns the id only; the caller loads messages separately so a large transcript
     * does not delay showing the screen.
     */
    /**
     * Resume the newest conversation for [serverId], or start one.
     *
     * Scoped per server so opening the assistant on host B does not continue the
     * transcript from host A.
     */
    suspend fun resumeOrCreate(modelId: String, serverId: Long? = null): Long {
        val existing = if (serverId != null) dao.latestForServer(serverId) else dao.latest()
        if (existing != null) return existing.id

        return dao.insertConversation(
            AiConversationEntity(
                title = NewChatTitle,
                providerId = 0L,
                modelId = modelId,
                selectedServerIds = serverId?.toString().orEmpty(),
            ),
        )
    }

    suspend fun create(modelId: String, title: String = NewChatTitle): Long =
        dao.insertConversation(
            AiConversationEntity(
                title = title,
                providerId = 0L,
                modelId = modelId,
            ),
        )

    suspend fun messages(conversationId: Long): List<AiMessageEntity> =
        dao.messages(conversationId)

    /**
     * Record one message and refresh the conversation timestamp.
     *
     * The title is derived from the first user message so the conversation list is
     * readable. [AiConversationDao.maybeTitle] only applies it while the row still has
     * the placeholder, so a manual rename survives.
     */
    suspend fun append(
        conversationId: Long,
        role: String,
        content: String,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        toolName: String? = null,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
    ): NeoResult<Long> = runCatching {
        val id = dao.insertMessage(
            AiMessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                toolCallsJson = toolCallsJson,
                toolCallId = toolCallId,
                toolName = toolName,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
            ),
        )
        dao.touch(conversationId)

        if (role == AiMessageEntity.ROLE_USER) {
            val title = content.trim().replace('\n', ' ').take(TitleLimit)
            if (title.isNotEmpty()) dao.maybeTitle(conversationId, title)
        }
        NeoResult.Ok(id)
    }.getOrElse { error ->
        NeoLog.w(LogTag.AI, "failed to persist a ${role} message: ${error.message}")
        NeoResult.Err(com.tuissh.app.core.NeoError.Io("save message: ${error.message}"))
    }

    suspend fun delete(conversationId: Long) {
        runCatching { dao.deleteWithMessages(conversationId) }
            .onFailure { NeoLog.w(LogTag.AI, "failed to delete conversation: ${it.message}") }
    }

    /** Trim old conversations so the database does not grow without bound. */
    suspend fun prune(keep: Int = KeepConversations) {
        runCatching { dao.pruneTo(keep) }
            .onFailure { NeoLog.w(LogTag.AI, "failed to prune conversations: ${it.message}") }
    }

    /** Literal placeholder the schema uses; kept in sync with [AiConversationEntity]. */
    fun placeholderTitle(): String = NewChatTitle

    private companion object {
        const val NewChatTitle = "New chat"
        const val TitleLimit = 60
        const val KeepConversations = 50
    }
}
