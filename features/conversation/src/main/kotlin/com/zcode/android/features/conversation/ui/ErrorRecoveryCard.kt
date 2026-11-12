package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zcode.android.features.conversation.R

/**
 * G66 (vc52 Phase 13): the typed error card's recovery actions — the desktop's
 * chat error card carries Switch model / Model settings / Refresh quota / Sign in
 * again / Retry captcha / Try again later / Retry / Copy TraceID / Copy full
 * details / Report issue; the phone's tool/turn failures rendered a raw
 * `ERROR — code: message` block with NO typed recovery. This card carries the
 * MOBILE-REACHABLE subset (provider sign-in, captcha and quota-refresh stay
 * desktop): Retry (wire-gated by the row's actions.canRetry + an entityId),
 * Switch model (the same §10.3 CAS switch the header chips use), Copy TraceID,
 * Copy full details, Report issue (the feedback form, error attached).
 */
data class ToolErrorActions(
    /** The row's wire capability (actions.canRetry + an entityId to target). */
    val canRetry: Boolean = false,
    val onRetry: () -> Unit = {},
    /** Opens the SAME model switcher the config chips use (§10.3). */
    val onSwitchModel: () -> Unit = {},
    /** Opens the feedback form with the error attached (the raw "code: message");
     *  null = hidden. */
    val onReportIssue: ((details: String) -> Unit)? = null,
)

@Composable
fun ErrorRecoveryCard(
    code: String,
    message: String,
    actions: ToolErrorActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .testTag("error_recovery_card"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        // The theme's shape vocabulary (the ThemeShapesTest discipline — no dp literals).
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.error_recovery_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (actions.canRetry) {
                    TextButton(
                        onClick = actions.onRetry,
                        modifier = Modifier.testTag("error_action_retry"),
                    ) { Text(stringResource(R.string.error_action_retry)) }
                }
                TextButton(
                    onClick = actions.onSwitchModel,
                    modifier = Modifier.testTag("error_action_switch_model"),
                ) { Text(stringResource(R.string.error_action_switch_model)) }
                if (actions.onReportIssue != null) {
                    val report = actions.onReportIssue
                    TextButton(
                        onClick = { report?.invoke("$code: $message") },
                        modifier = Modifier.testTag("error_action_report"),
                    ) { Text(stringResource(R.string.error_action_report)) }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.error_copy_trace_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CopyIconButton(text = code, tag = "error_copy_trace")
                CopyIconButton(text = "$code: $message", tag = "error_copy_details")
            }
        }
    }
}
