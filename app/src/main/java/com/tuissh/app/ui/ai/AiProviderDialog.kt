package com.tuissh.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.ui.theme.TuiPalette

/**
 * Provider setup.
 *
 * The key is typed once and handed straight to the vault; this dialog never reads it back
 * so a shoulder-surfer cannot recover it from the field, and the caller only ever sees
 * the encrypted form.
 */
@Composable
fun AiProviderDialog(
    initialBaseUrl: String,
    initialModel: String,
    onDismiss: () -> Unit,
    onSave: (baseUrl: String, model: String, apiKey: String) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var model by remember { mutableStateOf(initialModel) }
    var apiKey by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_setup_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.ai_setup_base_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.ai_setup_model)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.ai_setup_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.ai_setup_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = TuiPalette.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(baseUrl.trim(), model.trim(), apiKey) },
                enabled = baseUrl.isNotBlank() && model.isNotBlank(),
            ) { Text(stringResource(R.string.server_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.hostkey_cancel)) }
        },
    )
}

/**
 * Shown when no provider is configured yet.
 *
 * Deliberately not an empty chat box: typing into a screen that cannot send is worse than
 * being told what is missing.
 */
@Composable
fun AiNotConfiguredScreen(
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.ai_setup_needed),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.ai_setup_hint),
            style = MaterialTheme.typography.bodySmall,
            color = TuiPalette.TextSecondary,
        )
        TextButton(onClick = onConfigure) {
            Text(stringResource(R.string.ai_setup_title))
        }
    }
}
