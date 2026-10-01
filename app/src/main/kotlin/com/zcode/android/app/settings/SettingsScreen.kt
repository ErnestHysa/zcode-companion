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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zcode.android.core.common.log.ZLog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zcode.android.app.AppContainer

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
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }

            SectionLabel("Paired desktop")
            SettingsRow(
                title = endpointName ?: "Not paired",
                subtitle = desktopVersion?.let { "ZCode desktop $it" } ?: "Scan a QR code to pair",
                tag = "settings_endpoint",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel("Diagnostics")
            val diagBusy = remember { mutableStateOf(false) }
            SettingsRow(
                title = if (diagBusy.value) "Preparing report…" else "Export diagnostics",
                subtitle = if (diagBusy.value) "Building the report — this can take a few seconds."
                    else "Connection report + full redacted event log. No conversation content.",
                tag = "settings_export_diagnostics",
                onClick = { if (!diagBusy.value) exportDiagnostics(container, context) { diagBusy.value = it } },
            )
            SettingsRow(
                title = if (diagBusy.value) "Preparing report…" else "Copy diagnostics to clipboard",
                subtitle = "Same report, copied for pasting into a chat or issue.",
                tag = "settings_copy_diagnostics",
                onClick = { if (!diagBusy.value) copyDiagnostics(container, context) { diagBusy.value = it } },
            )
            SettingsRow(
                title = "Add a marker",
                subtitle = "Writes a timestamped marker into the log — tap this right before reproducing a problem.",
                tag = "settings_add_marker",
                onClick = {
                    container.log.info("diagnostics.mark", "user marker at ${java.time.Instant.now()} phase=${container.hub.phase.value}")
                    android.widget.Toast.makeText(context, "Marker added to diagnostics", android.widget.Toast.LENGTH_SHORT).show()
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel("Privacy")
            SettingsRow(
                title = "No analytics. No tracking.",
                subtitle = "Data leaves this device only through your paired ZCode connection.",
                tag = "settings_privacy",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SectionLabel("About")
            SettingsRow(
                title = "ZCode Companion",
                subtitle = "Unofficial native client for ZCode. Apache-2.0 components — see licenses.",
                tag = "settings_about",
            )
            SettingsRow(
                title = "Licenses",
                subtitle = "Open-source libraries used in this app",
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
                    Text("Unpair this device", color = MaterialTheme.colorScheme.onError)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "After unpairing, also press Stop in the desktop's Mobile Remote Control dialog — closing the dialog does not stop remote control.",
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
            title = { Text("Unpair from ${endpointName ?: "this desktop"}?") },
            text = { Text("The pairing credential and cached data will be removed from this phone. You will need to scan the QR again to reconnect.") },
            confirmButton = {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                TextButton(
                    onClick = {
                        showUnpairConfirm = false
                        scope.launch { container.pairingPort.unpair() }
                    },
                    modifier = Modifier.testTag("settings_unpair_confirm"),
                ) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) { Text("Cancel") }
            },
        )
    }
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
                val payload = if (clipped) text.take(clipLimit) + "\n\n…(truncated at $clipLimit chars — use Export diagnostics for the full report)" else text
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("ZCode diagnostics", payload))
                android.widget.Toast.makeText(
                    context,
                    if (clipped) "Diagnostics copied (truncated — use Export for the full report)" else "Diagnostics copied",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Diagnostics failed: ${e.message?.take(80)}", android.widget.Toast.LENGTH_LONG).show()
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
                    putExtra(android.content.Intent.EXTRA_TITLE, "ZCode Companion diagnostics")
                    // The read grant must survive the chooser hop to the target app.
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // Samsung-class shares verify the grant via ClipData too.
                    clipData = android.content.ClipData.newRawUri("diagnostics", uri)
                }
                context.startActivity(
                    android.content.Intent.createChooser(intent, "Export diagnostics"),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Export failed: ${e.message?.take(80)}", android.widget.Toast.LENGTH_LONG).show()
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
    context.startActivity(android.content.Intent.createChooser(intent, "Licenses"))
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
