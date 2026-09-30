package com.zcode.android.app.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.StatusTone
import com.zcode.android.core.ui.components.toneColor
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.ui.RelativeTime

/**
 * Connection center (§43.2-6.5; §18.3 "Connection center"): the §14.4.1 machine
 * state exposed HONESTLY — every state listed, current one highlighted, with the
 * meaning and the honest user action. Takeover UX (§15.3) surfaces the KICKED-class
 * error with Reconnect/Dismiss.
 */
@Composable
fun ConnectionCenterScreen(
    phase: ConnectionPhase,
    endpointName: String?,
    desktopVersion: String?,
    lastError: com.zcode.android.core.common.error.AppError?,
    lastRefreshAtMs: Long?,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("connection_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text("Connection", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                val current = currentChrome(phase)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current.progress) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.width(14.dp).height(14.dp),
                            strokeWidth = 2.dp,
                            color = toneColor(current.tone),
                        )
                    } else {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .width(12.dp)
                                .height(12.dp)
                                .background(toneColor(current.tone), CircleShape),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = current.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = toneColor(current.tone),
                        modifier = Modifier.testTag("connection_current"),
                    )
                }
                endpointName?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$it" + (desktopVersion?.let { v -> " · desktop $v" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Round-8 compat honesty: a desktop NEWER than the tested version may
                // have drifted relay-plane behavior. (The literal in the message tracks
                // CompatibilityGate.MAX_TESTED_DESKTOP — 3.14.4 live-verified 2026-09-30.)
                if (com.zcode.android.core.security.pairing.CompatibilityGate
                    .check(desktopVersion ?: "") is com.zcode.android.core.security.pairing.CompatibilityGate.Verdict.AboveTested
                ) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Desktop $desktopVersion is newer than the version this app was tested against " +
                            "(${com.zcode.android.core.security.pairing.CompatibilityGate.MAX_TESTED_DESKTOP}) — " +
                            "if connections keep failing, this may be why.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("connection_compat_warning"),
                    )
                }
                lastRefreshAtMs?.let {
                    val ago = RelativeTime.label(it)
                    if (ago != null) {
                        Text(
                            text = "Last refreshed $ago ago",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Takeover UX (§15.3): expected platform behavior, not an error wall.
            if (lastError != null && lastError.category == com.zcode.android.core.common.error.ErrorCategory.SESSION) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                        .padding(14.dp)
                        .testTag("connection_takeover"),
                ) {
                    Text(
                        "Another ZCode Remote page took over this connection",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Only one phone can be connected at a time. Reconnect to take over.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onRetry(); onDismissError() }) { Text("Reconnect") }
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    }
                }
            } else if (lastError != null) {
                Text(
                    text = lastError.userMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("connection_error"),
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "Connection states",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(16.dp),
            )
            MACHINE_STATES.forEach { (candidatePhase, meaning) ->
                StateRow(candidatePhase, meaning, active = candidatePhase == phase)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            if (phase == ConnectionPhase.DEGRADED || phase == ConnectionPhase.FAILED) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.padding(16.dp).testTag("connection_retry"),
                ) { Text("Retry connection") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private data class Chrome(val label: String, val tone: StatusTone, val progress: Boolean)

private fun currentChrome(phase: ConnectionPhase): Chrome = when (phase) {
    ConnectionPhase.CONNECTED -> Chrome("Connected", StatusTone.ACCENT, false)
    ConnectionPhase.RECOVERING -> Chrome("Synchronizing", StatusTone.INFO, true)
    ConnectionPhase.RECONNECTING -> Chrome("Reconnecting", StatusTone.WAITING, true)
    ConnectionPhase.CONNECTING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.PAIRING ->
        Chrome("Connecting", StatusTone.ACCENT, true)
    ConnectionPhase.DEGRADED -> Chrome("Disconnected", StatusTone.ERROR, false)
    ConnectionPhase.FAILED -> Chrome("Connection failed", StatusTone.ERROR, false)
    ConnectionPhase.UNPAIRED -> Chrome("Not paired", StatusTone.MUTED, false)
    ConnectionPhase.CLOSED -> Chrome("Closed", StatusTone.MUTED, false)
}

/** §14.4.1 states, verbatim exposure (§18.3: "expose §14.4.1 states honestly"). */
private val MACHINE_STATES: List<Pair<ConnectionPhase, String>> = listOf(
    ConnectionPhase.UNPAIRED to "No paired desktop. Scan the QR to pair.",
    ConnectionPhase.PAIRING to "Validating a pairing link.",
    ConnectionPhase.AUTHENTICATING to "Proving identity to the relay.",
    ConnectionPhase.CONNECTING to "Opening the workspace bridge.",
    ConnectionPhase.CONNECTED to "Live. Tasks and conversations stream.",
    ConnectionPhase.RECONNECTING to "Connection lost — retrying with backoff. Cached data is labeled.",
    ConnectionPhase.RECOVERING to "Re-syncing after reconnect (snapshot or delta replay).",
    ConnectionPhase.DEGRADED to "Could not reconnect. Check network; wake the desktop; retry.",
    ConnectionPhase.FAILED to "Pairing expired or invalid. Re-scan the QR on the desktop.",
    ConnectionPhase.CLOSED to "Pairing removed from this device.",
)

@Composable
private fun StateRow(phase: ConnectionPhase, meaning: String, active: Boolean) {
    val chrome = currentChrome(phase)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .width(10.dp)
                .height(10.dp)
                .background(if (active) toneColor(chrome.tone) else MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = chrome.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            )
            Text(
                text = meaning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
