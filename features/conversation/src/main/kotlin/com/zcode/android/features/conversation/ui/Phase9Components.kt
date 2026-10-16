package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import com.zcode.android.features.conversation.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.DiffViewerContent
import com.zcode.android.domain.model.ModelProviderOption
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.domain.model.TurnFileChanges
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.core.ui.components.rememberZCodeHaptics

/**
 * Phase 9 conversation affordances (§43.2-9): model/mode switch chips (9.5),
 * the per-turn diff viewer dialog (9.1), and the composer attachment strip (9.2).
 * All state renders honestly (INV-11); the picker options are exactly the
 * protocol's (never synthesized — §20.2 discipline applied to model lists).
 */

/**
 * FIELD (2026-10-12): a STANDALONE model picker for surfaces without a live
 * SessionConfigState (the new-task composer) — same provider/model options as the
 * conversation picker, onPick carries (provider, model) only.
 */
@Composable
fun ComposerModelPickerDialog(
    current: Pair<String, String>?,
    modelOptionsProvider: suspend () -> List<ModelProviderOption>,
    onPick: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberZCodeHaptics()
    val providers by produceState<List<ModelProviderOption>?>(initialValue = null) {
        value = runCatching { modelOptionsProvider() }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // E2: the title slot carried action_close (copy-paste) — live-verified twice.
        title = {
            Text(
                stringResource(R.string.composer_model_picker_title),
                modifier = Modifier.testTag("composer_model_picker_title"),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        text = {
            when (val list = providers) {
                null -> Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                // E9: an empty list rendered a completely EMPTY body — an honest
                // unavailable state instead.
                emptyList<ModelProviderOption>() -> Text(
                    stringResource(R.string.composer_model_picker_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("composer_model_picker_empty"),
                )
                else -> Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                ) {
                    for (provider in list) {
                        Text(
                            provider.providerName ?: provider.providerId,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        for (model in provider.models) {
                            val selected = current?.first == provider.providerId && current?.second == model.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptics.tick()
                                        onPick(provider.providerId, model.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                    .testTag("composer_model_row_${model.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(model.label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
    )
}

/** §10.3 echo-reconciled config chips: current mode + model, tap → verified options. */
@Composable
fun SessionModeChips(
    config: SessionConfigState?,
    onSwitchMode: (String) -> Unit,
    onSwitchModel: (provider: String, model: String, thought: String) -> Unit,
    modelOptionsProvider: suspend () -> List<ModelProviderOption>,
    modifier: Modifier = Modifier,
) {
    var modeSheet by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    val modeA11y = config?.let { stringResource(R.string.mode_a11y, it.mode) }
    val modelA11y = config?.let { stringResource(R.string.model_a11y, it.model, it.thought) }
    // FIELD (2026-10-04, vc32 device — Samsung S24): this Row used to chain
    // `.fillMaxWidth()` UNDER the caller's `weight(1f, fill = false)`, capping it at
    // the weighted share (~58 dp); the chips wrapped one character per column to
    // hundreds of dp tall, the header row swallowed the whole screen, the
    // CenterVertically-aligned Files/Git/Terminal chips landed mid-screen and the
    // weight(1f) timeline collapsed to zero ("overlay blocks the chat" field report).
    // Rules now: the Row RESPECTS the incoming constraint (no fillMaxWidth), the
    // model chip takes the flexible remainder, and every label is single-line
    // ellipsized — no label can ever explode the header height again.
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (config == null) return@Row
        // Mode chip — options are the VERIFIED switchable set (auto excluded, §10.3).
        AssistChip(
            onClick = { modeSheet = true },
            label = {
                Text(
                    "mode: ${config.mode}",
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier
                .testTag("conv_mode_chip")
                .semantics { modeA11y?.let { contentDescription = it } },
        )
        // Model chip — provider/model/thought from the echo; options from model-selection.
        AssistChip(
            onClick = { modelSheet = true },
            label = {
                Text(
                    "${config.provider}/${config.model} · ${config.thought}",
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag("conv_model_chip")
                .semantics { modelA11y?.let { contentDescription = it } },
        )
    }
    if (modeSheet && config != null) {
        // FIELD (2026-10-06, vc34 device): a DropdownMenu anchored at the very top of
        // the screen (this chip row) can flip upward and CLIP items at the status bar
        // (edge-to-edge popup insets) — the field report "macOS shows 4 modes, Android
        // shows 3". A modal dialog lists all options un-clipped, matching the model
        // picker's pattern.
        // GAP-17: items restyled to the desktop's composite affordance (icon + title +
        // description, radio check on the current mode, high-permission mode flagged
        // warning) — V4ComposerModeControls.tsx; vocabulary + wire behavior UNCHANGED.
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { modeSheet = false },
            confirmButton = { TextButton(onClick = { modeSheet = false }) { Text(stringResource(R.string.action_close)) } },
            title = { Text(stringResource(R.string.switch_mode_title), style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    for (mode in SessionConfigState.SWITCHABLE_MODES) {
                        val selected = mode == config.mode
                        androidx.compose.material3.ListItem(
                            leadingContent = {
                                Icon(
                                    imageVector = modeIcon(mode),
                                    contentDescription = null,
                                    tint = if (mode == "yolo") {
                                        com.zcode.android.core.ui.theme.LocalZCodeColors.current.railWaiting
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            headlineContent = { Text(modeTitle(mode)) },
                            supportingContent = { Text(modeDescription(mode)) },
                            trailingContent = {
                                androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = androidx.compose.ui.semantics.Role.RadioButton,
                                ) {
                                    modeSheet = false
                                    if (!selected) onSwitchMode(mode)
                                }
                                .testTag("mode_option_$mode"),
                        )
                    }
                }
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

/** GAP-17: desktop mode icons (display.tsx resolveModeOptionIcon): build = hand
 * (confirm-before-changes), edit = shield-check, plan = lightbulb, yolo = alert. */
private fun modeIcon(mode: String) = when (mode) {
    "edit" -> Icons.Filled.VerifiedUser
    "plan" -> Icons.Filled.Lightbulb
    "yolo" -> Icons.Filled.Warning
    else -> Icons.Filled.PanTool
}

/** GAP-17: desktop mode titles (i18n mode.label.glm.*). */
@Composable
private fun modeTitle(mode: String): String = stringResource(
    when (mode) {
        "edit" -> R.string.mode_title_edit
        "plan" -> R.string.mode_title_plan
        "yolo" -> R.string.mode_title_yolo
        else -> R.string.mode_title_build
    },
)

/** GAP-17: desktop mode descriptions (i18n mode.description.glm.*). */
@Composable
private fun modeDescription(mode: String): String = stringResource(
    when (mode) {
        "edit" -> R.string.mode_description_edit
        "plan" -> R.string.mode_description_plan
        "yolo" -> R.string.mode_description_yolo
        else -> R.string.mode_description_build
    },
)

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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.switch_model_title), style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    providers == null -> Text(stringResource(R.string.switch_loading), style = MaterialTheme.typography.bodySmall)
                    providers!!.isEmpty() -> Column {
                        Text(
                            stringResource(R.string.switch_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { onPick(config.provider, config.model, config.thought) }) {
                            Text(stringResource(R.string.switch_keep_current))
                        }
                    }
                    else -> {
                        // GAP-18: desktop model-picker parity (ModelConfigSelect.tsx) —
                        // provider GROUP headers, a radio check on the active model,
                        // disabled (inert, dimmed) rows for unavailable entries. Same
                        // options, same two-step pick → thought-level flow, same wire call.
                        for (provider in providers!!) {
                            Text(
                                (provider.providerName ?: provider.providerId).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .testTag("model_provider_${provider.providerId}"),
                            )
                            for (model in provider.models) {
                                val selected = provider.providerId == config.provider && model.id == config.model
                                androidx.compose.material3.ListItem(
                                    headlineContent = {
                                        Text(
                                            model.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    },
                                    trailingContent = {
                                        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = selected,
                                            enabled = model.available,
                                            role = androidx.compose.ui.semantics.Role.RadioButton,
                                        ) {
                                            thoughtMenuFor = provider.providerId to model.id
                                        }
                                        .alpha(if (model.available) 1f else 0.38f)
                                        .testTag("model_option_${model.id}"),
                                )
                            }
                        }
                        thoughtMenuFor?.let { (providerId, modelId) ->
                            Text(
                                stringResource(R.string.thought_level_for, modelId),
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
                                        modifier = Modifier.testTag("thought_option_$level"),
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(R.string.changes_title, turnLabel), style = MaterialTheme.typography.titleSmall, maxLines = 2) },
        text = {
            val loadError: Any? = result?.error
            when {
                result == null -> Text(stringResource(R.string.changes_loading), style = MaterialTheme.typography.bodySmall)
                loadError != null -> Text(
                    stringResource(R.string.changes_error, loadError),
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

/** §23.2 composer attachment chips (uploading/ready/failed states render honestly;
 *  failed chips carry a one-tap RETRY — the §33 failure is actionable, not a dead end). */
@Composable
fun AttachmentStrip(
    attachments: List<ConversationPort.ComposerAttachment>,
    onRemove: (uploadId: String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (uploadId: String) -> Unit = {},
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
                if (att.state == "failed") {
                    TextButton(onClick = { onRetry(att.uploadId) }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_retry)) }
                }
                TextButton(onClick = { onRemove(att.uploadId) }) { Text(stringResource(R.string.attachment_remove)) }
            }
        }
    }
}
