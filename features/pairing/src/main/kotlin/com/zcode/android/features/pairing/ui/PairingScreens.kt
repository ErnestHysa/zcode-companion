package com.zcode.android.features.pairing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zcode.android.features.pairing.PairingViewModel
import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.pairing.R

/**
 * Pairing screens — §18.3 (Welcome/Pairing row) + §2.6 journey states E1–E7.
 * One composable per machine state; the host renders exactly the current state.
 * Restraint: flat surfaces, one accent, no decoration (native-first, §18.1).
 */
@Composable
fun PairingHost(vm: PairingViewModel, scannerContent: @Composable (() -> Unit)?) {
    val state by vm.state.collectAsState()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = state) {
            is PairingFlow.State.Welcome -> WelcomeScreen(s, vm)
            PairingFlow.State.CameraPermissionNeeded -> CameraNeededScreen(vm)
            PairingFlow.State.Scanning -> ScanScreen(vm, scannerContent)
            is PairingFlow.State.ManualEntry -> ManualEntryScreen(s, vm)
            PairingFlow.State.Validating -> ProgressScreen(stringResource(R.string.validating_title))
            is PairingFlow.State.Rejected -> RejectedScreen(s, vm)
            is PairingFlow.State.StaleWarning -> StaleScreen(s, vm)
            is PairingFlow.State.CompatBlocked -> CompatBlockedScreen(s, vm)
            is PairingFlow.State.CompatAboveTested -> CompatAboveScreen(s, vm)
            is PairingFlow.State.SecuritySetup -> SecuritySetupScreen(s, vm)
            PairingFlow.State.Connecting -> ProgressScreen(stringResource(R.string.connecting_title))
            is PairingFlow.State.ConnectFailed -> ConnectFailedScreen(s, vm)
            PairingFlow.State.TakenOver -> TakenOverScreen(vm)
            is PairingFlow.State.Connected -> ConnectedScreen(s, vm)
            is PairingFlow.State.UnpairConfirm -> UnpairConfirmScreen(s, vm)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingTitle() {
    TopAppBar(title = { Text(stringResource(R.string.pairing_title)) })
}

@Composable
private fun WelcomeScreen(s: PairingFlow.State.Welcome, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.welcome_heading),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.welcome_explainer),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = "security explainer" },
            )
            if (s.link != null) {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.welcome_resumed_with, s.link.desktopName),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(onClick = { vm.dispatch(PairingFlow.Event.RetryRequested) }, modifier = Modifier.testTag("resume-connect")) {
                            Text(stringResource(R.string.action_connect))
                        }
                        OutlinedButton(onClick = { vm.dispatch(PairingFlow.Event.UnpairRequested) }, modifier = Modifier.testTag("resume-unpair")) {
                            Text(stringResource(R.string.action_unpair))
                        }
                    }
                }
            }
            Button(onClick = { vm.dispatch(PairingFlow.Event.StartScan) }, modifier = Modifier.fillMaxWidth().testTag("start-scan")) {
                Text(stringResource(R.string.action_scan_qr))
            }
            OutlinedButton(onClick = { vm.dispatch(PairingFlow.Event.OpenManualEntry) }, modifier = Modifier.fillMaxWidth().testTag("open-manual")) {
                Text(stringResource(R.string.action_enter_link))
            }
        }
    }
}

@Composable
private fun CameraNeededScreen(vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.camera_needed_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.camera_needed_rationale), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { vm.dispatch(PairingFlow.Event.CameraPermissionGranted) }, // re-asks via host launcher
                modifier = Modifier.fillMaxWidth().testTag("retry-camera"),
            ) { Text(stringResource(R.string.action_grant_camera)) }
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.OpenManualEntry) },
                modifier = Modifier.fillMaxWidth().testTag("manual-from-camera"),
            ) { Text(stringResource(R.string.action_use_manual)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanScreen(vm: PairingViewModel, scannerContent: @Composable (() -> Unit)?) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (scannerContent != null) {
                scannerContent()
            }
            Text(
                stringResource(R.string.scan_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.OpenManualEntry) },
                modifier = Modifier.padding(24.dp).fillMaxWidth().testTag("manual-from-scan"),
            ) { Text(stringResource(R.string.action_use_manual)) }
        }
    }
}

@Composable
private fun ManualEntryScreen(s: PairingFlow.State.ManualEntry, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.manual_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.manual_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = s.draft,
                onValueChange = { vm.dispatch(PairingFlow.Event.DraftChanged(it)) },
                label = { Text(stringResource(R.string.manual_field_label)) },
                isError = s.error != null,
                supportingText = s.error?.let { msg -> { Text(msg) } },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("manual-field"),
            )
            Button(
                onClick = { vm.dispatch(PairingFlow.Event.QrDecoded(s.draft)) },
                enabled = s.draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("manual-submit"),
            ) { Text(stringResource(R.string.action_validate)) }
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.CancelScan) },
                modifier = Modifier.fillMaxWidth().testTag("manual-cancel"),
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun ProgressScreen(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RejectedScreen(s: PairingFlow.State.Rejected, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.rejected_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.rejected_format), style = MaterialTheme.typography.bodyMedium)
            Text(s.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("reject-detail"))
            Button(
                onClick = { vm.dispatch(PairingFlow.Event.StartScan) },
                modifier = Modifier.fillMaxWidth().testTag("rejected-rescan"),
            ) { Text(stringResource(R.string.action_rescan)) }
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.OpenManualEntry) },
                modifier = Modifier.fillMaxWidth().testTag("rejected-edit"),
            ) { Text(stringResource(R.string.action_enter_link)) }
        }
    }
}

@Composable
private fun StaleScreen(s: PairingFlow.State.StaleWarning, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.stale_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.stale_detail), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${s.link.desktopName} · ${s.link.desktopAppVersion}",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { vm.dispatch(PairingFlow.Event.StaleAccepted) },
                modifier = Modifier.fillMaxWidth().testTag("stale-continue"),
            ) { Text(stringResource(R.string.action_continue_anyway)) }
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.StartScan) },
                modifier = Modifier.fillMaxWidth().testTag("stale-rescan"),
            ) { Text(stringResource(R.string.action_rescan)) }
        }
    }
}

@Composable
private fun CompatBlockedScreen(s: PairingFlow.State.CompatBlocked, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.compat_blocked_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            Text(
                // B2.10: the boundary comes from the gate (through the port), never
                // a UI literal; blank falls back to the blocked version's own copy.
                stringResource(
                    R.string.compat_blocked_detail,
                    s.version,
                    s.minSupported?.takeIf { it.isNotBlank() } ?: s.version,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("compat-detail"),
            )
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.CancelScan) },
                modifier = Modifier.fillMaxWidth().testTag("compat-close"),
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun CompatAboveScreen(s: PairingFlow.State.CompatAboveTested, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.compat_above_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(
                    R.string.compat_above_detail,
                    s.link.desktopAppVersion,
                    s.maxTestedDesktop?.takeIf { it.isNotBlank() } ?: s.link.desktopAppVersion,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("compat-above-detail"),
            )
            Button(
                onClick = { vm.dispatch(PairingFlow.Event.CompatAccepted) },
                modifier = Modifier.fillMaxWidth().testTag("compat-continue"),
            ) { Text(stringResource(R.string.action_continue_anyway)) }
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.StartScan) },
                modifier = Modifier.fillMaxWidth().testTag("compat-above-rescan"),
            ) { Text(stringResource(R.string.action_rescan)) }
        }
    }
}

@Composable
private fun SecuritySetupScreen(s: PairingFlow.State.SecuritySetup, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyMedium)
            if (s.requireE7Consent) {
                // E7 — explicit reduced-security consent; never a silent fallback (INV-14).
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.setup_no_lock_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.setup_no_lock_detail), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(
                    onClick = { vm.dispatch(PairingFlow.Event.SecurityConfirmed(reducedSecurityConsent = true)) },
                    modifier = Modifier.fillMaxWidth().testTag("consent-reduced"),
                ) { Text(stringResource(R.string.action_accept_reduced)) }
            } else {
                if (s.offerBiometricEnrollment) {
                    Text(stringResource(R.string.setup_biometric_offer), style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = { vm.dispatch(PairingFlow.Event.SecurityConfirmed(reducedSecurityConsent = false)) },
                    modifier = Modifier.fillMaxWidth().testTag("setup-confirm"),
                ) { Text(stringResource(R.string.action_continue_anyway)) }
            }
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.CancelScan) },
                modifier = Modifier.fillMaxWidth().testTag("setup-cancel"),
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun ConnectFailedScreen(s: PairingFlow.State.ConnectFailed, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (s.error.rePair) stringResource(R.string.connect_failed_repair_title) else stringResource(R.string.connect_failed_retry_title),
                style = MaterialTheme.typography.headlineSmall,
                color = if (s.error.rePair) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (s.error.rePair) {
                Text(stringResource(R.string.connect_failed_repair_detail), style = MaterialTheme.typography.bodyMedium)
            }
            Text(s.error.userMessage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("fail-message"))
            if (s.error.retryable) {
                Button(
                    onClick = { vm.dispatch(PairingFlow.Event.RetryRequested) },
                    modifier = Modifier.fillMaxWidth().testTag("fail-retry"),
                ) { Text(stringResource(R.string.action_retry)) }
            }
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.StartScan) },
                modifier = Modifier.fillMaxWidth().testTag("fail-rescan"),
            ) { Text(stringResource(R.string.action_rescan)) }
        }
    }
}

@Composable
private fun TakenOverScreen(vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.takenover_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.takenover_detail), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { vm.dispatch(PairingFlow.Event.TakeOverRequested) },
                modifier = Modifier.fillMaxWidth().testTag("takeover-reconnect"),
            ) { Text(stringResource(R.string.action_takeover_back)) }
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.DismissTakenOver) },
                modifier = Modifier.fillMaxWidth().testTag("takeover-dismiss"),
            ) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

@Composable
private fun ConnectedScreen(s: PairingFlow.State.Connected, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.connected_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.connected_detail, s.link.desktopName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("connected-detail"),
            )
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.UnpairRequested) },
                modifier = Modifier.fillMaxWidth().testTag("connected-unpair"),
            ) { Text(stringResource(R.string.action_unpair)) }
        }
    }
}

@Composable
private fun UnpairConfirmScreen(s: PairingFlow.State.UnpairConfirm, vm: PairingViewModel) {
    Scaffold(topBar = { PairingTitle() }) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.unpair_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.unpair_detail), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = { vm.dispatch(PairingFlow.Event.UnpairConfirmed) },
                modifier = Modifier.fillMaxWidth().testTag("unpair-confirm"),
            ) { Text(stringResource(R.string.action_unpair_confirm)) }
            OutlinedButton(
                onClick = { vm.dispatch(PairingFlow.Event.UnpairCancelled) },
                modifier = Modifier.fillMaxWidth().testTag("unpair-cancel"),
            ) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}
