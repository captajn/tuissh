package com.tuissh.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.tuissh.app.data.model.AiConversationEntity
import com.tuissh.app.data.model.AiMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for assistant conversations.
 *
 * A chat that vanishes when the process dies is not a chat, it is a scratchpad. The
 * original app kept conversations across launches, and losing that would be a visible
 * regression on the first restart.
 *
 * Two queries do the real work: [conversations] for the list, and [messages] for one
 * transcript. Everything else is bookkeeping.
 */
@Dao
interface AiConversationDao {

    /** The conversation list, newest activity first. */
    @Query("SELECT * FROM ai_conversations ORDER BY updatedAt DESC")
    fun conversations(): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM ai_conversations WHERE id = :id")
    suspend fun conversation(id: Long): AiConversationEntity?

    /** Most recent conversation, used to resume where the user left off. */
    @Query("SELECT * FROM ai_conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): AiConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(entity: AiConversationEntity): Long

    @Update
    suspend fun updateConversation(entity: AiConversationEntity)

    @Query("DELETE FROM ai_conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("UPDATE ai_conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long = System.currentTimeMillis())

    // --- messages ------------------------------------------------------

    /**
     * One transcript in write order.
     *
     * Ordered by id rather than `createdAt` because messages written in the same
     * millisecond — a tool card and the text after it — would otherwise come back in an
     * arbitrary order and the transcript would read wrong after a restart.
     */
    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun messages(conversationId: Long): List<AiMessageEntity>

    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun messagesFlow(conversationId: Long): Flow<List<AiMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(entity: AiMessageEntity): Long

    @Query("DELETE FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: Long)

    /**
     * Delete a conversation and its messages together.
     *
     * Wrapped in a transaction because a half-deleted conversation leaves orphan rows
     * that no query will ever read and that still occupy the file.
     */
    @Transaction
    suspend fun deleteWithMessages(conversationId: Long) {
        deleteMessages(conversationId)
        deleteConversation(conversationId)
    }

    /**
     * Give a conversation a title derived from its first user message.
     *
     * Only applied when the row still has the placeholder title, so renaming a
     * conversation by hand is not undone by the next message.
     */
    @Query(
        """
        UPDATE ai_conversations SET title = :title, updatedAt = :at
        WHERE id = :id AND (title = 'New chat' OR title = '')
        """,
    )
    suspend fun maybeTitle(id: Long, title: String, at: Long = System.currentTimeMillis())

    // --- maintenance ---------------------------------------------------

    @Query("SELECT COUNT(*) FROM ai_messages WHERE conversationId = :conversationId")
    suspend fun messageCount(conversationId: Long): Int

    /**
     * Drop the oldest conversations beyond [keep].
     *
     * A chat log grows without bound and nothing prunes it otherwise; a year of use
     * would silently bloat the database.
     */
    @Transaction
    suspend fun pruneTo(keep: Int) {
        if (keep <= 0) return
        val all = conversationIdsByAge()
        for (id in all.drop(keep)) {
            deleteWithMessages(id)
        }
    }

    @Query("SELECT id FROM ai_conversations ORDER BY updatedAt DESC")
    suspend fun conversationIdsByAge(): List<Long>
}
