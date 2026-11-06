package com.tuissh.app.ui.hostkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.core.NeoError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI-owned prompt service.
 *
 * The SSH client needs to block on a user decision (trust an unknown host key,
 * supply a password) while running on a background thread. This class is the bridge:
 * it stores a pending [CompletableDeferred], shows a dialog, and completes the
 * deferred when the user answers.
 *
 * Replaces the auto-trust placeholder that shipped during bring-up. Auto-trusting an
 * unknown host is a real security hole: it silently accepts a man-in-the-middle on
 * first connect. The dialog is not decoration.
 */
class HostKeyPromptState {

    /** Set while an unknown host key is waiting for a decision. */
    var unknown by mutableStateOf<UnknownRequest?>(null)
        private set

    /** Set while a changed host key is waiting for a decision. */
    var changed by mutableStateOf<ChangedRequest?>(null)
        private set

    data class UnknownRequest(
        val host: String,
        val port: Int,
        val keyType: String,
        val fingerprint: String,
        val answer: CompletableDeferred<Boolean>,
    )

    data class ChangedRequest(
        val error: NeoError.HostKeyChanged,
        val answer: CompletableDeferred<Boolean>,
    )

    /**
     * Ask the user to trust a new host.
     *
     * Suspends until the dialog is answered. Being called from the SSH connect
     * coroutine is intentional: the handshake waits, the UI thread does not.
     */
    suspend fun askUnknown(
        host: String,
        port: Int,
        keyType: String,
        fingerprint: String,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val request = UnknownRequest(host, port, keyType, fingerprint, deferred)
        withContext(Dispatchers.Main) { unknown = request }
        return deferred.await()
    }

    /** Ask the user to accept a host key change. Defaults to refusing. */
    suspend fun askChanged(error: NeoError.HostKeyChanged): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val request = ChangedRequest(error, deferred)
        withContext(Dispatchers.Main) { changed = request }
        return deferred.await()
    }

    fun answerUnknown(accept: Boolean) {
        unknown?.answer?.complete(accept)
        unknown = null
    }

    fun answerChanged(accept: Boolean) {
        changed?.answer?.complete(accept)
        changed = null
    }

    /** Fail any pending prompt, used when the screen is disposed. */
    fun cancelAll() {
        unknown?.answer?.complete(false)
        changed?.answer?.complete(false)
        unknown = null
        changed = null
    }
}

/**
 * First-connect dialog.
 *
 * Shows the key type and the fingerprint so the user can compare it against
 * `ssh-keyscan` output. Refusing is the default action.
 */
@Composable
fun UnknownHostDialog(state: HostKeyPromptState) {
    val request = state.unknown ?: return
    AlertDialog(
        onDismissRequest = { state.answerUnknown(false) },
        title = { Text(stringResource(R.string.hostkey_unknown_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.hostkey_unknown_body,
                        "${request.host}:${request.port}",
                    ),
                )
                Text(request.keyType, style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(
                        request.fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.answerUnknown(true) }) {
                Text(stringResource(R.string.hostkey_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = { state.answerUnknown(false) }) {
                Text(stringResource(R.string.hostkey_cancel))
            }
        },
    )
}

/**
 * Host-key-changed dialog.
 *
 * This is the case that matters: an attacker substituting a key produces exactly
 * this state after a successful first connection. The dialog never pre-selects
 * "trust"; the user has to make a deliberate choice.
 */
@Composable
fun ChangedHostKeyDialog(state: HostKeyPromptState) {
    val request = state.changed ?: return
    AlertDialog(
        onDismissRequest = { state.answerChanged(false) },
        title = { Text(stringResource(R.string.hostkey_changed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.err_host_key_changed))
                Text(
                    text = "${request.error.host}\n\n" +
                        "${stringResource(R.string.hostkey_previous)}\n" +
                        "${request.error.previousSha256}\n\n" +
                        "${stringResource(R.string.hostkey_current)}\n" +
                        "${request.error.currentSha256}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { state.answerChanged(true) }) {
                Text(stringResource(R.string.hostkey_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = { state.answerChanged(false) }) {
                Text(stringResource(R.string.hostkey_cancel))
            }
        },
    )
}
