package com.tuissh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.tuissh.app.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tuissh.app.terminal.ansi.TerminalMode
import com.tuissh.app.terminal.render.NeoTerminalView
import com.tuissh.app.terminal.render.TerminalPalette
import com.tuissh.app.terminal.session.TerminalInputEncoder
import com.tuissh.app.terminal.session.TerminalSession
import com.tuissh.app.ui.terminal.TerminalKeyBarView
import kotlinx.coroutines.launch

/**
 * Terminal screen.
 *
 * Replaces `TerminalView` / `ServerTerminalView`. The status strip at the bottom is
 * the same information the iOS build showed in its navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    session: TerminalSession,
    title: String,
    palette: TerminalPalette = TerminalPalette.default(),
    onBack: () -> Unit,
    onOpenSftp: () -> Unit = {},
) {
    val state by session.state.collectAsState()
    val remoteTitle by session.title.collectAsState()
    val grid by session.size.collectAsState()

    var input by remember { mutableStateOf("") }
    // Non-zero while the user is reading history, which hides the composer hint and
    // shows the jump-to-latest button instead.
    var scrolledBack by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(remoteTitle.ifBlank { title }, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = statusLine(state, grid.cols, grid.rows),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    // An icon, not the word "Back": a text label in the navigation
                    // slot is squeezed to a couple of characters on narrow layouts.
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.server_back),
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.IconButton(onClick = onOpenSftp) {
                        Text(
                            text = stringResource(R.string.nav_sftp),
                            style = MaterialTheme.typography.labelLarge,
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
                // The key bar must sit directly above the soft keyboard. Without
                // imePadding the keyboard covers it and the terminal becomes
                // unusable the moment the user starts typing.
                .imePadding(),
        ) {
            NeoTerminalView(
                emulator = session.emulator,
                palette = palette,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Edge-to-edge puts the canvas against the physical screen edge,
                    // where the first glyph column is clipped by the display cutout
                    // and the glyph's own left bearing. A small inset keeps column 0
                    // readable without wasting meaningful width.
                    .padding(horizontal = 4.dp),
                onResize = { cols, rows -> session.resize(cols, rows) },
                onScrollChanged = { scrolledBack = it },
            )

            // While reading history the terminal must say so, otherwise new output
            // arriving off-screen looks like the app has stopped responding.
            if (scrolledBack > 0) {
                androidx.compose.material3.Surface(
                    color = com.tuissh.app.ui.theme.TuiPalette.Amber,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .padding(8.dp)
                        .align(androidx.compose.ui.Alignment.CenterHorizontally),
                ) {
                    Text(
                        text = stringResource(R.string.terminal_scrolled_back, scrolledBack),
                        style = MaterialTheme.typography.labelSmall,
                        color = com.tuissh.app.ui.theme.TuiPalette.Ink,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // Single-line command entry. A real terminal needs raw key events; this
            // input row keeps the app usable until the soft keyboard drives the
            // session directly, and it uses the same encoders as the key bar.
            //
            // Order matters: the input row sits ABOVE the key bar so the bar is the
            // last thing before the keyboard, within thumb reach. Putting the bar
            // above the input row forces the user to reach past the text field.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send,
                    ),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(stringResource(R.string.terminal_input_hint), style = MaterialTheme.typography.bodySmall)
                        }
                        inner()
                    },
                )
                androidx.compose.material3.Button(
                    onClick = {
                        // Text first, then Enter. Sending the carriage return before
                        // the command runs an empty line and kills some shells.
                        session.submitLine(input)
                        input = ""
                        focus.clearFocus()
                    },
                ) { Text(stringResource(R.string.terminal_send)) }
            }

            // Fixed key bar. A soft keyboard has no Ctrl, Esc or arrows, so the
            // terminal supplies them; without this the session is read-only for any
            // full-screen program. Kept last so it sits against the keyboard.
            TerminalKeyBarView(
                appCursor = session.emulator.isModeSet(TerminalMode.APPLICATION_CURSOR),
                onBytes = { bytes -> session.send(bytes) },
            )
        }
    }

    // Bell / clipboard side channels.
    LaunchedEffect(Unit) {
        session.bell.collect {
            // A real beep would be jarring; haptics belong here.
        }
    }
    LaunchedEffect(Unit) {
        session.clipboard.collect { text ->
            // Remote clipboard writes are surfaced by the activity, not here.
            @Suppress("UNUSED_VARIABLE")
            val ignored = text
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch { session.stop() }
        }
    }
}

@Composable
private fun statusLine(state: TerminalSession.TerminalState, cols: Int, rows: Int): String =
    when (state) {
        is TerminalSession.TerminalState.CONNECTING -> stringResource(R.string.terminal_connecting)
        is TerminalSession.TerminalState.CONNECTED -> "${cols}x${rows}"
        is TerminalSession.TerminalState.CLOSED -> stringResource(R.string.terminal_closed, cols, rows)
        is TerminalSession.TerminalState.Failed -> stringResource(R.string.terminal_failed, state.error.localized())
        else -> stringResource(R.string.terminal_disconnected)
    }

/** Placeholder colour used by the scaffold until a theme row is loaded. */
@Suppress("unused")
private val NeutralText = Color(0xFFE5E5E5)


