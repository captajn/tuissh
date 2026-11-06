package com.tuissh.app.ui.key

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.model.PrivateKeyEntity
import com.tuissh.app.data.repo.ServerRepository
import com.tuissh.app.ssh.key.KeyParseResult
import com.tuissh.app.ssh.key.KeyParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Key management state.
 *
 * A key is pasted as text, parsed immediately, and only stored once the parse succeeded
 * and the user has seen what the parser decided it is. Storing first and validating
 * later would let a mis-pasted key sit in the database looking legitimate until a
 * connection fails with no useful message.
 */
class KeyListViewModel(private val repository: ServerRepository) : ViewModel() {

    data class UiState(
        val keys: List<PrivateKeyEntity> = emptyList(),
        val loading: Boolean = true,
        /** Result of the last parse attempt, shown before the user commits to saving. */
        val preview: Preview? = null,
        val error: String? = null,
        val importedName: String? = null,
    )

    /** What the parser made of the pasted text. */
    data class Preview(
        val name: String,
        val algorithm: String,
        val bits: Int,
        val fingerprint: String?,
        val publicKey: String?,
        val hasPassphrase: Boolean,
        val pem: String,
        /** Set when the text parsed but cannot be stored, with the reason. */
        val blockingProblem: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            // The repository exposes this as a Flow; taking the first emission is
            // enough here because every mutation in this screen calls load() again.
            val keys = repository.observeKeys().first()
            _state.value = _state.value.copy(keys = keys, loading = false)
        }
    }

    /**
     * Parse pasted text and show what it is, without storing anything.
     *
     * @param suggestedName used when the user does not type one; defaults to the
     *        algorithm so the list is readable straight away
     */
    fun previewPaste(text: String, suggestedName: String, passphrase: String?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(preview = null, error = null)
            return
        }

        when (val parsed = KeyParser.parse(trimmed, passphrase)) {
            is KeyParseResult.Success -> {
                val key = parsed.key
                _state.value = _state.value.copy(
                    error = null,
                    preview = Preview(
                        name = suggestedName.ifBlank { key.algorithm },
                        algorithm = key.algorithm,
                        bits = key.bits,
                        fingerprint = key.fingerprintSha256,
                        publicKey = key.publicKeyOpenSsh,
                        hasPassphrase = !key.passphrase.isNullOrEmpty(),
                        pem = key.pem,
                    ),
                )
            }

            is KeyParseResult.NeedsPassphrase -> {
                // The key is real but locked. Show that state instead of an error: the
                // user needs to know a passphrase will be required on every connect.
                _state.value = _state.value.copy(
                    error = null,
                    preview = Preview(
                        name = suggestedName.ifBlank { "encrypted key" },
                        algorithm = "unknown",
                        bits = 0,
                        fingerprint = null,
                        publicKey = null,
                        hasPassphrase = true,
                        pem = trimmed,
                        blockingProblem = "This key is encrypted. Enter its passphrase so it " +
                            "can be identified, or the app will ask on every connection.",
                    ),
                )
            }

            is KeyParseResult.BadPassphrase -> {
                _state.value = _state.value.copy(
                    preview = null,
                    error = "That passphrase does not open this key.",
                )
            }

            is KeyParseResult.Failure -> {
                // The parser's message already names the problem (a public key, an
                // unsupported PPK, a truncated body), so it is shown verbatim.
                _state.value = _state.value.copy(preview = null, error = parsed.reason)
            }
        }
    }

    /** Store the current preview as a key the server forms can pick. */
    fun commitPreview(passphrase: String?) {
        val preview = _state.value.preview ?: return
        if (preview.blockingProblem != null) return

        viewModelScope.launch {
            when (val saved = repository.importKey(
                name = preview.name,
                pem = preview.pem,
                algorithm = preview.algorithm,
                bits = preview.bits,
                passphrase = passphrase?.takeIf { it.isNotEmpty() },
                publicKeyOpenSsh = preview.publicKey,
                fingerprintSha256 = preview.fingerprint,
            )) {
                is NeoResult.Ok -> {
                    _state.value = _state.value.copy(
                        preview = null,
                        error = null,
                        importedName = preview.name,
                    )
                    load()
                }
                is NeoResult.Err -> _state.value = _state.value.copy(
                    error = saved.error.message ?: "could not store the key",
                )
            }
        }
    }

    fun clearPreview() {
        _state.value = _state.value.copy(preview = null, error = null)
    }

    fun clearImported() {
        _state.value = _state.value.copy(importedName = null)
    }

    /**
     * Delete a key.
     *
     * The repository refuses when servers still reference it, because silently breaking
     * those servers' logins would be worse than making the user reassign them first.
     */
    fun delete(key: PrivateKeyEntity) {
        viewModelScope.launch {
            when (val result = repository.deleteKey(key.id)) {
                is NeoResult.Ok -> load()
                is NeoResult.Err -> _state.value = _state.value.copy(
                    error = result.error.message ?: "could not delete the key",
                )
            }
        }
    }
}
