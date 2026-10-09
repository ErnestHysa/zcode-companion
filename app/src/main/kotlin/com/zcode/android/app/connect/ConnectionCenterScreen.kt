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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.core.ui.components.StatusTone
import com.zcode.android.core.ui.components.errorUserMessage
import com.zcode.android.core.ui.components.relativeTimeLabel
import com.zcode.android.core.ui.components.toneColor
import com.zcode.android.domain.state.ConnectionPhase

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
    updateState: com.zcode.android.app.update.UpdateCheckState? = null,
    downloadState: com.zcode.android.app.update.DownloadState? = null,
    onUpdateNow: () -> Unit = {},
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(com.zcode.android.core.ui.R.string.action_back), tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
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
                        text = it + (desktopVersion?.let { v -> stringResource(R.string.conn_desktop_version, v) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Round-8 compat honesty: a desktop NEWER than the tested version may
                // have drifted relay-plane behavior. (The literal in the message tracks
                // CompatibilityGate.MAX_TESTED_DESKTOP — 3.14.4 live-verified 2026-09-30.)
                // the update check (T4) (§14.2 T4): when the desktop is newer AND a newer app release
                // exists, the warning BECOMES the update action — banner + "Update now"
                // (§37 user-consented install). With no update available the honest
                // warning below stays exactly as it was.
                val aboveTested = com.zcode.android.core.security.pairing.CompatibilityGate
                    .check(desktopVersion ?: "") is com.zcode.android.core.security.pairing.CompatibilityGate.Verdict.AboveTested
                if (aboveTested &&
                    updateState is com.zcode.android.app.update.UpdateCheckState.Available
                ) {
                    val release = updateState.release
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                            .padding(12.dp)
                            .testTag("update_banner"),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.conn_update_banner,
                                desktopVersion ?: "",
                                com.zcode.android.core.security.pairing.CompatibilityGate.MAX_TESTED_DESKTOP,
                                release.versionName,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        when (val dl = downloadState) {
                            is com.zcode.android.app.update.DownloadState.Running ->
                                Text(
                                    text = stringResource(R.string.conn_update_progress, progressLabel(dl)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("update_progress"),
                                )
                            is com.zcode.android.app.update.DownloadState.Ready ->
                                Text(
                                    text = stringResource(R.string.conn_update_ready),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("update_ready"),
                                )
                            is com.zcode.android.app.update.DownloadState.Failed ->
                                Text(
                                    text = stringResource(R.string.conn_update_failed, dl.detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("update_error"),
                                )
                            else -> {}
                        }
                        if (downloadState !is com.zcode.android.app.update.DownloadState.Ready &&
                            downloadState !is com.zcode.android.app.update.DownloadState.Running
                        ) {
                            TextButton(
                                onClick = onUpdateNow,
                                modifier = Modifier.testTag("update_now"),
                            ) { Text(stringResource(R.string.conn_update_now)) }
                        }
                    }
                } else if (aboveTested) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.conn_compat_warning,
                            desktopVersion ?: "",
                            com.zcode.android.core.security.pairing.CompatibilityGate.MAX_TESTED_DESKTOP,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("connection_compat_warning"),
                    )
                    when (updateState) {
                        is com.zcode.android.app.update.UpdateCheckState.Checking ->
                            Text(
                                text = stringResource(R.string.conn_update_checking),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp).testTag("update_checking"),
                            )
                        is com.zcode.android.app.update.UpdateCheckState.UpToDate ->
                            Text(
                                text = stringResource(R.string.conn_update_none),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp).testTag("update_uptodate"),
                            )
                        is com.zcode.android.app.update.UpdateCheckState.Failed ->
                            Text(
                                text = stringResource(R.string.conn_update_failed, updateState.detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp).testTag("update_error"),
                            )
                        else -> {}
                    }
                    downloadState?.let { dl ->
                        when (dl) {
                            is com.zcode.android.app.update.DownloadState.Running ->
                                Text(
                                    text = stringResource(R.string.conn_update_progress, progressLabel(dl)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp).testTag("update_progress"),
                                )
                            is com.zcode.android.app.update.DownloadState.Failed ->
                                Text(
                                    text = stringResource(R.string.conn_update_failed, dl.detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp).testTag("update_error"),
                                )
                            else -> {}
                        }
                    }
                }
                lastRefreshAtMs?.let {
                    val ago = relativeTimeLabel(it)
                    if (ago != null) {
                        Text(
                            text = stringResource(R.string.conn_last_refreshed, ago),
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
                        stringResource(R.string.conn_takeover_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.conn_takeover_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onRetry(); onDismissError() }) { Text(stringResource(R.string.conn_takeover_reconnect)) }
                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.conn_takeover_dismiss)) }
                    }
                }
            } else if (lastError != null) {
                Text(
                    text = errorUserMessage(lastError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("connection_error"),
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.conn_states_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(16.dp),
            )
            machineStates().forEach { (candidatePhase, meaning) ->
                StateRow(candidatePhase, meaning, active = candidatePhase == phase)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            if (phase == ConnectionPhase.DEGRADED || phase == ConnectionPhase.FAILED) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.padding(16.dp).testTag("connection_retry"),
                ) { Text(stringResource(R.string.conn_retry_action)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private data class Chrome(val label: String, val tone: StatusTone, val progress: Boolean)

/** "42%" when the total is known, else a compact byte count ("1.2 MB"). */
private fun progressLabel(dl: com.zcode.android.app.update.DownloadState.Running): String =
    if (dl.totalBytes > 0) {
        "${(dl.bytesRead * 100 / dl.totalBytes)}%"
    } else {
        val mb = dl.bytesRead / (1024.0 * 1024.0)
        String.format(java.util.Locale.ROOT, "%.1f MB", mb)
    }

@Composable
private fun currentChrome(phase: ConnectionPhase): Chrome = when (phase) {
    ConnectionPhase.CONNECTED -> Chrome(stringResource(R.string.conn_center_connected), StatusTone.ACCENT, false)
    ConnectionPhase.RECOVERING -> Chrome(stringResource(R.string.conn_center_syncing), StatusTone.INFO, true)
    ConnectionPhase.RECONNECTING -> Chrome(stringResource(R.string.conn_center_reconnecting), StatusTone.WAITING, true)
    ConnectionPhase.CONNECTING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.PAIRING ->
        Chrome(stringResource(R.string.conn_center_connecting), StatusTone.ACCENT, true)
    ConnectionPhase.DEGRADED -> Chrome(stringResource(R.string.conn_center_disconnected), StatusTone.ERROR, false)
    ConnectionPhase.FAILED -> Chrome(stringResource(R.string.conn_center_failed), StatusTone.ERROR, false)
    ConnectionPhase.UNPAIRED -> Chrome(stringResource(R.string.conn_center_not_paired), StatusTone.MUTED, false)
    ConnectionPhase.CLOSED -> Chrome(stringResource(R.string.conn_center_closed), StatusTone.MUTED, false)
}

/** §14.4.1 states, verbatim exposure (§18.3: "expose §14.4.1 states honestly"). */
@Composable
private fun machineStates(): List<Pair<ConnectionPhase, String>> = listOf(
    ConnectionPhase.UNPAIRED to stringResource(R.string.conn_state_unpaired),
    ConnectionPhase.PAIRING to stringResource(R.string.conn_state_pairing),
    ConnectionPhase.AUTHENTICATING to stringResource(R.string.conn_state_authenticating),
    ConnectionPhase.CONNECTING to stringResource(R.string.conn_state_connecting),
    ConnectionPhase.CONNECTED to stringResource(R.string.conn_state_connected),
    ConnectionPhase.RECONNECTING to stringResource(R.string.conn_state_reconnecting),
    ConnectionPhase.RECOVERING to stringResource(R.string.conn_state_recovering),
    ConnectionPhase.DEGRADED to stringResource(R.string.conn_state_degraded),
    ConnectionPhase.FAILED to stringResource(R.string.conn_state_failed),
    ConnectionPhase.CLOSED to stringResource(R.string.conn_state_closed),
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
