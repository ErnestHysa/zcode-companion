package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.zcode.android.features.conversation.R
import androidx.compose.ui.res.stringResource
import com.zcode.android.core.ui.components.relativeTimeLabel
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Approvals inbox (§18.2/§18.3 "approval first"): cross-task open interactions in
 * one surface — one-tap approve for the RECOGNIZED primary option, deny with
 * confirm, tap-through to the task for full decision context. Answers ride the
 * SAME fail-closed gate as the conversation cards (§20.4) via
 * [ConversationPort.answerFromInbox] (store-hydrated cards, §18.3).
 */
@Composable
fun ApprovalsScreen(
    interactions: List<ConversationPort.InteractionSummary>,
    phase: com.zcode.android.domain.state.ConnectionPhase,
    onBack: () -> Unit,
    onOpenTask: (taskId: String) -> Unit,
    onAnswer: (interactionId: String, optionId: String, freeText: String?, onOutcome: (ConversationPort.AnswerOutcome) -> Unit) -> Unit,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var denyTarget by remember { mutableStateOf<ConversationPort.InteractionSummary?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    // FIELD (vc35 user report — "answered but the cards stay active"): answer
    // failures were silent; the user believed a request was handled while the
    // authoritative answer never landed. Every non-submit outcome surfaces now.
    var answerError by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("approvals_back")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(com.zcode.android.core.ui.R.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.approvals_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.testTag("approvals-title"),
                    )
                    Text(
                        text = stringResource(R.string.approvals_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (phase != com.zcode.android.domain.state.ConnectionPhase.CONNECTED) {
                Text(
                    text = stringResource(R.string.approvals_offline_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("approvals_offline_note"),
                )
            }
            answerError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("approvals_answer_error"),
                )
            }
            if (interactions.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp)
                        .testTag("approvals_empty"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.approvals_empty_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.approvals_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("approvals_list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(interactions, key = { it.interactionId }) { record ->
                        val isPermission = record.kind == "PERMISSION"
                        androidx.compose.material3.Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approvals_item_${record.interactionId.take(12)}")
                                .clickable { record.sessionId?.let(onOpenTask) },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = if (isPermission) stringResource(R.string.approvals_permission, record.toolName ?: stringResource(R.string.tool_fallback)) else stringResource(R.string.approvals_question),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    text = stringResource(R.string.approvals_task_ref, record.sessionId?.take(16) ?: "?", relativeTimeLabel(record.createdAtMs) ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.height(8.dp))
                                // B2.3: weighted + spaced — the unweighted Row sat FLUSH
                                // (dead Spacer(0), discarded PaddingValues) and the Deny
                                // button overflowed narrow cards.
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (isPermission) {
                                        // §20.2 one-tap approve: the recognized low-risk
                                        // primary (allowOnce) — higher-risk choices live
                                        // on the card inside the task.
                                        Button(
                                            onClick = {
                                                busyId = record.interactionId
                                                answerError = null
                                                scope.launch {
                                                    try {
                                                        onAnswer(record.interactionId, "allowOnce", null) { outcome ->
                                                            busyId = null
                                                            if (outcome != ConversationPort.AnswerOutcome.SUBMITTED) {
                                                                answerError = "The answer was not confirmed — open the task and answer there."
                                                            }
                                                        }
                                                    } finally {
                                                        busyId = null
                                                    }
                                                }
                                            },
                                            enabled = busyId == null,
                                            modifier = Modifier
                                                // §30: approval actions ≥ 56 dp.
                                                .heightIn(min = 56.dp)
                                                .weight(1f)
                                                .testTag("approvals_allow_once_${record.interactionId.take(12)}"),
                                        ) { Text(stringResource(com.zcode.android.core.ui.R.string.action_allow_once)) }
                                        OutlinedButton(
                                            onClick = { answerError = null; denyTarget = record },
                                            enabled = busyId == null,
                                            modifier = Modifier
                                                .heightIn(min = 56.dp)
                                                .weight(1f)
                                                .testTag("approvals_deny_${record.interactionId.take(12)}"),
                                        ) { Text(stringResource(com.zcode.android.core.ui.R.string.action_deny)) }
                                    } else {
                                        TextButton(onClick = { record.sessionId?.let(onOpenTask) }) {
                                            Text(stringResource(R.string.approvals_open_task))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    denyTarget?.let { target ->
        // §20.2: Deny ALWAYS confirms.
        AlertDialog(
            onDismissRequest = { denyTarget = null },
            title = { Text(stringResource(R.string.approvals_deny_title)) },
            text = { Text(stringResource(R.string.approvals_deny_body, target.toolName ?: stringResource(R.string.tool_fallback))) },
            confirmButton = {
                Button(
                    onClick = {
                        denyTarget = null
                        busyId = target.interactionId
                        answerError = null
                        scope.launch {
                            try {
                                onAnswer(target.interactionId, "deny", null) { outcome ->
                                    busyId = null
                                    if (outcome != ConversationPort.AnswerOutcome.SUBMITTED) {
                                        answerError = "The answer was not confirmed — open the task and answer there."
                                    }
                                }
                            } finally {
                                busyId = null
                            }
                        }
                    },
                    modifier = Modifier.testTag("approvals_deny_confirm"),
                ) { Text(stringResource(com.zcode.android.core.ui.R.string.action_deny)) }
            },
            dismissButton = { TextButton(onClick = { denyTarget = null }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) } },
        )
    }
}

