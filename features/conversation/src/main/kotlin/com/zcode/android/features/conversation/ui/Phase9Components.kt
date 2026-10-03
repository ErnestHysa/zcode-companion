package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.DiffViewerContent
import com.zcode.android.domain.model.ModelProviderOption
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.domain.model.TurnFileChanges
import com.zcode.android.features.conversation.port.ConversationPort

/**
 * Phase 9 conversation affordances (§43.2-9): model/mode switch chips (9.5),
 * the per-turn diff viewer dialog (9.1), and the composer attachment strip (9.2).
 * All state renders honestly (INV-11); the picker options are exactly the
 * protocol's (never synthesized — §20.2 discipline applied to model lists).
 */

/** §10.3 echo-reconciled config chips: current mode + model, tap → verified options. */
@Composable
fun SessionModeChips(
    config: SessionConfigState?,
    onSwitchMode: (String) -> Unit,
    onSwitchModel: (provider: String, model: String, thought: String) -> Unit,
    modelOptionsProvider: suspend () -> List<ModelProviderOption>,
    modifier: Modifier = Modifier,
) {
    var modeMenu by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (config == null) return@Row
        // Mode chip — options are the VERIFIED switchable set (auto excluded, §10.3).
        AssistChip(
            onClick = { modeMenu = true },
            label = { Text("mode: ${config.mode}") },
            modifier = Modifier.semantics { contentDescription = "collaboration mode ${config.mode}, tap to change" },
        )
        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
            for (mode in SessionConfigState.SWITCHABLE_MODES) {
                DropdownMenuItem(
                    text = { Text(if (mode == config.mode) "• $mode" else mode) },
                    onClick = {
                        modeMenu = false
                        if (mode != config.mode) onSwitchMode(mode)
                    },
                )
            }
        }
        // Model chip — provider/model/thought from the echo; options from model-selection.
        AssistChip(
            onClick = { modelSheet = true },
            label = { Text("${config.provider}/${config.model} · ${config.thought}") },
            modifier = Modifier.semantics {
                contentDescription = "model ${config.model}, thought ${config.thought}, tap to change"
            },
        )
    }
    if (modelSheet && config != null) {
        ModelPickerDialog(
            config = config,
            modelOptionsProvider = modelOptionsProvider,
            onPick = { provider, model, thought ->
                modelSheet = false
                onSwitchModel(provider, model, thought)
            },
            onDismiss = { modelSheet = false },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    config: SessionConfigState,
    modelOptionsProvider: suspend () -> List<ModelProviderOption>,
    onPick: (provider: String, model: String, thought: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val providers by produceState<List<ModelProviderOption>?>(initialValue = null) {
        value = runCatching { modelOptionsProvider() }.getOrDefault(emptyList())
    }
    var thoughtMenuFor by remember { mutableStateOf<Pair<String, String>?>(null) } // provider to model
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Switch model", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    providers == null -> Text("Loading options…", style = MaterialTheme.typography.bodySmall)
                    providers!!.isEmpty() -> Column {
                        Text(
                            "The model list is not available on this connection — switching between known configuration values is still possible.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onPick(config.provider, config.model, config.thought) }) {
                            Text("Keep current model")
                        }
                    }
                    else -> {
                        for (provider in providers!!) {
                            Text(
                                provider.providerName ?: provider.providerId,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            for (model in provider.models) {
                                val selected = provider.providerId == config.provider && model.id == config.model
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            thoughtMenuFor = provider.providerId to model.id
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (selected) "• ${model.label}" else model.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f, fill = true),
                                    )
                                    if (selected && config.thoughtLevels.isNotEmpty()) {
                                        Text(
                                            "thought: ${config.thought}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        thoughtMenuFor?.let { (providerId, modelId) ->
                            Text(
                                "Thought level for $modelId",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            val levels = config.thoughtLevels.ifEmpty { listOf(config.thought) }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (level in levels) {
                                    AssistChip(
                                        onClick = { onPick(providerId, modelId, level) },
                                        label = { Text(if (level == config.thought) "• $level" else level) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** 9.1 per-turn diff viewer dialog — fetches pre-parsed hunks on open (§10.1). */
@Composable
fun TurnDiffDialog(
    turnLabel: String,
    rowId: Long,
    entityId: String,
    provider: suspend (Long, String) -> ConversationPort.TurnChangesResult,
    onDismiss: () -> Unit,
) {
    val result by produceState<ConversationPort.TurnChangesResult?>(initialValue = null, key1 = rowId, key2 = entityId) {
        value = provider(rowId, entityId)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Changes · $turnLabel", style = MaterialTheme.typography.titleSmall, maxLines = 2) },
        text = {
            when {
                result == null -> Text("Loading changes…", style = MaterialTheme.typography.bodySmall)
                result!!.error != null -> Text(
                    "Could not load changes: ${result!!.error}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> TurnChangesBody(result!!.changes!!)
            }
        },
    )
}

@Composable
private fun TurnChangesBody(changes: TurnFileChanges) {
    Column {
        Text(
            "${changes.files} file(s) +${changes.additions} −${changes.deletions}" +
                (changes.state?.takeIf { it != "active" }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.lazy.LazyColumn {
            for (item in changes.items) {
                item(key = item.path) {
                    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.path,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = true),
                                    maxLines = 2,
                                )
                                com.zcode.android.core.ui.components.DiffCountBadge(added = item.additions, removed = item.deletions)
                            }
                            if (item.hunks.isEmpty()) {
                                Text(
                                    "no textual diff recorded for this file",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                com.zcode.android.core.ui.components.DiffHunksList(
                                    file = com.zcode.android.core.common.diff.DiffFile(path = item.path, hunks = item.hunks),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** §23.2 composer attachment chips (uploading/ready/failed states render honestly). */
@Composable
fun AttachmentStrip(
    attachments: List<ConversationPort.ComposerAttachment>,
    onRemove: (uploadId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (att in attachments) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (att.state) {
                        "ready" -> "✓"
                        "failed" -> "✗"
                        else -> "↑"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (att.state) {
                        "ready" -> MaterialTheme.colorScheme.primary
                        "failed" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    "${att.fileName} (${formatBytes(att.bytes)})" +
                        if (att.state == "uploading") " ${(
                            att.progress * 100
                        ).toInt()}%" else "",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f, fill = true),
                    maxLines = 1,
                )
                TextButton(onClick = { onRemove(att.uploadId) }) { Text("Remove") }
            }
        }
    }
}
