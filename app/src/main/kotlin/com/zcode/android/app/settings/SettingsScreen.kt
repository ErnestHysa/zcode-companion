package com.zcode.android.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.zcode.android.core.common.log.ZLog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zcode.android.app.AppContainer
import com.zcode.android.app.R

/**
 * Basic Phase 6 settings (ui-ux 13): paired desktop identity, connection entry,
 * diagnostics export, privacy note, about + licenses, unpair (danger, confirmed).
 * Full settings surface (notification levels, privacy toggles) is Phase 8/11.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    endpointName: String?,
    desktopVersion: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showUnpairConfirm by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .testTag("settings_scroll"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(com.zcode.android.core.ui.R.string.action_back), tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }

            SectionLabel(stringResource(R.string.settings_section_endpoints))
            EndpointsSection(container = container, endpointName = endpointName, desktopVersion = desktopVersion)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel(stringResource(R.string.settings_section_diagnostics))
            val diagBusy = remember { mutableStateOf(false) }
            SettingsRow(
                title = stringResource(if (diagBusy.value) R.string.settings_diag_export_busy else R.string.settings_diag_export),
                subtitle = stringResource(if (diagBusy.value) R.string.settings_diag_export_busy_sub else R.string.settings_diag_export_sub),
                tag = "settings_export_diagnostics",
                onClick = { if (!diagBusy.value) exportDiagnostics(container, context) { diagBusy.value = it } },
            )
            SettingsRow(
                title = stringResource(if (diagBusy.value) R.string.settings_diag_export_busy else R.string.settings_diag_copy),
                subtitle = stringResource(R.string.settings_diag_copy_sub),
                tag = "settings_copy_diagnostics",
                onClick = { if (!diagBusy.value) copyDiagnostics(container, context) { diagBusy.value = it } },
            )
            SettingsRow(
                title = stringResource(R.string.settings_diag_marker),
                subtitle = stringResource(R.string.settings_diag_marker_sub),
                tag = "settings_add_marker",
                onClick = {
                    container.log.info("diagnostics.mark", "user marker at ${java.time.Instant.now()} phase=${container.hub.phase.value}")
                    android.widget.Toast.makeText(context, context.getString(R.string.settings_marker_toast), android.widget.Toast.LENGTH_SHORT).show()
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel(stringResource(R.string.settings_section_notifications))
            NotificationsSection(container = container)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel(stringResource(R.string.settings_section_privacy))
            SettingsRow(
                title = stringResource(R.string.settings_privacy_row),
                subtitle = stringResource(R.string.settings_privacy_sub),
                tag = "settings_privacy",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel(stringResource(R.string.settings_section_about))
            SettingsRow(
                title = stringResource(R.string.settings_about_row),
                subtitle = stringResource(R.string.settings_about_sub),
                tag = "settings_about",
            )
            SettingsRow(
                title = stringResource(R.string.settings_licenses_row),
                subtitle = stringResource(R.string.settings_licenses_sub),
                tag = "settings_licenses",
                onClick = { showLicenses(context) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Spacer(Modifier.height(16.dp))
            if (endpointName != null) {
                Button(
                    onClick = { showUnpairConfirm = true },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .testTag("settings_unpair"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.settings_unpair), color = MaterialTheme.colorScheme.onError)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_unpair_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text(stringResource(R.string.settings_unpair_title, endpointName ?: context.getString(R.string.settings_your_desktop_fallback))) },
            text = { Text(stringResource(R.string.settings_unpair_body)) },
            confirmButton = {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                TextButton(
                    onClick = {
                        showUnpairConfirm = false
                        scope.launch {
                            container.pairingPort.unpair()
                            container.hub.onUnpaired()
                        }
                    },
                    modifier = Modifier.testTag("settings_unpair_confirm"),
                ) { Text(stringResource(R.string.settings_unpair_action), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) }
            },
        )
    }
}

/**
 * §11.6/U22 endpoint registry UI (Phase 10): every saved endpoint (relay + direct),
 * the ACTIVE one marked, tap = switch, add-direct flow (validated server-info probe
 * → Tink-sealed token → registry row → auto-switch), per-endpoint remove for direct.
 */
@Composable
private fun EndpointsSection(container: AppContainer, endpointName: String?, desktopVersion: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val endpoints by container.hub.endpoints.collectAsState()
    val activeEndpoint by container.hub.endpoint.collectAsState()
    val phase by container.hub.phase.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<String?>(null) }
    var switchMessage by remember { mutableStateOf<String?>(null) }

    // The paired RELAY endpoint (lives in the vault; the registry row is lazy).
    val relayRow = endpoints.firstOrNull { it.kind == com.zcode.android.domain.model.EndpointKind.RELAY_ZAI }
    val rows = buildList {
        if (endpointName != null && relayRow != null) add(relayRow)
        else if (endpointName != null && relayRow == null) add(null) // relay placeholder row
        addAll(endpoints.filter { it.kind == com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER })
    }

    for ((index, row) in rows.withIndex()) {
        val active = row != null && activeEndpoint?.endpointId == row.endpointId
        SettingsRow(
            title = (row?.displayName ?: endpointName ?: stringResource(R.string.settings_not_paired_row)) + if (active) "  •" else "",
            subtitle = buildString {
                val localized = stringResource(
                    if (row?.kind == com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER) R.string.settings_kind_direct
                    else R.string.settings_kind_relay,
                )
                append(localized)
                row?.desktopAppVersion?.let { append(stringResource(R.string.settings_version_suffix, it)) }
                if (active) append(stringResource(R.string.settings_connected_suffix, phase.name))
            },
            tag = "settings_endpoint_$index",
            onClick = {
                val id = row?.endpointId?.raw
                if (id != null && !active) {
                    scope.launch {
                        val ok = container.hub.switchTo(id)
                        switchMessage = context.getString(
                            if (ok) R.string.settings_switched else R.string.settings_switch_failed,
                        )
                    }
                }
            },
        )
        if (row?.kind == com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER) {
            androidx.compose.material3.OutlinedButton(
                onClick = { removeTarget = row.endpointId.raw },
                modifier = Modifier
                    .padding(start = 16.dp, bottom = 8.dp)
                    .testTag("settings_endpoint_remove_$index"),
            ) { Text(stringResource(R.string.settings_remove)) }
        }
    }
    SettingsRow(
        title = stringResource(R.string.settings_add_direct),
        subtitle = stringResource(R.string.settings_add_direct_sub),
        tag = "settings_add_direct",
        onClick = { showAddDialog = true },
    )
    switchMessage?.let {
        androidx.compose.material3.ListItem(headlineContent = { Text(it) })
        // auto-clear
        scope.launch { kotlinx.coroutines.delay(2500); switchMessage = null }
    }

    if (showAddDialog) {
        AddDirectEndpointDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, token, candidates ->
                showAddDialog = false
                scope.launch {
                    when (val outcome = container.hub.addDirectEndpoint(name, url, token, candidates)) {
                        is com.zcode.android.app.connect.ConnectionHub.AddDirectOutcome.Added -> {
                            switchMessage = context.getString(R.string.settings_added_connecting)
                            container.hub.switchTo(outcome.endpoint.endpointId.raw)
                        }
                        is com.zcode.android.app.connect.ConnectionHub.AddDirectOutcome.Rejected -> {
                            switchMessage = com.zcode.android.core.ui.components.errorMessage(context, outcome.error)
                        }
                    }
                }
            },
        )
    }
    if (removeTarget != null) {
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.settings_remove_endpoint_title)) },
            text = { Text(stringResource(R.string.settings_remove_endpoint_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = removeTarget
                    removeTarget = null
                    if (id != null) scope.launch { container.hub.removeDirectEndpoint(id) }
                }) { Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) } },
        )
    }
}

/** §12.2 discipline for the direct flow: nothing is written until validation passes. */
@Composable
private fun AddDirectEndpointDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, wsUrl: String, token: String, candidates: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf("") }
    val valid = url.startsWith("ws://") || url.startsWith("wss://") || url.startsWith("http://") || url.startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_add_direct_title)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_add_name_label)) },
                    singleLine = true,
                    modifier = Modifier.testTag("add_direct_name"),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_add_url_label)) },
                    singleLine = true,
                    modifier = Modifier.testTag("add_direct_url"),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.settings_add_token_label)) },
                    singleLine = true,
                    modifier = Modifier.testTag("add_direct_token"),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = candidates,
                    onValueChange = { candidates = it },
                    label = { Text(stringResource(R.string.settings_add_candidates_label)) },
                    singleLine = true,
                    modifier = Modifier.testTag("add_direct_candidates"),
                )
                Text(
                    stringResource(R.string.settings_add_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && token.isNotBlank(),
                onClick = {
                    onAdd(
                        name,
                        url,
                        token,
                        candidates.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    )
                },
                modifier = Modifier.testTag("add_direct_submit"),
            ) { Text(stringResource(R.string.settings_add_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) } },
    )
}

private fun copyDiagnostics(container: AppContainer, context: android.content.Context, onBusy: (Boolean) -> Unit) {
    val act = context as? androidx.fragment.app.FragmentActivity
    val scope = act?.lifecycleScope ?: return
    onBusy(true)
    // FIELD (2026-09-30, vc18 ANR): the report is multi-MB (5,000-entry ring + 384 KB×2
    // persisted tails + scrubbing) — building it on the MAIN thread blocked input for
    // tens of seconds ("app doesn't respond to any of my touches"). Build on Default,
    // hop to Main only for the clipboard/toast.
    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        try {
            val text = buildDiagnosticReport(container, context)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                // FIELD (2026-10-01, vc20): OEM clipboard services choke on multi-MB
                // strings (the full report exceeds Samsung's clipboard budget once the
                // ring fills) — copy a bounded prefix and say so.
                val clipLimit = 400_000
                val clipped = text.length > clipLimit
                val suffix = context.getString(R.string.settings_clip_truncated_suffix, clipLimit)
                val payload = if (clipped) text.take(clipLimit) + suffix else text
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("ZCode diagnostics", payload))
                android.widget.Toast.makeText(
                    context,
                    context.getString(if (clipped) R.string.settings_clip_copied_truncated else R.string.settings_clip_copied),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, context.getString(R.string.settings_clip_failed, e.message?.take(80) ?: ""), android.widget.Toast.LENGTH_LONG).show()
            }
        } finally {
            withContext(kotlinx.coroutines.Dispatchers.Main) { onBusy(false) }
        }
    }
}

private fun exportDiagnostics(container: AppContainer, context: android.content.Context, onBusy: (Boolean) -> Unit) {
    val act = context as? androidx.fragment.app.FragmentActivity
    val scope = act?.lifecycleScope ?: return
    onBusy(true)
    // Same ANR class as copyDiagnostics — build off-main, present on main.
    scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        try {
            val text = buildDiagnosticReport(container, context)
            // FIELD (2026-10-01, vc20): the report shares as a FILE now. The old path
            // put the whole report into Intent.EXTRA_TEXT — the Binder transaction
            // buffer is ~1 MiB SHARED, so once the 5,000-entry ring filled up the
            // startActivity call threw TransactionTooLargeException and the share
            // sheet silently never appeared ("export works right after app open,
            // fails later" — the ring was still small then). A FileProvider stream
            // URI has no size limit.
            val file = writeDiagnosticsFile(context, text)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider", file,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_TITLE, context.getString(R.string.app_name) + " " + context.getString(R.string.settings_section_diagnostics))
                    // The read grant must survive the chooser hop to the target app.
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // Samsung-class shares verify the grant via ClipData too.
                    clipData = android.content.ClipData.newRawUri("diagnostics", uri)
                }
                context.startActivity(
                    android.content.Intent.createChooser(intent, context.getString(R.string.settings_export_chooser)),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, context.getString(R.string.settings_export_failed, e.message?.take(80) ?: ""), android.widget.Toast.LENGTH_LONG).show()
            }
        } finally {
            withContext(kotlinx.coroutines.Dispatchers.Main) { onBusy(false) }
        }
    }
}

/**
 * Write the report into cacheDir/shared/ (the FileProvider root) and keep the
 * directory BOUNDED (INV-5 spirit): the newest report plus at most 4 older ones.
 */
internal fun writeDiagnosticsFile(context: android.content.Context, text: String): java.io.File {
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val out = java.io.File(dir, "zcode-diagnostics-${System.currentTimeMillis()}.txt")
    out.writeText(text)
    dir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
    return out
}

/**
 * Round-7 diagnostics enhancement: a STRUCTURED report, not just the ring dump —
 * environment, pairing identity (non-secret fields only, INV-3), live connection
 * snapshot, and the full redacted log ring (5,000 entries, DEBUG+).
 */
private suspend fun buildDiagnosticReport(container: AppContainer, context: android.content.Context): String = buildString {
    val pm = context.packageManager.getPackageInfo(context.packageName, 0)
    val phase = container.hub.phase.value
    val endpoint = container.hub.endpoint.value
    val err = container.hub.lastError.value
    val suggestRepair = container.hub.suggestRepair.value
    appendLine("=== ZCode Companion diagnostics ===")
    appendLine("generated: ${java.time.Instant.now()}")
    appendLine()
    appendLine("--- environment ---")
    appendLine("app: ${pm.versionName} (vc${pm.longVersionCode})")
    appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
    appendLine("androidBuild: ${android.os.Build.DISPLAY.take(60)}")
    appendLine("uptimeMs: ${android.os.SystemClock.elapsedRealtime()}")
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    val batteryManager = context.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
    val connectivity = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    val network = connectivity?.activeNetwork
    val caps = network?.let { connectivity.getNetworkCapabilities(it) }
    appendLine("powerSave: ${powerManager?.isPowerSaveMode}")
    appendLine("batteryPercent: ${batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}")
    appendLine("networkOnline: ${caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
    appendLine("networkMetered: ${connectivity?.isActiveNetworkMetered}")
    appendLine()
    appendLine("--- connection snapshot (at export time) ---")
    appendLine("phase: $phase")
    appendLine("suggestRepair: $suggestRepair")
    appendLine("endpointName: ${endpoint?.displayName}")
    appendLine("desktopAppVersion: ${endpoint?.desktopAppVersion}")
    appendLine("endpointKind: ${endpoint?.kind}")
    appendLine("lastError: ${err?.let { "${it.category} :: ${it.userMessage} :: ${it.developerDiagnostic.take(120)}" } ?: "none"}")
    appendLine("tasksCached: ${container.hub.cachedTaskCountForDiagnostics()}")
    val diagSnap = container.hub.diagnosticsSnapshot()
    appendLine("desktopAppVersion: ${diagSnap.desktopAppVersion ?: "unknown"}")
    appendLine("desktopAboveTested: ${diagSnap.desktopAboveTested}")
    appendLine()
    appendLine("--- workspace resolution (key → path, bootstrap-reported) ---")
    for (ws in container.hub.workspaces.value) {
        appendLine("  key=${ws.key.take(60)} path=${ws.path?.take(60) ?: "<null>"} label=${ws.label.take(30)}")
    }
    appendLine("--- pairing identity (non-secret, INV-3) ---")
    appendLine("endpointId: ${endpoint?.endpointId?.raw ?: "n/a"}")
    appendLine("deviceSid: ${endpoint?.deviceSid?.take(8) ?: "n/a"}…")
    appendLine("pairedAtMs: ${endpoint?.pairedAtMs ?: "n/a"}")
    appendLine()
    appendLine("--- transport wire counters (cumulative) ---")
    for ((k, v) in container.transport.statsSnapshot().toList().sortedByDescending { it.second }) {
        appendLine("  $k: $v")
    }
    appendLine()
    appendLine("--- event counters (whole ring) ---")
    val entries = container.log.snapshot()
    val byEvent = entries.groupingBy { it.event }.eachCount().entries
        .sortedByDescending { it.value }
        .take(25)
    for ((event, count) in byEvent) appendLine("  $event × $count")
    appendLine("bridge errors by reason:")
    entries.filter { it.event == "bridge.error" }
        .groupingBy { it.details?.removePrefix("reason=") ?: "?" }
        .eachCount()
        .toList().sortedByDescending { it.second }
        .forEach { (reason, count) -> appendLine("  $reason × $count") }
    appendLine()
    appendLine("--- warnings & errors (chronological) ---")
    val problems = entries.filter { it.level == ZLog.LogLevel.WARN || it.level == ZLog.LogLevel.ERROR }
    appendLine("count: ${problems.size}")
    for (e in problems) {
        val ctx = if (e.context.isEmpty()) "" else " " + e.context.entries.joinToString(" ") { "${it.key}=${it.value}" }
        appendLine("  ${java.time.Instant.ofEpochMilli(e.timestampMs)} [${e.level}] ${e.event}$ctx ${e.details ?: ""}")
    }
    appendLine()
    appendLine("--- connection timeline (state transitions, chronological) ---")
    for (e in entries) {
        if (e.event == "hub.phase" || e.event == "app.lifecycle" || e.event == "app.start" ||
            e.event.startsWith("hub.connect") || e.event.startsWith("hub.retry") ||
            e.event.startsWith("pairing.") || e.event.startsWith("bridge.")
        ) {
            val ctx = if (e.context.isEmpty()) "" else " " + e.context.entries.joinToString(" ") { "${it.key}=${it.value}" }
            appendLine("  ${java.time.Instant.ofEpochMilli(e.timestampMs)} ${e.event}${ctx} ${e.details ?: ""}")
        }
    }
    appendLine()
    appendLine("--- live session & protocol state ---")
    val diag = container.hub.diagnosticsSnapshot()
    appendLine("capabilities: ${diag.capabilities ?: "none (never connected)"}")
    appendLine("tasksCached: ${diag.taskCount}")
    if (diag.journalSubscriptions.isEmpty()) {
        appendLine("recovery journal: (empty)")
    } else {
        appendLine("recovery journal:")
        for (sub in diag.journalSubscriptions) appendLine("  $sub")
    }
    if (diag.sessions.isEmpty()) {
        appendLine("conversation sessions: (none attached)")
    } else {
        appendLine("conversation sessions:")
        for (s in diag.sessions) {
            appendLine("  ${s.sessionId.take(20)} state=${s.taskState} stale=${s.stale} cachedRows=${s.cachedRows}")
        }
    }
    appendLine()
    appendLine("--- per-connection timelines (from the current ring) ---")
    val withConn = entries.filter { it.context.containsKey("connectionId") }
    val byConn = withConn.groupBy { it.context["connectionId"]!! }
    if (byConn.isEmpty()) appendLine("  (no connection-scoped entries in the ring)")
    for ((connId, list) in byConn.toList().sortedBy { it.second.firstOrNull()?.timestampMs ?: 0L }) {
        appendLine("  [$connId] ${list.size} events")
        for (e in list) {
            val det = e.details?.let { " :: $it" } ?: ""
            appendLine("    ${java.time.Instant.ofEpochMilli(e.timestampMs)} [${e.level}] ${e.event}$det")
        }
    }
    appendLine()
    appendLine("--- last crash (auto-captured) ---")
    val crash = container.diagnosticsStore.readLastCrash()
    if (crash.isNotBlank()) appendLine(crash.take(12_000)) else appendLine("(none)")
    appendLine()
    appendLine("--- previous session (persisted across restart; capped) ---")
    val prev = container.diagnosticsStore.readPrevious()
    if (prev.isBlank()) appendLine("  (none)")
    else appendLine(prev)
    appendLine()
    appendLine("--- current session persisted tail (capped) ---")
    appendLine(container.diagnosticsStore.readCurrent())
    appendLine()
    appendLine("--- full redacted log ring (chronological; secrets scrubbed, INV-3) ---")
    appendLine(container.log.exportDiagnostics())
    appendLine("=== end ===")
}

private fun showLicenses(context: android.content.Context) {
    val text = buildString {
        appendLine("ZCode Companion — open-source components:")
        appendLine("• Kotlin/Jetbrains (Apache-2.0), Jetpack Compose & AndroidX (Apache-2.0)")
        appendLine("• OkHttp (Apache-2.0), Tink (Apache-2.0), ML Kit barcode (Apache-2.0), CameraX (Apache-2.0)")
        appendLine("• Protobuf (BSD), DataStore/Room (Apache-2.0), Material Icons (Apache-2.0)")
        appendLine("ZCode is a trademark of its owner; this is an unofficial companion client.")
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.settings_licenses_chooser)))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/**
 * Phase 8 notifications surface (§24.2/§24.4/§24.5/§25.3): the content-level privacy
 * toggle, the opt-in Live-connection FGS (started from the foreground ONLY — this
 * screen is its sole entry point), and the §25.3 auto-reconnect probe. The §24.4
 * honest copy is mandatory: no push exists via the relay — notifications arrive
 * while the app is in use or while the Live connection is enabled.
 */
@Composable
private fun NotificationsSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val prefs by container.stores.preferencesRepository.observe()
        .collectAsState(initial = com.zcode.android.domain.model.AppPreferences())

    // §24.2 lock-screen visibility: MINIMAL = category-only (default), CONTENT adds
    // the task title. The pending row shows the CURRENT value; tap toggles.
    SettingsRow(
        title = stringResource(
            R.string.settings_notif_detail,
            stringResource(
                if (prefs.notificationContentLevel == com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.CONTENT) R.string.settings_notif_detail_rich
                else R.string.settings_notif_detail_min,
            ),
        ),
        subtitle = stringResource(R.string.settings_notif_detail_sub),
        tag = "settings_notification_content",
        onClick = {
            scope.launch {
                container.stores.preferencesRepository.update { p ->
                    p.copy(
                        notificationContentLevel =
                        if (p.notificationContentLevel == com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.CONTENT) {
                            com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.MINIMAL
                        } else {
                            com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.CONTENT
                        },
                    )
                }
            }
        },
    )

    // §24.5/§25.2 Live connection (FGS candidate). Started HERE, from the
    // foreground; API 33+ first asks for POST_NOTIFICATIONS (the indicator IS the
    // required FGS notification).
    var liveConnection by remember {
        mutableStateOf(com.zcode.android.core.notifications.LiveConnectionService.isRunning)
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        container.log.info("settings.notifPermission", "granted=$granted")
        if (granted || android.os.Build.VERSION.SDK_INT < 33) {
            com.zcode.android.core.notifications.LiveConnectionService.start(context)
            liveConnection = true
        }
    }
    SwitchRow(
        title = stringResource(R.string.settings_live_connection),
        subtitle = stringResource(R.string.settings_live_connection_sub),
        tag = "settings_live_connection",
        checked = liveConnection,
        onToggle = { want ->
            if (want) {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    com.zcode.android.core.notifications.LiveConnectionService.start(context)
                    liveConnection = true
                }
            } else {
                com.zcode.android.core.notifications.LiveConnectionService.stop(context)
                liveConnection = false
            }
        },
    )

    // §25.3 auto-reconnect probe — the app's ONLY periodic work (≥15 min, network-
    // constrained; §29 no-polling rule).
    SwitchRow(
        title = stringResource(R.string.settings_auto_reconnect),
        subtitle = stringResource(R.string.settings_auto_reconnect_sub),
        tag = "settings_auto_reconnect",
        checked = prefs.autoReconnectProbe,
        onToggle = { want ->
            scope.launch {
                container.stores.preferencesRepository.update { p -> p.copy(autoReconnectProbe = want) }
            }
            if (want) {
                com.zcode.android.core.notifications.ReconnectProbeWorker.schedule(context)
            } else {
                com.zcode.android.core.notifications.ReconnectProbeWorker.cancel(context)
            }
        },
    )

    // §21/§13.3 terminal opt-in (Phase 9) — default OFF; the view-only terminal
    // renders nothing until this is on (never enabled silently).
    SwitchRow(
        title = stringResource(R.string.settings_terminal_opt_in),
        subtitle = stringResource(R.string.settings_terminal_opt_in_sub),
        tag = "settings_terminal_opt_in",
        checked = prefs.terminalOptIn,
        onToggle = { want ->
            scope.launch {
                container.stores.preferencesRepository.update { p -> p.copy(terminalOptIn = want) }
                if (!want) container.terminalPort.detach()
            }
        },
    )

    // §24.4 honest statement (mandatory copy).
    Text(
        text = stringResource(R.string.settings_honest_push),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    tag: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag("${tag}_switch"),
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    tag: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
