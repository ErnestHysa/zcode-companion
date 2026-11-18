package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
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
            // N-BG-1/D4 (vc52 Phase 9): the item list scrolls in ITS OWN viewport (the
            // caller caps the card inside the pinned budget) and "Trust N selected" is
            // PINNED at the card's bottom edge — Trust used to need a magic swipe (the
            // first swipes over the card scrolled the pinned STACK instead of the card).
            // N-BG-1/D4 + F-P3-A (pass-0 Phase 2): the item list scrolls in ITS OWN
            // viewport. A LazyColumn measures items with UNBOUNDED main-axis
            // constraints, so this weight resolved to ZERO whenever the CARD was not
            // itself bounded — the caller's per-card cap (ConversationScreen) is what
            // gives the weight real space (the pass-44 live face: "summary →
            // disabled trust area", the answer leg gated).
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    // F-P3-A pin seam: the body's own observable (bounds/height asserts).
                    .testTag("hook_review_items"),
            ) {
                for (item in hook.items) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        // N-BF-4 (vc52 Phase 9): the ROW is the toggle (the desktop's
                        // row-is-the-toggle contract) — a tap on the row/label toggled
                        // nothing before (only the checkbox did; Trust stayed disabled
                        // at "Trust 0 selected"). The inner Checkbox is display-only.
                        .toggleable(
                            value = item.reviewItemId in checked,
                            enabled = !busy && item.actionable,
                            role = Role.Checkbox,
                            onValueChange = { on ->
                                checked = if (on) checked + item.reviewItemId else checked - item.reviewItemId
                            },
                        )
                        .padding(vertical = 2.dp)
                        .testTag("hook_review_item_${item.reviewItemId}"),
                ) {
                    if (item.actionable) {
                        Checkbox(
                            checked = item.reviewItemId in checked,
                            onCheckedChange = null,
                            enabled = !busy && item.actionable,
                            modifier = Modifier.testTag("hook_review_check_${item.reviewItemId}"),
                        )
                    } else {
                        // N-BH-3: a non-actionable item's state is VISIBLE — the disabled
                        // checkbox looked identical to an actionable one and carried no
                        // description. The merged row text now announces the state.
                        Text(
                            text = stringResource(R.string.hook_review_trusted),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("hook_review_state_${item.reviewItemId}"),
                        )
                    }
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
