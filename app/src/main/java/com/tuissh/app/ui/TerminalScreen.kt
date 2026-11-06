package com.tuissh.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontFamily
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
import com.tuissh.app.ui.settings.TerminalPrefs
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import com.tuissh.app.ui.theme.LocalMacOsColors
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
    /** Appearance from Settings; the values used to be stored and never read. */
    prefs: TerminalPrefs = TerminalPrefs(),
    /** Set when an auto-start forwarding rule could not bind. */
    forwardProblem: String? = null,
) {
    val tokens = LocalMacOsColors.current
    val effectivePalette = if (palette.background == Color(0xFF000000) && palette.foreground == Color(0xFFE5E5E5)) {
        TerminalPalette.default(tokens.isDark)
    } else {
        palette
    }
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
        containerColor = effectivePalette.background,
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
                            remoteTitle.ifBlank { title },
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.textPrimary,
                        )
                        Text(
                            text = statusLine(state, grid.cols, grid.rows),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.textSecondary,
                        )
                    }
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.server_back),
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = onOpenSftp) {
                        Text(
                            text = stringResource(R.string.nav_sftp),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.primaryAccent,
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
                .background(effectivePalette.background)
                .imePadding(),
        ) {
            NeoTerminalView(
                emulator = session.emulator,
                palette = effectivePalette,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                onResize = { cols, rows -> session.resize(cols, rows) },
                onScrollChanged = { scrolledBack = it },
                fontSize = prefs.fontSize.sp,
                cursorBlink = prefs.cursorBlink,
            )

            if (scrolledBack > 0) {
                androidx.compose.material3.Surface(
                    color = tokens.statusOrange,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .padding(8.dp)
                        .align(androidx.compose.ui.Alignment.CenterHorizontally),
                ) {
                    Text(
                        text = stringResource(R.string.terminal_scrolled_back, scrolledBack),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // A forwarding rule that did not bind is shown here rather than as a toast:
            // the user needs to see which rule failed while they are in the session.
            forwardProblem?.let { message ->
                Text(
                    text = stringResource(R.string.forward_failed, message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            // Command entry in macOS 27 glass pill capsule
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.keyBarTray)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(tokens.surfaceElevated)
                        .border(
                            BorderStroke(0.75.dp, tokens.specularBorderTop),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            fontSize = 13.sp,
                            color = tokens.textPrimary,
                            fontFamily = FontFamily.Monospace,
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Send,
                        ),
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text(
                                    stringResource(R.string.terminal_input_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.textTertiary,
                                )
                            }
                            inner()
                        },
                    )
                }
                androidx.compose.material3.Button(
                    onClick = {
                        session.submitLine(input)
                        input = ""
                        focus.clearFocus()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.primaryAccent,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(stringResource(R.string.terminal_send)) }
            }

            // macOS 27 Magic Key Bar
            TerminalKeyBarView(
                appCursor = session.emulator.isModeSet(TerminalMode.APPLICATION_CURSOR),
                onBytes = { bytes -> session.send(bytes) },
            )
        }
    }

    // Keep the screen awake while a session is open, if the user asked for it. Done
    // with a window flag rather than a wake lock: a wake lock needs a permission and
    // outlives the screen if cleanup is missed, while this flag dies with the window.
    if (prefs.keepScreenOn) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
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


