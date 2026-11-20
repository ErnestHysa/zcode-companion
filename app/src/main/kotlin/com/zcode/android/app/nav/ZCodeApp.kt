package com.zcode.android.app.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.navigation.NavHostController
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
    const val ONBOARDING = "onboarding"
    const val CONVERSATION = "conversation/{taskId}"
    const val CONNECTION = "connection"
    const val SETTINGS = "settings"
    const val USAGE = "usage"
    const val PLUGINS = "plugins"
    const val SUBAGENTS = "subagents"
    const val PROVIDERS = "providers"
    const val BOTS = "bots"
    const val SKILLS = "skills"
    const val MCP = "mcp"
    const val HOOKS = "hooks"
    const val COMMANDS = "commands"
    const val MEMORY = "memory"
    const val APPROVALS = "approvals"
    const val FILES = "files"
    const val GIT = "git"
    const val TERMINAL = "terminal"
    /** F-34-E: the post-pairing notification step (the ask + the Live-connection offer). */
    const val NOTIFY_SETUP = "notify-setup"

    fun conversation(taskId: String) = "conversation/$taskId"
}

/**
 * F-34-E (vc52 Phase 8, rig-caught): the NavHost start destination is decided ONCE, at the
 * first composition (the splash holds until the paired gate is settled — C6 — so that
 * composition already knows). It used to be `if (paired != null) TASKS else PAIRING`,
 * re-evaluated on every composition: when pairing completed the start destination flipped,
 * Navigation rebuilt the graph and RESET the back stack to TASKS during composition — before
 * `LaunchedEffect(paired)` ran — so the effect saw TASKS and its `onPairing` branch (the
 * notification ask, the first-run onboarding) never executed on a real pairing. Every later
 * paired-driven transition belongs to that effect (its contract says so).
 */
@androidx.compose.runtime.Composable
internal fun rememberStartDestination(paired: Boolean): String =
    remember { if (paired) Routes.TASKS else Routes.PAIRING }

/**
 * F-34-A (vc52 Phase 8): the ONE conversation navigation seam. `launchSingleTop` is
 * WRONG twice over: Navigation matches the destination PATTERN (`conversation/{taskId}`),
 * not the arguments, so (a) a tap for task B while A is on top would REUSE (replace!)
 * A's entry — A's screen and its retained VM vanish; (b) the duplicate a notification
 * tap stacked for the SAME task was exactly what F-34-A filed. The contract is the TASK:
 * already on top → keep it (the retained VM survives, no duplicate); same task deeper
 * in the stack → pop back to that entry; a different task → push a new entry.
 */
fun NavHostController.openConversation(taskId: String) {
    val top = currentBackStackEntry
    if (top != null && top.destination.route == Routes.CONVERSATION && top.arguments?.getString("taskId") == taskId) return
    val existing = currentBackStack.value.lastOrNull {
        it.destination.route == Routes.CONVERSATION && it.arguments?.getString("taskId") == taskId
    }
    if (existing != null) {
        // No public pop-to-ENTRY API (popBackStack(String, …) takes a ROUTE, and the
        // destination-id overload stops at the TOPMOST conversation — maybe another task's).
        while (currentBackStackEntry !== existing) {
            if (!popBackStack()) break
        }
        return
    }
    navigate(Routes.conversation(taskId))
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
    val prefs by container.stores.preferencesRepository.observe()
        .collectAsState(com.zcode.android.domain.model.AppPreferences())
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
    val appContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(paired) {
        val onPairing = nav.currentDestination?.route == Routes.PAIRING
        when {
            paired == null ->
                if (!onPairing) nav.navigate(Routes.PAIRING) { popUpTo(0) { inclusive = true } }
            onPairing -> {
                container.hub.onPairingCompleted()
                // F-34-E: the END of pairing is where the notification ask lands — on API 33+
                // every fresh install has POST_NOTIFICATIONS denied, and nothing else ever
                // asked: approvals were "posted" into a blocked sink, zero delivered.
                if (!com.zcode.android.app.notifications.NotificationsGate.enabled(appContext)) {
                    nav.navigate(Routes.NOTIFY_SETUP) { popUpTo(Routes.PAIRING) { inclusive = true } }
                } else if (!prefs.onboardingDone) {
                    // H1.5: the ONE-TIME post-pairing pass — skippable; never re-shown.
                    nav.navigate(Routes.ONBOARDING) { popUpTo(Routes.PAIRING) { inclusive = true } }
                } else {
                    nav.navigate(Routes.TASKS) { popUpTo(Routes.PAIRING) { inclusive = true } }
                }
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
    // N-BI-4 (vc52 Phase 10): a rejected deep link renders the honest copy through
    // the Snackbar notifier (the StatesDiscipline contract) — never a silent no-op.
    val rejectedLinks by container.notifications.rejectedLinks.collectAsState()
    val linkRejectedText = stringResource(com.zcode.android.app.R.string.deeplink_rejected)
    androidx.compose.runtime.LaunchedEffect(rejectedLinks) {
        if (rejectedLinks > 0) {
            notifier.show(linkRejectedText, long = true)
            container.notifications.consumeRejectedLink()
        }
    }
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
    androidx.compose.runtime.LaunchedEffect(pendingLink, paired) {
        val link = pendingLink ?: return@LaunchedEffect
        // Connection-scoped links never land here (MainActivity routes them through the
        // H2.2 shortcut seam) — the task resolver below only ever sees Task-shaped links.
        if (link !is com.zcode.android.app.notifications.DeepLinks.Candidate.Task) return@LaunchedEffect
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
            -> nav.openConversation(link.taskId)
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
    // G43-P24 (vc52 Phase 13): the What's-new sheet owed ONCE after updating into
    // a recorded version (the record rides the install handoff; dismiss persists).
    val whatsNewPrefs by container.stores.preferencesRepository.observe()
        .collectAsState(com.zcode.android.domain.model.AppPreferences())
    val installedVersionCode = remember {
        runCatching {
            container.appContext.packageManager.getPackageInfo(container.appContext.packageName, 0).longVersionCode
        }.getOrNull()
    }
    val owesWhatsNew = !whatsNewPrefs.whatsNewShown &&
        whatsNewPrefs.whatsNewNotes != null &&
        whatsNewPrefs.whatsNewVersionCode != null &&
        whatsNewPrefs.whatsNewVersionCode == installedVersionCode
    if (owesWhatsNew) {
        com.zcode.android.app.settings.WhatsNewDialog(
            versionName = whatsNewPrefs.whatsNewVersionName,
            notes = whatsNewPrefs.whatsNewNotes,
            onDismiss = {
                scope.launch {
                    container.stores.preferencesRepository.update { p -> p.copy(whatsNewShown = true) }
                }
            },
        )
    }
    // B2.15/J4: ONE ticking clock for every relative-time label in the app.
    com.zcode.android.core.ui.components.ProvideRelativeNow {
    NavHost(
        navController = nav,
        startDestination = rememberStartDestination(paired != null),
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        enterTransition = { navMotion.enter },
        exitTransition = { navMotion.exit },
        popEnterTransition = { navMotion.popEnter },
        popExitTransition = { navMotion.popExit },
    ) {
        composable(Routes.PAIRING) {
            PairingHostRoute(pairingVm = pairingVm, scannerContent = scannerContent)
        }
        composable(Routes.ONBOARDING) {
            // H1.5: the one-time "what your phone can do" pass — Skip and Get-started
            // both mark the pref done and land on the task list.
            androidx.compose.runtime.LaunchedEffect(Unit) { container.hub.onPairingCompleted() }
            com.zcode.android.features.pairing.ui.OnboardingPager(
                onFinish = {
                    scope.launch {
                        container.stores.preferencesRepository.update { p -> p.copy(onboardingDone = true) }
                        nav.navigate(Routes.TASKS) { popUpTo(0) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.NOTIFY_SETUP) {
            val liveRunning by com.zcode.android.core.notifications.LiveConnectionService.isRunning.collectAsState()
            com.zcode.android.app.notifications.NotificationSetupScreen(
                onContinue = {
                    container.log.info("notify.setup", "continue enabled=${com.zcode.android.app.notifications.NotificationsGate.enabled(appContext)}")
                    nav.navigate(if (!prefs.onboardingDone) Routes.ONBOARDING else Routes.TASKS) {
                        popUpTo(Routes.NOTIFY_SETUP) { inclusive = true }
                    }
                },
                onStartLiveConnection = {
                    container.log.info("notify.setup", "live connection on")
                    com.zcode.android.core.notifications.LiveConnectionService.start(appContext)
                },
                liveConnectionRunning = liveRunning,
            )
        }
        composable(Routes.TASKS) {
            // §18.3 "Needs you": the approvals inbox entry carries the open count.
            val openApprovals by container.conversationPort.observeOpenInteractions()
                .collectAsState(initial = emptyList())
            val taskState by tasksVm.state.collectAsState()
            // G61 (vc52 Phase 13): the list-detail workbench selection. ≥600 dp the
            // tap swaps the detail pane IN PLACE; below the breakpoint it navigates
            // exactly as before. rememberSaveable: the pane survives rotation.
            var workbenchTaskId by androidx.compose.runtime.saveable.rememberSaveable {
                androidx.compose.runtime.mutableStateOf<String?>(null)
            }
            val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
            val workbenchOn = com.zcode.android.core.ui.components.WorkbenchPolicy.useListDetail(screenWidthDp)
            TasksRoute(
                container = container,
                vm = tasksVm,
                onTaskClick = { task ->
                    container.log.info("tasks.open", "task=${task.taskId.take(24)} title=${task.title.take(40)}")
                    if (workbenchOn) {
                        workbenchTaskId = task.taskId
                    } else {
                        nav.openConversation(task.taskId)
                    }
                },
                workbenchTaskId = workbenchTaskId,
                onCloseWorkbench = { workbenchTaskId = null },
                onOpenTaskFromWorkbench = { child ->
                    container.log.info("tasks.workbenchSwap", "task=${child.take(24)}")
                    workbenchTaskId = child
                },
                onPaneNavigate = { route ->
                    container.log.info("tasks.workbenchPaneNav", "route=$route")
                    nav.navigate(route)
                },
                // G66: the pane's error card → the feedback form with the error attached.
                onReportIssue = { details ->
                    container.pendingFeedbackPrefill.value = details
                    nav.navigate(Routes.SETTINGS)
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
                onOpenTaskId = { id -> container.log.info("tasks.openRun", "task=${id.take(24)}"); nav.openConversation(id) },
                // Phase 4C (C7): scratch create — resolve `{name}` → `{path}` (createScratchWorkspace),
                // then the SAME createTaskAndWait against the returned path.
                onCreateScratchTask = { name, text, provider, model ->
                    scope.launch {
                        container.log.info("tasks.scratch", "name=${name.take(24)}")
                        val path = container.tasksPort.createScratchWorkspace(name)
                        if (path != null) {
                            tasksVm.createTaskAndWait(path, text, provider, model)
                        } else {
                            container.log.warn("tasks.scratch", "createScratchWorkspace refused — nothing created")
                        }
                    }
                },
            )
        }
        composable(Routes.CONVERSATION) { entry ->
            val taskId = entry.arguments?.getString("taskId").orEmpty()
            val vm = rememberConversationViewModel(container, taskId)
            // G68 (vc52 Phase 12): the opt-in view-state self-report on open — the
            // desktop persists it (bootstrap mobileViewState) so it can restore the
            // phone's last task. The pref gate lives in the hub (default OFF).
            val reportScope = androidx.compose.runtime.rememberCoroutineScope()
            androidx.compose.runtime.LaunchedEffect(taskId) {
                val version = runCatching {
                    container.appContext.packageManager.getPackageInfo(container.appContext.packageName, 0).versionName
                }.getOrNull() ?: "unknown"
                val wsKey = container.hub.activeWorkspaceTarget()?.second
                    ?: container.hub.activeWorkspaceTarget()?.first
                runCatching { container.hub.reportMobileViewState(wsKey, taskId, version) }
            }
            ConversationRoute(
                container = container,
                vm = vm,
                onBack = { nav.popBackStack() },
                // G66: Report issue → the feedback form with the error attached.
                onReportIssue = { details ->
                    container.log.info("nav.reportIssue", "details=${details.take(60)}")
                    container.pendingFeedbackPrefill.value = details
                    nav.navigate(Routes.SETTINGS)
                },
                onOpenFiles = { container.log.info("nav.files", "open"); nav.navigate(Routes.FILES) },
                onOpenGit = { container.log.info("nav.git", "open"); nav.navigate(Routes.GIT) },
                onOpenTerminal = { container.log.info("nav.terminal", "open"); nav.navigate(Routes.TERMINAL) },
                // G11: the quota banner's chip lands on the Usage screen's quota section.
                onOpenQuotaScreen = { nav.navigate(Routes.USAGE) },
                // Phase 4A: an accepted fork opens the CHILD session in place (desktop onSessionCreated parity).
                onOpenTask = { child -> container.log.info("nav.fork", "open ${child.take(12)}"); nav.openConversation(child) },
                // N-BK-1(a) (vc52 Phase 12): a subagent row's child session opens in place.
                onOpenSubagentChildSession = { child ->
                    container.log.info("nav.subagent", "open ${child.take(12)}"); nav.openConversation(child)
                },
            )
        }

        composable(Routes.FILES) {
            FilesRoute(
                container = container,
                onBack = { nav.popBackStack() },
                // G78: the comment's task = the conversation entry BENEATH the Files
                // entry — the caller owns the back-stack, so it provides the id.
                commentTaskId = {
                    nav.currentBackStack.value.lastOrNull { entry ->
                        entry.destination.route == Routes.CONVERSATION
                    }?.arguments?.getString("taskId")
                },
            )
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
                onOpenSubagents = { nav.navigate(Routes.SUBAGENTS) },
                onOpenProviders = { nav.navigate(Routes.PROVIDERS) },
                onOpenBots = { nav.navigate(Routes.BOTS) },
                onOpenHooks = { nav.navigate(Routes.HOOKS) },
                onOpenCommands = { nav.navigate(Routes.COMMANDS) },
                onOpenMemory = { nav.navigate(Routes.MEMORY) },
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
        composable(Routes.SUBAGENTS) {
            // G25 (vc51 Phase 4B): read-only subagents viewer — scope/enable badges, no write affordance (INV-9).
            com.zcode.android.app.settings.SubagentsViewerScreen(port = container.subagentsPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.PROVIDERS) {
            // G27 (vc51 Phase 4B): read-only provider viewer — no credential plane (desktop-side boundary).
            com.zcode.android.app.settings.ProvidersViewerScreen(port = container.providersPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.BOTS) {
            // G30 (vc51 Phase 4B): read-only bots viewer (the INV-9 seed) — management stays desktop-side.
            com.zcode.android.app.settings.BotsViewerScreen(port = container.botsPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.SKILLS) {
            // GAP-8 (v4-wire.md §12.4): read-only skills reference catalog (no enable surface).
            SkillsViewerScreen(port = container.skillsPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.MCP) {
            // GAP-9 (v4-wire.md §12.5): read-only mcp/list status viewer (mode:"status", INV-9).
            McpViewerScreen(port = container.mcpPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.MEMORY) {
            // G75 (vc52 Phase 12): read-only workspace-memory viewer over the `file`
            // channel (the desktop memory tree is desktop-local node:fs — the phone
            // reads it through readdir/readTextFile; INV-9, no write affordance).
            com.zcode.android.app.settings.MemoryViewerScreen(port = container.memoryPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.COMMANDS) {
            // G74 (vc52 Phase 12): read-only custom-command viewer (commands.list decode;
            // authoring stays desktop-side — the dated boundary note renders in the screen).
            com.zcode.android.app.settings.CommandsViewerScreen(port = container.commandsPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.HOOKS) {
            // G71 (vc52 Phase 12): read-only workspace hooks viewer over the RETAINED
            // hook reviews (Refresh re-requests the review; authoring stays desktop-side).
            com.zcode.android.app.settings.HooksViewerScreen(port = container.hooksPort, onBack = { nav.popBackStack() }, modifier = Modifier.fillMaxSize())
        }
        composable(Routes.APPROVALS) {
            val open by container.conversationPort.observeOpenInteractions().collectAsState(initial = emptyList())
            val phase by container.hub.phase.collectAsState()
            ApprovalsScreen(
                interactions = open,
                phase = phase,
                onBack = { nav.popBackStack() },
                onOpenTask = { taskId -> nav.openConversation(taskId) },
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
            // G64 (vc52 Phase 11): the CUA permission card — the IN-APP face (the
            // desktop blocks a computer-use task on missing macOS permissions). The
            // notification plane owns the background face; this card renders whenever
            // a blocked observation is the latest (dismiss clears the state).
            val cuaObservation by container.hub.latestCuaObservation.collectAsState()
            var cuaCardDismissed by androidx.compose.runtime.saveable.rememberSaveable {
                androidx.compose.runtime.mutableStateOf(false)
            }
            cuaObservation?.takeIf { it.blocked && !cuaCardDismissed }?.let { o ->
                val missing = buildList {
                    if (o.accessibility != "granted") add("Accessibility: " + o.accessibility.replaceFirstChar { it.uppercase() })
                    if (o.screenRecording != "granted") add("Screen Recording: " + o.screenRecording.replaceFirstChar { it.uppercase() })
                }.joinToString(", ").ifBlank { "macOS permissions" }
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.shapes.medium,
                        )
                        .padding(12.dp)
                        .testTag("cua_permission_card"),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
                    androidx.compose.material3.Text(
                        stringResource(com.zcode.android.core.ui.R.string.cua_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    androidx.compose.material3.Text(
                        stringResource(com.zcode.android.core.ui.R.string.cua_permission_text, missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    androidx.compose.material3.TextButton(onClick = { cuaCardDismissed = true }, modifier = Modifier.testTag("cua_card_dismiss")) {
                        androidx.compose.material3.Text(stringResource(com.zcode.android.core.ui.R.string.cua_card_dismiss))
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
    onCreateScratchTask: (name: String, firstText: String, provider: String?, model: String?) -> Unit = { _, _, _, _ -> },
    /**
     * G61 (vc52 Phase 13): the list-detail workbench (≥600 dp). Non-null = the
     * detail pane hosts that task's conversation IN PLACE (no navigation); the
     * phone layout below the breakpoint never receives it.
     */
    workbenchTaskId: String? = null,
    onCloseWorkbench: () -> Unit = {},
    /** A fork/subagent child opened from the pane swaps the pane (desktop onSessionCreated parity). */
    onOpenTaskFromWorkbench: (String) -> Unit = {},

    /** G61: the workbench pane's full-screen pane exits (Files/Git/Terminal routes). */
    onPaneNavigate: (String) -> Unit = {},
    /** G66: the pane's error card Report issue (the feedback form, error attached). */
    onReportIssue: ((String) -> Unit)? = null,
) {
    val prefs by container.stores.preferencesRepository.observe()
        .collectAsState(com.zcode.android.domain.model.AppPreferences())
    val state by vm.state.collectAsState()
    val refreshing by vm.isRefreshing.collectAsState()
    val startScope = androidx.compose.runtime.rememberCoroutineScope()
    // F-P40-B: the three workspace-manager pref writes run on the PROCESS-LIFETIME
    // stores scope — never the composition scope (a write in flight dies silently
    // when the composition leaves; WorkspacePreferenceWriter carries the receipts).
    val workspacePrefs = androidx.compose.runtime.remember {
        WorkspacePreferenceWriter(container.stores.preferencesRepository, container.stores.appScope)
    }
    val notifier = com.zcode.android.core.ui.components.LocalNotifier.current
    val notificationsEnabled = com.zcode.android.app.notifications.rememberNotificationsEnabled()
    val startedCopy = stringResource(com.zcode.android.features.tasks.R.string.workflow_start_sent)
    val refusedCopy = stringResource(com.zcode.android.features.tasks.R.string.workflow_start_refused)
    // G69 (vc52 Phase 13): mark-read-on-open — opening an unread task clears the
    // desktop's unread marker (markTaskReadOnOpen parity) BEFORE the open navigates.
    val taskClickWithRead: (com.zcode.android.domain.model.Task) -> Unit = { task ->
        startScope.launch { vm.markReadOnOpen(task) }
        onTaskClick(task)
    }
    val taskList: @Composable () -> Unit = {
        TaskListScreen(
        state = state.copy(refreshing = refreshing),
        onTaskClick = taskClickWithRead,
        // G69: the organise/sort view state (the VM owns it).
        onOrganizeBy = vm::organizeBy,
        onSortBy = vm::sortBy,
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
        onCreateScratchTask = onCreateScratchTask,
        // F5-WIRE (Phase 5A discovery): the Phase-4D lifecycle + search callbacks were never
        // passed here — the menu and confirms rendered, the confirmed actions were no-ops.
        onDeleteTask = vm::deleteTask,
        onRenameTask = vm::renameTask,
        onSetTaskArchived = vm::setTaskArchived,
        onSearch = vm::search,
        onSearchShowMore = vm::searchShowMore,
        onConsumeActionNotice = vm::consumeActionNotice,
        collapseStore = collapseStore,
        // G41 (vc51): the workspace manager over RECEIVED workspaces (persisted).
        selectedWorkspaceKeyExternal = prefs.selectedWorkspaceKey,
        onWorkspaceSelected = workspacePrefs::select,
        onWorkspacesReordered = workspacePrefs::reorder,
        onWorkspaceRenamed = workspacePrefs::rename,
        workspaceOrder = prefs.workspaceOrder,
        workspaceAliases = prefs.workspaceAliases,
        // F-34-E: the persistent off-state row while Android blocks the app's notifications.
        notificationsBanner = if (notificationsEnabled) null else {
            { com.zcode.android.app.notifications.NotificationsOffRow() }
        },
        // Phase 5A (A3.8–A3.10): pin / unread / resume / groups / archived viewer.
        onSetTaskPinned = vm::setTaskPinned,
        onSetTaskUnread = vm::setTaskUnread,
        onResumeTask = vm::resumeTask,
        onMoveTaskToGroup = vm::moveTaskToGroup,
        onCreateGroup = vm::createGroup,
        // F-P39-A: the move sheet's New-group path completes the pending move.
        onCreateGroupAndMove = vm::createGroupAndMove,
        onRenameGroup = vm::renameGroup,
        onRecolorGroup = vm::recolorGroup,
        onDeleteGroup = vm::deleteGroup,
        onLoadArchived = vm::loadArchived,
        onUnarchive = vm::unarchiveFromViewer,
        onDeleteArchived = vm::deleteArchivedTask,
        onLoadGroups = vm::loadGroups,
        // L6.4 (Phase 5A): start a saved workflow in the chosen task, then open it (the runs
        // panel shows the run); a refusal surfaces the typed reason through the notifier.
        onStartWorkflow = { taskId, name, scope, args ->
            startScope.launch {
                val result = container.conversationPort.startSavedWorkflow(taskId, name, scope, args)
                if (result.outcome == com.zcode.android.features.conversation.port.ConversationPort.SendOutcome.ACCEPTED && result.reasonCode == null) {
                    notifier.show(startedCopy)
                    onOpenTaskId(taskId)
                } else {
                    notifier.show(refusedCopy.format(result.reasonCode ?: result.outcome.name.lowercase()), long = true)
                }
            }
        },
        )
    }
    // G61 (vc52 Phase 13): the list-detail workbench at >=600 dp — the task list on
    // the left, the selected task's conversation IN PLACE on the right (no
    // navigation); below the breakpoint the phone layout is unchanged. The pane
    // holds EXACTLY ONE live conversation VM (the F-33-A lesson):
    // WorkbenchPaneController tears the previous VM down before creating the next.
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    if (com.zcode.android.core.ui.components.WorkbenchPolicy.useListDetail(widthDp)) {
        val holder: ConversationVmHolder = androidx.lifecycle.viewmodel.compose.viewModel(
            key = "workbench-conversation-holder",
        )
        val workbench = androidx.compose.runtime.remember {
            WorkbenchPaneController<ConversationViewModel>(teardown = { it.onTeardown() })
        }
        val paneVm = workbenchTaskId?.let { id ->
            workbench.acquire(id) { t ->
                ConversationViewModel(container.conversationPort, holder.scope, t).also { holder.vm = it }
            }
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                workbench.close()
                holder.vm = null
            }
        }
        com.zcode.android.core.ui.components.WorkbenchPolicy.WorkbenchScaffold(
            list = taskList,
            detail = paneVm?.let { paneV ->
                {
                    ConversationRoute(
                        container = container,
                        vm = paneV,
                        onBack = onCloseWorkbench,
                        onOpenFiles = { onPaneNavigate(Routes.FILES) },
                        onOpenGit = { onPaneNavigate(Routes.GIT) },
                        onOpenTerminal = { onPaneNavigate(Routes.TERMINAL) },
                        onOpenQuotaScreen = { onPaneNavigate(Routes.USAGE) },
                        // A fork / subagent child swaps the pane (desktop onSessionCreated parity).
                        onOpenTask = onOpenTaskFromWorkbench,
                        onOpenSubagentChildSession = onOpenTaskFromWorkbench,
                        // G66: forwarded — the pane's error card reports with the error attached.
                        onReportIssue = onReportIssue,
                    )
                }
            },
            onOpenFromEmpty = {},
        )
    } else {
        taskList()
    }
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
        // GAP-A3/G14 (vc51): entry pop = task switch/exit — release the session's
        // wire subscription BEFORE the scope dies (the port relays to the hub's
        // scope, so the cancel below cannot eat the unsubscribe).
        vm?.onTeardown()
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
    /** N-BK-1(a) (vc52 Phase 12): a subagent row's child session opens in place. */
    onOpenSubagentChildSession: (String) -> Unit = {},
    onOpenQuotaScreen: () -> Unit = {},
    /** G66: the error card's Report issue → the feedback form with the error attached. */
    onReportIssue: ((String) -> Unit)? = null,
) {
    val state by vm.state.collectAsState()
    // G78 (vc52 Phase 11): the pending Files line comment lands in the composer
    // exactly once (the desktop's codeViewer.comment contract — the quoted file/line
    // context rides the next message; never an auto-send).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val pending = container.pendingLineComment.value ?: return@LaunchedEffect
        container.pendingLineComment.value = null
        val sessionId = state.task?.taskId
        if (sessionId == null || pending.taskId == sessionId) {
            vm.prefillDraft(pending.quoted)
            container.log.info("nav.lineComment", "prefilled task=${pending.taskId.take(12)}")
        } else {
            container.log.warn("nav.lineComment", "stale comment for task=${pending.taskId.take(12)} — dropped")
        }
    }
    // G4/AC6 (vc51): the local default interaction behavior seeds the composer's
    // follow-up steering while the session's wire followupMode is unset.
    val prefs by container.stores.preferencesRepository.observe()
        .collectAsState(com.zcode.android.domain.model.AppPreferences())
    // C6-taskdesc: the recents card carries the open task's name.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.LaunchedEffect(state.task?.title) {
        activity?.updateRecentsTaskLabel(state.task?.title)
    }
    // Phase 4A: an accepted fork names the child session — navigate once, then consume.
    val forked by vm.forkedSession.collectAsState()
    // Phase 5A (A3.3): the workflow runs panel + run sheet follow the VM's flows.
    val workflowRuns by vm.workflowRuns.collectAsState()
    val openWorkflowRun by vm.openWorkflowRun.collectAsState()
    // G51 (vc52 Phase 11): the open run's delta-assembled step graph.
    val openRunGraph by vm.openRunGraph.collectAsState()
    val shareCopy = androidx.compose.ui.res.stringResource(com.zcode.android.features.conversation.R.string.conv_share_entry)
    // G57: the no-viewer fallback copy (the typed notice names the file).
    val noViewerTemplate = androidx.compose.ui.res.stringResource(
        com.zcode.android.features.conversation.R.string.artifact_no_viewer,
        "…",
    )
    val noViewerCopy = { name: String -> noViewerTemplate.replace("…", name) }
    // F-P33-A (pass-0 Phase 7) stage-1: the download-failed copy for BOTH external-open
    // arms (the template's "…" holds the file name, the noViewer precedent).
    val artifactDownloadFailedTemplate = androidx.compose.ui.res.stringResource(
        com.zcode.android.features.conversation.R.string.artifact_download_failed,
        "…",
    )
    val artifactDownloadFailedCopy = { name: String -> artifactDownloadFailedTemplate.replace("…", name) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // G57: the notifier for the no-viewer fallback notice (the StatesDiscipline surface).
    val notifier = com.zcode.android.core.ui.components.LocalNotifier.current
    // Phase 5B (A3.12 + A3.13): plans + the trajectory/search sheet.
    val plans by vm.plans.collectAsState()
    val trajectory by vm.trajectory.collectAsState()
    // N-CB-1: the explicit in-flight flag (see the call site below).
    val trajectoryLoading by vm.trajectoryLoading.collectAsState()
    val trajectorySearch by vm.sessionSearch.collectAsState()
    var showTrajectory by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // G28 (vc51 Phase 4D): the attachment zoom-viewer target (null = closed) — full
    // bytes load through the SAME fetchAttachment chunked path the artifact opener uses.
    // G19 (vc51 Phase 4D): the share-mode chooser over the conv_share chip.
    var showShareModes by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var attachmentViewerTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.zcode.android.domain.model.ConversationRow.UserInput.AttachmentMeta?>(null)
    }
    val attachmentLoader: suspend (com.zcode.android.domain.model.ConversationRow.UserInput.AttachmentMeta) -> ByteArray? = { att ->
        val taskId = state.task?.taskId
        if (taskId == null) {
            null
        } else {
            val result = runCatching {
                container.conversationPort.fetchAttachment(taskId, att.ref, null, null, null)
            }.getOrNull()
            val file = result?.file
            // decode-in-memory cap: 12 MiB — larger media keeps the viewer honest-empty
            if (file != null && file.length() <= 12L * 1024 * 1024) {
                runCatching { file.readBytes() }.getOrNull()
            } else {
                null
            }
        }
    }
    if (attachmentViewerTarget != null) {
        com.zcode.android.features.conversation.ui.AttachmentViewerDialog(
            att = attachmentViewerTarget!!,
            loader = attachmentLoader,
            onDismiss = { attachmentViewerTarget = null },
        )
    }
    var trajectoryQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(forked) {
        val child = forked ?: return@LaunchedEffect
        vm.consumeForkedSession()
        onOpenTask(child)
    }
    ConversationScreen(
        onOpenSubagentChildSession = onOpenSubagentChildSession,
        state = state,
        phase = state.phase,
        onBack = onBack,
        onLoadOlder = vm::loadOlder,
        onDismissHistoryNotice = vm::dismissHistoryNotice,
        onRetryOlder = vm::retryOlder,
        onDraftChange = vm::onDraftChange,
        onSend = vm::send,
        // F-P20-A: a dismissed disposition answer drops a staged sheet goal.
        onHeldQueueDismissed = vm::cancelGoalForChoice,
        onStop = vm::stopGeneration,
        onRetrySend = vm::retryPendingSend,
        onDismissSendNotice = vm::dismissSendNotice,
        onAnswer = vm::answerInteraction,
        onSendQueuedNow = vm::sendQueuedNow,
        onEditQueueItem = vm::editQueueItem,
        // G12 (vc51): the persisted replayable set above the composer.
        pendingRecovery = vm.pendingRecovery.collectAsState().value,
        onReplayPendingCommands = vm::replayPendingCommands,
        // G15 (vc51): the background-work indicator + read-only output viewer.
        backgroundOutputProvider = { workId -> vm.fetchBackgroundWorkOutput(workId) },
        onCancelBackgroundWork = vm::cancelBackgroundWork,
        // G26 (vc51): the sheet's goal affordances.
        onSetGoal = vm::setGoal,
        onPauseGoal = vm::pauseGoalFromSheet,
        onResumeGoal = vm::resumeGoalFromSheet,
        // G6 (vc51): the cards' keep-waiting snooze.
        onSnoozeInteraction = vm::snoozeInteraction,
        // G7+G8 (vc51): the row-selection actions — share rides the system sheet
        // (the transcript share's shape), the side session dispatches the wire verb.
        onDiscardSharedContext = vm::discardSharedContext,
        // G10 (vc51): the rotating suggested prompts + prefill.
        promptCursor = vm.suggestedPromptCursor(),
        onPrefillDraft = vm::prefillDraft,
        // G11 (vc51): the in-conversation quota banner → the Usage screen's quota section.
        quotaBanner = vm.quotaBanner.collectAsState().value,
        onOpenQuotaScreen = onOpenQuotaScreen,
        onDismissQuotaBanner = vm::dismissQuotaBanner,
        onSideSessionRow = { text -> vm.createSelectionSideSession(text) },
        onShareSelectionRow = { text ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }
            context.startActivity(android.content.Intent.createChooser(send, shareCopy))
        },
        onAnswerQuestions = vm::answerUserInputQuestions,
        // G66: forwarded to the screen (the error card's Report issue affordance).
        onReportIssue = onReportIssue,
        onDeclineQuestions = vm::declineUserInput,
        // GAP-A1 (vc51, ADR-024): the hook-trust review verbs — their FIRST UI callers.
        onHookTrust = vm::trustHooks,
        onHookRevoke = vm::revokeHooks,
        onHookToggle = vm::toggleHookItem,
        onHookGrant = { hook -> vm.grantHookTrust(hook, hook.bundleDigest) },
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
        // G28 (vc51 Phase 4D): thumbnails open the zoom viewer; PDF/video chips hand
        // off to the system viewer (the onOpenArtifact external-open pattern).
        onOpenAttachment = { att -> attachmentViewerTarget = att },
        onOpenAttachmentExternally = { att ->
            val taskId = state.task?.taskId
            if (taskId != null) {
                scope.launch {
                val result = runCatching {
                    container.conversationPort.fetchAttachment(taskId, att.ref, null, null, null)
                }.getOrNull()
                val file = result?.file
                if (file == null) {
                    // F-P33-A (pass-0 Phase 7) stage-1: the download-null branch was
                    // LOG-ONLY — the user tapped the chip and got NOTHING. The typed
                    // notice (the diagnostic stays in the ring).
                    container.log.warn("attachment.open", "download failed: ${result?.error?.take(80)}")
                    notifier.show(
                        context.getString(
                            com.zcode.android.features.conversation.R.string.artifact_download_failed,
                            att.fileName,
                        ),
                    )
                    return@launch
                }
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", file,
                    )
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, att.mime)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.onFailure { container.log.warn("attachment.open", "${it.javaClass.simpleName}: ${it.message?.take(80)}") }
                }
            }
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
        onRetryCompaction = vm::retryCompaction,
        // L7: a timeline-section crash lands in the diagnostics store (the boundary seam).
        onSectionCrash = { t ->
            runCatching { container.diagnosticsStore.writeCrash("conversation-timeline", t.stackTraceToString()) }
        },
        // I2.8: open a generated artifact externally — download via the file channel
        // (app cache, never desktop paths) then the system viewer chooser.
        onOpenArtifact = { ref, mime ->
            scope.launch {
                val result = runCatching { container.filesPort.download(ref) }.getOrNull()
                val file = result?.file
                if (file == null) {
                    // F-P33-A (pass-0 Phase 7) stage-1: LOG-ONLY before — the preview
                    // card's Open tap ended in nothing when an unserved ref faulted the
                    // download. The typed notice names the file (the F-P0-C silent
                    // family closes here).
                    container.log.warn("preview.open", "download failed: ${result?.error?.take(80)}")
                    notifier.show(artifactDownloadFailedCopy(ref.substringAfterLast('/')))
                    return@launch
                }
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, context.packageName + ".fileprovider", file,
                    )
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.onFailure { e ->
                    // G57 (vc52 Phase 13): no dead preview cards — a handoff with NO
                    // installed viewer degrades to the TYPED notice (names the file);
                    // other faults keep the honest log face.
                    if (com.zcode.android.features.conversation.ArtifactOpenFallback.isNoViewerActivityLaunch(e)) {
                        notifier.show(noViewerCopy(file.name))
                    } else {
                        container.log.warn("preview.open", "${e.javaClass.simpleName}: ${e.message?.take(80)}")
                    }
                }
            }
        },
        onSlashMenuOpen = vm::onSlashMenuOpen,
        onSetThoughtLevel = vm::setThoughtLevel,
        fileSearchProvider = { query, refresh -> container.conversationPort.searchWorkspaceFiles(state.task?.taskId.orEmpty(), query, refresh) },
        // G65 (vc52 Phase 12): the desktop's mention categories over the phone's
        // existing read surfaces — sessions = the task list (#id), skills = the
        // reference catalog ($name), plugins = the enabled plugins (the desktop's
        // markdown link), agents = the subagents catalog (@name).
        mentionCategoriesProvider = { category, query ->
            val target = container.hub.activeWorkspaceTarget()
            val q = query.trim()
            fun matches(label: String, id: String): Boolean =
                q.isBlank() || label.contains(q, ignoreCase = true) || id.contains(q, ignoreCase = true)
            when (category) {
                "sessions" -> container.hub.currentTasks()
                    .filter { matches(it.title, it.taskId) }
                    .take(8)
                    .map { com.zcode.android.features.conversation.ui.MentionOption(it.title.ifBlank { it.taskId }, it.taskId, "#${it.taskId}") }
                "skills" -> target?.let { (wp, wi) ->
                    container.hub.viewerTools().skillCatalog(wp, wi).skills
                        .filter { matches(it.name, it.id) }
                        .take(8)
                        .map { com.zcode.android.features.conversation.ui.MentionOption(it.name, it.description, "\$${it.name}") }
                } ?: emptyList()
                "plugins" -> target?.let { (wp, wi) ->
                    container.hub.viewerTools().plugins(wp, wi).plugins
                        .filter { it.enabled && matches(it.name, it.id) }
                        .take(8)
                        .map { com.zcode.android.features.conversation.ui.MentionOption(it.name, it.description, "[@${it.name}](plugin://${it.id})") }
                } ?: emptyList()
                "agents" -> target?.let { (wp, wi) ->
                    container.hub.viewerTools().subagentsDetailed(wp, wi, null)
                        .filter { matches(it.name, it.name) }
                        .take(8)
                        .map { com.zcode.android.features.conversation.ui.MentionOption(it.name, it.description, "@${it.name}") }
                } ?: emptyList()
                else -> emptyList()
            }
        },
        workflowRuns = workflowRuns,
        openWorkflowRun = openWorkflowRun,
        openRunGraph = openRunGraph,
        onOpenWorkflowRun = vm::openWorkflowRun,
        onCloseWorkflowRun = vm::closeWorkflowRun,
        onLoadMoreRunEvents = vm::loadMoreRunEvents,
        onResumeWorkflowRun = vm::resumeWorkflowRun,
        onAmendWorkflowRun = vm::amendWorkflowRunSettings,
        onMoveQueueItem = vm::moveQueueItem,
        onSetAutoDrain = vm::setAutoDrain,
        onSetFollowupMode = vm::setFollowupMode,
        onDeleteQueueItem = vm::deleteQueueItem,
        defaultFollowupMode = prefs.interactionBehavior,
        // G60 (vc52 Phase 12): the per-task token usage read for the context sheet.
        taskUsageProvider = {
            runCatching { container.hub.agentFacade().taskTokenUsage(state.task?.taskId.orEmpty()) }.getOrNull()
        },
        // G36 (vc51): the presentation mode gates the timeline's tool detail.
        officeMode = prefs.interfaceMode == com.zcode.android.domain.model.AppPreferences.InterfaceMode.OFFICE,
        plans = plans,
        showTrajectory = showTrajectory,
        trajectory = trajectory,
        // N-CB-1: the VM's explicit in-flight flag — the null-derivation made every
        // fetch fault render an eternal loading bar (the unavailable face was
        // structurally unreachable; receipted spinning through a full relay swap).
        trajectoryLoading = trajectoryLoading,
        trajectorySearchResults = trajectorySearch,
        trajectoryQuery = trajectoryQuery,
        onTrajectoryQueryChange = { trajectoryQuery = it; vm.searchSession(it) },
        onOpenTrajectory = { showTrajectory = true; vm.loadTrajectory() },
        onCloseTrajectory = { showTrajectory = false; trajectoryQuery = ""; vm.searchSession("") },
        rewindPreviewProvider = { rowId, entityId ->
            val taskId = state.task?.taskId
            if (taskId == null) null else container.conversationPort.fetchRewindPreview(taskId, rowId, entityId)
        },
        // A3.16 (Phase 5C): share-OUT via the Android system sheet — the transcript is
        // built from the timeline rows (TranscriptBuilder); import/publish stay
        // desktop-only per PROBE-L6 (the host's rejectMobileShare stub).
        // G19 (vc51 Phase 4D): the chip opens the MODE chooser (plain text / markdown
        // export); the dispatch builds the chosen transcript and rides ACTION_SEND.
        onShareTranscript = { showShareModes = true },
    )

    if (showShareModes) {
        com.zcode.android.features.conversation.ui.ShareModeSheet(
            onMode = { mode ->
                showShareModes = false
                when (mode) {
                    com.zcode.android.features.conversation.ui.ShareMode.PRINT -> {
                        // G56 (vc52 Phase 13): print to PDF — the Android Printing
                        // framework handoff (the system sheet; the adapter writes pages).
                        com.zcode.android.app.connect.TranscriptPrintJob.print(
                            context,
                            state.task?.title ?: "Conversation",
                            com.zcode.android.features.conversation.TranscriptBuilder.build(state.task?.title, state.rows),
                            container.log,
                        )
                    }
                    else -> {
                        val transcript = when (mode) {
                            com.zcode.android.features.conversation.ui.ShareMode.PLAIN_TEXT ->
                                com.zcode.android.features.conversation.TranscriptBuilder.build(state.task?.title, state.rows)
                            else ->
                                com.zcode.android.features.conversation.TranscriptBuilder.buildMarkdown(state.task?.title, state.rows)
                        }
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, state.task?.title ?: "Conversation")
                            putExtra(android.content.Intent.EXTRA_TEXT, transcript)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, shareCopy))
                    }
                }
            },
            onDismiss = { showShareModes = false },
        )
    }
}

@Composable
private fun FilesRoute(
    container: AppContainer,
    onBack: () -> Unit,
    /** G78: the conversation task the comment lands in (null = the comment drops). */
    commentTaskId: () -> String? = { null },
) {
    val topBarScroll = com.zcode.android.core.ui.components.rememberZCodeTopBarScroll()
    val filesScope = androidx.compose.runtime.rememberCoroutineScope()
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
        // G78 (vc52 Phase 11): the line comment lands as the QUOTED file/line context
        // in the ACTIVE conversation's composer (the prefill plane); the user's Send
        // makes it the chat message (never an auto-send — the draft contract).
        com.zcode.android.features.files.ui.FilesScreen(
            port = container.filesPort,
            modifier = Modifier.fillMaxSize().padding(inner),
            onLineComment = { path, line, comment ->
                val taskId = commentTaskId().orEmpty()
                if (taskId.isNotBlank()) {
                    container.pendingLineComment.value = AppContainer.LineComment(
                        taskId = taskId,
                        quoted = "Comment on `$path:$line`:\n\n> $comment\n\n",
                    )
                    container.log.info("nav.lineComment", "task=${taskId.take(12)} path=$path line=$line")
                    onBack()
                } else {
                    container.log.warn("nav.lineComment", "no conversation beneath Files — the comment was dropped")
                }
            },
        )
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
    val ccScope = androidx.compose.runtime.rememberCoroutineScope()
    // G43-P24: the skip-version marker read (the route's own prefs view).
    val prefs by container.stores.preferencesRepository.observe()
        .collectAsState(com.zcode.android.domain.model.AppPreferences())
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
        // G67 (vc52 Phase 11): the Reconnect card fires the workspace-reconnect-request
        // then retries the connection (the bridge succeeds against the reconnected workspace).
        onReconnectWorkspace = {
            // G67 (vc52 Phase 11): the key resolves on the HUB (transport-discovered
            // target → the cached task's workspace identity on a cold process).
            ccScope.launch {
                val key = runCatching { container.hub.reconnectKey() }.getOrNull()
                container.log.info("nav.reconnectWorkspace", "key=${key?.take(24) ?: "null"}")
                if (!key.isNullOrBlank()) {
                    val ok = runCatching { container.hub.reconnectWorkspace(key) }.getOrDefault(false)
                    container.log.info("nav.reconnectWorkspace", "success=$ok")
                    if (ok) container.hub.retry()
                }
            }
        },
        onBack = onBack,
        updateState = updateState,
        downloadState = downloadState,
        onUpdateNow = container.appUpdate::updateNow,
        onCheckForUpdate = { container.appUpdate.checkForUpdate(endpoint?.desktopAppVersion, force = true) },
        // G43-P24: the skip-version marker (persisted; suppresses THIS release's prompt).
        skippedUpdateVersionCode = prefs.skippedUpdateVersionCode,
        onSkipVersion = {
            val vc = (updateState as? com.zcode.android.app.update.UpdateCheckState.Available)?.release?.versionCode
            if (vc != null) {
                ccScope.launch {
                    container.stores.preferencesRepository.update { p ->
                        p.copy(skippedUpdateVersionCode = vc, whatsNewShown = p.whatsNewShown)
                    }
                }
            }
        },
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
    onOpenSubagents: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
    onOpenBots: () -> Unit = {},
    onOpenHooks: () -> Unit = {},
    onOpenCommands: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
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
        onOpenSubagents = onOpenSubagents,
        onOpenProviders = onOpenProviders,
        onOpenBots = onOpenBots,
        onOpenHooks = onOpenHooks,
        onOpenCommands = onOpenCommands,
        onOpenMemory = onOpenMemory,
    )
}
