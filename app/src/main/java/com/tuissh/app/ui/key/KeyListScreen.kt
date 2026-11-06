package com.tuissh.app.ui.key

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import com.tuissh.app.ui.theme.LocalMacOsColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.data.model.PrivateKeyEntity
import com.tuissh.app.ui.theme.TuiPalette

/**
 * SSH key management.
 *
 * The app can authenticate with a key, but until this screen existed there was no way to
 * put one in: the parser, the keystore encryption and the server form field were all
 * built, and a user with a key-only server had no path through the UI at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyListScreen(
    viewModel: KeyListViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val tokens = LocalMacOsColors.current
    var importing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PrivateKeyEntity?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        containerColor = tokens.windowBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = tokens.chromeGlassBar,
                    titleContentColor = tokens.textPrimary,
                    navigationIconContentColor = tokens.textPrimary,
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.key_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.key_count, state.keys.size),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.server_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                containerColor = tokens.primaryAccent,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                onClick = { importing = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.key_import)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(tokens.windowBackground),
        ) {
            state.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.keys.isEmpty() && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = TuiPalette.TextDisabled,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            stringResource(R.string.key_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TuiPalette.TextSecondary,
                        )
                        Text(
                            stringResource(R.string.key_empty_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = TuiPalette.TextDisabled,
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.keys, key = { it.id }) { key ->
                        KeyRow(
                            key = key,
                            onDelete = { pendingDelete = key },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (importing) {
        ImportKeyDialog(
            state = state,
            onPreview = viewModel::previewPaste,
            onCommit = { passphrase ->
                viewModel.commitPreview(passphrase)
                importing = false
            },
            onDismiss = {
                viewModel.clearPreview()
                importing = false
            },
        )
    }

    pendingDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.key_delete_title, key.name)) },
            text = { Text(stringResource(R.string.key_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(key)
                    pendingDelete = null
                }) { Text(stringResource(R.string.server_action_delete), color = TuiPalette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.hostkey_cancel))
                }
            },
        )
    }
}

@Composable
private fun KeyRow(key: PrivateKeyEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.VpnKey,
            contentDescription = null,
            tint = if (key.hasPassphrase) TuiPalette.Amber else TuiPalette.Success,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = key.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(key.algorithm)
                    if (key.bits > 0) append(' ').append(key.bits).append(" bit")
                    if (key.hasPassphrase) append(" · ").append(stringResource(R.string.key_locked))
                    if (key.usedByServers > 0) {
                        append(" · ").append(stringResource(R.string.key_used_by, key.usedByServers))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = TuiPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            key.fingerprintSha256?.let { fingerprint ->
                Text(
                    text = fingerprint,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TuiPalette.TextDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.server_action_delete),
                tint = TuiPalette.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Import dialog.
 *
 * The preview step is not decoration: it shows the algorithm, size and fingerprint the
 * parser derived, so a mis-pasted key is caught here rather than at connect time on a
 * production server.
 */
@Composable
private fun ImportKeyDialog(
    state: KeyListViewModel.UiState,
    onPreview: (String, String, String?) -> Unit,
    onCommit: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    val preview = state.preview

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_import)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        onPreview(it, name, passphrase.takeIf { p -> p.isNotEmpty() })
                    },
                    label = { Text(stringResource(R.string.key_paste)) },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.server_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = passphrase,
                    onValueChange = {
                        passphrase = it
                        onPreview(text, name, it.takeIf { p -> p.isNotEmpty() })
                    },
                    label = { Text(stringResource(R.string.key_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                preview?.let { card ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TuiPalette.InkCard)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "${card.algorithm}" + if (card.bits > 0) " · ${card.bits} bit" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = TuiPalette.Amber,
                        )
                        card.fingerprint?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = TuiPalette.TextSecondary,
                            )
                        }
                        card.blockingProblem?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = TuiPalette.Danger,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCommit(passphrase.takeIf { it.isNotEmpty() }) },
                enabled = preview != null && preview.blockingProblem == null,
            ) { Text(stringResource(R.string.key_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.hostkey_cancel)) }
        },
    )
}

/** Row used by the server form to pick a key. */
@Composable
fun KeyPickerRow(
    keys: List<PrivateKeyEntity>,
    selectedId: Long?,
    onSelect: (PrivateKeyEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = com.tuissh.app.ui.theme.LocalMacOsColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (keys.isEmpty()) {
            Text(
                text = stringResource(R.string.key_none_yet),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        for (key in keys) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (key.id == selectedId) tokens.selectionPill
                        else tokens.surfaceGlass,
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = if (key.id == selectedId) tokens.primaryAccent else tokens.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = key.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (key.id == selectedId) tokens.textPrimary else tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
