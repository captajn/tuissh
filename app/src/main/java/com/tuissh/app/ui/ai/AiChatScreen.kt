package com.tuissh.app.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuissh.app.R
import com.tuissh.app.ai.formatToolDuration
import com.tuissh.app.ui.localized
import com.tuissh.app.ui.theme.TuiPalette

/**
 * Assistant chat.
 *
 * Layout follows the original app: user turns right-aligned in the accent colour,
 * assistant turns plain, and every tool call its own card showing status and how long it
 * took. That card is the feature — a tool that runs for two minutes with no feedback
 * reads as a hang.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: AiChatViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Follow the stream. Keyed on the row count and the last row's text length so a
    // delta scrolls but a tool card expanding does not jump the view.
    val lastTextLength = (state.rows.lastOrNull() as? AiChatViewModel.Row.Assistant)?.text?.length ?: 0
    LaunchedEffect(state.rows.size, lastTextLength) {
        if (state.rows.isNotEmpty()) {
            listState.animateScrollToItem(state.rows.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.nav_ai),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.model,
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
                    if (state.sending) {
                        IconButton(onClick = viewModel::stop) {
                            Icon(Icons.Default.Close, stringResource(R.string.ai_halt))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (state.rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.ai_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TuiPalette.TextSecondary,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rows, key = { it.id }) { row ->
                        when (row) {
                            is AiChatViewModel.Row.User -> UserBubble(row.text)
                            is AiChatViewModel.Row.Assistant -> AssistantBubble(row.text, row.streaming)
                            is AiChatViewModel.Row.Tool -> ToolCard(
                                row = row,
                                onToggle = { viewModel.toggleTool(row.id) },
                            )
                            is AiChatViewModel.Row.Notice -> Text(
                                text = row.text,
                                color = TuiPalette.Danger,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }

            state.error?.let { error ->
                Text(
                    text = error.localized(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            Composer(
                value = state.input,
                enabled = !state.sending,
                canSend = state.canSend,
                onValueChange = viewModel::setInput,
                onSend = viewModel::send,
            )
        }
    }

    state.awaitingApproval?.let { request ->
        ApprovalDialog(
            request = request,
            onAnswer = viewModel::answerApproval,
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                .background(TuiPalette.Violet)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AssistantBubble(text: String, streaming: Boolean) {
    Text(
        text = if (streaming) "$text▍" else text,
        style = MaterialTheme.typography.bodyMedium,
        color = TuiPalette.TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
            .background(TuiPalette.InkRaised)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Tool card.
 *
 * The header always shows name, status dot and duration so a long run is visibly alive;
 * the payload expands on tap.
 */
@Composable
private fun ToolCard(row: AiChatViewModel.Row.Tool, onToggle: () -> Unit) {
    val statusColor = when (row.state) {
        AiChatViewModel.ToolState.RUNNING -> TuiPalette.Amber
        AiChatViewModel.ToolState.OK -> TuiPalette.Success
        AiChatViewModel.ToolState.FAILED -> TuiPalette.Danger
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TuiPalette.InkCard)
            .clickable(onClick = onToggle)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.state == AiChatViewModel.ToolState.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = statusColor,
                )
            } else {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }
            Box(Modifier.size(8.dp))
            Text(
                text = row.name,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = TuiPalette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (row.state == AiChatViewModel.ToolState.RUNNING) {
                    stringResource(R.string.ai_tool_running)
                } else {
                    formatToolDuration(row.durationMs)
                },
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
            Icon(
                imageVector = if (row.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TuiPalette.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        if (row.display.isNotBlank()) {
            Text(
                text = row.display,
                style = MaterialTheme.typography.labelSmall,
                color = TuiPalette.TextSecondary,
                maxLines = if (row.expanded) 20 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (row.expanded) {
            Text(
                text = row.argumentsJson,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TuiPalette.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(max = 240.dp),
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    enabled: Boolean,
    canSend: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TuiPalette.InkRaised)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(TuiPalette.Ink)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(R.string.ai_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TuiPalette.TextDisabled,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = TextStyle(
                    color = TuiPalette.TextPrimary,
                    fontSize = 15.sp,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (canSend) TuiPalette.Amber else TuiPalette.InkCard)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = stringResource(R.string.terminal_send),
                tint = if (canSend) TuiPalette.Ink else TuiPalette.TextDisabled,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Confirmation for a shell command.
 *
 * The command is shown verbatim in a monospace block: approving a summary instead of the
 * literal string is how a user ends up deleting the wrong directory.
 */
@Composable
private fun ApprovalDialog(
    request: AiChatViewModel.ApprovalRequest,
    onAnswer: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(R.string.ai_approve_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                request.reason.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = request.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = TuiPalette.Amber,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(TuiPalette.Ink)
                        .padding(10.dp),
                )
                Text(
                    text = stringResource(R.string.ai_approve_body),
                    style = MaterialTheme.typography.labelSmall,
                    color = TuiPalette.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) {
                Text(stringResource(R.string.ai_approve_run), color = TuiPalette.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) {
                Text(stringResource(R.string.hostkey_cancel))
            }
        },
    )
}

