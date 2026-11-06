package com.tuissh.app.ui.docker

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tuissh.app.ui.theme.LocalMacOsColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.core.NeoFormat
import com.tuissh.app.docker.DockerParsers
import com.tuissh.app.ui.localized

/**
 * Container host browser.
 *
 * Four tabs mirror the four things people check on a Docker host. The list is built
 * from parsed JSON, so a container whose name contains spaces renders correctly —
 * the regex-on-columns approach the original app used did not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerScreen(
    viewModel: DockerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val tokens = LocalMacOsColors.current

    Scaffold(
        containerColor = tokens.windowBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = tokens.chromeGlassBar,
                    titleContentColor = tokens.textPrimary,
                    navigationIconContentColor = tokens.textPrimary,
                    actionIconContentColor = tokens.primaryAccent,
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.docker_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.engine?.let {
                                "${it.binary} ${it.version}" + if (it.rootless) " · rootless" else ""
                            } ?: stringResource(R.string.docker_detecting),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.server_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.common_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(tokens.windowBackground),
        ) {
            TabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = tokens.chromeGlassBar,
                contentColor = tokens.primaryAccent,
                indicator = { tabPositions ->
                    if (state.tab.ordinal < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[state.tab.ordinal]),
                            color = tokens.primaryAccent,
                        )
                    }
                },
            ) {
                DockerViewModel.Tab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                text = when (tab) {
                                    DockerViewModel.Tab.CONTAINERS ->
                                        stringResource(R.string.docker_tab_containers, state.containers.size)
                                    DockerViewModel.Tab.IMAGES ->
                                        stringResource(R.string.docker_tab_images, state.images.size)
                                    DockerViewModel.Tab.VOLUMES ->
                                        stringResource(R.string.docker_tab_volumes, state.volumes.size)
                                    DockerViewModel.Tab.NETWORKS ->
                                        stringResource(R.string.docker_tab_networks, state.networks.size)
                                },
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.error?.let { error ->
                Text(
                    text = error.localized(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            when (state.tab) {
                DockerViewModel.Tab.CONTAINERS -> ContainerList(
                    state = state,
                    onToggle = viewModel::toggleRunning,
                    onRestart = viewModel::restart,
                    onLogs = viewModel::openLogs,
                )

                DockerViewModel.Tab.IMAGES -> SimpleList(
                    empty = stringResource(R.string.docker_no_images),
                    rows = state.images.map {
                        Triple(it.reference, it.size, it.createdSince)
                    },
                )

                DockerViewModel.Tab.VOLUMES -> SimpleList(
                    empty = stringResource(R.string.docker_no_volumes),
                    rows = state.volumes.map {
                        Triple(it.name, it.driver, it.mountPoint.orEmpty())
                    },
                )

                DockerViewModel.Tab.NETWORKS -> SimpleList(
                    empty = stringResource(R.string.docker_no_networks),
                    rows = state.networks.map {
                        Triple(it.name, it.driver, it.scope)
                    },
                )
            }
        }
    }

    state.logTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::closeLogs,
            title = {
                Text(
                    text = stringResource(R.string.docker_logs_title, target.name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.terminalBg)
                        .padding(8.dp),
                ) {
                    if (state.logsLoading) {
                        Text(
                            stringResource(R.string.docker_logs_loading),
                            color = tokens.terminalFg,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            text = state.logs.ifBlank { stringResource(R.string.docker_logs_empty) },
                            color = tokens.terminalFg,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.openLogs(target)
                }) { Text(stringResource(R.string.common_refresh)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeLogs) {
                    Text(stringResource(R.string.hostkey_cancel))
                }
            },
        )
    }
}

@Composable
private fun ContainerList(
    state: DockerViewModel.UiState,
    onToggle: (DockerParsers.Container) -> Unit,
    onRestart: (DockerParsers.Container) -> Unit,
    onLogs: (DockerParsers.Container) -> Unit,
) {
    if (state.containers.isEmpty() && !state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.docker_no_containers),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.containers, key = { it.id }) { container ->
            ContainerRow(
                container = container,
                busy = state.busyId == container.id,
                onToggle = { onToggle(container) },
                onRestart = { onRestart(container) },
                onLogs = { onLogs(container) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ContainerRow(
    container: DockerParsers.Container,
    busy: Boolean,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    onLogs: () -> Unit,
) {
    val tokens = LocalMacOsColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (container.isRunning) tokens.statusGreen else tokens.textTertiary,
                ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = container.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = container.image,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(container.status, style = MaterialTheme.typography.labelSmall)
                if (container.ports.isNotEmpty()) {
                    Text(container.portsLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        TextButton(onClick = onLogs, enabled = !busy) {
            Text(stringResource(R.string.docker_logs), style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = onToggle, enabled = !busy) {
            Icon(
                imageVector = if (container.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (container.isRunning) {
                    stringResource(R.string.docker_stop)
                } else {
                    stringResource(R.string.docker_start)
                },
            )
        }
        IconButton(onClick = onRestart, enabled = !busy) {
            Icon(Icons.Default.Refresh, stringResource(R.string.docker_restart))
        }
    }
}

@Composable
private fun SimpleList(empty: String, rows: List<Triple<String, String, String>>) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(empty, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows) { (title, meta, extra) ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (meta.isNotBlank()) {
                        Text(meta, style = MaterialTheme.typography.labelSmall)
                    }
                    if (extra.isNotBlank()) {
                        Text(extra, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
