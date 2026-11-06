package com.tuissh.app.ui.sftp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuissh.app.R
import com.tuissh.app.core.NeoFormat
import com.tuissh.app.sftp.SftpClient
import com.tuissh.app.ui.localized
import com.tuissh.app.ui.theme.LocalMacOsColors
import com.tuissh.app.ui.theme.liquidGlassCard

/**
 * SFTP file browser with macOS 27 Liquid Glass aesthetic.
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
    val tokens = LocalMacOsColors.current
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var actionDialog by remember { mutableStateOf<ActionDialog?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = tokens.windowBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = tokens.chromeGlassBar,
                    titleContentColor = tokens.textPrimary,
                    navigationIconContentColor = tokens.textPrimary,
                    actionIconContentColor = tokens.textPrimary,
                ),
                title = {
                    Column {
                        Text(
                            text = state.path.ifBlank { "/" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp,
                            ),
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
                            color = tokens.textSecondary,
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
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = stringResource(R.string.server_back),
                            tint = tokens.textSecondary,
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, null)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.background(tokens.surfaceElevated),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_sort_name), color = tokens.textPrimary) },
                            leadingIcon = { Icon(Icons.Default.SortByAlpha, null, tint = tokens.textSecondary) },
                            onClick = { viewModel.toggleSort(SftpBrowserViewModel.SortBy.NAME); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_sort_size), color = tokens.textPrimary) },
                            onClick = { viewModel.toggleSort(SftpBrowserViewModel.SortBy.SIZE); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_sort_date), color = tokens.textPrimary) },
                            onClick = { viewModel.toggleSort(SftpBrowserViewModel.SortBy.MODIFIED); menuOpen = false },
                        )
                        HorizontalDivider(color = tokens.dividerHairline)
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_toggle_hidden), color = tokens.textPrimary) },
                            onClick = { viewModel.toggleHidden(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sftp_new_folder), color = tokens.textPrimary) },
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
                    icon = { Icon(Icons.Default.Delete, null, tint = Color.White) },
                    text = { Text(stringResource(R.string.sftp_delete_n, state.selectionCount), color = Color.White) },
                    containerColor = tokens.statusRed,
                    shape = RoundedCornerShape(16.dp),
                )
            } else {
                FloatingActionButton(
                    onClick = onUploadRequest,
                    containerColor = tokens.primaryAccent,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.Upload, stringResource(R.string.sftp_upload))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(tokens.windowBackground),
        ) {
            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = tokens.primaryAccent,
                    trackColor = tokens.surfaceGlass,
                )
            }

            state.error?.let { err ->
                Text(
                    text = err.localized(),
                    color = tokens.statusRed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.entries.isEmpty() && !state.loading && !state.canGoUp) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.sftp_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    // Parent directory entry ".."
                    if (state.canGoUp) {
                        item(key = "__parent_dir__") {
                            ParentFolderRow(
                                onClick = viewModel::goUp,
                            )
                            HorizontalDivider(color = tokens.dividerHairline)
                        }
                    }

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
                            onToggleSelect = { viewModel.toggleSelection(entry.path) },
                            onLongClick = { actionDialog = ActionDialog.EntryActions(entry) },
                            onMenu = { actionDialog = ActionDialog.EntryActions(entry) },
                        )
                        HorizontalDivider(color = tokens.dividerHairline)
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
                }) { Text(stringResource(R.string.server_action_delete), color = tokens.statusRed) }
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
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${stringResource(R.string.sftp_size)}: ${NeoFormat.bytes(dialog.entry.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textPrimary,
                    )
                    Text(
                        "${stringResource(R.string.sftp_permissions)}: ${dialog.entry.permissionsText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                    dialog.entry.linkTarget?.let {
                        Text("→ $it", style = MaterialTheme.typography.bodySmall, color = tokens.secondaryAccent)
                    }
                }
            },
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
                    }) { Text(stringResource(R.string.sftp_download), color = tokens.primaryAccent) }
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
                color = tokens.statusRed,
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

@Composable
private fun ParentFolderRow(onClick: () -> Unit) {
    val tokens = LocalMacOsColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SftpFileIcons.FolderIcon(modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(14.dp))
        Text(
            text = "..",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.textPrimary,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SftpRow(
    entry: SftpClient.Entry,
    selected: Boolean,
    sizeLabel: String,
    dateLabel: String,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val tokens = LocalMacOsColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) tokens.selectionPill
                else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SftpFileIcons.ItemIcon(entry = entry, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory && sizeLabel.isNotEmpty()) {
                Text(
                    text = sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                )
            }
        }

        // macOS Styled Checkbox
        MacOsCheckbox(
            checked = selected,
            onToggle = onToggleSelect,
        )

        IconButton(onClick = onMenu) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tokens.textSecondary,
            )
        }
    }
}

@Composable
private fun MacOsCheckbox(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMacOsColors.current
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) tokens.primaryAccent else Color.Transparent)
            .border(
                BorderStroke(
                    1.2.dp,
                    if (checked) tokens.primaryAccent else tokens.specularBorderTop,
                ),
                shape = RoundedCornerShape(5.dp),
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
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
