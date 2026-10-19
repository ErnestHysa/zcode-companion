package com.zcode.android.app.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zcode.android.app.AppContainer
import com.zcode.android.app.updateRecentsTaskLabel
import com.zcode.android.app.R
import com.zcode.android.features.conversation.ui.ApprovalsScreen
import com.zcode.android.app.connect.ConnectionCenterScreen
import com.zcode.android.app.settings.SettingsScreen
import com.zcode.android.app.settings.PluginsViewerScreen
import com.zcode.android.app.settings.McpViewerScreen
import com.zcode.android.app.settings.SkillsViewerScreen
import com.zcode.android.app.settings.UsageScreen
import com.zcode.android.features.conversation.ConversationViewModel
import com.zcode.android.features.conversation.ui.ConversationScreen
import com.zcode.android.features.pairing.PairingViewModel
import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.tasks.TasksViewModel
import com.zcode.android.features.tasks.ui.TaskListScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.material3.Scaffold
import com.zcode.android.core.ui.components.rememberSnackbarNotifier
import androidx.compose.runtime.setValue

/**
 * Phase 6 navigation graph (§18.2; ui-ux navigation.md): pairing (unpaired gate) →
 * tasks → conversation; connection center + settings as secondary destinations.
 * Predictive back is the system default (no custom interception — no drafts yet,
 * Phase 7 adds that guard). Deep links arrive with Phase 8 (§24.7).
 */
object Routes {
    const val PAIRING = "pairing"
    const val TASKS = "tasks"
    const val CONVERSATION = "conversation/{taskId}"
    const val CONNECTION = "connection"
    const val SETTINGS = "settings"
    const val USAGE = "usage"
    const val PLUGINS = "plugins"
    const val SKILLS = "skills"
    const val MCP = "mcp"
    const val APPROVALS = "approvals"
    const val FILES = "files"
    const val GIT = "git"
    const val TERMINAL = "terminal"

    fun conversation(taskId: String) = "conversation/$taskId"
}

@Composable
fun ZCodeApp(
    container: AppContainer,
    pairingVm: PairingViewModel,
    tasksVm: TasksViewModel,
    scope: CoroutineScope,
    scannerContent: @Composable () -> Unit,
) {
    val nav = rememberNavController()
    // B3-states: in-app notices ride the root SnackbarHost (Toast → Snackbar).
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val notifier = com.zcode.android.core.ui.components.rememberSnackbarNotifier(snackbarHostState)
    // §11.6 (Phase 10): "paired" = ANY activatable endpoint — the relay pairing
    // OR a registry endpoint with a usable credential (direct endpoints have no
    // vault relay-metadata; a device paired ONLY to a direct server must land on
    // the task list, not the QR gate). The surrogate carries the deep-link
    // environment's deviceSid slot (= the endpoint id; routing input only).
    val relayPaired by container.pairingPort.pairedEndpoint.collectAsState()
    val hubEndpoint by container.hub.endpoint.collectAsState()
    // The surrogate is for DIRECT-ONLY devices: once a relay pairing exists in this
    // process, it is the sole gate (a vault wipe must surface as unpaired — the
    // always-on surrogate kept a wiped activity "paired", perturbing the teardown
    // nav state, device-caught 2026-10-03).
    val sawRelayPairing = remember { androidx.compose.runtime.mutableStateOf(false) }
    if (relayPaired != null && !sawRelayPairing.value) sawRelayPairing.value = true
    val paired: com.zcode.android.features.pairing.flow.PairingFlow.LinkView? =
        if (sawRelayPairing.value) relayPaired
        else hubEndpoint?.let { ep ->
            com.zcode.android.features.pairing.flow.PairingFlow.LinkView(
                deviceSid = ep.deviceSid ?: ep.endpointId.raw,
                desktopName = ep.displayName,
                desktopAppVersion = ep.desktopAppVersion ?: "",
                origin = "",
                issuedAtMs = ep.pairedAtMs,
            )
        }

    // Round-7b breadcrumb: every screen change lands in the diagnostics ring.
    androidx.compose.runtime.LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            container.log.info("nav.screen", "route=${entry.destination.route}")
        }
    }

    // Pairing lifecycle ↔ navigation edges (ui-ux navigation.md back behavior).
    // [paired] is the SINGLE owner of paired-driven navigation: QR-pairing success,
    // unpair, AND the cold-restore edge (a recreated activity restores its previous
    // back stack — restored state beats startDestination — so a paired device that
    // was pre-seeded mid-restore must be MOVED from the pairing destination to tasks).
    LaunchedEffect(paired) {
        val onPairing = nav.currentDestination?.route == Routes.PAIRING
        when {
            paired == null ->
                if (!onPairing) nav.navigate(Routes.PAIRING) { popUpTo(0) { inclusive = true } }
            onPairing -> {
                container.hub.onPairingCompleted()
                nav.navigate(Routes.TASKS) { popUpTo(Routes.PAIRING) { inclusive = true } }
            }
            else -> container.hub.onPairingCompleted()
        }
    }

    // Phase 8 (§24.7): deep links are routing HINTS — every case resolves through
    // the domain DeepLinkRoute matrix against the authoritative projection; stale,
    // missing, wrong-endpoint, and malformed ids each land on an explicit, graceful
    // route (never a crash, never an empty screen). An UNPAIRED device keeps the
    // link pending; it re-resolves after pairing completes (§24.7 re-validation).
    val pendingLink by container.notifications.pendingLink.collectAsState()
    // H2.2: launcher shortcuts — routed after the paired gate like deep links.
    val pendingShortcut by container.notifications.pendingShortcut.collectAsState()
    var createRequested by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pendingShortcut, paired) {
        val target = pendingShortcut ?: return@LaunchedEffect
        if (paired == null) return@LaunchedEffect // UNPAIRED → hold; re-routed post-pairing
        container.notifications.consumeShortcut()
        when (target) {
            com.zcode.android.app.notifications.AppShortcuts.Target.NEW_TASK -> {
                createRequested = true
                nav.navigate(Routes.TASKS) { popUpTo(Routes.TASKS) { inclusive = false }; launchSingleTop = true }
            }
            com.zcode.android.app.notifications.AppShortcuts.Target.APPROVALS -> nav.navigate(Routes.APPROVALS)
            com.zcode.android.app.notifications.AppShortcuts.Target.CONNECTION -> nav.navigate(Routes.CONNECTION)
        }
        container.log.info("nav.shortcut", "target=${target.id}")
    }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(pendingLink, paired) {
        val link = pendingLink ?: return@LaunchedEffect
        if (paired == null) return@LaunchedEffect // UNPAIRED → hold; re-validated post-pairing
        // Consume BEFORE resolving: the effect re-runs whenever [paired] moves, and
        // a run cancelled mid-resolve (suspend points below) must never leave the
        // link pending — a restart would NAVIGATE AGAIN (stacked duplicate entries).
        container.notifications.consumeDeepLink()
        val connected = container.hub.phase.value == com.zcode.android.domain.state.ConnectionPhase.CONNECTED
        // §24.7 resolution inputs read from the SAME projection snapshots the task
        // list renders (the wiring's collector state) — never a second query path.
        val taskExists = container.notifications.knowsTask(link.taskId)
        val interactionKnown: Boolean? = link.interactionId?.let { container.notifications.knowsInteraction(it) }
        val linkToResolve = com.zcode.android.domain.recovery.DeepLinkRoute.parse(link.taskId, link.interactionId)
        // §11.6 cross-endpoint disambiguation: deferred (Phase 11+). The naive
        // in-effect Room probe (observeAllTasks().first()) perturbed the nav
        // teardown on device (NavBackStackEntry double-destroy in the FGS
        // tap-through) — the honest stub stays until the resolution runs off the
        // composition effect (findings recorded in docs/adr/phase-10-gate.md §4).
        val existsOnOtherEndpoint = false
        val environment = com.zcode.android.domain.recovery.DeepLinkRoute.Environment.Paired(
            endpointId = paired?.deviceSid ?: "",
            connected = connected,
            taskExists = taskExists,
            interactionKnown = interactionKnown,
            existsOnOtherEndpoint = existsOnOtherEndpoint,
        )
        fun navigateToTasks() {
            nav.navigate(Routes.TASKS)
        }
        when (com.zcode.android.domain.recovery.DeepLinkRoute.resolve(linkToResolve, environment)) {
            com.zcode.android.domain.recovery.DeepLinkRoute.Outcome.OPEN_TASK,
            com.zcode.android.domain.recovery.DeepLinkRoute.Outcome.OPEN_TASK_INTERACTION_STALE,
            -> nav.navigate(Routes.conversation(link.taskId))
            com.zcode.android.domain.recovery.DeepLinkRoute.Outcome.TASK_GONE -> {
                navigateToTasks()
                notifier.show(appContext.getString(R.string.deeplink_task_gone), long = true)
            }
            com.zcode.android.domain.recovery.DeepLinkRoute.Outcome.WRONG_ENDPOINT -> {
                navigateToTasks()
                notifier.show(appContext.getString(R.string.deeplink_wrong_endpoint), long = true)
            }
            // MALFORMED never reaches here (parse filtered at intake) — drop silently.
            com.zcode.android.domain.recovery.DeepLinkRoute.Outcome.MALFORMED -> Unit
            com.zcode.android.domain.recovery.DeepLinkRoute.Outcome.UNPAIRED -> return@LaunchedEffect
        }
        container.log.info("nav.deeplink", "resolved task=${link.taskId.take(24)}")
    }

    // testTagsAsResourceId: exposes Compose testTags to UiAutomator/Macrobenchmark
    // (§43.2-11.1 waits); pure test plumbing, no behavioral surface.
    // B3-motion + K2: shared-axis route transitions (≈300ms fade + directional slide),
    // EnterTransition.None/ExitTransition.None under the system reduced-motion switch.
    val navMotion = com.zcode.android.core.ui.components.rememberNavTransitions()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        // Each route hosts its own Scaffold (ZCodeTopBar insets): the root must not
        // add system-bar insets a second time. The snackbar still avoids the nav bar
        // through its own host placement.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { inner ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(inner)) {
            androidx.compose.runtime.CompositionLocalProvider(
                com.zcode.android.core.ui.components.LocalNotifier provides notifier,
            ) {
    // B2.15/J4: ONE ticking clock for every relative-time label in the app.
    com.zcode.android.core.ui.components.ProvideRelativeNow {
    NavHost(
        navController = nav,
        startDestination = if (paired != null) Routes.TASKS else Routes.PAIRING,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        enterTransition = { navMotion.enter },
        exitTransition = { navMotion.exit },
        popEnterTransition = { navMotion.popEnter },
        popExitTransition = { navMotion.popExit },
    ) {
        composable(Routes.PAIRING) {
            PairingHostRoute(pairingVm = pairingVm, scannerContent = scannerContent)
        }
        composable(Routes.TASKS) {
            // §18.3 "Needs you": the approvals inbox entry carries the open count.
            val openApprovals by container.conversationPort.observeOpenInteractions()
                .collectAsState(initial = emptyList())
            val taskState by tasksVm.state.collectAsState()
            TasksRoute(
                container = container,
                vm = tasksVm,
                onTaskClick = { task ->
                    container.log.info("tasks.open", "task=${task.taskId.take(24)} title=${task.title.take(40)}")
                    nav.navigate(Routes.conversation(task.taskId))
                },
                onOpenSettings = { container.log.info("nav.settings", "open"); nav.navigate(Routes.SETTINGS) },
                onOpenConnection = { container.log.info("nav.connection", "open"); nav.navigate(Routes.CONNECTION) },
                openApprovals = openApprovals.size,
                onOpenApprovals = { container.log.info("nav.approvals", "open"); nav.navigate(Routes.APPROVALS) },
                onCreateTask = { key, text, provider, model ->
                    container.log.info("tasks.create", "workspace=${key.take(24)} model=${(provider ?: "default") + "/" + (model ?: "default")}")
                    scope.launch { tasksVm.createTaskAndWait(key, text, provider, model) }
                },
                workspaces = taskState.workspaces,
                creatingTask = taskState.creatingTask,
                createNotice = taskState.createNotice,
                onConsumeCreateNotice = tasksVm::consumeCreateNotice,
                collapseStore = container.workspaceCollapseStore,
                requestCreate = createRequested,
                onCreateRequestConsumed = { createRequested = false },
                // Phase 4B (A3.2): open-session-from-run lands on the run's session.
                onOpenTaskId = { id -> container.log.info("tasks.openRun", "task=${id.take(24)}"); nav.navigate(Routes.conversation(id)) },
            )
        }
        composable(Routes.CONVERSATION) { entry ->
            val taskId = entry.arguments?.getString("taskId").orEmpty()
            val vm = rememberConversationViewModel(container, taskId)
            ConversationRoute(
                container = container,
                vm = vm,
                onBack = { nav.popBackStack() },
                onOpenFiles = { container.log.info("nav.files", "open"); nav.navigate(Routes.FILES) },
                onOpenGit = { container.log.info("nav.git", "open"); nav.navigate(Routes.GIT) },
                onOpenTerminal = { container.log.info("nav.terminal", "open"); nav.navigate(Routes.TERMINAL) },
                // Phase 4A: an accepted fork opens the CHILD session in place (desktop onSessionCreated parity).
                onOpenTask = { child -> container.log.info("nav.fork", "open ${child.take(12)}"); nav.navigate(Routes.conversation(child)) },
            )
        }

        composable(Routes.FILES) {
            FilesRoute(container = container, onBack = { nav.popBackStack() })
        }
        composable(Routes.GIT) {
            GitRoute(container = container, onBack = { nav.popBackStack() })
        }
        composable(Routes.TERMINAL) {
            TerminalRoute(container = container, onBack = { nav.popBackStack() })
        }

        composable(Routes.CONNECTION) {
            ConnectionCenterRoute(container = container, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                container = container,
                pairingVm = pairingVm,
                onBack = { nav.popBackStack() },
                onOpenUsage = { nav.navigate(Routes.USAGE) },
                onOpenPlugins = { nav.navigate(Routes.PLUGINS) },
                onOpenSkills = { nav.navigate(Routes.SKILLS) },
                onOpenMcp = { nav.navigate(Routes.MCP) },
            )
        }
        composable(Routes.USAGE) {
            // GAP-5 (v4-wire.md §12.1): read-only usage dashboard over the usage-stats R read.
            UsageScreen(port = container.usagePort, quotaPort = container.quotaPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.PLUGINS) {
            // GAP-7 (v4-wire.md §12.3): read-only plugins viewer — no install surface (INV-9).
            PluginsViewerScreen(port = container.pluginsPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.SKILLS) {
            // GAP-8 (v4-wire.md §12.4): read-only skills reference catalog (no enable surface).
            SkillsViewerScreen(port = container.skillsPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.MCP) {
            // GAP-9 (v4-wire.md §12.5): read-only mcp/list status viewer (mode:"status", INV-9).
            McpViewerScreen(port = container.mcpPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.APPROVALS) {
            val open by container.conversationPort.observeOpenInteractions().collectAsState(initial = emptyList())
            val phase by container.hub.phase.collectAsState()
            ApprovalsScreen(
                interactions = open,
                phase = phase,
                onBack = { nav.popBackStack() },
                onOpenTask = { taskId -> nav.navigate(Routes.conversation(taskId)) },
                onAnswer = { interactionId, optionId, freeText, onOutcome ->
                    container.log.info("approvals.answer", "interaction=${interactionId.take(12)} option=$optionId")
                    scope.launch {
                        val outcome = container.conversationPort.answerFromInbox(interactionId, optionId, freeText)
                        onOutcome(outcome)
                    }
                },
                scope = scope,
            )
        }

    }
    }
            }
        }
    }
}

@Composable
private fun PairingHostRoute(pairingVm: PairingViewModel, scannerContent: @Composable () -> Unit) {
    com.zcode.android.features.pairing.ui.PairingHost(vm = pairingVm, scannerContent = scannerContent)
}

@Composable
private fun TasksRoute(
    container: AppContainer,
    vm: TasksViewModel,
    onTaskClick: (com.zcode.android.domain.model.Task) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnection: () -> Unit,
    openApprovals: Int = 0,
    onOpenApprovals: () -> Unit = {},
    onCreateTask: (String, String, String?, String?) -> Unit = { _, _, _, _ -> },
    modelPicker: @Composable (
        current: Pair<String, String>?,
        onPick: (provider: String, model: String) -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit = { _, _, _ -> },
    workspaces: List<com.zcode.android.domain.model.WorkspaceOption> = emptyList(),
    creatingTask: Boolean = false,
    createNotice: String? = null,
    onConsumeCreateNotice: () -> Unit = {},
    collapseStore: com.zcode.android.features.tasks.ui.WorkspaceCollapseStore? = null,

    requestCreate: Boolean = false,
    onCreateRequestConsumed: () -> Unit = {},
    onOpenTaskId: (String) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val refreshing by vm.isRefreshing.collectAsState()
    TaskListScreen(
        state = state.copy(refreshing = refreshing),
        onTaskClick = onTaskClick,
        onOpenSettings = onOpenSettings,
        onOpenConnection = onOpenConnection,
        openApprovals = openApprovals,
        onOpenApprovals = onOpenApprovals,
        onCreateTask = onCreateTask,
        modelPicker = { current, onPick, onDismiss ->
            // B1 (Phase 3A): the create-task SHEET hosts the shared picker BODY inline —
            // never a second sheet stacked on it (the live E2/F3 "grey skeleton").
            com.zcode.android.features.conversation.ui.ModelPickerContent(
                current = current,
                currentThought = null,
                thoughtLevels = emptyList(),
                modelOptionsProvider = {
                    val tools = container.hub.phase9()
                    runCatching { tools.modelOptions() }.getOrDefault(emptyList())
                },
                onPick = { provider, model, _ -> onPick(provider, model) },
                onDismiss = onDismiss,
                title = stringResource(com.zcode.android.features.conversation.R.string.composer_model_picker_title),
                rowTagPrefix = "composer_model_row_",
                titleTag = "composer_model_picker_title",
                emptyTag = "composer_model_picker_empty",
            )
        },
        workspaces = workspaces,
        creatingTask = creatingTask,
        createNotice = createNotice,
        onConsumeCreateNotice = onConsumeCreateNotice,
        onRetry = vm::retryConnection,
        onRefresh = vm::refresh,
        onRepairPairing = vm::repairPairing,
        automationsPort = container.automationsPort,
        requestCreate = requestCreate,
        onCreateRequestConsumed = onCreateRequestConsumed,
        onOpenTaskId = onOpenTaskId,
    )
}

/**
 * B2.5: backstack-scoped conversation VM. The old `remember(taskId)` wiring died
 * on ANY activity recreation (uiMode/density/fontScale flips — rotation is covered
 * by the manifest's configChanges), discarding the conversation state. The holder
 * lives on the NavBackStackEntry's ViewModelStore: it survives recreation, is
 * per-entry (each conversation route push = a new entry), and clears on pop. The
 * VM's scope is the holder's (entry-lived), not a composition scope.
 */
class ConversationVmHolder : androidx.lifecycle.ViewModel() {
    var vm: ConversationViewModel? = null

    /** Entry-lived scope: survives recreation, cancelled when the entry pops. */
    private val holderScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )

    val scope: CoroutineScope get() = holderScope

    override fun onCleared() {
        holderScope.cancel()
        super.onCleared()
    }
}

@Composable
private fun rememberConversationViewModel(
    container: AppContainer,
    taskId: String,
): ConversationViewModel {
    val holder: ConversationVmHolder = androidx.lifecycle.viewmodel.compose.viewModel()
    return holder.vm ?: ConversationViewModel(
        container.conversationPort,
        holder.scope,
        taskId,
    ).also { holder.vm = it }
}

@Composable
private fun ConversationRoute(
    container: AppContainer,
    vm: ConversationViewModel,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenTask: (taskId: String) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    // C6-taskdesc: the recents card carries the open task's name.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.LaunchedEffect(state.task?.title) {
        activity?.updateRecentsTaskLabel(state.task?.title)
    }
    // Phase 4A: an accepted fork names the child session — navigate once, then consume.
    val forked by vm.forkedSession.collectAsState()
    androidx.compose.runtime.LaunchedEffect(forked) {
        val child = forked ?: return@LaunchedEffect
        vm.consumeForkedSession()
        onOpenTask(child)
    }
    ConversationScreen(
        state = state,
        phase = state.phase,
        onBack = onBack,
        onLoadOlder = vm::loadOlder,
        onDismissHistoryNotice = vm::dismissHistoryNotice,
        onRetryOlder = vm::retryOlder,
        onDraftChange = vm::onDraftChange,
        onSend = vm::send,
        onStop = vm::stopGeneration,
        onRetrySend = vm::retryPendingSend,
        onDismissSendNotice = vm::dismissSendNotice,
        onAnswer = vm::answerInteraction,
        onSendQueuedNow = vm::sendQueuedNow,
        onEditQueueItem = vm::editQueueItem,
        onAnswerQuestions = vm::answerUserInputQuestions,
        // Phase 3C (C7): lazy full tool output — slice when the desktop answers, else the row payload.
        toolOutputProvider = { row ->
            state.task?.taskId?.let { container.conversationPort.fetchToolCallOutput(it, row.toolCallId) }
        },
        // Phase 3C (I2.2): the status strip rides the git summary channel for this workspace.
        sessionStatusProvider = label@{
            val target = container.hub.activeWorkspaceTarget() ?: return@label null
            target.first?.let { ws -> runCatching { container.hub.phase9().gitSummary(ws) }.getOrNull() }
        },
        // Phase 3D (A3.1): in-conversation attachment previews off the read channel.
        loadAttachment = { att ->
            state.task?.taskId?.let { container.conversationPort.fetchAttachmentPreview(it, att.ref) }
        },
        // Phase 9 (§43.2-9)
        onOpenFiles = onOpenFiles,
        onOpenGit = onOpenGit,
        onOpenTerminal = onOpenTerminal,
        onSwitchMode = vm::switchMode,
        onSwitchModel = vm::switchModel,
        modelOptionsProvider = {
            val tools = container.hub.phase9()
            runCatching { tools.modelOptions() }.getOrDefault(emptyList())
        },
        turnChangesProvider = { rowId, entityId -> vm.turnFileChanges(rowId, entityId) },
        onUploadAttachment = { fileName, mime, bytes -> vm.uploadAttachment(fileName, mime, bytes) },
        onRemoveAttachment = vm::removeUpload,
        onRetryAttachment = vm::retryUpload,
        // Phase 2 (§11.4, GAP-10): message edit (long-press → sheet → confirm).
        onEditUserQuery = { rowId, entityId, newText -> vm.editUserQuery(rowId, entityId, newText) },
        // Phase 4A (E3.2+I1+N5): the flag-gated row actions over the proven wire.
        onRetryTurn = vm::retryTurn,
        onForkAssistant = vm::forkAssistant,
        onApplyFileRewind = vm::applyFileRewind,
        onAssistantFeedback = vm::setAssistantFeedback,
        onSlashMenuOpen = vm::onSlashMenuOpen,
        onSetThoughtLevel = vm::setThoughtLevel,
        fileSearchProvider = { query -> container.conversationPort.searchWorkspaceFiles(state.task?.taskId ?: taskId, query) },
    )
}

@Composable
private fun FilesRoute(container: AppContainer, onBack: () -> Unit) {
    val topBarScroll = com.zcode.android.core.ui.components.rememberZCodeTopBarScroll()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            com.zcode.android.core.ui.components.ZCodeTopBar(
                title = stringResource(R.string.nav_files_title),
                onBack = onBack,
                backTestTag = "files_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        com.zcode.android.features.files.ui.FilesScreen(port = container.filesPort, modifier = Modifier.fillMaxSize().padding(inner))
    }
}

@Composable
private fun GitRoute(container: AppContainer, onBack: () -> Unit) {
    val topBarScroll = com.zcode.android.core.ui.components.rememberZCodeTopBarScroll()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            com.zcode.android.core.ui.components.ZCodeTopBar(
                title = stringResource(R.string.nav_git_title),
                onBack = onBack,
                backTestTag = "git_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        com.zcode.android.features.git.ui.GitScreen(port = container.gitPort, modifier = Modifier.fillMaxSize().padding(inner))
    }
}

@Composable
private fun TerminalRoute(container: AppContainer, onBack: () -> Unit) {
    val topBarScroll = com.zcode.android.core.ui.components.rememberZCodeTopBarScroll()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            com.zcode.android.core.ui.components.ZCodeTopBar(
                title = stringResource(R.string.nav_terminal_title),
                onBack = onBack,
                backTestTag = "terminal_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        com.zcode.android.features.terminal.ui.TerminalScreen(port = container.terminalPort, modifier = Modifier.fillMaxSize().padding(inner))
    }
}

@Composable
private fun ConnectionCenterRoute(container: AppContainer, onBack: () -> Unit) {
    val phase by container.hub.phase.collectAsState()
    val endpoint by container.hub.endpoint.collectAsState()
    val lastError by container.hub.lastError.collectAsState()
    val lastRefresh by container.hub.lastRefreshAtMs.collectAsState()
    val updateState by container.appUpdate.check.collectAsState()
    val downloadState by container.appUpdate.download.collectAsState()
    // AAP-3 (§14.2 T4): the foreground update check — runs on connection-center
    // entry when a desktop version is known; no-ops (no network) when the desktop
    // is within the tested matrix (§29 no-polling: the screen entry is the trigger).
    LaunchedEffect(endpoint?.desktopAppVersion) {
        container.appUpdate.checkForUpdate(endpoint?.desktopAppVersion)
    }
    ConnectionCenterScreen(
        phase = phase,
        endpointName = endpoint?.displayName,
        desktopVersion = endpoint?.desktopAppVersion,
        lastError = lastError,
        lastRefreshAtMs = lastRefresh,
        onRetry = container.hub::retry,
        onDismissError = container.hub::dismissError,
        onBack = onBack,
        updateState = updateState,
        downloadState = downloadState,
        onUpdateNow = container.appUpdate::updateNow,
        onCheckForUpdate = { container.appUpdate.checkForUpdate(endpoint?.desktopAppVersion, force = true) },
    )
}

@Composable
private fun SettingsRoute(
    container: AppContainer,
    pairingVm: PairingViewModel,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit = {},
    onOpenPlugins: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
) {
    val endpoint by container.hub.endpoint.collectAsState()
    SettingsScreen(
        container = container,
        endpointName = endpoint?.displayName,
        desktopVersion = endpoint?.desktopAppVersion,
        onBack = onBack,
        onOpenUsage = onOpenUsage,
        onOpenPlugins = onOpenPlugins,
        onOpenSkills = onOpenSkills,
        onOpenMcp = onOpenMcp,
    )
}
