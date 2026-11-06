package com.tuissh.app.ui.sftp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.core.NeoFormat
import com.tuissh.app.sftp.SftpClient
import com.tuissh.app.ui.localized

/**
 * SFTP file browser.
 *
 * Replaces `SFTPBrowserScreen` from the original app: path breadcrumb, sortable
 * listing, multi-select, and the file operations that live behind the overflow menu.
 * Transfers are delegated to the caller through stream callbacks so this screen does
 * not need to know about SAF or app storage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpBrowserScreen(
    viewModel: SftpBrowserViewModel,
    onBack: () -> Unit,
    onUploadRequest: () -> Unit,
    onDownloadRequest: (SftpClient.Entry) -> Unit,
    onOpenFile: (SftpClient.Entry) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var actionDialog by remember { mutableStateOf<ActionDialog?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.path.substringAfterLast('/').ifEmpty { "/" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(state.entries.size).append(" ")
                                append(stringResource(R.string.sftp_items))
                                viewModel.usageLabel().takeIf { it.isNotEmpty() }?.let {
                                    append(" · ").append(it)
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.canGoUp) viewModel.goUp() else onBack() },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.server_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.common_refresh))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_sort_name)) },
                            leadingIcon = { Icon(Icons.Default.SortByAlpha, null) },
                            onClick = { viewModel.toggleSort(SftpBrowserViewModel.SortBy.NAME); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_sort_size)) },
                            onClick = { viewModel.toggleSort(SftpBrowserViewModel.SortBy.SIZE); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_sort_date)) },
                            onClick = { viewModel.toggleSort(SftpBrowserViewModel.SortBy.MODIFIED); menuOpen = false },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_toggle_hidden)) },
                            onClick = { viewModel.toggleHidden(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_new_folder)) },
                            onClick = { actionDialog = ActionDialog.NewFolder; menuOpen = false },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.selectionCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = { actionDialog = ActionDialog.DeleteSelected },
                    icon = { Icon(Icons.Default.Delete, null) },
                    text = { Text(stringResource(R.string.sftp_delete_n, state.selectionCount)) },
                )
            } else {
                FloatingActionButton(onClick = onUploadRequest) {
                    Icon(Icons.Default.Upload, stringResource(R.string.sftp_upload))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.error?.let { err ->
                Text(
                    text = err.localized(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.entries.isEmpty() && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.sftp_empty), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.visible, key = { it.path }) { entry ->
                        SftpRow(
                            entry = entry,
                            selected = entry.path in state.selected,
                            sizeLabel = viewModel.sizeLabel(entry),
                            dateLabel = viewModel.dateLabel(entry),
                            onClick = {
                                if (state.selectionCount > 0) {
                                    viewModel.toggleSelection(entry.path)
                                } else if (entry.isDirectory) {
                                    if (entry.isSymlink && entry.linkTarget != null) {
                                        viewModel.openLink(entry)
                                    } else {
                                        viewModel.open(entry)
                                    }
                                } else {
                                    onOpenFile(entry)
                                }
                            },
                            onLongClick = { viewModel.toggleSelection(entry.path) },
                            onMenu = { actionDialog = ActionDialog.EntryActions(entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    when (val dialog = actionDialog) {
        null -> Unit

        ActionDialog.NewFolder -> TextInputDialog(
            title = stringResource(R.string.sftp_new_folder),
            label = stringResource(R.string.server_name),
            onDismiss = { actionDialog = null },
            onConfirm = { name ->
                actionDialog = null
                viewModel.createDirectory(name) { err -> message = err?.message }
            },
        )

        ActionDialog.DeleteSelected -> AlertDialog(
            onDismissRequest = { actionDialog = null },
            title = { Text(stringResource(R.string.sftp_delete_confirm, state.selectionCount)) },
            text = { Text(stringResource(R.string.sftp_delete_irreversible)) },
            confirmButton = {
                TextButton(onClick = {
                    actionDialog = null
                    viewModel.deleteSelected { failures ->
                        message = failures.firstOrNull()?.message
                    }
                }) { Text(stringResource(R.string.server_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { actionDialog = null }) {
                    Text(stringResource(R.string.hostkey_cancel))
                }
            },
        )

        is ActionDialog.EntryActions -> AlertDialog(
            onDismissRequest = { actionDialog = null },
            title = { Text(dialog.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${stringResource(R.string.sftp_size)}: ${NeoFormat.bytes(dialog.entry.size)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${stringResource(R.string.sftp_permissions)}: ${dialog.entry.permissionsText}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    dialog.entry.linkTarget?.let {
                        Text("→ $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            // Two actions only. An earlier version put the download action in
            // confirmButton and the copy action in dismissButton, which made the
            // dialog look interactive but left "download" doing nothing visible.
            confirmButton = {
                if (dialog.entry.isDirectory) {
                    TextButton(onClick = {
                        val entry = dialog.entry
                        actionDialog = null
                        viewModel.rename(entry.path, entry.name) { }
                    }) { Text(stringResource(R.string.sftp_permissions)) }
                } else {
                    TextButton(onClick = {
                        val entry = dialog.entry
                        actionDialog = null
                        onDownloadRequest(entry)
                    }) { Text(stringResource(R.string.sftp_download)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("path", dialog.entry.path))
                    actionDialog = null
                }) { Text(stringResource(R.string.common_copy)) }
            },
        )

        is ActionDialog.CopyPath -> CopyPathDialog(
            entry = dialog.entry,
            viewModel = viewModel,
            onDismiss = { actionDialog = null },
        )
    }

    message?.let { text ->
        LaunchedEffect(text) {
            kotlinx.coroutines.delay(2500)
            message = null
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private sealed interface ActionDialog {
    data object NewFolder : ActionDialog
    data object DeleteSelected : ActionDialog
    data class EntryActions(val entry: SftpClient.Entry) : ActionDialog
    data class CopyPath(val entry: SftpClient.Entry) : ActionDialog
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SftpRow(
    entry: SftpClient.Entry,
    selected: Boolean,
    sizeLabel: String,
    dateLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                entry.isSymlink -> Icons.Outlined.Link
                entry.isDirectory -> Icons.Outlined.Folder
                else -> Icons.Outlined.Description
            },
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = when {
                entry.isDirectory -> MaterialTheme.colorScheme.primary
                entry.isSymlink -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sizeLabel.isNotEmpty()) {
                    Text(sizeLabel, style = MaterialTheme.typography.labelSmall)
                }
                if (dateLabel.isNotEmpty()) {
                    Text(dateLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (selected) {
            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onMenu) {
            Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.server_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.hostkey_cancel)) }
        },
    )
}

@Composable
private fun CopyPathDialog(
    entry: SftpClient.Entry,
    viewModel: SftpBrowserViewModel,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissions by remember { mutableStateOf(entry.permissionsText) }
    var octal by remember { mutableStateOf(entry.permissions.toString(8).takeLast(4)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sftp_path_and_permissions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.path, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = octal,
                    onValueChange = { v ->
                        if (v.length <= 4 && v.all { c -> c in '0'..'7' }) octal = v
                    },
                    label = { Text("chmod") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(permissions, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.changePermissions(entry.path, octal)
                onDismiss()
            }) { Text(stringResource(R.string.server_save)) }
        },
        dismissButton = {
            TextButton(onClick = {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                clip?.setPrimaryClip(
                    android.content.ClipData.newPlainText("path", entry.path),
                )
                onDismiss()
            }) { Text(stringResource(R.string.common_copy)) }
        },
    )
}

/** Empty-state helper used by the list when a directory has no readable entries. */
@Composable
private fun EmptyFolder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.common_loading))
        }
    }
}
