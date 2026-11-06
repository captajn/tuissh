package com.tuissh.app.ui.docker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.docker.DockerClient
import com.tuissh.app.docker.DockerParsers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Container list state.
 *
 * Replaces `DockerViewModel` from the original app. Tabs mirror it: containers,
 * images, volumes, networks — the four things an operator actually opens a container
 * host to look at.
 */
class DockerViewModel(private val client: DockerClient) : ViewModel() {

    enum class Tab { CONTAINERS, IMAGES, VOLUMES, NETWORKS }

    data class UiState(
        val tab: Tab = Tab.CONTAINERS,
        val loading: Boolean = false,
        val engine: DockerClient.EngineInfo? = null,
        val error: NeoError? = null,
        val containers: List<DockerParsers.Container> = emptyList(),
        val images: List<DockerParsers.Image> = emptyList(),
        val volumes: List<DockerParsers.Volume> = emptyList(),
        val networks: List<DockerParsers.Network> = emptyList(),
        val includeStopped: Boolean = true,
        /** Container whose logs are open, and the log text. */
        val logTarget: DockerParsers.Container? = null,
        val logs: String = "",
        val logsLoading: Boolean = false,
        /** Container id with an action in flight, so its buttons can be disabled. */
        val busyId: String? = null,
    ) {
        val runningCount: Int get() = containers.count { it.isRunning }
        val stoppedCount: Int get() = containers.count { !it.isRunning }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)

            // Detect first so an install/permission problem is reported as itself
            // rather than as an empty container list.
            when (val detected = client.detect()) {
                is NeoResult.Err -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = detected.error,
                        containers = emptyList(),
                    )
                    return@launch
                }
                is NeoResult.Ok -> {
                    _state.value = _state.value.copy(engine = detected.value)
                }
            }

            when (val list = client.listContainers(_state.value.includeStopped)) {
                is NeoResult.Ok -> _state.value = _state.value.copy(
                    loading = false,
                    containers = list.value,
                    error = null,
                )
                is NeoResult.Err -> _state.value = _state.value.copy(
                    loading = false,
                    error = list.error,
                )
            }
        }
    }

    fun selectTab(tab: Tab) {
        _state.value = _state.value.copy(tab = tab)
        when (tab) {
            Tab.CONTAINERS -> load()
            Tab.IMAGES -> loadImages()
            Tab.VOLUMES -> loadVolumes()
            Tab.NETWORKS -> loadNetworks()
        }
    }

    fun loadImages() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val r = client.listImages()) {
                is NeoResult.Ok -> _state.value = _state.value.copy(
                    loading = false,
                    images = r.value,
                )
                is NeoResult.Err -> _state.value = _state.value.copy(
                    loading = false,
                    error = r.error,
                )
            }
        }
    }

    fun loadVolumes() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val r = client.listVolumes()) {
                is NeoResult.Ok -> _state.value = _state.value.copy(
                    loading = false,
                    volumes = r.value,
                )
                is NeoResult.Err -> _state.value = _state.value.copy(
                    loading = false,
                    error = r.error,
                )
            }
        }
    }

    fun loadNetworks() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val r = client.listNetworks()) {
                is NeoResult.Ok -> _state.value = _state.value.copy(
                    loading = false,
                    networks = r.value,
                )
                is NeoResult.Err -> _state.value = _state.value.copy(
                    loading = false,
                    error = r.error,
                )
            }
        }
    }

    fun toggleIncludeStopped() {
        _state.value = _state.value.copy(includeStopped = !_state.value.includeStopped)
        load()
    }

    /**
     * Start or stop a container, then refresh.
     *
     * The refresh is unconditional: a start that reports success but leaves the
     * container exited happens (missing volume, bad command), and a stale list would
     * hide it.
     */
    fun toggleRunning(container: DockerParsers.Container) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = container.id)
            val result = if (container.isRunning) {
                client.stop(container.id)
            } else {
                client.start(container.id)
            }
            _state.value = _state.value.copy(busyId = null)
            if (result is NeoResult.Err) {
                _state.value = _state.value.copy(error = result.error)
            }
            load()
        }
    }

    fun restart(container: DockerParsers.Container) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = container.id)
            val result = client.restart(container.id)
            _state.value = _state.value.copy(busyId = null)
            if (result is NeoResult.Err) {
                _state.value = _state.value.copy(error = result.error)
            }
            load()
        }
    }

    fun openLogs(container: DockerParsers.Container, lines: Int = 500) {
        viewModelScope.launch {
            _state.value = _state.value.copy(logTarget = container, logsLoading = true, logs = "")
            when (val r = client.logs(container.id, lines)) {
                is NeoResult.Ok -> _state.value = _state.value.copy(
                    logsLoading = false,
                    logs = r.value,
                )
                is NeoResult.Err -> _state.value = _state.value.copy(
                    logsLoading = false,
                    logs = r.error.message.orEmpty(),
                )
            }
        }
    }

    fun closeLogs() {
        _state.value = _state.value.copy(logTarget = null, logs = "")
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
