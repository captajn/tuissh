package com.tuissh.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.tuissh.app.R
import androidx.compose.ui.unit.dp
import com.tuissh.app.data.model.ServerEntity
import com.tuissh.app.ui.server.ServerEditViewModel
import com.tuissh.app.ui.server.ServerListViewModel

/**
 * Server list screen.
 *
 * Replaces `ServerListView` from the original app. Tapping a row opens the terminal;
 * long-press selection and multi-server actions land with the AI feature, since that
 * is what multi-select exists for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServerListViewModel,
    onOpenServer: (ServerEntity) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (ServerEntity) -> Unit,
) {
    val servers by viewModel.servers.collectAsState()
    val query by viewModel.query.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.server_list_title)) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddServer) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.server_action_add))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.server_list_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.server_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(servers, key = { it.id }) { server ->
                        ServerRow(
                            server = server,
                            onClick = { onOpenServer(server) },
                            onEdit = { onEditServer(server) },
                            onDelete = { viewModel.delete(server.id) },
                            onFavorite = { viewModel.toggleFavorite(server) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ServerEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Computer,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = server.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${server.username}@${server.host}:${server.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (server.useMosh) {
                Text(
                    text = "Mosh",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(
                Icons.Default.Star,
                contentDescription = stringResource(if (server.favorite) R.string.server_action_unfavorite else R.string.server_action_favorite),
                tint = if (server.favorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.server_action_delete))
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = stringResource(R.string.server_action_edit))
        }
    }
}

/**
 * Create / edit form.
 *
 * Field set mirrors `ServerEditView` in the original app. Validation messages are
 * the same strings, translated through `values-vi`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    viewModel: ServerEditViewModel,
    onSaved: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val form by viewModel.form.collectAsState()
    val saved by viewModel.saved.collectAsState()

    androidx.compose.runtime.LaunchedEffect(saved) {
        saved?.let(onSaved)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (form.id == 0L) R.string.server_edit_new else R.string.server_edit_existing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("\u2039")
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(
                        onClick = viewModel::save,
                        enabled = form.canSave,
                    ) { Text(stringResource(R.string.server_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.server_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = form.name.isBlank(),
            )
            OutlinedTextField(
                value = form.host,
                onValueChange = { v -> viewModel.update { it.copy(host = v) } },
                label = { Text(stringResource(R.string.server_host)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = form.hostError,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.port,
                    onValueChange = { v ->
                        if (v.all { c -> c.isDigit() } || v.isEmpty()) {
                            viewModel.update { it.copy(port = v) }
                        }
                    },
                    label = { Text(stringResource(R.string.server_port)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    isError = form.portError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = form.username,
                    onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                    label = { Text(stringResource(R.string.server_user)) },
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                    isError = form.userError,
                )
            }
            OutlinedTextField(
                value = form.password,
                onValueChange = { v -> viewModel.update { it.copy(password = v) } },
                label = { Text(stringResource(R.string.server_password_keep)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = form.keepaliveSeconds,
                onValueChange = { v ->
                    if (v.all { c -> c.isDigit() } || v.isEmpty()) {
                        viewModel.update { it.copy(keepaliveSeconds = v) }
                    }
                },
                label = { Text(stringResource(R.string.server_keepalive)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    text = stringResource(R.string.server_advanced_note),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}


