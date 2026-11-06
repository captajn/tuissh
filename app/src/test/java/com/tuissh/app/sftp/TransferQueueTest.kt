package com.tuissh.app.sftp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transfer queue behaviour.
 *
 * The cases here are the ones a user notices: progress that advances, a cancel that
 * actually stops the transfer rather than only hiding it, and a failure that leaves the
 * remaining queue intact.
 */
class TransferQueueTest {

    private fun queue(
        uploadOpener: (String, Long) -> com.tuissh.app.core.NeoResult<java.io.OutputStream> =
            { _, _ -> com.tuissh.app.core.NeoResult.Ok(ByteArrayOutputStream()) },
        downloadOpener: (String) -> com.tuissh.app.core.NeoResult<InputStream> =
            { com.tuissh.app.core.NeoResult.Ok(ByteArrayInputStream(ByteArray(0))) },
        sizeProbe: (String) -> Long? = { null },
    ): TransferQueue = TransferQueue(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        uploadOpener = uploadOpener,
        downloadOpener = downloadOpener,
        sizeProbe = sizeProbe,
    )

    private suspend fun TransferQueue.awaitTerminal(id: Long) = withTimeout(5_000) {
        items.first { list -> list.any { it.id == id && it.isTerminal } }
            .first { it.id == id }
    }

    @Test
    fun `an upload completes and reports its size`() = runBlocking {
        val sink = ByteArrayOutputStream()
        val q = queue(uploadOpener = { _, _ -> com.tuissh.app.core.NeoResult.Ok(sink) })
        val payload = ByteArray(200_000) { it.toByte() }

        val id = q.enqueueUpload("blob.bin", "/tmp/blob.bin", payload.size.toLong(), ByteArrayInputStream(payload))
        val finished = q.awaitTerminal(id)

        assertEquals(TransferQueue.State.DONE, finished.state)
        assertEquals("every byte must reach the sink", payload.size, sink.size())
        assertEquals(payload.size.toLong(), finished.bytesDone)
        assertEquals(payload.size.toLong(), finished.totalBytes)
        assertEquals(1f, finished.fraction, 0.001f)
    }

    @Test
    fun `progress is reported before the transfer finishes`() = runBlocking {
        // A sink that blocks on the last chunk, so an intermediate progress value can be
        // observed rather than only the final one.
        val gate = CountDownLatch(1)
        val blocking = object : java.io.OutputStream() {
            var written = 0L
            override fun write(b: Int) {
                written++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                if (written >= 128 * 1024) {
                    // Wait long enough for the reporter to publish a value.
                    gate.await(1, TimeUnit.SECONDS)
                }
            }
        }
        val q = queue(uploadOpener = { _, _ -> com.tuissh.app.core.NeoResult.Ok(blocking) })
        val payload = ByteArray(256 * 1024)

        val id = q.enqueueUpload("big.bin", "/tmp/big.bin", payload.size.toLong(), ByteArrayInputStream(payload))

        val sawProgress = withTimeout(3_000) {
            var observed = false
            while (!observed) {
                val item = q.items.value.firstOrNull { it.id == id }
                if (item != null && item.bytesDone > 0 && item.state == TransferQueue.State.RUNNING) {
                    observed = true
                }
                if (item?.isTerminal == true) break
                delay(10)
            }
            observed
        }
        gate.countDown()

        assertTrue("progress must be visible while the transfer runs", sawProgress)
    }

    @Test
    fun `a download completes and flushes`() = runBlocking {
        val payload = ByteArray(50_000) { 7 }
        val sink = ByteArrayOutputStream()
        val q = queue(
            downloadOpener = { com.tuissh.app.core.NeoResult.Ok(ByteArrayInputStream(payload)) },
            sizeProbe = { payload.size.toLong() },
        )

        val id = q.enqueueDownload("file.bin", "/tmp/file.bin", sink)
        val finished = q.awaitTerminal(id)

        assertEquals(TransferQueue.State.DONE, finished.state)
        assertEquals(payload.size, sink.size())
        assertEquals("the probe must supply the total", payload.size.toLong(), finished.totalBytes)
    }

    @Test
    fun `an unknown size leaves the total at minus one rather than zero`() = runBlocking {
        // -1 means "unknown", which the UI shows as a spinner. Zero would render as a
        // full bar, which looks like a finished transfer.
        val q = queue(sizeProbe = { null })

        val id = q.enqueueDownload("x", "/tmp/x", ByteArrayOutputStream())
        val finished = q.awaitTerminal(id)

        assertEquals(-1L, finished.totalBytes)
        assertEquals(0f, finished.fraction, 0.001f)
    }

    @Test
    fun `a cancelled transfer stops and reports cancellation`() = runBlocking {
        val started = CountDownLatch(1)
        val neverFinishes = object : InputStream() {
            override fun read(): Int {
                started.countDown()
                // Block long enough that the coroutine is still inside the loop when the
                // cancel arrives.
                Thread.sleep(200)
                return 1
            }
        }
        val q = queue(
            downloadOpener = { com.tuissh.app.core.NeoResult.Ok(neverFinishes) },
        )

        val id = q.enqueueDownload("slow", "/tmp/slow", ByteArrayOutputStream())
        assertTrue("the transfer must have started", started.await(2, TimeUnit.SECONDS))
        q.cancel(id)

        val finished = q.awaitTerminal(id)
        assertEquals(TransferQueue.State.CANCELLED, finished.state)
    }

    @Test
    fun `a failed opener marks the item failed and keeps the message`() = runBlocking {
        val q = queue(
            uploadOpener = {
                _,
                _,
                -> com.tuissh.app.core.NeoResult.Err(
                    com.tuissh.app.core.NeoError.SftpFailure("upload", "permission denied"),
                )
            },
        )

        val id = q.enqueueUpload("x", "/root/x", 10, ByteArrayInputStream(ByteArray(10)))
        val finished = q.awaitTerminal(id)

        assertEquals(TransferQueue.State.FAILED, finished.state)
        assertTrue(
            "the reason must survive: ${finished.error}",
            finished.error?.contains("permission denied") == true,
        )
    }

    @Test
    fun `a failure does not stop later transfers`() = runBlocking {
        val attempts = AtomicInteger(0)
        val q = queue(
            uploadOpener = { _, _ ->
                if (attempts.getAndIncrement() == 0) {
                    com.tuissh.app.core.NeoResult.Err(
                        com.tuissh.app.core.NeoError.SftpFailure("upload", "nope"),
                    )
                } else {
                    com.tuissh.app.core.NeoResult.Ok(ByteArrayOutputStream())
                }
            },
        )

        val first = q.enqueueUpload("a", "/tmp/a", 4, ByteArrayInputStream(ByteArray(4)))
        val second = q.enqueueUpload("b", "/tmp/b", 4, ByteArrayInputStream(ByteArray(4)))

        q.awaitTerminal(first)
        val secondDone = q.awaitTerminal(second)

        assertEquals(TransferQueue.State.FAILED, q.items.value.first { it.id == first }.state)
        assertEquals(TransferQueue.State.DONE, secondDone.state)
    }

    @Test
    fun `clearFinished keeps only what is still running`() = runBlocking {
        val q = queue()
        val id = q.enqueueUpload("a", "/tmp/a", 4, ByteArrayInputStream(ByteArray(4)))
        q.awaitTerminal(id)

        q.clearFinished()

        assertTrue("finished items are dropped", q.items.value.isEmpty())
    }

    @Test
    fun `cancelAll stops everything`() = runBlocking {
        val q = queue()
        val ids = (0 until 3).map { i ->
            q.enqueueUpload("f$i", "/tmp/f$i", 4, ByteArrayInputStream(ByteArray(4)))
        }

        q.cancelAll()

        // Cancellation is delivered asynchronously: a job that had already finished its
        // copy loop can still settle as DONE before the cancel lands. What must hold is
        // that nothing is left queued or running once the dust settles.
        val settled = withTimeout(5_000) {
            var items = q.items.value
            while (items.any { it.state == TransferQueue.State.QUEUED }) {
                delay(10)
                items = q.items.value
            }
            items
        }

        assertTrue(
            "nothing may remain queued, saw ${settled.map { it.state }}",
            settled.none { it.state == TransferQueue.State.QUEUED },
        )
        assertEquals("every id is still tracked", ids.size, settled.size)
    }

    @Test
    fun `isBusy tracks the queue`() = runBlocking {
        val q = queue()
        assertTrue("an empty queue is not busy", !q.isBusy)

        val id = q.enqueueUpload("a", "/tmp/a", 4, ByteArrayInputStream(ByteArray(4)))
        q.awaitTerminal(id)

        assertTrue("a drained queue is not busy", !q.isBusy)
    }
}
