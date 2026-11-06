package com.tuissh.app.data.repo

import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.dao.AiConversationDao
import com.tuissh.app.data.model.AiConversationEntity
import com.tuissh.app.data.model.AiMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the conversation repository against an in-memory DAO.
 *
 * The DAO is faked rather than backed by a real SQLite file because what needs proving
 * is the repository's decisions — when a title is set, what happens when a write fails,
 * whether a delete takes the messages with it — not Room's SQL generation. Room's own
 * identity check covers the schema.
 */
class AiConversationRepositoryTest {

    /** In-memory stand-in that records calls. */
    private class FakeDao : AiConversationDao {
        val conversations = mutableListOf<AiConversationEntity>()
        val messages = mutableListOf<AiMessageEntity>()
        var nextConversationId = 1L
        var nextMessageId = 1L
        var failOnInsertMessage = false

        override fun conversations(): Flow<List<AiConversationEntity>> =
            flowOf(conversations.sortedByDescending { it.updatedAt })

        override suspend fun conversation(id: Long): AiConversationEntity? =
            conversations.firstOrNull { it.id == id }

        override suspend fun latest(): AiConversationEntity? =
            conversations.maxByOrNull { it.updatedAt }

        override suspend fun insertConversation(entity: AiConversationEntity): Long {
            val id = if (entity.id == 0L) nextConversationId++ else entity.id
            entity.id.let { }
            val stored = entity.copy(id = id)
            conversations.add(stored)
            return id
        }

        override suspend fun updateConversation(entity: AiConversationEntity) {
            val index = conversations.indexOfFirst { it.id == entity.id }
            if (index >= 0) conversations[index] = entity
        }

        override suspend fun deleteConversation(id: Long) {
            conversations.removeIf { it.id == id }
        }

        override suspend fun touch(id: Long, at: Long) {
            conversations.firstOrNull { it.id == id }?.updatedAt = at
        }

        override suspend fun messages(conversationId: Long): List<AiMessageEntity> =
            messages.filter { it.conversationId == conversationId }.sortedBy { it.id }

        override fun messagesFlow(conversationId: Long): Flow<List<AiMessageEntity>> =
            flowOf(messages.filter { it.conversationId == conversationId }.sortedBy { it.id })

        override suspend fun insertMessage(entity: AiMessageEntity): Long {
            if (failOnInsertMessage) error("disk full")
            val id = nextMessageId++
            messages.add(entity.copy(id = id))
            return id
        }

        override suspend fun deleteMessages(conversationId: Long) {
            messages.removeIf { it.conversationId == conversationId }
        }

        override suspend fun maybeTitle(id: Long, title: String, at: Long) {
            val row = conversations.firstOrNull { it.id == id } ?: return
            if (row.title == "New chat" || row.title.isEmpty()) {
                row.title = title
                row.updatedAt = at
            }
        }

        override suspend fun messageCount(conversationId: Long): Int =
            messages.count { it.conversationId == conversationId }

        override suspend fun conversationIdsByAge(): List<Long> =
            conversations.sortedByDescending { it.updatedAt }.map { it.id }

        override suspend fun deleteWithMessages(conversationId: Long) {
            deleteMessages(conversationId)
            deleteConversation(conversationId)
        }

        override suspend fun pruneTo(keep: Int) {
            if (keep <= 0) return
            for (id in conversationIdsByAge().drop(keep)) deleteWithMessages(id)
        }
    }

    @Test
    fun `resuming with no history creates a conversation`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)

        val id = repo.resumeOrCreate("deepseek-v4.1-flash-1017")

        assertEquals(1L, id)
        assertEquals(1, dao.conversations.size)
        assertEquals("New chat", dao.conversations.single().title)
    }

    @Test
    fun `resuming with history returns the most recent conversation`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        val first = repo.create("m", "older")
        val second = repo.create("m", "newer")
        dao.touch(first, at = 1_000L)
        dao.touch(second, at = 2_000L)

        val resumed = repo.resumeOrCreate("m")

        assertEquals("the newest conversation must be resumed", second, resumed)
        assertEquals("no extra conversation may be created", 2, dao.conversations.size)
    }

    @Test
    fun `the first user message becomes the title`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        val id = repo.create("m", "New chat")

        repo.append(id, AiMessageEntity.ROLE_USER, "Liet ke container dang chay")

        assertEquals("Liet ke container dang chay", dao.conversations.single().title)
    }

    @Test
    fun `a long first message is truncated for the title`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        val id = repo.create("m", "New chat")
        val long = "x".repeat(500)

        repo.append(id, AiMessageEntity.ROLE_USER, long)

        val title = dao.conversations.single().title
        assertTrue("title must be bounded, was ${title.length}", title.length <= 60)
    }

    @Test
    fun `a newline in the first message does not break the list row`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        val id = repo.create("m", "New chat")

        repo.append(id, AiMessageEntity.ROLE_USER, "line one\nline two")

        assertEquals("line one line two", dao.conversations.single().title)
    }

    @Test
    fun `a renamed conversation keeps its name`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        val id = repo.create("m", "My own name")

        repo.append(id, AiMessageEntity.ROLE_USER, "something else")

        assertEquals("My own name", dao.conversations.single().title)
    }

    @Test
    fun `an assistant message does not set the title`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        val id = repo.create("m", "New chat")

        repo.append(id, AiMessageEntity.ROLE_ASSISTANT, "I am the assistant")

        assertEquals("New chat", dao.conversations.single().title)
    }

    @Test
    fun `messages come back in write order`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        val id = repo.create("m")

        repo.append(id, AiMessageEntity.ROLE_USER, "first")
        repo.append(id, AiMessageEntity.ROLE_ASSISTANT, "second")
        repo.append(id, AiMessageEntity.ROLE_TOOL, "third", toolName = "docker_ps")

        val stored = repo.messages(id)

        assertEquals(listOf("first", "second", "third"), stored.map { it.content })
        assertEquals("docker_ps", stored.last().toolName)
    }

    @Test
    fun `a failed write is reported rather than thrown`() = runBlocking {
        val dao = FakeDao().apply { failOnInsertMessage = true }
        val repo = AiConversationRepository(dao)
        val id = repo.create("m")

        val result = repo.append(id, AiMessageEntity.ROLE_USER, "will not persist")

        assertTrue("a disk failure must surface as an error, got $result", result is NeoResult.Err)
        assertEquals("nothing may be stored", 0, dao.messages.size)
    }

    @Test
    fun `deleting a conversation removes its messages`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        val keep = repo.create("m", "keep")
        val drop = repo.create("m", "drop")
        repo.append(keep, AiMessageEntity.ROLE_USER, "kept")
        repo.append(drop, AiMessageEntity.ROLE_USER, "dropped")

        repo.delete(drop)

        assertEquals(1, dao.conversations.size)
        assertEquals("the kept conversation still has its message", 1, repo.messages(keep).size)
        assertEquals("the dropped conversation has none", 0, repo.messages(drop).size)
    }

    @Test
    fun `pruning keeps the newest conversations and drops the rest`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        repeat(5) { index ->
            val id = repo.create("m", "chat $index")
            repo.append(id, AiMessageEntity.ROLE_USER, "message $index")
            // Ordering is applied after the append, because append() touches the row to
            // now and would otherwise overwrite the deliberate timestamps.
            dao.touch(id, at = index.toLong() * 1_000)
        }

        repo.prune(keep = 2)

        assertEquals(2, dao.conversations.size)
        assertEquals(
            "the newest two survive",
            setOf("chat 3", "chat 4"),
            dao.conversations.map { it.title }.toSet(),
        )
        assertEquals("dropped conversations leave no messages", 2, dao.messages.size)
    }

    @Test
    fun `pruning with a non-positive limit does nothing`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        repo.create("m", "one")

        repo.prune(keep = 0)

        assertEquals(1, dao.conversations.size)
    }

    @Test
    fun `appending after a resume continues the same conversation`() = runBlocking {
        val dao = FakeDao()
        val repo = AiConversationRepository(dao)
        val first = repo.resumeOrCreate("m")
        repo.append(first, AiMessageEntity.ROLE_USER, "hello")

        // Simulate a restart: a fresh repository over the same storage.
        val second = AiConversationRepository(dao).resumeOrCreate("m")
        repo.append(second, AiMessageEntity.ROLE_ASSISTANT, "hi again")

        assertEquals("a restart must not fork the conversation", first, second)
        val stored = repo.messages(first)
        assertEquals(2, stored.size)
        assertNotNull(stored.first().content)
    }
}
