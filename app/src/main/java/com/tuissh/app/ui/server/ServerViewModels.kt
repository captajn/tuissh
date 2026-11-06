package com.tuissh.app.ui.server

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuissh.app.data.model.ServerEntity
import com.tuissh.app.data.repo.ServerRepository
import com.tuissh.app.data.model.PrivateKeyEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Server list state and commands.
 *
 * Replaces the list half of `ServerStatusViewModel` / `SSHManager`'s bookkeeping.
 */
class ServerListViewModel(
    private val repository: ServerRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    /** Servers filtered by the current search text. */
    val servers: StateFlow<List<ServerEntity>> =
        combine(repository.observeServers(), _query) { all, q ->
            if (q.isBlank()) {
                all
            } else {
                val needle = q.trim().lowercase()
                all.filter {
                    it.name.lowercase().contains(needle) ||
                        it.host.lowercase().contains(needle) ||
                        it.username.lowercase().contains(needle) ||
                        it.tags.lowercase().contains(needle)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun toggleSelection(id: Long) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.deleteServer(id)
            _selected.value = _selected.value - id
        }
    }

    fun toggleFavorite(server: ServerEntity) {
        viewModelScope.launch { repository.setFavorite(server.id, !server.favorite) }
    }
}

/**
 * Form state for creating or editing a server.
 */
class ServerEditViewModel(
    private val repository: ServerRepository,
    private val serverId: Long?,
) : ViewModel() {

    data class Form(
        val id: Long = 0,
        val name: String = "",
        val host: String = "",
        val port: String = "22",
        val username: String = "",
        val password: String = "",
        val authType: String = ServerEntity.AUTH_PASSWORD,
        val privateKeyId: Long? = null,
        val savePassword: Boolean = true,
        val groupId: Long? = null,
        val tags: String = "",
        val note: String = "",
        val terminalType: String = "xterm-256color",
        val keepaliveSeconds: String = "30",
        val bastionServerId: Long? = null,
        val useMosh: Boolean = false,
        val moshUdpPort: String = "",
        val macAddress: String = "",
        val favorite: Boolean = false,
    ) {
        /** Simple validation mirroring the original "Save failed, ..." messages. */
        val hostError: Boolean get() = host.isBlank()
        val userError: Boolean get() = username.isBlank()
        val portError: Boolean get() = port.toIntOrNull()?.let { it in 1..65535 } != true
        val canSave: Boolean get() = !hostError && !userError && !portError && name.isNotBlank()
    }

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    /**
     * Keys available to this form.
     *
     * Collected here rather than in the screen so the list survives recomposition and
     * is already loaded by the time the user switches to "private key".
     */
    val keys: StateFlow<List<PrivateKeyEntity>> = repository.observeKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saved = MutableStateFlow<Long?>(null)
    val saved: StateFlow<Long?> = _saved.asStateFlow()

    init {
        if (serverId != null) {
            viewModelScope.launch {
                repository.server(serverId)?.let { s ->
                    _form.value = Form(
                        id = s.id,
                        name = s.name,
                        host = s.host,
                        port = s.port.toString(),
                        username = s.username,
                        password = "",
                        authType = s.authType,
                        privateKeyId = s.privateKeyId,
                        savePassword = s.savePassword,
                        groupId = s.groupId,
                        tags = s.tags,
                        note = s.note,
                        terminalType = s.terminalType,
                        keepaliveSeconds = s.keepaliveSeconds.toString(),
                        bastionServerId = s.bastionServerId,
                        useMosh = s.useMosh,
                        moshUdpPort = s.moshUdpPort.orEmpty(),
                        macAddress = s.macAddress.orEmpty(),
                        favorite = s.favorite,
                    )
                }
            }
        }
    }

    fun update(transform: (Form) -> Form) {
        _form.value = transform(_form.value)
    }

    fun save() {
        val f = _form.value
        if (!f.canSave) return
        viewModelScope.launch {
            val existing = if (f.id > 0) repository.server(f.id) else null
            val entity = (existing ?: ServerEntity(name = f.name, host = f.host, username = f.username))
                .copy(
                    name = f.name.trim(),
                    host = f.host.trim(),
                    username = f.username.trim(),
                    port = f.port.toIntOrNull() ?: 22,
                    authType = f.authType,
                    privateKeyId = f.privateKeyId,
                    groupId = f.groupId,
                    tags = f.tags,
                    note = f.note,
                    terminalType = f.terminalType,
                    keepaliveSeconds = f.keepaliveSeconds.toIntOrNull() ?: 30,
                    bastionServerId = f.bastionServerId,
                    useMosh = f.useMosh,
                    moshUdpPort = f.moshUdpPort.ifBlank { null },
                    macAddress = f.macAddress.ifBlank { null },
                    favorite = f.favorite,
                    savePassword = f.savePassword,
                )

            // null keeps the stored secret; "" clears it; anything else re-encrypts.
            val plain = when {
                f.authType != ServerEntity.AUTH_PASSWORD -> null
                f.password.isEmpty() -> null
                else -> f.password
            }

            when (val result = repository.saveServer(entity, plain)) {
                is com.tuissh.app.core.NeoResult.Ok -> _saved.value = result.value
                is com.tuissh.app.core.NeoResult.Err -> Unit
            }
        }
    }
}

/** Convenience accessor used by the compose layer. */
fun Context.neoserver(): com.tuissh.app.NeoApplication =
    applicationContext as com.tuissh.app.NeoApplication
