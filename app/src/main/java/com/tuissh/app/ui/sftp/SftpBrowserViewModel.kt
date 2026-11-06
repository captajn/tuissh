package com.tuissh.app.ui.sftp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.core.NeoFormat
import com.tuissh.app.sftp.SftpClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Stack

/**
 * SFTP browser state.
 *
 * Replaces `SFTPBrowserViewModel`. Owns a directory stack so "up" is instant and
 * does not require a round trip, and caches the listing per path so going back is
 * not slower than going forward.
 */
class SftpBrowserViewModel(
    private val client: SftpClient,
    initialPath: String = "~",
) : ViewModel() {

    data class UiState(
        val path: String = "",
        val entries: List<SftpClient.Entry> = emptyList(),
        val loading: Boolean = false,
        val error: NeoError? = null,
        val showHidden: Boolean = true,
        val sortBy: SortBy = SortBy.NAME,
        val sortAscending: Boolean = true,
        val selected: Set<String> = emptySet(),
        val diskUsage: SftpClient.DiskUsage? = null,
    ) {
        val canGoUp: Boolean get() = path != "/" && path.isNotEmpty()
        val selectionCount: Int get() = selected.size

        /** Sorted and filtered view over [entries]. */
        val visible: List<SftpClient.Entry>
            get() {
                val filtered = if (showHidden) entries else entries.filter { !it.name.startsWith(".") }
                val comparator = when (sortBy) {
                    SortBy.NAME -> compareBy<SftpClient.Entry> { it.name.lowercase() }
                    SortBy.SIZE -> compareBy { it.size }
                    SortBy.MODIFIED -> compareBy { it.modifiedAt }
                    SortBy.TYPE -> compareBy { if (it.isDirectory) 0 else 1 }
                }
                val ordered = if (sortAscending) filtered.sortedWith(comparator)
                else filtered.sortedWith(comparator.reversed())
                // Directories always float to the top, matching the original browser.
                return ordered.sortedByDescending { it.isDirectory }
            }
    }

    enum class SortBy { NAME, SIZE, MODIFIED, TYPE }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val history = Stack<String>()
    private var loadJob: Job? = null

    init {
        navigateTo(initialPath, pushHistory = false)
    }

    // --- navigation --------------------------------------------------------

    fun navigateTo(path: String, pushHistory: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val resolved = client.canonical(path)
            _state.value = _state.value.copy(loading = true, error = null, selected = emptySet())

            when (val listing = client.list(resolved)) {
                is NeoResult.Ok -> {
                    if (pushHistory && _state.value.path.isNotEmpty()) {
                        history.push(_state.value.path)
                    }
                    val usage = client.statVfs(resolved).getOrNull()
                    _state.value = _state.value.copy(
                        path = resolved,
                        entries = listing.value,
                        loading = false,
                        error = null,
                        diskUsage = usage,
                    )
                }
                is NeoResult.Err -> {
                    _state.value = _state.value.copy(loading = false, error = listing.error)
                }
            }
        }
    }

    fun refresh() = navigateTo(_state.value.path, pushHistory = false)

    fun goUp() {
        val current = _state.value.path
        if (current == "/" || current.isEmpty()) return
        val parent = if (history.isNotEmpty()) {
            history.pop()
        } else {
            current.substringBeforeLast('/', "/").ifEmpty { "/" }
        }
        navigateTo(parent, pushHistory = false)
    }

    fun open(entry: SftpClient.Entry) {
        if (entry.isDirectory) navigateTo(entry.path)
    }

    /** Resolve a symlink target directory when the user taps through. */
    fun openLink(entry: SftpClient.Entry) {
        val target = entry.linkTarget ?: return
        val resolved = if (target.startsWith("/")) {
            target
        } else {
            "${entry.path.substringBeforeLast('/', "")}/$target"
        }
        navigateTo(resolved)
    }

    // --- sorting and filters ----------------------------------------------

    fun toggleSort(by: SortBy) {
        val s = _state.value
        _state.value = if (s.sortBy == by) {
            s.copy(sortAscending = !s.sortAscending)
        } else {
            s.copy(sortBy = by, sortAscending = true)
        }
    }

    fun toggleHidden() {
        _state.value = _state.value.copy(showHidden = !_state.value.showHidden)
    }

    // --- selection ---------------------------------------------------------

    fun toggleSelection(path: String) {
        val current = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (path in current) current - path else current + path,
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet())
    }

    // --- mutations ---------------------------------------------------------

    fun deleteSelected(onDone: (List<NeoError>) -> Unit = {}) {
        val targets = _state.value.selected.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val failures = mutableListOf<NeoError>()
            for (path in targets) {
                val entry = _state.value.entries.firstOrNull { it.path == path }
                val result = if (entry?.isDirectory == true) {
                    client.removeRecursive(path)
                } else {
                    client.remove(path)
                }
                if (result is NeoResult.Err) failures.add(result.error)
            }
            clearSelection()
            refresh()
            onDone(failures)
        }
    }

    fun rename(path: String, newName: String, onDone: (NeoError?) -> Unit = {}) {
        val entry = _state.value.entries.firstOrNull { it.path == path } ?: return
        if (newName.isBlank() || newName.contains('/')) {
            onDone(NeoError.SftpFailure("rename", "invalid name"))
            return
        }
        viewModelScope.launch {
            val parent = entry.path.substringBeforeLast('/', "")
            val target = if (parent.isEmpty()) newName else "$parent/$newName"
            val result = client.rename(entry.path, target)
            refresh()
            onDone((result as? NeoResult.Err)?.error)
        }
    }

    fun createDirectory(name: String, onDone: (NeoError?) -> Unit = {}) {
        if (name.isBlank() || name.contains('/')) {
            onDone(NeoError.SftpFailure("mkdir", "invalid name"))
            return
        }
        viewModelScope.launch {
            val base = _state.value.path
            val target = if (base.endsWith("/")) "$base$name" else "$base/$name"
            val result = client.mkdir(target)
            refresh()
            onDone((result as? NeoResult.Err)?.error)
        }
    }

    fun changePermissions(path: String, octal: String, onDone: (NeoError?) -> Unit = {}) {
        viewModelScope.launch {
            val result = client.chmod(path, octal)
            refresh()
            onDone((result as? NeoResult.Err)?.error)
        }
    }

    /** Upload from an already-opened stream; the caller owns the stream's lifetime. */
    fun upload(
        fileName: String,
        source: java.io.InputStream,
        onProgress: (Long) -> Unit = {},
        onDone: (NeoError?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val base = _state.value.path
            val target = if (base.endsWith("/")) "$base$fileName" else "$base/$fileName"
            val result = client.upload(source, target)
            onProgress((result.getOrNull() ?: 0L))
            refresh()
            onDone((result as? NeoResult.Err)?.error)
        }
    }

    /** Download into [sink]; the caller owns the stream's lifetime. */
    fun download(
        path: String,
        sink: java.io.OutputStream,
        onDone: (NeoError?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val result = client.download(path, sink)
            onDone((result as? NeoResult.Err)?.error)
        }
    }

    fun close() {
        viewModelScope.launch { client.close() }
    }

    /**
     * The underlying client, exposed for the SAF transfer host.
     *
     * Transfers need the same channel the browser uses; opening a second SFTP
     * channel per transfer would double the session cost for no benefit.
     */
    fun client(): SftpClient = client

    // --- presentation helpers ---------------------------------------------

    fun sizeLabel(entry: SftpClient.Entry): String =
        if (entry.isDirectory) "" else NeoFormat.bytes(entry.size)

    fun dateLabel(entry: SftpClient.Entry): String {
        if (entry.modifiedAt <= 0) return ""
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(entry.modifiedAt))
    }

    fun usageLabel(): String {
        val usage = _state.value.diskUsage ?: return ""
        return "${NeoFormat.bytes(usage.usedBytes)} / ${NeoFormat.bytes(usage.totalBytes)}"
            .let { it }
    }
}
