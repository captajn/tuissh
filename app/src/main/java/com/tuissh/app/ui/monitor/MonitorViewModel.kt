package com.tuissh.app.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.monitor.MonitorClient
import com.tuissh.app.monitor.MonitorParsers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Metrics tab state.
 *
 * Replaces the original app's `MonitorViewModel`. Polls while the tab is visible and
 * stops when it is not: an SSH session kept busy reading `/proc` every second drains
 * the phone battery and the server's CPU for a screen nobody is looking at.
 */
class MonitorViewModel(
    private val client: MonitorClient,
    private val pollIntervalMs: Long = 2_000L,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val error: NeoError? = null,
        val snapshot: MonitorClient.Snapshot? = null,
        val watching: Boolean = false,
        /** Set while the first CPU sample has no baseline yet. */
        val cpuWarmingUp: Boolean = false,
    ) {
        val cpu: MonitorParsers.CpuSnapshot? get() = snapshot?.cpu
        val memory: MonitorParsers.MemoryInfo? get() = snapshot?.memory
        val disks: List<MonitorParsers.DiskInfo> get() = snapshot?.disks.orEmpty()
        val processes: List<MonitorParsers.ProcessInfo> get() = snapshot?.processes.orEmpty()
        val hostLabel: String? get() = snapshot?.hostLabel
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /**
     * Start polling.
     *
     * The first snapshot always reports `cpuWarmingUp` because a percentage needs two
     * `/proc/stat` reads; the UI shows a dash instead of inventing 0%.
     */
    fun start() {
        if (pollJob?.isActive == true) return
        _state.value = _state.value.copy(watching = true, loading = true)
        pollJob = viewModelScope.launch {
            // A fresh view must not average over the gap while it was closed.
            client.resetCpuBaseline()

            var first = true
            while (isActive) {
                when (val result = client.sample()) {
                    is NeoResult.Ok -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = null,
                            snapshot = result.value,
                            cpuWarmingUp = result.value.cpu == null,
                        )
                        first = false
                    }
                    is NeoResult.Err -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = result.error,
                        )
                        // A failed host should not be hammered; back off but keep the
                        // previous snapshot on screen so the last known values stay
                        // readable while the connection recovers.
                        delay(FAILED_POLL_BACKOFF_MS)
                        continue
                    }
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _state.value = _state.value.copy(watching = false)
    }

    /** One-shot refresh, used by pull-to-refresh. */
    fun refresh() {
        viewModelScope.launch {
            when (val result = client.sample()) {
                is NeoResult.Ok -> _state.value = _state.value.copy(
                    error = null,
                    snapshot = result.value,
                    cpuWarmingUp = result.value.cpu == null,
                )
                is NeoResult.Err -> _state.value = _state.value.copy(error = result.error)
            }
        }
    }

    /**
     * Ask a process to stop.
     *
     * The list is refreshed immediately afterwards: a signal that succeeds still takes
     * a moment to take effect, but showing the old row forever is worse than showing
     * it once more.
     */
    fun kill(process: MonitorParsers.ProcessInfo, force: Boolean = false) {
        viewModelScope.launch {
            val result = client.kill(process.pid, force)
            if (result is NeoResult.Err) {
                _state.value = _state.value.copy(error = result.error)
            }
            delay(500)
            refresh()
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private companion object {
        /** Longer gap after a failure, so a dead host is not polled every 2 seconds. */
        const val FAILED_POLL_BACKOFF_MS = 10_000L
    }
}
