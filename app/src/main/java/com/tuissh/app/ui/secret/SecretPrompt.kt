package com.tuissh.app.ui.secret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Password, passphrase and keyboard-interactive prompts.
 *
 * The SSH client resolves credentials on a background thread but the answer has to
 * come from the user, so [SecretPromptState] is the bridge: it parks a
 * [CompletableDeferred], shows a dialog, and completes it when the user answers.
 *
 * Replaces the ad-hoc dialogs scattered through the original app's
 * `SSHAuthPrompter`. Everything here is suspend-on-the-caller, main-thread-on-the-UI.
 */
class SecretPromptState {

    /** A pending password or passphrase request. */
    data class Request(
        val title: String,
        val message: String?,
        val label: String,
        val isPassphrase: Boolean,
        val allowEmpty: Boolean,
        val answer: CompletableDeferred<String?>,
    )

    /** A pending keyboard-interactive round. */
    data class InteractiveRequest(
        val name: String,
        val instruction: String,
        val prompts: List<InteractiveField>,
        val answer: CompletableDeferred<List<String>?>,
    )

    data class InteractiveField(
        val prompt: String,
        val echo: Boolean,
    )

    var password by mutableStateOf<Request?>(null)
        private set

    var interactive by mutableStateOf<InteractiveRequest?>(null)
        private set

    /** Ask for a password. Returns null when the user cancels. */
    suspend fun askPassword(
        title: String,
        message: String?,
        allowEmpty: Boolean = false,
    ): String? = ask(
        Request(
            title = title,
            message = message,
            label = "Mật khẩu",
            isPassphrase = false,
            allowEmpty = allowEmpty,
            answer = CompletableDeferred(),
        ),
        isInteractive = false,
    )

    /** Ask for a key passphrase. Returns null when the user cancels. */
    suspend fun askPassphrase(title: String, message: String?): String? = ask(
        Request(
            title = title,
            message = message,
            label = "Cụm mật khẩu",
            isPassphrase = true,
            allowEmpty = false,
            answer = CompletableDeferred(),
        ),
        isInteractive = false,
    )

    private suspend fun ask(request: Request, isInteractive: Boolean): String? {
        withContext(Dispatchers.Main) { password = request }
        return request.answer.await()
    }

    /** Ask one keyboard-interactive round. Returns null when the user cancels. */
    suspend fun askInteractive(
        name: String,
        instruction: String,
        prompts: List<String>,
        echo: List<Boolean>,
    ): List<String>? {
        val fields = prompts.mapIndexed { index, prompt ->
            InteractiveField(prompt, echo.getOrElse(index) { false })
        }
        val request = InteractiveRequest(
            name = name,
            instruction = instruction,
            prompts = fields,
            answer = CompletableDeferred(),
        )
        withContext(Dispatchers.Main) { interactive = request }
        return request.answer.await()
    }

    fun answerPassword(value: String?) {
        password?.answer?.complete(value)
        password = null
    }

    fun answerInteractive(values: List<String>?) {
        interactive?.answer?.complete(values)
        interactive = null
    }

    /** Fail every pending prompt; used when the screen goes away. */
    fun cancelAll() {
        password?.answer?.complete(null)
        interactive?.answer?.complete(null)
        password = null
        interactive = null
    }
}

/**
 * Password / passphrase dialog.
 *
 * The field is masked, the confirm button is disabled while empty (unless the caller
 * allows an empty secret, which some servers accept), and cancel returns null rather
 * than an empty string so the caller can tell "refused" from "blank".
 */
@Composable
fun SecretDialog(state: SecretPromptState) {
    val request = state.password ?: return
    var value by mutableStateOf("")
    var visible by mutableStateOf(false)

    AlertDialog(
        onDismissRequest = { state.answerPassword(null) },
        title = { Text(request.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(request.label) },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(
                                if (visible) {
                                    stringResource(R.string.secret_hide)
                                } else {
                                    stringResource(R.string.secret_show)
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { state.answerPassword(value) },
                enabled = value.isNotEmpty() || request.allowEmpty,
            ) {
                Text(stringResource(R.string.secret_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = { state.answerPassword(null) }) {
                Text(stringResource(R.string.hostkey_cancel))
            }
        },
    )
}

/**
 * Keyboard-interactive dialog.
 *
 * Most servers ask one prompt, but the RFC allows several in one round, so each is
 * rendered as its own field. Echo flags decide masking per field.
 */
@Composable
fun KeyboardInteractiveDialog(state: SecretPromptState) {
    val request = state.interactive ?: return
    var values by mutableStateOf(List(request.prompts.size) { "" })

    AlertDialog(
        onDismissRequest = { state.answerInteractive(null) },
        title = { Text(request.name.ifBlank { stringResource(R.string.secret_title) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.instruction.isNotBlank()) {
                    Text(request.instruction, style = MaterialTheme.typography.bodySmall)
                }
                request.prompts.forEachIndexed { index, field ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { new ->
                            values = values.toMutableList().also { it[index] = new }
                        },
                        label = { Text(field.prompt) },
                        singleLine = true,
                        visualTransformation = if (field.echo) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field.echo) KeyboardType.Text else KeyboardType.Password,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.answerInteractive(values) }) {
                Text(stringResource(R.string.secret_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = { state.answerInteractive(null) }) {
                Text(stringResource(R.string.hostkey_cancel))
            }
        },
    )
}
