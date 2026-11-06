package com.tuissh.app.ssh

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Contract of [SessionCredentialCache]: what it remembers, for how long, and what
 * concurrent access is allowed to do to it.
 *
 * Every test owns its instance and its clock. Expiry is exercised by moving a fake
 * clock rather than waiting, so the suite stays fast and deterministic.
 */
class SessionCredentialCacheTest {

    private val now = AtomicLong(1_000_000L)
    private lateinit var cache: SessionCredentialCache

    @Before
    fun setUp() {
        cache = SessionCredentialCache(clock = { now.get() })
    }

    @After
    fun tearDown() {
        SessionCredentialCache.reset()
    }

    @Test
    fun `write then read returns both secrets`() {
        cache.write(7L, password = "hunter2", passphrase = "phrase")

        val held = cache.read(7L)

        assertEquals("hunter2", held?.password)
        assertEquals("phrase", held?.passphrase)
    }

    @Test
    fun `read of an unknown server returns null`() {
        cache.write(7L, password = "hunter2")

        assertNull(cache.read(8L))
    }

    @Test
    fun `clear removes one entry and leaves the others`() {
        cache.write(1L, password = "one")
        cache.write(2L, password = "two")

        cache.clear(1L)

        assertNull(cache.read(1L))
        assertEquals("two", cache.read(2L)?.password)
    }

    @Test
    fun `clearAll removes every entry`() {
        cache.write(1L, password = "one")
        cache.write(2L, passphrase = "two")

        cache.clearAll()

        assertNull(cache.read(1L))
        assertNull(cache.read(2L))
        assertEquals(0, cache.size())
    }

    @Test
    fun `an entry older than the TTL is not readable`() {
        cache.write(1L, password = "hunter2")

        now.addAndGet(SessionCredentialCache.DEFAULT_TTL_MILLIS - 1L)
        assertEquals("hunter2", cache.read(1L)?.password)

        now.addAndGet(1L)
        assertNull("the TTL boundary must count as expired", cache.read(1L))
        assertEquals("an expired entry is dropped, not only hidden", 0, cache.size())
    }

    @Test
    fun `a zero TTL expires immediately`() {
        val noStore = SessionCredentialCache(ttlMillis = 0L, clock = { now.get() })

        noStore.write(1L, password = "hunter2")

        assertNull(noStore.read(1L))
    }

    @Test
    fun `a shorter injected TTL is honoured`() {
        val shortLived = SessionCredentialCache(ttlMillis = 5_000L, clock = { now.get() })
        shortLived.write(1L, password = "hunter2")

        now.addAndGet(4_999L)
        assertEquals("hunter2", shortLived.read(1L)?.password)

        now.addAndGet(1L)
        assertNull(shortLived.read(1L))
    }

    @Test
    fun `concurrent writes keep every server readable`() = runBlocking {
        val servers = 64L
        val workers = 8
        val start = CountDownLatch(1)

        val jobs = (0 until workers).map { worker ->
            async {
                start.await(5, TimeUnit.SECONDS)
                for (server in 1L..servers) {
                    cache.write(server, password = "s$server-w$worker")
                    // Interleave reads and clears so writers keep meeting a mutating map.
                    cache.read(server)
                    if (server % 16 == 0L) cache.clear(server)
                }
            }
        }

        start.countDown()
        jobs.awaitAll()

        // A corrupted map shows up as a lost or mismatched entry, never as an exception.
        for (server in 1L..servers) {
            if (server % 16 == 0L) {
                assertNull("server $server was cleared", cache.read(server))
            } else {
                val held = cache.read(server)?.password
                assertEquals(
                    "server $server lost under contention",
                    "s$server",
                    held?.substringBefore("-w"),
                )
            }
        }
        assertNull(cache.read(servers + 1L))
    }

    @Test
    fun `concurrent readers never observe a half-written entry`() = runBlocking {
        val writers = 4
        val readers = 4
        val mismatches = AtomicReference<String?>(null)
        val start = CountDownLatch(1)

        val writerJobs = (0 until writers).map { worker ->
            async {
                start.await(5, TimeUnit.SECONDS)
                repeat(500) {
                    cache.write(1L, password = "w$worker", passphrase = "p$worker")
                }
            }
        }
        val readerJobs = (0 until readers).map {
            async {
                start.await(5, TimeUnit.SECONDS)
                repeat(500) {
                    val held = cache.read(1L) ?: return@repeat
                    // Both halves are always written together, so a torn read would
                    // pair the password of one writer with the passphrase of another.
                    val passwordTag = held.password?.removePrefix("w")
                    val passphraseTag = held.passphrase?.removePrefix("p")
                    if (passwordTag != passphraseTag) {
                        mismatches.compareAndSet(null, "${held.password}/${held.passphrase}")
                    }
                }
            }
        }

        start.countDown()
        (writerJobs + readerJobs).awaitAll()

        assertNull(mismatches.get())
    }

    @Test
    fun `a null password clears the field instead of storing the text null`() {
        cache.write(1L, password = "hunter2", passphrase = "phrase")

        cache.updatePassword(1L, null)

        val held = cache.read(1L)
        assertNull(held?.password)
        assertNotEquals("null", held?.password)
        assertEquals("clearing one field must not drop the other", "phrase", held?.passphrase)
    }

    @Test
    fun `clearing the last field removes the entry`() {
        cacheWrite(cache, 1L, "hunter2", null)

        cacheWrite(cache, 1L, null, null)

        assertNull(cache.read(1L))
        assertEquals(0, cache.size())
    }

    // Both write() overloads are called through their signatures. A default-argument
    // bridge generated against an older signature would otherwise keep serving stale
    // bytecode, and the assertions below are exactly what catches it.
    private fun cacheWrite(cache: SessionCredentialCache, id: Long, password: String?, passphrase: String?) =
        cache.write(serverId = id, password = password, passphrase = passphrase)

    private fun sharedWrite(id: Long, password: String?, passphrase: String?) =
        SessionCredentialCache.write(serverId = id, password = password, passphrase = passphrase)

    @Test
    fun `a null half never stores the text null`() {
        cacheWrite(cache, 1L, "hunter2", null)

        // Only the passphrase is written, so the password must not survive.
        cacheWrite(cache, 1L, null, "phrase")

        val held = cache.read(1L)
        assertNull("write() is a full replacement, not a merge", held?.password)
        assertEquals("phrase", held?.passphrase)
        assertNotEquals("null", held?.passphrase)
    }

    @Test
    fun `clearing one field keeps the other`() {
        cacheWrite(cache, 1L, "hunter2", "phrase")

        cacheWrite(cache, 1L, null, "phrase")

        val held = cache.read(1L)
        assertNull("a null field is cleared rather than stored or merged back", held?.password)
        assertEquals("phrase", held?.passphrase)
    }

    @Test
    fun `a later write restarts the TTL`() {
        cache.write(1L, password = "first")
        now.addAndGet(SessionCredentialCache.DEFAULT_TTL_MILLIS - 1_000L)

        cache.write(1L, password = "second")
        now.addAndGet(2_000L)

        assertEquals("second", cache.read(1L)?.password)
    }

    @Test
    fun `reset empties the shared cache`() {
        sharedWrite(1L, "one", null)
        sharedWrite(2L, "two", null)

        SessionCredentialCache.reset()

        assertNull(SessionCredentialCache.read(1L))
        assertNull(SessionCredentialCache.read(2L))
    }

    @Test
    fun `the shared cache is shared between reads and writes`() {
        sharedWrite(42L, "hunter2", null)

        assertEquals("hunter2", SessionCredentialCache.read(42L)?.password)

        SessionCredentialCache.clear(42L)
        assertNull(SessionCredentialCache.read(42L))
    }

    @Test
    fun `the default TTL is fifteen minutes`() {
        assertEquals(15L * 60L * 1000L, SessionCredentialCache.DEFAULT_TTL_MILLIS)
        assertEquals(SessionCredentialCache.DEFAULT_TTL_MILLIS, SessionCredentialCache().ttl)
    }
}
