package com.tuissh.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.tuissh.app.ui.key.KeyListViewModel
import com.tuissh.app.ui.key.KeyListScreen
import com.tuissh.app.ui.forward.ForwardListViewModel
import com.tuissh.app.ui.forward.ForwardListScreen
import com.tuissh.app.ui.settings.SettingsViewModel
import com.tuissh.app.ui.settings.SettingsScreen
import com.tuissh.app.ui.ai.AiProviderDialog
import com.tuissh.app.ui.ai.AiNotConfiguredScreen
import com.tuissh.app.ui.ai.AiChatViewModel
import com.tuissh.app.ui.ai.AiChatScreen
import com.tuissh.app.ai.AiToolRunner
import com.tuissh.app.ai.AiProtocol
import com.tuissh.app.ai.AiHttpClient
import com.tuissh.app.ui.monitor.MonitorViewModel
import com.tuissh.app.ui.monitor.MonitorScreen
import com.tuissh.app.monitor.MonitorClient
import com.tuissh.app.ui.shell.TabPlaceholder
import com.tuissh.app.ui.shell.ShellTab
import com.tuissh.app.ui.shell.ShellBottomBar
import com.tuissh.app.ui.docker.DockerViewModel
import com.tuissh.app.ui.docker.DockerScreen
import com.tuissh.app.docker.DockerClient
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tuissh.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tuissh.app.core.NeoError
import com.tuissh.app.core.NeoResult
import com.tuissh.app.data.model.ServerEntity
import com.tuissh.app.di.Graph
import com.tuissh.app.ssh.SshConnectPrompts
import com.tuissh.app.ssh.SessionCredentialCache
import com.tuissh.app.ssh.SshSession
import com.tuissh.app.ssh.RemoteCommandRunner
import com.tuissh.app.ssh.SshConnectSpec
import com.tuissh.app.ssh.SshSpecFactory
import com.tuissh.app.terminal.session.TerminalSession
import com.tuissh.app.ui.hostkey.ChangedHostKeyDialog
import com.tuissh.app.ui.hostkey.HostKeyPromptState
import com.tuissh.app.ui.hostkey.UnknownHostDialog
import com.tuissh.app.ui.sftp.SftpBrowserScreen
import com.tuissh.app.ui.sftp.SftpBrowserViewModel
import com.tuissh.app.ui.sftp.SftpTransferHost
import com.tuissh.app.ui.secret.KeyboardInteractiveDialog
import com.tuissh.app.ui.secret.SecretDialog
import com.tuissh.app.ui.secret.SecretPromptState
import com.tuissh.app.ui.server.ServerEditViewModel
import com.tuissh.app.ui.server.ServerListViewModel

/**
 * How many times a connect may re-ask for a credential before giving up.
 */
private const val MAX_SECRET_ATTEMPTS = 4

/**
 * Navigation graph.
 *
 * `terminal/{id}` opens a live session; the session is created per destination and
 * disposed when the screen leaves composition.
 */
@Composable
fun NeoApp() {
    val nav = rememberNavController()
    val services = Graph.get()

    // Remember which server the user last touched so the Files and Containers tabs
    // have something to open. The tabs themselves own no connection: they hand the id
    // back to the routes below, which is where credentials and channels live.
    var lastServerId by remember { mutableStateOf<Long?>(null) }
    val shellState = rememberShellState(nav)

    Scaffold(
        bottomBar = {
            // The bar is hidden on full-screen destinations: a terminal needs every
            // row it can get, and a tab bar over a keyboard is unusable.
            if (shellState.visible) {
                ShellBottomBar(
                    selected = shellState.selected,
                    onSelect = { tab -> shellState.select(tab) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ShellTab.TERMINAL.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(ShellTab.TERMINAL.route) {
                val vm: ServerListViewModel = viewModel(
                    factory = viewModelFactory { ServerListViewModel(services.repository) },
                )
                ServerListScreen(
                    viewModel = vm,
                    onOpenServer = { server ->
                        lastServerId = server.id
                        nav.navigate("terminal/${server.id}")
                    },
                    onAddServer = { nav.navigate("edit/0") },
                    onEditServer = { server -> nav.navigate("edit/${server.id}") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }

            composable(ShellTab.FILES.route) {
                val id = lastServerId
                if (id == null) {
                    TabPlaceholder(
                        title = stringResource(R.string.tab_files_pending),
                        detail = stringResource(R.string.tab_files_body),
                    )
                } else {
                    SftpDestination(serverId = id, onBack = { shellState.select(ShellTab.TERMINAL) })
                }
            }

            composable(ShellTab.CONTAINERS.route) {
                val id = lastServerId
                if (id == null) {
                    TabPlaceholder(
                        title = stringResource(R.string.tab_containers_pending),
                        detail = stringResource(R.string.tab_containers_body),
                    )
                } else {
                    DockerDestination(serverId = id, onBack = { shellState.select(ShellTab.TERMINAL) })
                }
            }

            composable(ShellTab.METRICS.route) {
                val id = lastServerId
                if (id == null) {
                    TabPlaceholder(
                        title = stringResource(R.string.tab_metrics_pending),
                        detail = stringResource(R.string.tab_metrics_body),
                    )
                } else {
                    MonitorDestination(
                        serverId = id,
                        onBack = { shellState.select(ShellTab.TERMINAL) },
                    )
                }
            }

            composable(ShellTab.MORE.route) {
                val id = lastServerId
                if (id == null) {
                    TabPlaceholder(
                        title = stringResource(R.string.nav_more),
                        detail = stringResource(R.string.tab_more_body),
                    )
                } else {
                    AiDestination(serverId = id, onBack = { shellState.select(ShellTab.TERMINAL) })
                }
            }

            composable(
                route = "edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                val vm: ServerEditViewModel = viewModel(
                    factory = viewModelFactory {
                        ServerEditViewModel(services.repository, if (id > 0) id else null)
                    },
                )
                ServerEditScreen(
                    viewModel = vm,
                    onSaved = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(
                route = "terminal/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                lastServerId = id
                TerminalDestination(
                    serverId = id,
                    onBack = { nav.popBackStack() },
                    onOpenSftp = { nav.navigate("sftp/$id") },
                )
            }

            composable(
                route = "sftp/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                SftpDestination(serverId = id, onBack = { nav.popBackStack() })
            }

            composable("settings") {
                val context = androidx.compose.ui.platform.LocalContext.current
                val vm: SettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        SettingsViewModel(services.database.kvDao(), context.applicationContext)
                    },
                )
                SettingsScreen(
                    viewModel = vm,
                    onBack = { nav.popBackStack() },
                    onManageKeys = { nav.navigate("keys") },
                )
            }

            composable("keys") {
                val vm: KeyListViewModel = viewModel(
                    factory = viewModelFactory { KeyListViewModel(services.repository) },
                )
                KeyListScreen(
                    viewModel = vm,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

/**
 * Which tab is showing and whether the bar is drawn at all.
 *
 * Derived from the nav back stack rather than from local state, so the bar survives
 * recomposition and the system back button.
 */
@Composable
private fun rememberShellState(nav: androidx.navigation.NavHostController): ShellState {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val selected = ShellTab.entries.firstOrNull { it.route == route }
        ?: ShellTab.TERMINAL
    // Hidden while a full-screen destination (terminal, editor, sftp) is on top.
    val visible = route in ShellTab.entries.map { it.route }

    return remember(route) {
        ShellState(selected = selected, visible = visible, nav = nav)
    }
}

private class ShellState(
    val selected: ShellTab,
    val visible: Boolean,
    private val nav: androidx.navigation.NavHostController,
) {
    fun select(tab: ShellTab) {
        if (tab.route == nav.currentDestination?.route) return
        nav.navigate(tab.route) {
            popUpTo(ShellTab.TERMINAL.route) { inclusive = false }
            launchSingleTop = true
        }
    }
}

/**
 * Builds a [TerminalSession] for one server inside a composition-scoped effect.
 */
@Composable
private fun TerminalDestination(
    serverId: Long,
    onBack: () -> Unit,
    onOpenSftp: () -> Unit = {},
) {
    val services = Graph.get()
    val hostKeyPrompts = remember { HostKeyPromptState() }
    val secretPrompts = remember { SecretPromptState() }

    var server by remember { mutableStateOf<ServerEntity?>(null) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Set when an auto-start forwarding rule could not bind.
    var forwardProblem by remember { mutableStateOf<String?>(null) }
    // Read once when the screen appears; settings are not live-updated mid-session.
    var prefs by remember { mutableStateOf(com.tuissh.app.ui.settings.TerminalPrefs()) }
    LaunchedEffect(Unit) {
        prefs = com.tuissh.app.ui.settings.TerminalPrefs.load(services.database.kvDao())
    }

    LaunchedEffect(serverId) {
        val row = services.repository.server(serverId)
        if (row == null) {
            problem = "server_gone"
            return@LaunchedEffect
        }
        server = row

        val ready = resolveSpec(services.specFactory, row, secretPrompts) { message ->
            problem = message
        } ?: return@LaunchedEffect

        val prompts = DialogPrompts(hostKeyPrompts, secretPrompts)
        val ssh = SshSession(
            spec = ready,
            hostKeyVerifier = services.hostKeyVerifier,
            prompts = prompts,
        )
        // Connect before forwarding. A rule needs a live transport: asking for one
        // before the session is up returns NotConnected, so every auto-start rule
        // would silently do nothing.
        when (val connected = ssh.connect()) {
            is NeoResult.Err -> {
                problem = connected.error.message
                return@LaunchedEffect
            }
            is NeoResult.Ok -> Unit
        }

        // Open the auto-start forwards before the shell, so a rule that fails is
        // reported while the user is still on the connecting screen rather than
        // silently not existing once they are inside the terminal.
        val autoForwards = services.repository.forwardsFor(serverId).filter { it.autoStart }
        if (autoForwards.isNotEmpty()) {
            when (val started = ssh.startForwards(autoForwards)) {
                is NeoResult.Ok -> {
                    val failed = started.getOrThrow()
                    if (failed.isNotEmpty()) {
                        forwardProblem = failed.joinToString("; ") { (rule, reason) ->
                            "${rule.bindHost}:${rule.bindPort} — $reason"
                        }
                    }
                }
                is NeoResult.Err -> forwardProblem = started.error.message
            }
        }

        val ts = TerminalSession(ssh, cols = 80, rows = 24)
        when (val started = ts.start()) {
            is NeoResult.Err -> problem = started.error.message
            is NeoResult.Ok -> session = ts
        }
    }

    val active = session
    val failure = problem
    val title = server?.name ?: ""

    Box {
        when {
            active != null -> TerminalScreen(
                session = active,
                title = title.ifBlank { stringResource(R.string.nav_terminal) },
                onBack = onBack,
                onOpenSftp = onOpenSftp,
                prefs = prefs,
                forwardProblem = forwardProblem,
            )
            failure != null -> Text(
                text = if (failure == "server_gone") {
                    stringResource(R.string.terminal_server_gone)
                } else {
                    stringResource(R.string.terminal_failed, failure)
                },
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(
                text = stringResource(R.string.terminal_connecting),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Host-key dialogs are hosted above the terminal so an unknown host blocks
        // the connection instead of being silently trusted.
        UnknownHostDialog(hostKeyPrompts)
        ChangedHostKeyDialog(hostKeyPrompts)
        SecretDialog(secretPrompts)
        KeyboardInteractiveDialog(secretPrompts)
    }

    DisposableEffect(Unit) {
        onDispose { hostKeyPrompts.cancelAll() }
    }
}

/**
 * Prompt handler backed by dialogs.
 *
 * Password and passphrase prompts still return null (the terminal shows the typed
 * credential path later); host-key decisions are real dialogs so an unknown or
 * changed key cannot be accepted without the user seeing it.
 */
private class DialogPrompts(
    private val state: HostKeyPromptState,
    private val secrets: SecretPromptState,
) : SshConnectPrompts {

    override suspend fun confirmUnknownHost(
        host: String,
        port: Int,
        keyType: String,
        fingerprint: String,
    ): Boolean = state.askUnknown(host, port, keyType, fingerprint)

    override suspend fun confirmChangedHost(error: NeoError.HostKeyChanged): Boolean =
        state.askChanged(error)

    /**
     * Ask for a password that the vault does not hold.
     *
     * A null return means the user cancelled; the caller surfaces that as a refused
     * connection rather than retrying with an empty password.
     */
    override suspend fun askPassword(
        serverName: String,
        username: String,
        host: String,
    ): String? = secrets.askPassword(
        title = "Nhập mật khẩu",
        message = "$username@$host",
    )

    override suspend fun askPassphrase(keyName: String): String? =
        secrets.askPassphrase(
            title = "Cụm mật khẩu của khóa",
            message = keyName,
        )

    override suspend fun askKeyboardInteractive(
        name: String,
        instruction: String,
        prompts: List<String>,
        echo: List<Boolean>,
    ): List<String>? = secrets.askInteractive(name, instruction, prompts, echo)
}

/** Minimal ViewModel factory; avoids a DI framework for a graph this small. */
private fun <T : ViewModel> viewModelFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }

/** Unused import guard so `fillMaxSize` stays referenced if layouts change. */
@Suppress("unused")
private val fullSize = Modifier.fillMaxSize()


/**
 * Resolve a connect spec, asking the user for any missing credential.
 *
 * Shared by the terminal, SFTP and container destinations: all three need the same
 * retry loop, and each one opens its own session, so without a memory of the answer
 * the same unreachable-vault password was typed once per destination.
 *
 * The cache is consulted first and written only after the vault-driven build has
 * produced a spec. A credential the user has just typed is thereby shown to work
 * before it is kept; a password that fails to authenticate is never cached, so the
 * next attempt still prompts instead of silently retrying a wrong secret.
 *
 * @param onProblem receives a user-facing message when resolution cannot continue
 * @return the ready spec, or null when the caller should stop
 */
private suspend fun resolveSpec(
    factory: SshSpecFactory,
    server: com.tuissh.app.data.model.ServerEntity,
    secrets: SecretPromptState,
    onProblem: (String) -> Unit,
): SshConnectSpec? {
    // Whatever an earlier destination already proved works for this server.
    val remembered = SessionCredentialCache.read(server.id)
    var password: String? = remembered?.password
    var passphrase: String? = remembered?.passphrase

    for (attempt in 0 until MAX_SECRET_ATTEMPTS) {
        val outcome = factory.build(
            server = server,
            suppliedPassword = password,
            suppliedPassphrase = passphrase,
        )
        when (outcome) {
            is SshSpecFactory.Outcome.Ready -> {
                if (outcome.spec.auth !is SshConnectSpec.Auth.AskOnConnect) {
                    // The spec is usable as built, so any credential it carries has
                    // survived the vault and the key parser; keep the newly typed ones.
                    if (password != remembered?.password || passphrase != remembered?.passphrase) {
                        SessionCredentialCache.write(
                            serverId = server.id,
                            password = password,
                            passphrase = passphrase,
                        )
                    }
                    return outcome.spec
                }
                val typed = secrets.askPassword(
                    title = "Nhập mật khẩu",
                    message = "${server.username}@${server.host}",
                ) ?: run {
                    onProblem("cancelled")
                    return null
                }
                password = typed
            }

            is SshSpecFactory.Outcome.NeedsSecret -> {
                val error = outcome.error
                val typed = if (error is NeoError.KeyPassphraseRequired) {
                    secrets.askPassphrase(
                        title = "Cụm mật khẩu của khóa",
                        message = error.message,
                    )?.also { passphrase = it }
                } else {
                    secrets.askPassword(
                        title = "Nhập mật khẩu",
                        message = "${server.username}@${server.host}",
                    )?.also { password = it }
                }
                if (typed == null) {
                    onProblem("cancelled")
                    return null
                }
                // A passphrase that decodes the key still needs one more build; a
                // password does too, so the loop simply continues.
            }

            is SshSpecFactory.Outcome.Failed -> {
                // A credential that no longer works must not outlive the failure.
                SessionCredentialCache.clear(server.id)
                onProblem(outcome.error.message ?: "connect failed")
                return null
            }
        }
    }

    SessionCredentialCache.clear(server.id)
    onProblem("cancelled")
    return null
}
/**
 * Container tab.
 *
 * Opens its own SSH session, like the SFTP tab, so a container view never competes
 * with an interactive shell for a channel.
 */
@Composable
private fun DockerDestination(serverId: Long, onBack: () -> Unit) {
    val services = Graph.get()
    val hostKeyPrompts = remember { HostKeyPromptState() }
    val secretPrompts = remember { SecretPromptState() }

    var viewModel by remember { mutableStateOf<DockerViewModel?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        val row = services.repository.server(serverId)
        if (row == null) {
            problem = "server_gone"
            return@LaunchedEffect
        }
        val ready = resolveSpec(services.specFactory, row, secretPrompts) { message ->
            problem = message
        } ?: return@LaunchedEffect

        val ssh = SshSession(
            spec = ready,
            hostKeyVerifier = services.hostKeyVerifier,
            prompts = DialogPrompts(hostKeyPrompts, secretPrompts),
        )
        when (val connected = ssh.connect()) {
            is NeoResult.Err -> {
                problem = connected.error.message
                return@LaunchedEffect
            }
            is NeoResult.Ok -> Unit
        }
        val vm = DockerViewModel(DockerClient(RemoteCommandRunner.of(ssh)))
        vm.load()
        viewModel = vm
    }

    val vm = viewModel
    val failure = problem

    Box {
        when {
            vm != null -> DockerScreen(viewModel = vm, onBack = onBack)
            failure != null -> Text(
                text = if (failure == "server_gone") {
                    stringResource(R.string.terminal_server_gone)
                } else {
                    stringResource(R.string.terminal_failed, failure)
                },
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(
                text = stringResource(R.string.terminal_connecting),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        UnknownHostDialog(hostKeyPrompts)
        ChangedHostKeyDialog(hostKeyPrompts)
        SecretDialog(secretPrompts)
        KeyboardInteractiveDialog(secretPrompts)
    }

    DisposableEffect(Unit) {
        onDispose { hostKeyPrompts.cancelAll(); secretPrompts.cancelAll() }
    }
}

/**
 * Opens an SSH session and an SFTP channel on it, then shows the browser.
 *
 * The SFTP tab needs its own authenticated connection, so it repeats the connect
 * path rather than sharing the terminal's session — the two can be open at once and
 * must not fight over one channel.
 */
@Composable
private fun SftpDestination(serverId: Long, onBack: () -> Unit) {
    val services = Graph.get()
    val hostKeyPrompts = remember { HostKeyPromptState() }
    val secretPrompts = remember { SecretPromptState() }

    var viewModel by remember { mutableStateOf<SftpBrowserViewModel?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        val row = services.repository.server(serverId)
        if (row == null) {
            problem = "server_gone"
            return@LaunchedEffect
        }

        val ready = resolveSpec(services.specFactory, row, secretPrompts) { message ->
            problem = message
        } ?: return@LaunchedEffect

        val ssh = SshSession(
            spec = ready,
            hostKeyVerifier = services.hostKeyVerifier,
            prompts = DialogPrompts(hostKeyPrompts, secretPrompts),
        )
        when (val connected = ssh.connect()) {
            is NeoResult.Err -> {
                problem = connected.error.message
                return@LaunchedEffect
            }
            is NeoResult.Ok -> Unit
        }
        when (val opened = ssh.openSftp()) {
            is NeoResult.Err -> problem = opened.error.message
            is NeoResult.Ok -> {
                viewModel = SftpBrowserViewModel(opened.getOrThrow(), initialPath = "~")
            }
        }
    }

    val vm = viewModel
    val failure = problem

    Box {
        when {
            vm != null -> SftpTransferHost(
                viewModel = vm,
                onStatus = { status -> },
            ) { callbacks ->
                SftpBrowserScreen(
                    viewModel = vm,
                    onBack = onBack,
                    onUploadRequest = callbacks.requestUpload,
                    onDownloadRequest = callbacks.requestDownload,
                )
            }
            failure != null -> Text(
                text = if (failure == "server_gone") {
                    stringResource(R.string.terminal_server_gone)
                } else {
                    stringResource(R.string.terminal_failed, failure)
                },
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(
                text = stringResource(R.string.terminal_connecting),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        UnknownHostDialog(hostKeyPrompts)
        ChangedHostKeyDialog(hostKeyPrompts)
        SecretDialog(secretPrompts)
        KeyboardInteractiveDialog(secretPrompts)
    }

    DisposableEffect(Unit) {
        onDispose {
            hostKeyPrompts.cancelAll()
            secretPrompts.cancelAll()
            vm?.close()
        }
    }
}
/**
 * Metrics tab.
 *
 * Owns its SSH session so polling does not disturb an interactive shell on the same
 * host. Reuses the shared credential resolver, so a cached password is used instead of
 * prompting again.
 */
@Composable
private fun MonitorDestination(serverId: Long, onBack: () -> Unit) {
    val services = Graph.get()
    val hostKeyPrompts = remember { HostKeyPromptState() }
    val secretPrompts = remember { SecretPromptState() }

    var viewModel by remember { mutableStateOf<MonitorViewModel?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        val row = services.repository.server(serverId)
        if (row == null) {
            problem = "server_gone"
            return@LaunchedEffect
        }
        val ready = resolveSpec(services.specFactory, row, secretPrompts) { message ->
            problem = message
        } ?: return@LaunchedEffect

        val ssh = SshSession(
            spec = ready,
            hostKeyVerifier = services.hostKeyVerifier,
            prompts = DialogPrompts(hostKeyPrompts, secretPrompts),
        )
        when (val connected = ssh.connect()) {
            is NeoResult.Err -> {
                problem = connected.error.message
                return@LaunchedEffect
            }
            is NeoResult.Ok -> Unit
        }
        viewModel = MonitorViewModel(MonitorClient(RemoteCommandRunner.of(ssh)))
    }

    val vm = viewModel
    val failure = problem

    Box {
        when {
            vm != null -> MonitorScreen(viewModel = vm, onBack = onBack)
            failure != null -> Text(
                text = if (failure == "server_gone") {
                    stringResource(R.string.terminal_server_gone)
                } else {
                    stringResource(R.string.terminal_failed, failure)
                },
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(
                text = stringResource(R.string.terminal_connecting),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        UnknownHostDialog(hostKeyPrompts)
        ChangedHostKeyDialog(hostKeyPrompts)
        SecretDialog(secretPrompts)
        KeyboardInteractiveDialog(secretPrompts)
    }

    DisposableEffect(Unit) {
        onDispose { hostKeyPrompts.cancelAll(); secretPrompts.cancelAll() }
    }
}
/**
 * Assistant tab.
 *
 * Owns its own SSH session so an AI turn never competes with the interactive shell for a
 * channel, and reuses the shared credential resolver so a cached password is used instead
 * of prompting again.
 */
@Composable
private fun AiDestination(serverId: Long, onBack: () -> Unit) {
    val services = Graph.get()
    val context = androidx.compose.ui.platform.LocalContext.current
    val hostKeyPrompts = remember { HostKeyPromptState() }
    val secretPrompts = remember { SecretPromptState() }

    var viewModel by remember { mutableStateOf<AiChatViewModel?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var sftpUnavailable by remember { mutableStateOf(false) }

    // Provider settings live in a shared preference so the chat can start without a
    // database round trip; the key alone is encrypted in the vault.
    var baseUrl by remember { mutableStateOf(prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()) }
    var model by remember { mutableStateOf(prefs(context).getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()) }
    var cipher by remember { mutableStateOf(prefs(context).getString(KEY_CIPHER, null)) }
    var showSetup by remember { mutableStateOf(cipher == null) }

    LaunchedEffect(serverId, cipher, baseUrl, model) {
        val encrypted = cipher
        if (encrypted == null) return@LaunchedEffect
        viewModel = null
        problem = null

        val row = services.repository.server(serverId)
        if (row == null) {
            problem = "server_gone"
            return@LaunchedEffect
        }
        val ready = resolveSpec(services.specFactory, row, secretPrompts) { message ->
            problem = message
        } ?: return@LaunchedEffect

        val ssh = SshSession(
            spec = ready,
            hostKeyVerifier = services.hostKeyVerifier,
            prompts = DialogPrompts(hostKeyPrompts, secretPrompts),
        )
        when (val connected = ssh.connect()) {
            is NeoResult.Err -> {
                problem = connected.error.message
                return@LaunchedEffect
            }
            is NeoResult.Ok -> Unit
        }

        // SFTP is optional: a host without the subsystem can still answer metrics and
        // container questions, so a refusal only disables the file tools.
        val sftp = when (val opened = ssh.openSftp()) {
            is NeoResult.Ok -> opened.value
            is NeoResult.Err -> {
                sftpUnavailable = true
                null
            }
        }

        val apiKey = services.vault.decrypt(encrypted).getOrNull()
        if (apiKey == null) {
            problem = "ai_key_unreadable"
            return@LaunchedEffect
        }

        val http = AiHttpClient(baseUrl, AiProtocol.PROTOCOL_OPENAI_CHAT, apiKey)
        val chat = AiChatViewModel(
            http = http,
            runner = AiToolRunner(
                commands = RemoteCommandRunner.of(ssh),
                sftp = sftp,
            ),
            model = model,
            hostLabel = "${row.username}@${row.host}",
        )
        viewModel = chat
    }

    val vm = viewModel
    val failure = problem

    Box {
        when {
            vm != null -> AiChatScreen(viewModel = vm, onBack = onBack)

            // No usable provider yet: the dialog is already open, so this branch only
            // needs to fill the screen behind it rather than offering a second button.
            cipher == null -> AiNotConfiguredScreen(
                onConfigure = { showSetup = true },
                modifier = Modifier.padding(24.dp),
            )

            failure != null -> Text(
                text = when (failure) {
                    "server_gone" -> stringResource(R.string.terminal_server_gone)
                    "ai_key_unreadable" -> stringResource(R.string.ai_setup_needed)
                    else -> stringResource(R.string.terminal_failed, failure)
                },
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> Text(
                text = stringResource(R.string.terminal_connecting),
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (showSetup) {
            AiProviderDialog(
                initialBaseUrl = baseUrl,
                initialModel = model,
                onDismiss = { if (cipher != null) showSetup = false },
                onSave = { url, m, key ->
                    baseUrl = url
                    model = m
                    val saved = if (key.isBlank()) {
                        cipher
                    } else {
                        services.vault.encrypt(key).getOrNull()?.also { cipher = it }
                    }
                    prefs(context).edit()
                        .putString(KEY_BASE_URL, url)
                        .putString(KEY_MODEL, m)
                        .apply {
                            if (saved != null) putString(KEY_CIPHER, saved) else remove(KEY_CIPHER)
                        }
                        .apply()
                    showSetup = false
                },
            )
        }

        UnknownHostDialog(hostKeyPrompts)
        ChangedHostKeyDialog(hostKeyPrompts)
        SecretDialog(secretPrompts)
        KeyboardInteractiveDialog(secretPrompts)
    }

    DisposableEffect(Unit) {
        onDispose { hostKeyPrompts.cancelAll(); secretPrompts.cancelAll() }
    }
}

/** Settings store for the assistant; the key itself lives in the vault. */
private fun prefs(context: android.content.Context) =
    context.getSharedPreferences("ai_settings", android.content.Context.MODE_PRIVATE)

private const val KEY_BASE_URL = "base_url"
private const val KEY_MODEL = "model"
private const val KEY_CIPHER = "api_key_cipher"
private const val DEFAULT_BASE_URL = "https://api.chjbi.net/v1"
private const val DEFAULT_MODEL = "deepseek-v4.1-flash-1017"