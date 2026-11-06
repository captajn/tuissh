package com.tuissh.app.ui.forward

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.data.model.ForwardEntity
import com.tuissh.app.ui.theme.TuiPalette

/**
 * Port forwarding rules.
 *
 * Shows the rules as the SSH flags they correspond to (`-L`, `-R`, `-D`), because that is
 * what someone comparing against a command line will look for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardListScreen(
    viewModel: ForwardListViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<ForwardEntity?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.forward_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.forward_count, state.rules.size),
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
                onClick = viewModel::startNew,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.forward_add)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            state.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (!state.hasRules && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = TuiPalette.TextDisabled,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            stringResource(R.string.forward_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TuiPalette.TextSecondary,
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rules, key = { it.id }) { rule ->
                        ForwardRow(rule = rule, onDelete = { pendingDelete = rule })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (state.showEditor) {
        val editing = state.editing
        if (editing != null) {
            ForwardEditor(
                rule = editing,
                kinds = viewModel.kinds,
                error = state.error,
                onSave = viewModel::save,
                onDismiss = viewModel::dismissEditor,
            )
        }
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.forward_delete_title)) },
            text = { Text(describe(rule)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(rule)
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

/** The rule in the form a user would type on a command line. */
private fun describe(rule: ForwardEntity): String = when (rule.kind) {
    ForwardEntity.KIND_DYNAMIC ->
        "-D ${rule.bindHost}:${rule.bindPort}"
    ForwardEntity.KIND_REMOTE ->
        "-R ${rule.bindHost}:${rule.bindPort} -> ${rule.targetHost}:${rule.targetPort}"
    else ->
        "-L ${rule.bindHost}:${rule.bindPort} -> ${rule.targetHost}:${rule.targetPort}"
}

@Composable
private fun ForwardRow(rule: ForwardEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (rule.kind) {
                ForwardEntity.KIND_DYNAMIC -> "-D"
                ForwardEntity.KIND_REMOTE -> "-R"
                else -> "-L"
            },
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = TuiPalette.Amber,
            modifier = Modifier.width(34.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = rule.name.ifBlank { describe(rule) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = describe(rule),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TuiPalette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (rule.autoStart) {
                Text(
                    text = stringResource(R.string.forward_auto_start),
                    style = MaterialTheme.typography.labelSmall,
                    color = TuiPalette.Success,
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

@Composable
private fun ForwardEditor(
    rule: ForwardEntity,
    kinds: List<Pair<String, String>>,
    error: String?,
    onSave: (ForwardEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(rule.id, rule.kind) { mutableStateOf(rule) }
    val isDynamic = draft.kind == ForwardEntity.KIND_DYNAMIC

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.forward_add)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in kinds) {
                        FilterChip(
                            selected = draft.kind == value,
                            onClick = { draft = draft.copy(kind = value) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text(stringResource(R.string.forward_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.bindHost,
                        onValueChange = { draft = draft.copy(bindHost = it) },
                        label = { Text(stringResource(R.string.forward_bind_host)) },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = draft.bindPort.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { draft = draft.copy(bindPort = it) }
                        },
                        label = { Text(stringResource(R.string.server_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (!isDynamic) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.targetHost,
                            onValueChange = { draft = draft.copy(targetHost = it) },
                            label = { Text(stringResource(R.string.forward_target_host)) },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = draft.targetPort.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { draft = draft.copy(targetPort = it) }
                            },
                            label = { Text(stringResource(R.string.forward_target_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.forward_auto_start),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = draft.autoStart,
                        onCheckedChange = { draft = draft.copy(autoStart = it) },
                    )
                }

                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text(stringResource(R.string.server_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.hostkey_cancel)) }
        },
    )
}

/** Compact bullet used when a rule has no name. */
@Composable
private fun RuleDot() {
    Box(
        Modifier
            .size(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(TuiPalette.TextDisabled),
    )
}
