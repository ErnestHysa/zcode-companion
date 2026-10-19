package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

/**
 * FIELD (2026-10-12): a STANDALONE model picker for surfaces without a live
 * SessionConfigState (the new-task composer) — same provider/model options as the
 * conversation picker, onPick carries (provider, model) only.
 *
 * B1 (Phase 3A): a thin entry point over the ONE shared [ModelPickerSheet] — no
 * thought step here (the new session has no runtime-projected level set yet), so
 * every row is a one-tap pick; the composer's own row tags are preserved.
 */
@Composable
fun ComposerModelPickerDialog(
    current: Pair<String, String>?,
    modelOptionsProvider: suspend () -> List<ModelProviderOption>,
    onPick: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModelPickerSheet(
        current = current,
        currentThought = null,
        thoughtLevels = emptyList(),
        modelOptionsProvider = modelOptionsProvider,
        onPick = { provider, model, _ -> onPick(provider, model) },
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.composer_model_picker_title),
        rowTagPrefix = "composer_model_row_",
        titleTag = "composer_model_picker_title",
        emptyTag = "composer_model_picker_empty",
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
    /** Phase 4C (A3.4): the wire-driven effort switch (setThoughtLevel). */
    onSetThoughtLevel: (String) -> Unit = {},
) {
    var modeSheet by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var effortSheet by remember { mutableStateOf(false) }
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
                    stringResource(R.string.composer_mode_echo, config.mode),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            },
            modifier = Modifier
                .testTag("conv_mode_chip")
                .semantics { modeA11y?.let { contentDescription = it } },
        )
        // Effort chip (A3.4) — WIRE-DRIVEN: the offered levels are the CURRENT model's
        // config.thoughtLevels (runtime-projected); no levels → nothing to offer.
        if (config.thoughtLevels.isNotEmpty()) {
            AssistChip(
                onClick = { effortSheet = true },
                label = {
                    Text(
                        "${stringResource(R.string.effort_chip_prefix)}${config.thought}",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.testTag("conv_effort_chip"),
            )
        }
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
                            headlineContent = { Text(modeTitle(mode, config.provider)) },
                            supportingContent = { Text(modeDescription(mode, config.provider)) },
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
    if (effortSheet && config != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { effortSheet = false },
            confirmButton = { TextButton(onClick = { effortSheet = false }) { Text(stringResource(R.string.action_close)) } },
            title = { Text(stringResource(R.string.effort_dialog_title), style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    for (level in config.thoughtLevels) {
                        val selected = level == config.thought
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(level) },
                            trailingContent = {
                                androidx.compose.material3.RadioButton(selected = selected, onClick = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = androidx.compose.ui.semantics.Role.RadioButton,
                                ) {
                                    effortSheet = false
                                    if (!selected) onSetThoughtLevel(level)
                                }
                                .testTag("effort_option_$level"),
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

/**
 * J3: per-runtime label tables (desktop mode.label.*). The WIRE vocabulary stays the
 * verified switchable four for every runtime — the shipped host normalizes claude/
 * codex/gemini vocabularies onto them (toZCodeMode) — but a NON-glm runtime gets the
 * neutral copy, never glm's "Ask before changes" branding.
 */
private fun isGlmRuntime(provider: String?): Boolean =
    provider == null || provider == "zai" || provider == "glm" || provider.startsWith("glm")

/** GAP-17 + J3: desktop mode titles (i18n mode.label.glm.* / neutral for other runtimes). */
@Composable
private fun modeTitle(mode: String, provider: String? = null): String = stringResource(
    when (mode) {
        "edit" -> if (isGlmRuntime(provider)) R.string.mode_title_edit else R.string.mode_title_neutral_edit
        "plan" -> if (isGlmRuntime(provider)) R.string.mode_title_plan else R.string.mode_title_neutral_plan
        "yolo" -> if (isGlmRuntime(provider)) R.string.mode_title_yolo else R.string.mode_title_neutral_yolo
        else -> if (isGlmRuntime(provider)) R.string.mode_title_build else R.string.mode_title_neutral_build
    },
)

/** GAP-17 + J3: desktop mode descriptions (mode.description.glm.* / neutral). */
@Composable
private fun modeDescription(mode: String, provider: String? = null): String = stringResource(
    when (mode) {
        "edit" -> if (isGlmRuntime(provider)) R.string.mode_description_edit else R.string.mode_description_neutral_edit
        "plan" -> if (isGlmRuntime(provider)) R.string.mode_description_plan else R.string.mode_description_neutral_plan
        "yolo" -> if (isGlmRuntime(provider)) R.string.mode_description_yolo else R.string.mode_description_neutral_yolo
        else -> if (isGlmRuntime(provider)) R.string.mode_description_build else R.string.mode_description_neutral_build
    },
)

/**
 * The conversation-side entry point (the header model chip) over the ONE shared
 * [ModelPickerSheet]. B1/G2: the pending highlight is the sheet's LOCAL state (never
 * `config` — the echo only supplies the starting point) and the thought step is
 * attached to the tapped row; the switch itself still rides the §10.3 CAS command.
 */
@Composable
private fun ModelPickerDialog(
    config: SessionConfigState,
    modelOptionsProvider: suspend () -> List<ModelProviderOption>,
    onPick: (provider: String, model: String, thought: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModelPickerSheet(
        current = config.provider to config.model,
        currentThought = config.thought,
        thoughtLevels = config.thoughtLevels,
        modelOptionsProvider = modelOptionsProvider,
        onPick = { provider, model, thought -> onPick(provider, model, thought ?: config.thought) },
        onDismiss = onDismiss,
        title = stringResource(R.string.switch_model_title),
        keepCurrentOnEmpty = true,
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
                                    stringResource(R.string.row_diff_empty_file),
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
