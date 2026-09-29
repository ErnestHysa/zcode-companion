package com.zcode.android.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zcode.android.app.AppContainer
import com.zcode.android.app.connect.ConnectionCenterScreen
import com.zcode.android.app.settings.SettingsScreen
import com.zcode.android.features.conversation.ConversationViewModel
import com.zcode.android.features.conversation.ui.ConversationScreen
import com.zcode.android.features.pairing.PairingViewModel
import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.tasks.TasksViewModel
import com.zcode.android.features.tasks.ui.TaskListScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    val paired by container.pairingPort.pairedEndpoint.collectAsState()

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

    NavHost(navController = nav, startDestination = if (paired != null) Routes.TASKS else Routes.PAIRING) {
        composable(Routes.PAIRING) {
            PairingHostRoute(pairingVm = pairingVm, scannerContent = scannerContent)
        }
        composable(Routes.TASKS) {
            TasksRoute(
                vm = tasksVm,
                onTaskClick = { task -> nav.navigate(Routes.conversation(task.taskId)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenConnection = { nav.navigate(Routes.CONNECTION) },
            )
        }
        composable(Routes.CONVERSATION) { entry ->
            val taskId = entry.arguments?.getString("taskId").orEmpty()
            val vm = rememberConversationViewModel(container, scope, taskId)
            ConversationRoute(vm = vm, onBack = { nav.popBackStack() })
        }

        composable(Routes.CONNECTION) {
            ConnectionCenterRoute(container = container, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(container = container, pairingVm = pairingVm, onBack = { nav.popBackStack() })
        }

    }
}

@Composable
private fun PairingHostRoute(pairingVm: PairingViewModel, scannerContent: @Composable () -> Unit) {
    com.zcode.android.features.pairing.ui.PairingHost(vm = pairingVm, scannerContent = scannerContent)
}

@Composable
private fun TasksRoute(
    vm: TasksViewModel,
    onTaskClick: (com.zcode.android.domain.model.Task) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val refreshing by vm.isRefreshing.collectAsState()
    TaskListScreen(
        state = state.copy(refreshing = refreshing),
        onTaskClick = onTaskClick,
        onOpenSettings = onOpenSettings,
        onOpenConnection = onOpenConnection,
        onRetry = vm::retryConnection,
        onRefresh = vm::refresh,
        onRepairPairing = vm::repairPairing,
    )
}

@Composable
private fun rememberConversationViewModel(
    container: AppContainer,
    scope: CoroutineScope,
    taskId: String,
): ConversationViewModel {
    val vm = androidx.compose.runtime.remember(taskId) {
        ConversationViewModel(container.conversationPort, scope, taskId)
    }
    return vm
}

@Composable
private fun ConversationRoute(vm: ConversationViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    ConversationScreen(
        state = state,
        phase = state.phase,
        onBack = onBack,
        onLoadOlder = vm::loadOlder,
        onDismissHistoryNotice = vm::dismissHistoryNotice,
        onRetryOlder = vm::retryOlder,
    )
}

@Composable
private fun ConnectionCenterRoute(container: AppContainer, onBack: () -> Unit) {
    val phase by container.hub.phase.collectAsState()
    val endpoint by container.hub.endpoint.collectAsState()
    val lastError by container.hub.lastError.collectAsState()
    val lastRefresh by container.hub.lastRefreshAtMs.collectAsState()
    ConnectionCenterScreen(
        phase = phase,
        endpointName = endpoint?.displayName,
        desktopVersion = endpoint?.desktopAppVersion,
        lastError = lastError,
        lastRefreshAtMs = lastRefresh,
        onRetry = container.hub::retry,
        onDismissError = container.hub::dismissError,
        onBack = onBack,
    )
}

@Composable
private fun SettingsRoute(container: AppContainer, pairingVm: PairingViewModel, onBack: () -> Unit) {
    val endpoint by container.hub.endpoint.collectAsState()
    SettingsScreen(
        container = container,
        endpointName = endpoint?.displayName,
        desktopVersion = endpoint?.desktopAppVersion,
        onBack = onBack,
    )
}
