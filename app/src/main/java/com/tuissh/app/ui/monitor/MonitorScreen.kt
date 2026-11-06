package com.tuissh.app.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import com.tuissh.app.monitor.MonitorParsers
import com.tuissh.app.ui.localized
import com.tuissh.app.ui.theme.LocalMacOsColors
import com.tuissh.app.ui.theme.TuiPalette
import com.tuissh.app.ui.theme.liquidGlassCard
import kotlin.math.roundToInt

/**
 * Host metrics.
 *
 * Polling is bound to the composition: it starts when the tab appears and stops when
 * it leaves. Keeping a one-second `/proc/stat` loop alive behind another screen would
 * cost battery and server CPU for output nobody sees.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val tokens = LocalMacOsColors.current

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

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
                            text = stringResource(R.string.nav_metrics),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.hostLabel
                                ?: stringResource(R.string.monitor_reading),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                actions = {
                    IconButton(onClick = viewModel::refresh) {
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
            if (state.loading && state.snapshot == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            state.error?.let { error ->
                Text(
                    text = error.localized(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.snapshot == null && state.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.monitor_reading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    CpuCard(
                        cpu = state.cpu,
                        warmingUp = state.cpuWarmingUp,
                    )
                }
                item { MemoryCard(state.memory) }
                item {
                    SectionTitle(stringResource(R.string.monitor_disks))
                }
                items(state.disks) { disk ->
                    DiskRow(disk)
                    HorizontalDivider()
                }
                item {
                    SectionTitle(stringResource(R.string.monitor_processes))
                }
                items(state.processes) { process ->
                    ProcessRow(
                        process = process,
                        onKill = { viewModel.kill(process) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val tokens = LocalMacOsColors.current
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = tokens.primaryAccent,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun CpuCard(cpu: MonitorParsers.CpuSnapshot?, warmingUp: Boolean) {
    val tokens = LocalMacOsColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .liquidGlassCard(RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(stringResource(R.string.monitor_cpu), style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary)
            Text(
                // A percentage needs two samples; showing 0% during the first poll
                // would look like an idle host rather than an unfinished read.
                text = if (warmingUp || cpu == null) {
                    stringResource(R.string.monitor_measuring)
                } else {
                    "${(cpu.userPercent + cpu.systemPercent).roundToInt()}%"
                },
                style = MaterialTheme.typography.titleLarge,
                color = tokens.primaryAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }

        if (cpu != null) {
            Meter(
                user = cpu.userPercent.toFloat(),
                system = cpu.systemPercent.toFloat(),
                iowait = cpu.iowaitPercent.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric(stringResource(R.string.monitor_load), "%.2f %.2f %.2f".format(cpu.load1, cpu.load5, cpu.load15))
                Metric(stringResource(R.string.monitor_iowait), "${cpu.iowaitPercent.roundToInt()}%")
            }
        }
    }
}

/**
 * Three-segment bar: user, system, io wait.
 *
 * Colour carries meaning — primary accent for user work, secondary accent for kernel, grey for waiting on
 * disk — so the bar can be read without the legend.
 */
@Composable
private fun Meter(user: Float, system: Float, iowait: Float) {
    val tokens = LocalMacOsColors.current
    val total = (user + system + iowait).coerceAtMost(100f).coerceAtLeast(0f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(tokens.surfaceElevated),
    ) {
        val scale = if (total <= 0f) 0f else 1f
        if (user > 0f) {
            Box(
                Modifier
                    .weight(user * scale + 0.0001f)
                    .fillMaxSize()
                    .background(tokens.primaryAccent),
            )
        }
        if (system > 0f) {
            Box(
                Modifier
                    .weight(system * scale + 0.0001f)
                    .fillMaxSize()
                    .background(tokens.secondaryAccent),
            )
        }
        if (iowait > 0f) {
            Box(
                Modifier
                    .weight(iowait * scale + 0.0001f)
                    .fillMaxSize()
                    .background(tokens.textTertiary),
            )
        }
        val remainder = 100f - total
        if (remainder > 0f) {
            Box(
                Modifier
                    .weight(remainder + 0.0001f)
                    .fillMaxSize()
                    .background(tokens.surfaceElevated),
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    val tokens = LocalMacOsColors.current
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tokens.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = tokens.textPrimary)
    }
}

@Composable
private fun MemoryCard(memory: MonitorParsers.MemoryInfo?) {
    if (memory == null) return
    val tokens = LocalMacOsColors.current
    val fraction = if (memory.totalKb <= 0) 0f else {
        (memory.usedKb.toFloat() / memory.totalKb.toFloat()).coerceIn(0f, 1f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .liquidGlassCard(RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(stringResource(R.string.monitor_memory), style = MaterialTheme.typography.titleSmall, color = tokens.textPrimary)
            Text(
                "${(fraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleLarge,
                color = tokens.primaryAccent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(tokens.surfaceElevated),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxSize()
                    .background(if (fraction > 0.9f) tokens.statusRed else tokens.primaryAccent),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric(
                stringResource(R.string.monitor_used),
                MonitorParsers.formatBytes(memory.usedKb * 1024),
            )
            Metric(
                stringResource(R.string.monitor_total),
                MonitorParsers.formatBytes(memory.totalKb * 1024),
            )
            if (memory.swapTotalKb > 0) {
                Metric(
                    stringResource(R.string.monitor_swap),
                    MonitorParsers.formatBytes(
                        (memory.swapTotalKb - memory.swapFreeKb) * 1024,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DiskRow(disk: MonitorParsers.DiskInfo) {
    val tokens = LocalMacOsColors.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                disk.mount,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                "${disk.usedPercent.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = when {
                    disk.usedPercent >= 90 -> tokens.statusRed
                    disk.usedPercent >= 75 -> tokens.statusOrange
                    else -> tokens.textPrimary
                },
            )
        }
        Text(
            "${MonitorParsers.formatBytes(disk.usedBytes)} / " +
                MonitorParsers.formatBytes(disk.totalBytes) +
                "  ·  " + disk.filesystem,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProcessRow(process: MonitorParsers.ProcessInfo, onKill: () -> Unit) {
    val tokens = LocalMacOsColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                process.command,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${process.pid}  ·  ${process.user}  ·  " +
                    "cpu ${"%.1f".format(process.cpuPercent)}%  ·  " +
                    "mem ${"%.1f".format(process.memPercent)}%",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onKill) {
            Text(
                stringResource(R.string.monitor_kill),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.statusRed,
            )
        }
    }
}

/** Empty state used when a host answers but exposes nothing worth charting. */
@Composable
private fun NothingToShow() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.monitor_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = TuiPalette.TextSecondary,
        )
    }
}

/** Plain swatch, kept for the legend if the meters gain one. */
@Composable
private fun Swatch(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}
