package com.zcode.android.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.R
import com.zcode.android.core.ui.theme.LocalZCodeColors
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase

/**
 * Shared presentational status components (ui-ux/component-library.md v2).
 * Status is ALWAYS structural + textual — color is never the sole signal (§18.5,
 * §30 color-independence). TalkBack users get one flattened announcement per
 * component (clearAndSetSemantics) instead of decorative dot nodes.
 */

enum class StatusTone { ACCENT, WAITING, SUCCESS, ERROR, INFO, MUTED }

@Composable
fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.ACCENT -> LocalZCodeColors.current.statusRunning
    StatusTone.WAITING -> LocalZCodeColors.current.statusWaiting
    StatusTone.SUCCESS -> LocalZCodeColors.current.statusSuccess
    StatusTone.ERROR -> LocalZCodeColors.current.statusError
    StatusTone.INFO -> LocalZCodeColors.current.statusInfo
    StatusTone.MUTED -> LocalZCodeColors.current.textMuted
}

/** L4.1: the localized phase label (never the raw enum name). */
@Composable
fun connectionPhaseLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.CONNECTED -> stringResource(R.string.conn_connected)
    ConnectionPhase.RECOVERING -> stringResource(R.string.conn_syncing)
    ConnectionPhase.RECONNECTING -> stringResource(R.string.conn_reconnecting)
    ConnectionPhase.CONNECTING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.PAIRING -> stringResource(R.string.conn_connecting)
    ConnectionPhase.DEGRADED -> stringResource(R.string.conn_disconnected)
    ConnectionPhase.FAILED -> stringResource(R.string.conn_failed)
    ConnectionPhase.UNPAIRED, ConnectionPhase.CLOSED -> stringResource(R.string.conn_not_connected)
}

/** ui-ux connection chrome: compact dot + muted label under the app bar title. */
@Composable
fun ConnectionStatusLine(
    phase: ConnectionPhase,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val (label, tone, progress) = when (phase) {
        ConnectionPhase.CONNECTED -> Triple(stringResource(R.string.conn_connected), StatusTone.ACCENT, false)
        ConnectionPhase.RECOVERING -> Triple(stringResource(R.string.conn_syncing), StatusTone.INFO, true)
        ConnectionPhase.RECONNECTING -> Triple(stringResource(R.string.conn_reconnecting), StatusTone.WAITING, true)
        ConnectionPhase.CONNECTING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.PAIRING ->
            Triple(stringResource(R.string.conn_connecting), StatusTone.ACCENT, true)
        ConnectionPhase.DEGRADED -> Triple(stringResource(R.string.conn_disconnected), StatusTone.ERROR, false)
        ConnectionPhase.FAILED -> Triple(stringResource(R.string.conn_failed), StatusTone.ERROR, false)
        ConnectionPhase.UNPAIRED, ConnectionPhase.CLOSED -> Triple(stringResource(R.string.conn_not_connected), StatusTone.MUTED, false)
    }
    val full = if (trailing.isNullOrBlank()) label else "$label · $trailing"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (progress) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = toneColor(tone),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(toneColor(tone), CircleShape),
            )
        }
        Text(
            text = full,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Task status label (colored text + implicit rail contract — see TaskRow rail). */
@Composable
fun taskStatusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.IDLE -> stringResource(R.string.status_idle)
    TaskStatus.RUNNING -> stringResource(R.string.status_running)
    TaskStatus.WAITING -> stringResource(R.string.status_waiting)
    TaskStatus.PAUSED -> stringResource(R.string.status_paused)
    TaskStatus.COMPLETED -> stringResource(R.string.status_completed)
    TaskStatus.ERROR -> stringResource(R.string.status_failed)
    TaskStatus.UNKNOWN -> stringResource(R.string.status_unknown)
}

/**
 * §30.6 color-independence: every status carries a GLYPH alongside the color —
 * the icon is decorative (TalkBack reads the merged label, not the glyph), but
 * sighted color-blind users always have the symbol + the textual label.
 */
fun taskStatusGlyph(status: TaskStatus): androidx.compose.ui.graphics.vector.ImageVector = when (status) {
    // L5-idle (Phase 6C): Idle is the app's most common state — a minus bar read as a
    // stray "—"; a clock says "waiting for its window" (UNKNOWN shares the quiet glyph).
    TaskStatus.IDLE, TaskStatus.UNKNOWN -> Icons.Outlined.Schedule
    TaskStatus.RUNNING -> Icons.Filled.PlayArrow
    TaskStatus.WAITING -> Icons.Filled.Warning
    TaskStatus.PAUSED -> Icons.Filled.Pause
    TaskStatus.COMPLETED -> Icons.Filled.CheckCircle
    TaskStatus.ERROR -> Icons.Filled.Error
}

fun taskStatusTone(status: TaskStatus): StatusTone = when (status) {
    TaskStatus.IDLE, TaskStatus.UNKNOWN -> StatusTone.MUTED
    TaskStatus.RUNNING -> StatusTone.ACCENT
    TaskStatus.WAITING -> StatusTone.WAITING
    TaskStatus.PAUSED -> StatusTone.MUTED
    TaskStatus.COMPLETED -> StatusTone.SUCCESS
    TaskStatus.ERROR -> StatusTone.ERROR
}

@Composable
fun taskStatusRail(status: TaskStatus): Color = when (status) {
    TaskStatus.RUNNING -> LocalZCodeColors.current.railRunning
    TaskStatus.WAITING -> LocalZCodeColors.current.railWaiting
    TaskStatus.COMPLETED, TaskStatus.ERROR, TaskStatus.IDLE, TaskStatus.PAUSED, TaskStatus.UNKNOWN ->
        LocalZCodeColors.current.railDone
}

/** ui-ux error/attention strip: tinted surface, clear title + action — not a full-screen alarm. */
@Composable
fun AttentionStrip(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    tone: StatusTone = StatusTone.WAITING,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val container = when (tone) {
        StatusTone.WAITING -> LocalZCodeColors.current.statusWaiting.copy(alpha = 0.16f)
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        StatusTone.INFO -> LocalZCodeColors.current.statusInfo.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (tone) {
        StatusTone.WAITING -> LocalZCodeColors.current.statusWaiting
        StatusTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.INFO -> LocalZCodeColors.current.statusInfo
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            if (!message.isNullOrBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) {
                Text(actionLabel, color = contentColor)
            }
        }
    }
}

