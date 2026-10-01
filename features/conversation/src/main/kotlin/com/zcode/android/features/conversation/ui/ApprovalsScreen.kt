package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    onAnswer: (interactionId: String, optionId: String, freeText: String?) -> Unit,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var denyTarget by remember { mutableStateOf<ConversationPort.InteractionSummary?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }

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
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Approvals",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.testTag("approvals-title"),
                    )
                    Text(
                        text = "Requests waiting across your tasks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (phase != com.zcode.android.domain.state.ConnectionPhase.CONNECTED) {
                Text(
                    text = "Offline — answers are blocked until the connection returns. Nothing is queued.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("approvals_offline_note"),
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
                    Text("Nothing needs you", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Permission requests and questions from your tasks appear here.",
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
                                    text = if (isPermission) "Permission — ${record.toolName ?: "tool"}" else "Question",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    text = "task ${record.sessionId?.take(16) ?: "?"} · ${ageOf(record.createdAtMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row {
                                    if (isPermission) {
                                        // §20.2 one-tap approve: the recognized low-risk
                                        // primary (allowOnce) — higher-risk choices live
                                        // on the card inside the task.
                                        Button(
                                            onClick = {
                                                busyId = record.interactionId
                                                scope.launch {
                                                    try {
                                                        onAnswer(record.interactionId, "allowOnce", null)
                                                    } finally {
                                                        busyId = null
                                                    }
                                                }
                                            },
                                            enabled = busyId == null,
                                            modifier = Modifier
                                                .testTag("approvals_allow_once_${record.interactionId.take(12)}"),
                                        ) { Text("Allow once") }
                                        Spacer(Modifier.height(0.dp))
                                        androidx.compose.foundation.layout.PaddingValues(4.dp)
                                        OutlinedButton(
                                            onClick = { denyTarget = record },
                                            enabled = busyId == null,
                                            modifier = Modifier.testTag("approvals_deny_${record.interactionId.take(12)}"),
                                        ) { Text("Deny") }
                                    } else {
                                        TextButton(onClick = { record.sessionId?.let(onOpenTask) }) {
                                            Text("Open task to answer")
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
            title = { Text("Deny this request?") },
            text = { Text("The agent is told no for this request (${target.toolName ?: "tool"}).") },
            confirmButton = {
                Button(
                    onClick = {
                        denyTarget = null
                        busyId = target.interactionId
                        scope.launch {
                            try {
                                onAnswer(target.interactionId, "deny", null)
                            } finally {
                                busyId = null
                            }
                        }
                    },
                    modifier = Modifier.testTag("approvals_deny_confirm"),
                ) { Text("Deny") }
            },
            dismissButton = { TextButton(onClick = { denyTarget = null }) { Text("Cancel") } },
        )
    }
}

private fun ageOf(createdAtMs: Long): String {
    val seconds = (System.currentTimeMillis() - createdAtMs) / 1000
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}
