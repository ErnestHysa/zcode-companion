package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.ZCodeButton
import com.zcode.android.core.ui.components.ZCodeOutlinedButton
import com.zcode.android.features.conversation.R
import com.zcode.android.domain.model.WorkspaceHookReview

/**
 * GAP-A1 / G1 (vc51 roadmap, sub-phase 1C): the workspace hook-trust review card —
 * the half of ADR-024 that never landed. The decode (SessionState), the four v4
 * commands (ConversationPort) and the VM verbs all existed, but no composable
 * rendered `PendingInteraction.Kind.WORKSPACE_HOOK_REVIEW`, so a session blocking
 * on hook trust had NO phone remedy. The desktop anchors are
 * `WorkspaceHookPendingBanner.tsx` + `ConversationHookDetailsAction.tsx`.
 *
 * ADR-024 UX contract (this card): hooks execute shell code — trust is PERMISSION
 * for persistent code execution, so the per-item opt-in checkboxes default OFF,
 * "Trust N selected hooks" is disabled at N=0, the trust action CONFIRMS via a
 * sheet (never a trust-all), and `stale_digest` items carry an explicit stale
 * marker. The confirmed path is also the ONLY caller of the persistent grant.
 */
@Composable
fun HookTrustReviewCard(
    hook: WorkspaceHookReview,
    busy: Boolean,
    onTrustSelected: (reviewItemIds: List<String>) -> Unit,
    onRevoke: (reviewItemIds: List<String>) -> Unit,
    onToggleItem: (reviewItemId: String, enabled: Boolean) -> Unit,
    onGrantPersistent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Per-item opt-in, DEFAULT OFF (ADR-024): the checked set starts empty every
    // render keyed to the review flow — a re-delivered review must not inherit checks.
    var checked by remember(hook.reviewFlowId, hook.generation) {
        mutableStateOf(emptySet<String>())
    }
    var confirmTrust by remember { mutableStateOf(false) }
    var confirmRevokeFor by remember { mutableStateOf<String?>(null) }
    val selected = hook.items.filter { it.reviewItemId in checked }.map { it.reviewItemId }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hook_review_card_${hook.interactionId}"),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.hook_review_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = hook.workspaceLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.hook_review_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            hook.summary.let { s ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.hook_review_counts, s.pendingCount, s.hookCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.height(8.dp))
            for (item in hook.items) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .testTag("hook_review_item_${item.reviewItemId}"),
                ) {
                    Checkbox(
                        checked = item.reviewItemId in checked,
                        onCheckedChange = { on ->
                            checked = if (on) checked + item.reviewItemId else checked - item.reviewItemId
                        },
                        enabled = !busy && item.actionable,
                        modifier = Modifier.testTag("hook_review_check_${item.reviewItemId}"),
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (item.trustState == "stale_digest") {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.hook_review_stale),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("hook_review_stale_${item.reviewItemId}"),
                                )
                            }
                        }
                        if (item.displayCommand.isNotBlank()) {
                            Text(
                                text = item.displayCommand,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (item.editable) {
                        TextButton(
                            onClick = { onToggleItem(item.reviewItemId, !item.configuredEnabled) },
                            enabled = !busy,
                            modifier = Modifier.testTag("hook_review_toggle_${item.reviewItemId}"),
                        ) {
                            Text(
                                stringResource(
                                    if (item.configuredEnabled) R.string.hook_review_disable else R.string.hook_review_enable,
                                ),
                            )
                        }
                    }
                    if (item.trustState == "trusted_persistent") {
                        TextButton(
                            onClick = { confirmRevokeFor = item.reviewItemId },
                            enabled = !busy,
                            modifier = Modifier.testTag("hook_review_revoke_${item.reviewItemId}"),
                        ) { Text(stringResource(R.string.hook_review_revoke), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            ZCodeButton(
                onClick = { confirmTrust = true },
                enabled = !busy && selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hook_review_trust_selected"),
            ) {
                Text(stringResource(R.string.hook_review_trust_n, selected.size))
            }
        }
    }

    if (confirmTrust) {
        AlertDialog(
            onDismissRequest = { confirmTrust = false },
            title = { Text(stringResource(R.string.hook_review_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.hook_review_confirm_body,
                        selected.size,
                        hook.workspaceLabel,
                    ),
                )
            },
            confirmButton = {
                ZCodeButton(
                    onClick = {
                        confirmTrust = false
                        onTrustSelected(selected)
                        onGrantPersistent()
                    },
                    modifier = Modifier.testTag("hook_review_confirm_yes"),
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrust = false }, modifier = Modifier.testTag("hook_review_confirm_no")) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }
    confirmRevokeFor?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmRevokeFor = null },
            title = { Text(stringResource(R.string.hook_review_revoke_confirm_title)) },
            text = { Text(stringResource(R.string.hook_review_revoke_confirm_body)) },
            confirmButton = {
                ZCodeButton(
                    onClick = {
                        confirmRevokeFor = null
                        onRevoke(listOf(id))
                    },
                    modifier = Modifier.testTag("hook_review_revoke_yes"),
                ) { Text(stringResource(R.string.hook_review_revoke)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevokeFor = null }, modifier = Modifier.testTag("hook_review_revoke_no")) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }
}
