package com.tuissh.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuissh.app.R
import com.tuissh.app.data.model.ServerEntity
import com.tuissh.app.ui.key.KeyPickerRow
import com.tuissh.app.ui.server.ServerEditViewModel
import com.tuissh.app.ui.server.ServerListViewModel
import com.tuissh.app.ui.theme.LocalMacOsColors
import com.tuissh.app.ui.theme.liquidGlassCard

/**
 * Server list screen in macOS 27 Liquid Glass style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServerListViewModel,
    onOpenServer: (ServerEntity) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (ServerEntity) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val tokens = LocalMacOsColors.current
    val servers by viewModel.servers.collectAsState()
    val query by viewModel.query.collectAsState()

    Scaffold(
        containerColor = tokens.windowBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = tokens.chromeGlassBar,
                    titleContentColor = tokens.textPrimary,
                    actionIconContentColor = tokens.primaryAccent,
                ),
                title = { Text(stringResource(R.string.server_list_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.nav_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddServer,
                containerColor = tokens.primaryAccent,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.server_action_add))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(tokens.windowBackground),
        ) {
            // macOS 27 Spotlight Search Bar
            MacOsSearchBar(
                query = query,
                onQueryChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )

            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.server_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(servers, key = { it.id }) { server ->
                        ServerCardRow(
                            server = server,
                            onClick = { onOpenServer(server) },
                            onEdit = { onEditServer(server) },
                            onDelete = { viewModel.delete(server.id) },
                            onFavorite = { viewModel.toggleFavorite(server) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MacOsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMacOsColors.current
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(tokens.surfaceElevated)
            .border(
                BorderStroke(0.75.dp, tokens.specularBorderTop),
                shape = shape,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = tokens.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.server_list_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textTertiary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = tokens.textPrimary,
                    fontSize = 14.sp,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = tokens.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ServerCardRow(
    server: ServerEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
) {
    val tokens = LocalMacOsColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassCard(shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Computer,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = tokens.primaryAccent,
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.textPrimary,
            )
            Text(
                text = "${server.username}@${server.host}:${server.port}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
            if (server.useMosh) {
                Text(
                    text = "Mosh",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryAccent,
                )
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(
                Icons.Default.Star,
                contentDescription = stringResource(
                    if (server.favorite) R.string.server_action_unfavorite
                    else R.string.server_action_favorite,
                ),
                tint = if (server.favorite) tokens.statusOrange else tokens.textTertiary,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.server_action_delete),
                tint = tokens.textSecondary,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.server_action_edit),
                tint = tokens.textSecondary,
            )
        }
    }
}

/**
 * Server edit screen in macOS 27 Liquid Glass style.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    viewModel: ServerEditViewModel,
    onSaved: (Long) -> Unit,
    onBack: () -> Unit,
    onManageKeys: () -> Unit = {},
    onManageForwards: (Long) -> Unit = {},
) {
    val tokens = LocalMacOsColors.current
    val form by viewModel.form.collectAsState()
    val saved by viewModel.saved.collectAsState()
    val keys by viewModel.keys.collectAsState()
    val servers by viewModel.servers.collectAsState()

    androidx.compose.runtime.LaunchedEffect(saved) {
        saved?.let(onSaved)
    }

    Scaffold(
        containerColor = tokens.windowBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = tokens.chromeGlassBar,
                    titleContentColor = tokens.textPrimary,
                    navigationIconContentColor = tokens.textPrimary,
                ),
                title = { Text(stringResource(if (form.id == 0L) R.string.server_edit_new else R.string.server_edit_existing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.server_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::save,
                        enabled = form.canSave,
                    ) {
                        Text(
                            stringResource(R.string.server_save),
                            color = if (form.canSave) tokens.primaryAccent else tokens.textTertiary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(tokens.windowBackground)
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
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.primaryAccent,
                    unfocusedBorderColor = tokens.specularBorderTop,
                ),
            )
            OutlinedTextField(
                value = form.host,
                onValueChange = { v -> viewModel.update { it.copy(host = v) } },
                label = { Text(stringResource(R.string.server_host)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = form.hostError,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.primaryAccent,
                    unfocusedBorderColor = tokens.specularBorderTop,
                ),
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
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.primaryAccent,
                        unfocusedBorderColor = tokens.specularBorderTop,
                    ),
                )
                OutlinedTextField(
                    value = form.username,
                    onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                    label = { Text(stringResource(R.string.server_user)) },
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                    isError = form.userError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.primaryAccent,
                        unfocusedBorderColor = tokens.specularBorderTop,
                    ),
                )
            }

            Text(
                text = stringResource(R.string.server_auth_method),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.textPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val passwordTab = form.authType == ServerEntity.AUTH_PASSWORD
                FilterChip(
                    selected = passwordTab,
                    onClick = {
                        viewModel.update {
                            it.copy(authType = ServerEntity.AUTH_PASSWORD, privateKeyId = null)
                        }
                    },
                    label = { Text(stringResource(R.string.server_auth_password)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tokens.selectionPill,
                        selectedLabelColor = tokens.primaryAccent,
                    ),
                )
                FilterChip(
                    selected = !passwordTab,
                    onClick = {
                        viewModel.update { it.copy(authType = ServerEntity.AUTH_PRIVATE_KEY) }
                    },
                    label = { Text(stringResource(R.string.server_auth_key)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tokens.selectionPill,
                        selectedLabelColor = tokens.primaryAccent,
                    ),
                )
            }

            if (form.authType == ServerEntity.AUTH_PRIVATE_KEY) {
                KeyPickerRow(
                    keys = keys,
                    selectedId = form.privateKeyId,
                    onSelect = { key ->
                        viewModel.update { it.copy(privateKeyId = key.id) }
                    },
                )
                if (keys.isEmpty()) {
                    TextButton(onClick = onManageKeys) {
                        Text(stringResource(R.string.key_add_new), color = tokens.primaryAccent)
                    }
                }
            } else {
                OutlinedTextField(
                    value = form.password,
                    onValueChange = { v -> viewModel.update { it.copy(password = v) } },
                    label = { Text(stringResource(R.string.server_password_keep)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.primaryAccent,
                        unfocusedBorderColor = tokens.specularBorderTop,
                    ),
                )
            }

            Text(
                text = stringResource(R.string.server_bastion),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.textPrimary,
            )
            if (servers.isEmpty()) {
                Text(
                    text = stringResource(R.string.server_bastion_none),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                )
            } else {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        FilterChip(
                            selected = form.bastionServerId == null,
                            onClick = {
                                viewModel.update { it.copy(bastionServerId = null) }
                            },
                            label = { Text(stringResource(R.string.server_bastion_direct)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tokens.selectionPill,
                                selectedLabelColor = tokens.primaryAccent,
                            ),
                        )
                    }
                    items(servers.filter { it.id != form.id }) { candidate ->
                        FilterChip(
                            selected = form.bastionServerId == candidate.id,
                            onClick = {
                                viewModel.update { it.copy(bastionServerId = candidate.id) }
                            },
                            label = {
                                Text(
                                    text = candidate.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tokens.selectionPill,
                                selectedLabelColor = tokens.primaryAccent,
                            ),
                        )
                    }
                }
            }

            if (form.id > 0) {
                TextButton(
                    onClick = { onManageForwards(form.id) },
                ) { Text(stringResource(R.string.forward_title), color = tokens.primaryAccent) }
            }
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
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.primaryAccent,
                    unfocusedBorderColor = tokens.specularBorderTop,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = tokens.surfaceGlass),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.border(0.75.dp, tokens.specularBorderTop, RoundedCornerShape(12.dp)),
            ) {
                Text(
                    text = stringResource(R.string.server_advanced_note),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
            }
        }
    }
}
