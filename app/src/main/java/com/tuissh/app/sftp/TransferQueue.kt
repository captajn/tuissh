package com.tuissh.app.sftp

import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Sequential transfer queue.
 *
 * Transfers run one at a time on purpose: an SFTP channel multiplexes requests, and
 * running several large transfers in parallel on one channel mostly adds contention
 * rather than throughput, while making progress reporting meaningless.
 *
 * Each job carries its own [Job] so cancelling one transfer does not disturb the queue.
 * That matters because the case a user actually hits is "this 2 GB upload was a mistake",
 * not "stop everything".
 */
class TransferQueue(
    private val scope: CoroutineScope,
    /** Opens an upload of [length] bytes into [remotePath]. */
    private val uploadOpener: (remotePath: String, length: Long) -> NeoResult<OutputStream>,
    /** Opens a stream to read [remotePath]. */
    private val downloadOpener: (remotePath: String) -> NeoResult<InputStream>,
    /** Size of a remote file, or null when it cannot be determined. */
    private val sizeProbe: (remotePath: String) -> Long? = { null },
) {

    enum class Direction { UPLOAD, DOWNLOAD }

    enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    /**
     * One queued transfer.
     *
     * [bytesDone] and [totalBytes] are what the progress bar needs; `totalBytes` is -1
     * when the size is unknown, which happens for an upload from a content provider
     * that does not report one.
     */
    data class Item(
        val id: Long,
        val direction: Direction,
        val name: String,
        val remotePath: String,
        val state: State = State.QUEUED,
        val bytesDone: Long = 0,
        val totalBytes: Long = -1,
        val error: String? = null,
    ) {
        val fraction: Float
            get() = when {
                totalBytes <= 0 -> 0f
                else -> (bytesDone.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            }

        val isTerminal: Boolean
            get() = state == State.DONE || state == State.FAILED || state == State.CANCELLED
    }

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val nextId = AtomicLong(1)
    private val jobs = mutableMapOf<Long, Job>()

    /** Serialises byte movement so the queue behaves as documented. */
    private val channel = Mutex()

    /** Queue an upload; returns the id so the caller can cancel it. */
    fun enqueueUpload(
        name: String,
        remotePath: String,
        length: Long,
        source: InputStream,
    ): Long {
        val id = nextId.getAndIncrement()
        add(Item(id, Direction.UPLOAD, name, remotePath, totalBytes = length))

        jobs[id] = scope.launch {
            run(id) {
                val sink = uploadOpener(remotePath, length)
                if (sink is NeoResult.Err) return@run sink
                sink.getOrThrow().use { out ->
                    pump(id, source, out)
                }
                NeoResult.Ok(Unit)
            }
        }
        return id
    }

    /** Queue a download. */
    fun enqueueDownload(name: String, remotePath: String, destination: OutputStream): Long {
        val id = nextId.getAndIncrement()
        val size = runCatching { sizeProbe(remotePath) }.getOrNull() ?: -1L
        add(Item(id, Direction.DOWNLOAD, name, remotePath, totalBytes = size))

        jobs[id] = scope.launch {
            run(id) {
                val sourceStream = downloadOpener(remotePath)
                if (sourceStream is NeoResult.Err) return@run sourceStream
                sourceStream.getOrThrow().use { input ->
                    pump(id, input, destination)
                }
                destination.flush()
                NeoResult.Ok(Unit)
            }
        }
        return id
    }

    /**
     * Cancel one transfer.
     *
     * A queued item is dropped; a running one has its coroutine cancelled, which closes
     * the stream it is using and therefore stops the network read at the next boundary.
     */
    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        update(id) { item ->
            if (item.state == State.QUEUED || item.state == State.RUNNING) {
                item.copy(state = State.CANCELLED)
            } else {
                item
            }
        }
    }

    /** Drop every finished item, leaving anything still in flight. */
    fun clearFinished() {
        _items.update { list -> list.filterNot { it.isTerminal } }
    }

    /** Cancel everything and empty the list. */
    fun cancelAll() {
        // Copy before cancelling: a cancelled job's completion handler removes itself
        // from `jobs`, and iterating the map while it mutates throws
        // ConcurrentModificationException.
        val pending = jobs.values.toList()
        jobs.clear()
        for (job in pending) job.cancel()
        _items.update { list ->
            list.map { item ->
                if (item.isTerminal) item else item.copy(state = State.CANCELLED)
            }
        }
    }

    /** True while at least one transfer is still moving bytes. */
    val isBusy: Boolean
        get() = _items.value.any { it.state == State.QUEUED || it.state == State.RUNNING }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun run(id: Long, body: suspend () -> NeoResult<Unit>) {
        // Wait for the channel. This is what makes the class a queue rather than a set of
        // jobs fired at once: three transfers sharing one SFTP channel mostly contend,
        // and their progress numbers stop meaning anything.
        channel.withLock {
            // The copy-on-write update keeps the list immutable for Compose while
            // avoiding a lock around state changes.
            update(id) { it.copy(state = State.RUNNING, error = null) }
            val outcome = try {
                body()
            } catch (e: CancellationException) {
                update(id) { it.copy(state = State.CANCELLED) }
                jobs.remove(id)
                throw e
            } catch (e: Exception) {
                NeoLog.e(LogTag.SFTP, "transfer $id failed", e)
                NeoResult.Err(NeoError.Io(e.message ?: "transfer failed"))
            }

            when (outcome) {
                is NeoResult.Ok -> update(id) { it.copy(state = State.DONE) }
                is NeoResult.Err -> update(id) {
                    it.copy(state = State.FAILED, error = outcome.error.message)
                }
            }
            jobs.remove(id)
        }
    }

    /**
     * Copy the stream, reporting progress.
     *
     * The buffer is 64 KiB because smaller reads turn into proportionally more SFTP
     * round trips, and the progress update is throttled to roughly one per 32 KiB so the
     * UI is not rebuilt for every chunk.
     */
    private suspend fun pump(id: Long, input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        var done = 0L
        var sinceReport = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            done += read
            sinceReport += read
            if (sinceReport >= REPORT_BYTES) {
                sinceReport = 0
                val snapshot = done
                update(id) { it.copy(bytesDone = snapshot) }
            }
        }
        output.flush()
        val finalDone = done
        update(id) { it.copy(bytesDone = finalDone) }
    }

    /**
     * Append atomically.
     *
     * `_items.value = _items.value + item` loses entries when two enqueues race: both
     * read the same list and the second write replaces the first. MutableStateFlow.update
     * loops on compare-and-set, so a burst of enqueues cannot drop a transfer.
     */
    private fun add(item: Item) {
        _items.update { current -> current + item }
    }

    private fun update(id: Long, transform: (Item) -> Item) {
        _items.update { current ->
            current.map { if (it.id == id) transform(it) else it }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val REPORT_BYTES = 32 * 1024
    }
}
