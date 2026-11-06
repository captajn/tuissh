package com.tuissh.app.terminal.session

import com.tuissh.app.core.LogTag
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoLog
import com.tuissh.app.core.NeoResult
import com.tuissh.app.ssh.SshSession
import com.tuissh.app.terminal.parse.VtEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Wires an [SshSession] shell channel to a [VtEmulator].
 *
 * Replaces `TerminalViewModel` + `TermSessionModel` + `ServerTerminalViewModel`
 * from the original app. Responsibilities:
 *
 *   - pump remote bytes into the emulator (read loop)
 *   - push local keystrokes to the remote pty (write queue)
 *   - keep grid size in sync across emulator / pty / renderer
 *   - expose damage events so the Compose layer repaints only what changed
 *
 * Threading: one reader coroutine on IO, one writer coroutine on IO. The UI never
 * touches the socket.
 */
class TerminalSession(
    private val ssh: SshSession,
    cols: Int,
    rows: Int,
    private val scrollbackLimit: Int = 5000,
) {

    /** Terminal engine; safe to read from the UI thread between [onDamage] ticks. */
    val emulator: VtEmulator = VtEmulator(rows, cols, scrollbackLimit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var shell: SshSession.ShellHandle? = null

    private val writeQueue = Channel<ByteArray>(capacity = 256)

    // --- observable state ---------------------------------------------------

    private val _state = MutableStateFlow<TerminalState>(TerminalState.DISCONNECTED)
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val _size = MutableStateFlow(GridSize(cols, rows))
    val size: StateFlow<GridSize> = _size.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /**
     * Emitted once per batch of remote output. The UI collects this and re-reads
     * the emulator; it is deliberately not a per-frame state value.
     */
    private val _damage = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val damage: SharedFlow<Long> = _damage.asSharedFlow()

    private var damageSeq = 0L

    data class GridSize(val cols: Int, val rows: Int)

    sealed interface TerminalState {
        data object DISCONNECTED : TerminalState
        data object CONNECTING : TerminalState
        data object CONNECTED : TerminalState
        data class Failed(val error: NeoError) : TerminalState
        data object CLOSED : TerminalState
    }

    init {
        emulator.onTitleChanged = { t ->
            _title.value = t
            _damage.tryEmit(++damageSeq)
        }
        emulator.damageListener = {
            // Called for every feed(); batching is handled by the collector's
            // conflation, so emitting here stays cheap.
        }
        emulator.onBell = { _bell.tryEmit(Unit) }
        emulator.onClipboardWrite = { _clipboard.tryEmit(it) }
    }

    private val _bell = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val bell: SharedFlow<Unit> = _bell.asSharedFlow()

    private val _clipboard = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val clipboard: SharedFlow<String> = _clipboard.asSharedFlow()

    // =====================================================================
    // Lifecycle
    // =====================================================================

    /** Connect and start pumping. */
    suspend fun start(): NeoResult<Unit> {
        _state.value = TerminalState.CONNECTING

        val connect = ssh.connect()
        if (connect is NeoResult.Err) {
            _state.value = TerminalState.Failed(connect.error)
            return connect
        }

        val opened = ssh.openShell(_size.value.cols, _size.value.rows)
        if (opened is NeoResult.Err) {
            _state.value = TerminalState.Failed(opened.error)
            return opened
        }

        val handle = opened.getOrThrow()
        shell = handle

        readerJob = scope.launch { readLoop(handle) }
        writerJob = scope.launch { writeLoop(handle) }

        _state.value = TerminalState.CONNECTED
        NeoLog.i(LogTag.TERM, "terminal session started (${_size.value.cols}x${_size.value.rows})")
        return NeoResult.Ok(Unit)
    }

    private suspend fun readLoop(handle: SshSession.ShellHandle) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            while (scope.isActive) {
                val n = handle.output.read(buffer)
                if (n < 0) {
                    NeoLog.i(LogTag.TERM, "remote closed the shell channel")
                    _state.value = TerminalState.CLOSED
                    return
                }
                if (n == 0) continue
                emulator.feed(buffer, 0, n)
                _damage.tryEmit(++damageSeq)
            }
        } catch (e: IOException) {
            if (scope.isActive) {
                NeoLog.w(LogTag.TERM, "read loop ended: ${e.message}")
                _state.value = TerminalState.Failed(NeoError.ConnectionLost)
            }
        } catch (e: Exception) {
            NeoLog.e(LogTag.TERM, "read loop crashed", e)
            _state.value = TerminalState.Failed(NeoError.Io(e.message ?: "read failed"))
        }
    }

    private suspend fun writeLoop(handle: SshSession.ShellHandle) {
        try {
            for (bytes in writeQueue) {
                if (bytes.isEmpty()) continue
                handle.input.write(bytes)
                handle.input.flush()
            }
        } catch (e: IOException) {
            NeoLog.w(LogTag.TERM, "write loop ended: ${e.message}")
        }
    }

    // =====================================================================
    // Input
    // =====================================================================

    /**
     * Send a line of text and press Enter, in that order.
     *
     * The command text must reach the pty *before* the carriage return. Sending
     * `\r` first runs an empty command, which closes some shells (Windows `cmd`
     * with `ProcessShellCommandFactory` in particular) and looked like the session
     * dying on its own.
     */
    fun submitLine(text: String) {
        if (_state.value !is TerminalState.CONNECTED) return
        if (text.isNotEmpty()) {
            writeQueue.trySend(text.toByteArray(Charsets.UTF_8))
        }
        writeQueue.trySend(byteArrayOf(0x0D))
    }

    /** Send already-encoded terminal input (see [TerminalInputEncoder]). */
    fun send(bytes: ByteArray) {
        if (_state.value !is TerminalState.CONNECTED) return
        writeQueue.trySend(bytes)
    }

    fun sendText(text: String) = send(text.toByteArray(Charsets.UTF_8))

    /** Paste, honouring bracketed-paste mode when the app enabled it. */
    fun paste(text: String) {
        val bracketed = emulator.isModeSet(com.tuissh.app.terminal.ansi.TerminalMode.BRACKETED_PASTE)
        if (bracketed) {
            sendText("\u001B[200~$text\u001B[201~")
        } else {
            sendText(text)
        }
    }

    // =====================================================================
    // Resize
    // =====================================================================

    /**
     * Apply a new grid size to the emulator and the remote pty.
     *
     * Called from the UI layout pass, so it is intentionally cheap and idempotent.
     */
    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        val current = _size.value
        if (current.cols == cols && current.rows == rows) return
        _size.value = GridSize(cols, rows)
        emulator.resize(rows, cols)
        ssh.resize(cols, rows)
        _damage.tryEmit(++damageSeq)
    }

    // =====================================================================
    // Teardown
    // =====================================================================

    suspend fun stop() {
        writeQueue.close()
        runCatching { shell?.channel?.disconnect() }
        readerJob?.cancel()
        writerJob?.cancel()
        ssh.disconnect()
        scope.cancel()
        _state.value = TerminalState.CLOSED
        NeoLog.i(LogTag.TERM, "terminal session stopped")
    }

    private companion object {
        const val READ_BUFFER = 16 * 1024
    }
}
