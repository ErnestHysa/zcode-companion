package com.zcode.android.features.git.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.ZCodeButton
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import com.zcode.android.domain.model.GitBranchIssue
import com.zcode.android.domain.model.GitBranchList
import com.zcode.android.domain.model.GitIdentity
import com.zcode.android.features.git.R

/**
 * ADR-021 (gaps-and-polish Phase 5D) Tier B/C sheets. Every destructive path is GATED:
 * the commit sheet blocks on the identity-missing state (the phone cannot set identity);
 * push renders a confirm naming the branch (publishing is outward-facing); the branch
 * sheet renders the TYPED issue map verbatim; discard requires the typed `DISCARD` word
 * and is the ONLY way the transport word is reachable (ADR-020 pattern, pinned).
 */

/** Tier B: the commit sheet — generate/regenerate, include-unstaged, identity gate. */
@Composable
fun CommitSheet(
    identity: GitIdentity?,
    generating: Boolean,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit,
    onCommit: (message: String, includeUnstaged: Boolean, andPush: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var message by remember { mutableStateOf("") }
    var includeUnstaged by remember { mutableStateOf(false) }
    var andPush by remember { mutableStateOf(false) }
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier.testTag("commit_sheet")) {
        Text(
            stringResource(R.string.commit_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (identity != null && !identity.present) {
            Text(
                stringResource(R.string.commit_identity_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("commit_identity_missing"),
            )
        }
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text(stringResource(R.string.commit_message_hint)) },
                modifier = Modifier.weight(1f).testTag("commit_message_field"),
            )
            TextButton(onClick = onGenerate, enabled = !generating, modifier = Modifier.testTag("commit_generate")) {
                Text(stringResource(R.string.commit_generate))
            }
        }
        if (generating) {
            Text(
                stringResource(R.string.commit_generate_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("commit_generate_unavailable"),
            )
        }
        androidx.compose.material3.ListItem(
            headlineContent = { Text(stringResource(R.string.commit_include_unstaged), style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
                androidx.compose.material3.Switch(checked = includeUnstaged, onCheckedChange = { includeUnstaged = it }, modifier = Modifier.testTag("commit_include_unstaged"))
            },
        )
        androidx.compose.material3.ListItem(
            headlineContent = { Text(stringResource(R.string.commit_and_push), style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
                androidx.compose.material3.Switch(checked = andPush, onCheckedChange = { andPush = it }, modifier = Modifier.testTag("commit_and_push"))
            },
        )
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            ZCodeButton(
                onClick = { onCommit(message, includeUnstaged, andPush) },
                enabled = message.isNotBlank() && (identity == null || identity.present),
                modifier = Modifier.testTag("commit_send"),
            ) { Text(stringResource(R.string.commit_send)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Tier B: the push confirm — names the branch and the no-upstream consequence. */
@Composable
fun PushConfirm(
    branchName: String,
    hasUpstream: Boolean,
    onDismiss: () -> Unit,
    onPush: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.push_title)) },
        text = { Text(stringResource(R.string.push_body, branchName, if (hasUpstream) "" else stringResource(R.string.push_no_upstream))) },
        confirmButton = {
            ZCodeButton(onClick = onPush, modifier = Modifier.testTag("push_confirm")) { Text(stringResource(R.string.push_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) }
        },
    )
}

/** Tier C: the DISCARD typed-confirm — the ONLY gate to the destructive word. */
@Composable
fun DiscardConfirm(
    paths: List<String>,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by remember { mutableStateOf("") }
    val word = stringResource(R.string.discard_confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.discard_title), color = MaterialTheme.colorScheme.error) },
        text = {
            Column {
                Text(stringResource(R.string.discard_body, paths.size))
                Spacer(Modifier.height(8.dp))
                paths.take(5).forEach { p ->
                    Text(p, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.discard_type_word), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("discard_typed"),
                )
            }
        },
        confirmButton = {
            ZCodeButton(
                onClick = onDiscard,
                enabled = typed.trim() == word,
                modifier = Modifier.testTag("discard_confirm_btn"),
            ) { Text(stringResource(R.string.discard_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) }
        },
    )
}

/** Tier B: the branch sheet — switch (current marked) + create-and-switch, typed issues rendered. */
@Composable
fun BranchSheet(
    branches: GitBranchList?,
    issue: GitBranchIssue?,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onCreateAndSwitch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by remember { mutableStateOf("") }
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier.testTag("branch_sheet")) {
        Text(
            stringResource(R.string.branches_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        issue?.let { IssueLine(it) }
        branches?.branches?.forEach { b ->
            androidx.compose.material3.ListItem(
                headlineContent = { Text(b.name, fontWeight = if (b.isCurrent) FontWeight.Bold else null) },
                trailingContent = {
                    if (!b.isCurrent) {
                        TextButton(onClick = { onSwitch(b.name) }, modifier = Modifier.testTag("branch_switch_${b.name}")) {
                            Text(stringResource(R.string.branches_switch))
                        }
                    } else Text("✓", color = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.testTag("branch_row_${b.name}"),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text(stringResource(R.string.branches_create_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("branch_new_field"),
        )
        TextButton(
            onClick = { onCreateAndSwitch(newName) },
            enabled = newName.isNotBlank(),
            modifier = Modifier.padding(horizontal = 16.dp).testTag("branch_create"),
        ) { Text(stringResource(R.string.branches_create)) }
        Spacer(Modifier.height(12.dp))
    }
}

/** The typed issue map — code kebab-case → the localized line (+ paths listed, detail copyable). */
@Composable
fun IssueLine(issue: GitBranchIssue, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("branch_issue_${issue.code}")) {
        Text(
            branchIssueCopy(issue.code),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (issue.paths.isNotEmpty()) {
            Text(issue.paths.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun branchIssueCopy(code: String): String = stringResource(
    when (code) {
        "tracked-changes-would-be-overwritten" -> R.string.branch_issue_tracked_changes_would_be_overwritten
        "untracked-changes-would-be-overwritten" -> R.string.branch_issue_untracked_changes_would_be_overwritten
        "branch-already-exists" -> R.string.branch_issue_branch_already_exists
        "target-branch-not-found" -> R.string.branch_issue_target_branch_not_found
        "branch-in-other-worktree" -> R.string.branch_issue_branch_in_other_worktree
        "conflicts-present" -> R.string.branch_issue_conflicts_present
        "operation-in-progress" -> R.string.branch_issue_operation_in_progress
        "invalid-branch-name" -> R.string.branch_issue_invalid_branch_name
        else -> R.string.branch_issue_unknown
    },
)
