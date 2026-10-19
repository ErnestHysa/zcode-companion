package com.zcode.android.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.zcode.android.core.ui.components.ZCodeButton
import androidx.compose.material3.Scaffold
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.zcode.android.core.ui.components.ZCodeTopBar
import com.zcode.android.core.ui.components.rememberZCodeTopBarScroll
import com.zcode.android.core.ui.components.LocalNotifier
import com.zcode.android.core.ui.components.SectionHeader
import com.zcode.android.core.ui.components.Notifier
import com.zcode.android.core.ui.components.connectionPhaseLabel
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size

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
    /** GAP-5: opens the read-only Usage screen (v4-wire.md §12.1). */
    onOpenUsage: () -> Unit = {},
    /** GAP-7: opens the read-only plugins viewer (v4-wire.md §12.3). */
    onOpenPlugins: () -> Unit = {},
    /** GAP-8: opens the read-only skills catalog (v4-wire.md §12.4). */
    onOpenSkills: () -> Unit = {},
    /** GAP-9: opens the read-only MCP server viewer (v4-wire.md §12.5). */
    onOpenMcp: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notifier = LocalNotifier.current
    var showUnpairConfirm by remember { mutableStateOf(false) }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                backTestTag = "settings_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .testTag("settings_scroll"),
        ) {

            SectionHeader(stringResource(R.string.settings_section_endpoints))
            EndpointsSection(container = container, endpointName = endpointName, desktopVersion = desktopVersion)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionHeader(stringResource(R.string.settings_section_insights))
            SettingsRow(
                title = stringResource(R.string.settings_usage_row),
                subtitle = stringResource(R.string.settings_usage_sub),
                tag = "settings_usage",
                onClick = onOpenUsage,
            )
            SettingsRow(
                title = stringResource(R.string.settings_plugins_row),
                subtitle = stringResource(R.string.settings_plugins_sub),
                tag = "settings_plugins",
                onClick = onOpenPlugins,
            )
            SettingsRow(
                title = stringResource(R.string.settings_skills_row),
                subtitle = stringResource(R.string.settings_skills_sub),
                tag = "settings_skills",
                onClick = onOpenSkills,
            )
            SettingsRow(
                title = stringResource(R.string.settings_mcp_row),
                subtitle = stringResource(R.string.settings_mcp_sub),
                tag = "settings_mcp",
                onClick = onOpenMcp,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionHeader(stringResource(R.string.settings_section_diagnostics))
            val diagBusy = remember { mutableStateOf(false) }
            SettingsRow(
                title = stringResource(if (diagBusy.value) R.string.settings_diag_export_busy else R.string.settings_diag_export),
                subtitle = stringResource(if (diagBusy.value) R.string.settings_diag_export_busy_sub else R.string.settings_diag_export_sub),
                tag = "settings_export_diagnostics",
                onClick = { if (!diagBusy.value) exportDiagnostics(container, context, notifier) { diagBusy.value = it } },
            )
            SettingsRow(
                title = stringResource(if (diagBusy.value) R.string.settings_diag_export_busy else R.string.settings_diag_copy),
                subtitle = stringResource(R.string.settings_diag_copy_sub),
                tag = "settings_copy_diagnostics",
                onClick = { if (!diagBusy.value) copyDiagnostics(container, context, notifier) { diagBusy.value = it } },
            )
            SettingsRow(
                title = stringResource(R.string.settings_diag_marker),
                subtitle = stringResource(R.string.settings_diag_marker_sub),
                tag = "settings_add_marker",
                onClick = {
                    container.log.info("diagnostics.mark", "user marker at ${java.time.Instant.now()} phase=${container.hub.phase.value}")
                    notifier.show(context.getString(R.string.settings_marker_toast))
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Phase 4F (H1.4-phase1): the first phone appearance preference.
            SectionHeader(stringResource(R.string.settings_section_appearance))
            AppearanceSection(container = container)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionHeader(stringResource(R.string.settings_section_notifications))
            NotificationsSection(container = container)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionHeader(stringResource(R.string.settings_section_privacy))
            SettingsRow(
                title = stringResource(R.string.settings_privacy_row),
                subtitle = stringResource(R.string.settings_privacy_sub),
                tag = "settings_privacy",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionHeader(stringResource(R.string.settings_section_about))
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
            // FIELD (2026-10-12): ALWAYS-VISIBLE update check — the user asked for
            // it "all the time", not buried in the connection center.
            val updateState by container.appUpdate.check.collectAsState()
            val updateDownloading by container.appUpdate.download.collectAsState()
            SettingsRow(
                title = stringResource(R.string.settings_check_updates),
                subtitle = when {
                    updateDownloading is com.zcode.android.app.update.DownloadState.Running ->
                        stringResource(R.string.conn_update_progress,
                            "${(updateDownloading.let { it as com.zcode.android.app.update.DownloadState.Running }.bytesRead * 100 / maxOf(1, updateDownloading.let { it as com.zcode.android.app.update.DownloadState.Running }.totalBytes))}%")
                    updateDownloading is com.zcode.android.app.update.DownloadState.Ready ->
                        stringResource(R.string.conn_update_ready)
                    updateState is com.zcode.android.app.update.UpdateCheckState.Checking ->
                        stringResource(R.string.conn_update_checking)
                    updateState is com.zcode.android.app.update.UpdateCheckState.Available ->
                        stringResource(R.string.settings_check_updates_found, updateState.let { it as com.zcode.android.app.update.UpdateCheckState.Available }.release.versionName)
                    updateState is com.zcode.android.app.update.UpdateCheckState.UpToDate ->
                        stringResource(R.string.settings_check_updates_latest)
                    updateState is com.zcode.android.app.update.UpdateCheckState.Failed ->
                        stringResource(R.string.conn_update_failed, updateState.let { it as com.zcode.android.app.update.UpdateCheckState.Failed }.detail)
                    else -> stringResource(R.string.settings_check_updates_sub)
                },
                tag = "settings_check_updates",
                onClick = { container.appUpdate.checkForUpdate(desktopVersion, force = true) },
            )
            if (updateState is com.zcode.android.app.update.UpdateCheckState.Available &&
                updateDownloading is com.zcode.android.app.update.DownloadState.Ready
            ) {
                TextButton(
                    onClick = { container.appUpdate.updateNow() },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("settings_update_now"),
                ) { Text(stringResource(R.string.conn_update_now)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Spacer(Modifier.height(16.dp))
            if (endpointName != null) {
                ZCodeButton(
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
    val notifier = LocalNotifier.current
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
            title = row?.displayName ?: endpointName ?: stringResource(R.string.settings_not_paired_row),
            active = active,
            subtitle = buildString {
                val localized = stringResource(
                    if (row?.kind == com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER) R.string.settings_kind_direct
                    else R.string.settings_kind_relay,
                )
                append(localized)
                row?.desktopAppVersion?.let { append(stringResource(R.string.settings_version_suffix, it)) }
                if (active) append(stringResource(R.string.settings_connected_suffix, connectionPhaseLabel(phase)))
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
            com.zcode.android.core.ui.components.ZCodeOutlinedButton(
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
    AutoDismissMessage(message = switchMessage, onDismiss = { switchMessage = null })

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

private fun copyDiagnostics(container: AppContainer, context: android.content.Context, notifier: Notifier, onBusy: (Boolean) -> Unit) {
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
                notifier.show(context.getString(if (clipped) R.string.settings_clip_copied_truncated else R.string.settings_clip_copied))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                notifier.show(context.getString(R.string.settings_clip_failed, e.message?.take(80) ?: ""), long = true)
            }
        } finally {
            withContext(kotlinx.coroutines.Dispatchers.Main) { onBusy(false) }
        }
    }
}

private fun exportDiagnostics(container: AppContainer, context: android.content.Context, notifier: Notifier, onBusy: (Boolean) -> Unit) {
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
                notifier.show(context.getString(R.string.settings_export_failed, e.message?.take(80) ?: ""), long = true)
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
    appendLine("desktopAppVersion (endpoint/live): ${endpoint?.desktopAppVersion}")
    appendLine("endpointKind: ${endpoint?.kind}")
    appendLine("lastError: ${err?.let { "${it.category} :: ${it.userMessage} :: ${it.developerDiagnostic.take(120)}" } ?: "none"}")
    appendLine("tasksCached: ${container.hub.cachedTaskCountForDiagnostics()}")
    val diagSnap = container.hub.diagnosticsSnapshot()
    appendLine("desktopAppVersion (caps/hello at connect): ${diagSnap.desktopAppVersion ?: "unknown"}")
    appendLine("desktopAboveTested (vs MAX_TESTED ${com.zcode.android.core.security.pairing.CompatibilityGate.MAX_TESTED_DESKTOP}): ${diagSnap.desktopAboveTested}")
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
    val notifier = LocalNotifier.current
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

    // Phase 4F (H1.4-phase1): the PER-CATEGORY toggles (the desktop's notification
    // prefs groups) — approvals / task outcomes / connection loss. Unset = ON.
    CategoryToggle(
        label = stringResource(R.string.settings_notif_cat_approvals),
        checked = prefs.notifyApprovals,
        tag = "settings_notif_cat_approvals",
    ) { checked ->
        scope.launch { container.stores.preferencesRepository.update { p -> p.copy(notifyApprovals = checked) } }
    }
    CategoryToggle(
        label = stringResource(R.string.settings_notif_cat_tasks),
        checked = prefs.notifyTaskOutcomes,
        tag = "settings_notif_cat_tasks",
    ) { checked ->
        scope.launch { container.stores.preferencesRepository.update { p -> p.copy(notifyTaskOutcomes = checked) } }
    }
    CategoryToggle(
        label = stringResource(R.string.settings_notif_cat_connection),
        checked = prefs.notifyConnection,
        tag = "settings_notif_cat_connection",
    ) { checked ->
        scope.launch { container.stores.preferencesRepository.update { p -> p.copy(notifyConnection = checked) } }
    }

    // §24.5 (vc35 + FIELD 2026-10-12): the auto-timeout window is CUSTOMIZABLE via
    // a RADIO PICKER — the old tap-to-cycle row looked like static text and users
    // reported the options "don't exist". 0 = stay connected until toggled off.
    val windowOptions = listOf(15, 30, 60, 240, 0)
    // H3.1: the switch COLLECTS the service's observable state — the FGS 30-min
    // auto-timeout flips it OFF live (the old remember{} snapshot lied until the
    // screen was recreated). The service lifecycle is the single writer; no manual
    // mirroring on toggle.
    val liveConnection by com.zcode.android.core.notifications.LiveConnectionService.isRunning.collectAsState()
    var windowPickerOpen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    SettingsRow(
        title = stringResource(R.string.settings_live_window),
        subtitle = stringResource(
            R.string.settings_live_window_sub,
            if (prefs.liveConnectionTimeoutMinutes == 0) stringResource(R.string.settings_live_window_none)
            else stringResource(R.string.settings_live_window_value, prefs.liveConnectionTimeoutMinutes),
        ),
        tag = "settings_live_window",
        onClick = { windowPickerOpen = true },
        enabled = liveConnection,
    )
    if (windowPickerOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { windowPickerOpen = false },
            title = { Text(stringResource(R.string.settings_live_window)) },
            text = {
                Column {
                    for (minutes in windowOptions) {
                        val selected = prefs.liveConnectionTimeoutMinutes == minutes
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        container.stores.preferencesRepository.update { p ->
                                            p.copy(liveConnectionTimeoutMinutes = minutes)
                                        }
                                    }
                                    windowPickerOpen = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (minutes == 0) stringResource(R.string.settings_live_window_none)
                                else stringResource(R.string.settings_live_window_value, minutes),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { windowPickerOpen = false }) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }

    // §24.5/§25.2 Live connection (FGS candidate). Started HERE, from the
    // foreground; API 33+ first asks for POST_NOTIFICATIONS (the indicator IS the
    // required FGS notification).
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        container.log.info("settings.notifPermission", "granted=$granted")
        if (granted || android.os.Build.VERSION.SDK_INT < 33) {
            com.zcode.android.core.notifications.LiveConnectionService.start(context)
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
                }
            } else {
                com.zcode.android.core.notifications.LiveConnectionService.stop(context)
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
    enabled: Boolean = true,
    /** M5.3: the active-endpoint marker is a semantic badge (TalkBack reads it), not a symbol-only suffix. */
    active: Boolean = false,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha))
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    val activeA11y = stringResource(R.string.settings_active_endpoint_a11y)
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                            .semantics { contentDescription = activeA11y },
                    )
                }
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
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

/**
 * B2.6 seam: the endpoint-switch message banner with its auto-clear. [delay] is
 * injectable so the pin can observe launches without real clocks. [churn] models
 * ANY unrelated parent state change: the original inline code re-ran its launch
 * on EVERY recomposition while visible (timer restarted, dismissals piled up,
 * message vanished abruptly) — the red state replicates that by re-arming per
 * recomposition; the fix keys ONE effect on the message.
 */
@Composable
internal fun AutoDismissMessage(
    message: String?,
    autoDismissMs: Long = 2_500,
    churn: Int = 0,
    onDismiss: () -> Unit,
    delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    if (message == null) return
    androidx.compose.material3.ListItem(
        headlineContent = { Text(message) },
        modifier = Modifier.testTag("settings_switch_message"),
    )
    // B2.6 fix: ONE effect keyed on the message — parent recompositions never
    // re-arm it; the dismissal is exactly once per message, after the full window.
    androidx.compose.runtime.LaunchedEffect(message) {
        delay(autoDismissMs)
        onDismiss()
    }
}

/** Phase 4F (H1.4-phase1): the theme override — light/dark/system radio row (the desktop's settings.appearance.themeMode); applies WITHOUT restart. */
@Composable
private fun AppearanceSection(container: AppContainer) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val prefs by container.stores.preferencesRepository.observe()
        .collectAsState(com.zcode.android.domain.model.AppPreferences())
    var pickerOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    @androidx.compose.runtime.Composable
    fun modeLabel(mode: com.zcode.android.domain.model.AppPreferences.ThemeMode): String = stringResource(
        when (mode) {
            com.zcode.android.domain.model.AppPreferences.ThemeMode.LIGHT -> R.string.settings_theme_light
            com.zcode.android.domain.model.AppPreferences.ThemeMode.DARK -> R.string.settings_theme_dark
            com.zcode.android.domain.model.AppPreferences.ThemeMode.SYSTEM -> R.string.settings_theme_system
        },
    )
    SettingsRow(
        title = stringResource(R.string.settings_theme_row, modeLabel(prefs.themeMode)),
        subtitle = stringResource(R.string.settings_theme_sub),
        tag = "settings_theme",
        onClick = { pickerOpen = true },
    )
    if (pickerOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text(stringResource(R.string.settings_theme_row_title)) },
            text = {
                Column {
                    for (mode in com.zcode.android.domain.model.AppPreferences.ThemeMode.entries) {
                        val selected = prefs.themeMode == mode
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        container.stores.preferencesRepository.update { p -> p.copy(themeMode = mode) }
                                    }
                                    pickerOpen = false
                                }
                                .padding(vertical = 10.dp)
                                .testTag("settings_theme_${mode.name}"),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(modeLabel(mode))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }
}

/** Phase 4F: one per-category notification switch (the desktop's per-category prefs). */
@Composable
private fun CategoryToggle(
    label: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(tag),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}
