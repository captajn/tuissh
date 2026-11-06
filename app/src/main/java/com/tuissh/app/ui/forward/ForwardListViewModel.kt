package com.tuissh.app.ui.forward

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.model.ForwardEntity
import com.tuissh.app.data.repo.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Port forwarding rules for one server.
 *
 * The entity, the DAO and the save/delete paths already existed; what was missing was any
 * way to create a rule. Without this screen the forwarding tables could only be filled by
 * editing the database.
 */
class ForwardListViewModel(
    private val repository: ServerRepository,
    private val serverId: Long,
) : ViewModel() {

    data class UiState(
        val rules: List<ForwardEntity> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        /** Rule being edited, or null when the list is showing. */
        val editing: ForwardEntity? = null,
        val showEditor: Boolean = false,
    ) {
        val hasRules: Boolean get() = rules.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Kind labels, in the order a user thinks about them. */
    val kinds: List<Pair<String, String>> = listOf(
        ForwardEntity.KIND_LOCAL to "Local (-L)",
        ForwardEntity.KIND_REMOTE to "Remote (-R)",
        ForwardEntity.KIND_DYNAMIC to "Dynamic / SOCKS (-D)",
    )

    fun load() {
        viewModelScope.launch {
            val rules = repository.forwardsFor(serverId)
            _state.value = _state.value.copy(rules = rules, loading = false)
        }
    }

    fun startNew() {
        _state.value = _state.value.copy(
            editing = ForwardEntity(serverId = serverId, bindPort = 8080, targetPort = 80),
            showEditor = true,
            error = null,
        )
    }

    fun edit(rule: ForwardEntity) {
        _state.value = _state.value.copy(editing = rule, showEditor = true, error = null)
    }

    fun dismissEditor() {
        _state.value = _state.value.copy(editing = null, showEditor = false)
    }

    /**
     * Validate and save.
     *
     * A dynamic rule has no fixed target, so its target fields are normalised to the
     * empty-ish defaults the transport expects rather than being left as whatever the
     * user typed before switching kinds.
     */
    fun save(rule: ForwardEntity) {
        val problem = validate(rule)
        if (problem != null) {
            _state.value = _state.value.copy(error = problem)
            return
        }

        val normalised = if (rule.kind == ForwardEntity.KIND_DYNAMIC) {
            rule.copy(targetHost = "", targetPort = 0)
        } else {
            rule
        }

        viewModelScope.launch {
            when (val saved = repository.saveForward(normalised)) {
                is NeoResult.Ok -> {
                    _state.value = _state.value.copy(showEditor = false, editing = null, error = null)
                    load()
                }
                is NeoResult.Err -> _state.value = _state.value.copy(
                    error = saved.error.message ?: "could not save the rule",
                )
            }
        }
    }

    fun delete(rule: ForwardEntity) {
        viewModelScope.launch {
            when (val result = repository.deleteForward(rule.id)) {
                is NeoResult.Ok -> load()
                is NeoResult.Err -> _state.value = _state.value.copy(
                    error = result.error.message ?: "could not delete the rule",
                )
            }
        }
    }

    /**
     * Rule problems that would only show up as a bind failure at connect time.
     *
     * Checking here is worth it because the transport's error ("Address already in use")
     * never mentions which rule caused it.
     */
    private fun validate(rule: ForwardEntity): String? = when {
        rule.bindPort !in 1..65535 -> "The local port must be between 1 and 65535."
        rule.kind == ForwardEntity.KIND_DYNAMIC && rule.bindHost.isBlank() ->
            "A SOCKS rule needs a bind address."
        rule.kind != ForwardEntity.KIND_DYNAMIC && rule.targetHost.isBlank() ->
            "The target host cannot be empty."
        rule.kind != ForwardEntity.KIND_DYNAMIC && rule.targetPort !in 1..65535 ->
            "The target port must be between 1 and 65535."
        else -> null
    }
}
