package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.selectable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.zcode.android.features.conversation.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.StatusTone
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.domain.model.QueueState
import com.zcode.android.features.conversation.ConversationUiState
import com.zcode.android.features.conversation.SendNotice
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.core.ui.components.ZCodeOutlinedButton
import com.zcode.android.core.ui.components.ZCodeButton
import com.zcode.android.core.ui.components.rememberZCodeHaptics
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet

/**
 * Phase 7 write-surface components (§18.3, ui-ux future-phase-ui.md "Phase 7"):
 * composer with draft + pending overlay, approval card (fail-closed §20.4,
 * confirm-steps §20.2), user-input question card (U9), queue strip (§7.5), stop
 * control. Every destructive/deny action confirms; every option renders its
 * consequence; unknown options render INERT (never actionable — INV-14).
 */

// ------------------------------------------------------------------ approval card

/**
 * §20.2 approval card: tool name, summary (the protocol's reason), the decision
 * target (detail — collapsed JSON preview with "view full"), and EXACTLY the
 * protocol's option set — never synthesized. Answering routes through the ONE
 * fail-closed gate ([com.zcode.android.domain.state.PermissionStateMachine.AnswerPolicy]).
 */
@Composable
fun ApprovalCard(
    interaction: PendingInteraction,
    busy: Boolean,
    onAnswer: (optionId: String, freeText: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val payload = interaction.permission ?: return
    // B2.13: keyed on the interaction — a second request landing in the same card
    // slot must not inherit the first one's armed confirm or typed refine text.
    var confirmOption by remember(interaction.interactionId) { mutableStateOf<PermissionOption?>(null) }
    var refineText by remember(interaction.interactionId) { mutableStateOf("") }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("approval_card_${interaction.interactionId}"),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.approval_needed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = payload.toolName,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            payload.summary?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            payload.detail?.let { detail ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail.toString().take(400),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 6,
                )
            }
            Spacer(Modifier.height(10.dp))
            // §20.4: EXACTLY the protocol's options — recognized ids actionable with
            // their consequence spelled out; unknown ids INERT ("unsupported option").
            for (option in payload.options) {
                val label = consequenceLabel(option, payload)
                when (option.semantics) {
                    PermissionOption.Semantics.ALLOW_ONCE, PermissionOption.Semantics.ALLOW_PROJECT -> {
                        ZCodeButton(
                            onClick = { onAnswer(option.optionId, null) },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                // §30: approval actions ≥ 56 dp.
                                .heightIn(min = 56.dp)
                                .testTag("approval_${option.optionId}")
                                .semantics { contentDescription = label },
                        ) { Text(label) }
                    }
                    PermissionOption.Semantics.ALLOW_ALWAYS, PermissionOption.Semantics.DENY -> {
                        // §20.2: confirm step for allowAlways-class; Deny ALWAYS confirms.
                        ZCodeOutlinedButton(
                            onClick = { confirmOption = option },
                            enabled = !busy,
                            colors = if (option.semantics == PermissionOption.Semantics.DENY) {
                                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                // §30: approval actions ≥ 56 dp.
                                .heightIn(min = 56.dp)
                                .testTag("approval_${option.optionId}")
                                .semantics { contentDescription = label },
                        ) { Text(label) }
                    }
                    PermissionOption.Semantics.REFINE -> {
                        OutlinedTextField(
                            value = refineText,
                            onValueChange = { refineText = it },
                            placeholder = { Text(stringResource(R.string.approval_refine_hint)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approval_refine_text"),
                        )
                        ZCodeButton(
                            onClick = { onAnswer(option.optionId, refineText) },
                            enabled = !busy && refineText.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approval_refine_send"),
                        ) { Text(stringResource(R.string.approval_refine_send)) }
                    }
                    // §20.4 row 2: unknown/custom ids render inert — NEVER sent.
                    PermissionOption.Semantics.OTHER -> {
                        ZCodeOutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approval_unsupported_${option.optionId}"),
                        ) { Text(stringResource(R.string.approval_unsupported)) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    confirmOption?.let { option ->
        AlertDialog(
            onDismissRequest = { confirmOption = null },
            title = { Text(stringResource(if (option.semantics == PermissionOption.Semantics.DENY) R.string.approval_deny_confirm_title else R.string.approval_allow_confirm_title)) },
            text = {
                Text(
                    if (option.semantics == PermissionOption.Semantics.DENY) {
                        stringResource(R.string.approval_deny_confirm_body)
                    } else {
                        stringResource(R.string.approval_allow_confirm_body)
                    }
                )
            },
            confirmButton = {
                ZCodeButton(
                    onClick = {
                        confirmOption = null
                        onAnswer(option.optionId, null)
                    },
                    modifier = Modifier.testTag("approval_confirm_yes"),
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOption = null }, modifier = Modifier.testTag("approval_confirm_no")) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
            },
        )
    }
}

/** §20.2: every option renders its consequence, not just its protocol label. */
@Composable
private fun consequenceLabel(option: PermissionOption, payload: PendingInteraction.PermissionPayload): String =
    when (option.semantics) {
        PermissionOption.Semantics.ALLOW_ONCE -> stringResource(R.string.consequence_allow_once)
        PermissionOption.Semantics.ALLOW_ALWAYS -> stringResource(R.string.consequence_allow_always)
        PermissionOption.Semantics.ALLOW_PROJECT -> stringResource(R.string.consequence_allow_project)
        PermissionOption.Semantics.DENY -> stringResource(R.string.consequence_deny)
        PermissionOption.Semantics.REFINE -> stringResource(R.string.consequence_refine)
        PermissionOption.Semantics.OTHER -> stringResource(R.string.consequence_other)
    }

// ------------------------------------------------------------------ user-input card

/**
 * §20.1 U9 plane: prompt + the request's own option set (answered verbatim via
 * `answer {optionId}` — the CLI-verified compat path) or free text when the
 * payload allows. Question-array payloads render read-only with guidance
 * (multi-question content shape is CLI-side — not guessed here).
 */
@Composable
fun UserInputCard(
    interaction: PendingInteraction,
    busy: Boolean,
    onAnswer: (optionId: String, freeText: String?) -> Unit,
    modifier: Modifier = Modifier,
    onAnswerQuestions: (Map<String, List<String>>) -> Unit = {},
) {
    val payload = interaction.userInput ?: return
    // B2.13: keyed on the interaction — selections never leak across requests.
    var selected by remember(interaction.interactionId) { mutableStateOf<String?>(null) }
    var freeText by remember(interaction.interactionId) { mutableStateOf("") }
    // AskUserQuestion plane: per-question selected option values + custom answers.
    val qSelections = remember(interaction.interactionId) { androidx.compose.runtime.mutableStateMapOf<String, List<String>>() }
    val qCustom = remember(interaction.interactionId) { androidx.compose.runtime.mutableStateMapOf<String, String>() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("userinput_card_${interaction.interactionId}"),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.userinput_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            payload.prompt?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (payload.questions.isNotEmpty()) {
                // AskUserQuestion plane (verified ElicitationDialog shape): selectable
                // options per question + optional custom answer; multiSelect honored.
                for ((qIndex, q) in payload.questions.withIndex()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = q.header,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.testTag("userinput_q_header_$qIndex"),
                    )
                    Text(
                        text = q.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    for (option in q.options) {
                        val checked = qSelections[q.question].orEmpty().contains(option.value)
                        val multi = q.multiSelect == true
                        // B2.4: the ROW is the touch target (≥48dp) with the RIGHT
                        // affordance — checkbox for multi-select, a radio that SELECTS
                        // (never toggles off) for single-select. The inner control is
                        // display-only; the row owns the toggle.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("userinput_q${qIndex}_opt_${option.value.take(24)}")
                                .selectable(
                                    selected = checked,
                                    enabled = !busy,
                                    role = if (multi) Role.Checkbox else Role.RadioButton,
                                ) {
                                    val current = qSelections[q.question].orEmpty()
                                    qSelections[q.question] = if (multi) {
                                        if (checked) current.filter { it != option.value } else current + option.value
                                    } else {
                                        listOf(option.value)
                                    }
                                },
                        ) {
                            if (multi) {
                                Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
                            } else {
                                RadioButton(selected = checked, onClick = null, enabled = !busy)
                            }
                            Text(
                                text = option.label.ifBlank { option.value },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = qCustom[q.question] ?: "",
                        onValueChange = { qCustom[q.question] = it },
                        placeholder = { Text(stringResource(R.string.userinput_own_answer)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("userinput_q${qIndex}_custom"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                ZCodeButton(
                    onClick = {
                        val answers = buildMap {
                            for (q in payload.questions) {
                                val values = buildList {
                                    addAll(qSelections[q.question].orEmpty())
                                    qCustom[q.question]?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
                                }
                                if (values.isNotEmpty()) put(q.question, values)
                            }
                        }
                        if (answers.isNotEmpty()) onAnswerQuestions(answers)
                    },
                    enabled = !busy && payload.questions.any { q ->
                        qSelections[q.question].orEmpty().isNotEmpty() || !qCustom[q.question].isNullOrBlank()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("userinput_questions_submit"),
                ) { Text(stringResource(R.string.userinput_send_answers)) }
            } else if (payload.options.isNotEmpty()) {
                for (option in payload.options) {
                    // B2.4: the whole row is the touch target — the label was dead
                    // (~20dp radio-only) before.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("userinput_option_${option.optionId}")
                            .selectable(
                                selected = selected == option.optionId,
                                enabled = !busy,
                                role = Role.RadioButton,
                            ) { selected = option.optionId },
                    ) {
                        RadioButton(
                            selected = selected == option.optionId,
                            onClick = null,
                            enabled = !busy,
                        )
                        Text(
                            text = option.optionId,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ZCodeButton(
                    onClick = { selected?.let { onAnswer(it, null) } },
                    enabled = !busy && selected != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("userinput_submit"),
                ) { Text(stringResource(R.string.userinput_send_answer)) }
            } else if (payload.freeText == true) {
                OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    placeholder = { Text(stringResource(if (payload.sensitive == true) R.string.userinput_sensitive_placeholder else R.string.userinput_own_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("userinput_free_text"),
                )
                Spacer(Modifier.height(8.dp))
                ZCodeButton(
                    onClick = { onAnswer("", freeText) },
                    enabled = !busy && freeText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("userinput_submit"),
                ) { Text(stringResource(R.string.userinput_send_answer)) }
            } else {
                Text(
                    text = stringResource(R.string.userinput_desktop_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ queue strip

/** §7.5 queue display + edit (sendQueuedNow / editQueueItem — CAS-guarded upstream). */
@Composable
fun QueueStrip(
    queue: QueueState,
    busy: Boolean,
    onSendNow: (queueItemId: String) -> Unit,
    onEdit: (queueItemId: String, newText: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.items.isEmpty()) return
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("queue_strip"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val pausedSuffix = stringResource(R.string.queue_paused_suffix)
            Text(
                text = buildString {
                    append(stringResource(R.string.queue_label, queue.items.size))
                    if (queue.autoDrain == false) append(pausedSuffix)
                    queue.pauseReason?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (item in queue.items) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = item.text.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("queue_item_${item.queueItemId}"),
                    )
                    TextButton(
                        onClick = { onSendNow(item.queueItemId) },
                        enabled = !busy,
                        modifier = Modifier.testTag("queue_send_now_${item.queueItemId}"),
                    ) { Text(stringResource(R.string.queue_send_now)) }
                    TextButton(
                        onClick = { editing = item.queueItemId to item.text },
                        enabled = !busy,
                        modifier = Modifier.testTag("queue_edit_${item.queueItemId}"),
                    ) { Text(stringResource(R.string.queue_edit)) }
                }
            }
        }
    }
    editing?.let { (id, text) ->
        var newText by remember(id) { mutableStateOf(text) }
        // B3-sheets: the queue item edit is a bottom sheet, not a dialog.
        ZCodeModalBottomSheet(onDismissRequest = { editing = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.queue_edit_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("queue_edit_text"),
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editing = null }) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    ZCodeButton(
                        onClick = {
                            onEdit(id, newText)
                            editing = null
                        },
                        modifier = Modifier.testTag("queue_edit_save"),
                    ) { Text(stringResource(R.string.action_save)) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ composer

/**
 * §17.4/§17.6/§18.3 composer: draft-bound input (Room-backed, debounced upstream),
 * send blocked OFFLINE with the explicit copy (never a silent queue), stop control
 * from the authoritative `control.canStop`, input-routing hint (§9 inputRouting).
 */
@Composable
fun ComposerBar(
    state: ConversationUiState,
    onDraftChange: (String) -> Unit,
    onSend: (heldQueueDisposition: String?) -> Unit,
    onStop: () -> Unit,
    onRetrySend: (commandId: String) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
    onUploadAttachment: suspend (fileName: String, mime: String, bytes: ByteArray) -> ConversationPort.UploadResult =
        { _, _, _ -> ConversationPort.UploadResult.Failed("attachments unavailable") },
    onRemoveAttachment: (uploadId: String) -> Unit = {},
    onRetryAttachment: (uploadId: String) -> Unit = {},
    /** §43.2-11.5 voice experiment; tests inject a fake (null = platform, availability-gated). */
    voiceInputOverride: com.zcode.android.features.conversation.voice.VoiceInputPort? = null,
) {
    val haptics = rememberZCodeHaptics()
    val control = state.sessionState?.control
    val routing = state.sessionState?.inputRouting
    var dispositionDialog by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Routing hint (verified inputRouting modes, §9).
            if (routing?.mode == com.zcode.android.domain.model.InputRouting.Mode.ENQUEUE) {
                Text(
                    text = stringResource(R.string.composer_busy_queued),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("composer_route_hint"),
                )
            }
            if (state.pendingSends.isNotEmpty()) {
                PendingSendsStrip(pending = state.pendingSends, onRetry = onRetrySend, modifier = Modifier.fillMaxWidth())
            }
            // §23.2 composer attachments (Phase 9): picker → v4 transaction (begin/
            // chunks/commit with progress; abort on remove). Media picker + document
            // picker only — NO broad storage permission (§27).
            val scope = rememberCoroutineScope()
            val context = androidx.compose.ui.platform.LocalContext.current
            val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        val (name, mime, bytes) = context.readPickedFile(uri)
                        onUploadAttachment(name, mime, bytes)
                    }
                }
            }
            val docPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        val (name, mime, bytes) = context.readPickedFile(uri)
                        onUploadAttachment(name, mime, bytes)
                    }
                }
            }
            if (state.composerAttachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = state.composerAttachments,
                    onRemove = onRemoveAttachment,
                    onRetry = onRetryAttachment,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // GAP-12: the "/" catalog menu — visible while the draft is a bare
            // slash COMMAND TOKEN (no whitespace yet). Picking inserts into the
            // draft via onDraftChange (the field stays the single state source).
            if (state.draft.startsWith("/") && !state.draft.contains(' ')) {
                SlashCommandMenu(
                    commands = state.slashCommands,
                    query = state.draft,
                    onPick = onDraftChange,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // §43.2-11.5 voice input EXPERIMENT (#305): platform recognizer, availability-
            // gated, dictates into the draft (final results only — never partial noise).
            val voice = remember(voiceInputOverride) {
                (
                    voiceInputOverride
                        ?: com.zcode.android.features.conversation.voice.PlatformVoiceInput(context)
                    )
                    .takeIf { it.available }
            }
            var voiceError by remember { mutableStateOf(false) }
            // B2.2: dictation merges into the CURRENT field value — the callback must
            // not append to the draft captured when the mic was tapped (text typed
            // while listening was discarded).
            val currentDraft by androidx.compose.runtime.rememberUpdatedState(state.draft)
            val conductor = stringResource(R.string.composer_attach_image)
            val attachFileDesc = stringResource(R.string.composer_attach_file)
            val stopDesc = stringResource(R.string.composer_stop)
            val sendDesc = stringResource(R.string.composer_send)
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(stringResource(R.string.composer_placeholder)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("composer_field"),
                    maxLines = 5,
                )
                Spacer(Modifier.width(8.dp))
                if (voice != null) {
                    val voiceDesc = stringResource(R.string.composer_voice)
                    IconButton(
                        onClick = {
                            voiceError = false
                            if (voice.listening) {
                                voice.stop()
                            } else {
                                voice.start(
                                    onPartial = {},
                                    onFinal = { spoken ->
                                        if (spoken.isNotBlank()) {
                                            val base = currentDraft
                                            onDraftChange(base + (if (base.isBlank()) "" else " ") + spoken)
                                        }
                                    },
                                    onError = { voiceError = true },
                                )
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("composer_voice")
                            .semantics { contentDescription = voiceDesc },
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = null,
                            tint = if (voice.listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // §23.2 attach affordance (Phase 9): image first, then any document.
                IconButton(
                    onClick = {
                        photoPicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    enabled = state.connected,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("composer_attach")
                        .semantics { contentDescription = conductor },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
                IconButton(
                    onClick = { docPicker.launch(arrayOf("*/*")) },
                    enabled = state.connected,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("composer_attach_doc")
                        .semantics { contentDescription = attachFileDesc },
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null)
                }
                // §7.5 stop: visible only from the AUTHORITATIVE control block.
                if (control?.canStop == true && control.stopState == com.zcode.android.domain.model.SessionControl.StopState.STOPPABLE) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("composer_stop")
                            .semantics { contentDescription = stopDesc },
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(
                    onClick = {
                        if (routing?.mode == com.zcode.android.domain.model.InputRouting.Mode.CHOICE) {
                            dispositionDialog = true
                        } else {
                            haptics.confirm()
                            onSend(null)
                        }
                    },
                    enabled = state.draft.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("composer_send")
                        .semantics { contentDescription = sendDesc },
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                }
            }
            if (voice?.listening == true) {
                Text(
                    text = stringResource(R.string.composer_voice_listening),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("composer_voice_listening"),
                )
            }
            if (voiceError) {
                Text(
                    text = stringResource(R.string.composer_voice_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("composer_voice_error"),
                )
            }
            state.sendNotice?.let { SendNoticeBar(it, onDismiss = onDismissNotice, modifier = Modifier.fillMaxWidth()) }
        }
    }

    if (dispositionDialog) {
        // Verified `choice` routing (held queue): the desktop asks the client to
        // disposition the queue — clear it or keep it (sendText.heldQueueDisposition).
        AlertDialog(
            onDismissRequest = { dispositionDialog = false },
            title = { Text(stringResource(R.string.queue_held_title)) },
            text = { Text(stringResource(R.string.queue_held_body)) },
            confirmButton = {
                ZCodeButton(
                    onClick = {
                        dispositionDialog = false
                        haptics.confirm()
                        onSend("clearQueueAndSend")
                    },
                    modifier = Modifier.testTag("composer_disposition_clear"),
                ) { Text(stringResource(R.string.queue_clear_send)) }
            },
            dismissButton = {
                ZCodeOutlinedButton(
                    onClick = {
                        dispositionDialog = false
                        haptics.confirm()
                        onSend("keepQueueAndSend")
                    },
                    modifier = Modifier.testTag("composer_disposition_keep"),
                ) { Text(stringResource(R.string.queue_keep_send)) }
            },
        )
    }
}

/** §14.2/§14.3 pending sends — truthful state; retry is user-gated (INV-4). */
@Composable
fun PendingSendsStrip(
    pending: List<ConversationPort.PendingSendView>,
    onRetry: (commandId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("pending_sends_strip")) {
        for (send in pending) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (send.state) {
                        "SENDING" -> stringResource(R.string.pending_sending)
                        "SENT_UNKNOWN" -> stringResource(R.string.pending_sent_unknown)
                        else -> stringResource(R.string.pending_not_sent)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pending_send_${send.commandId.take(12)}"),
                )
                if (send.state != "SENDING") {
                    TextButton(onClick = { onRetry(send.commandId) }, modifier = Modifier.testTag("pending_send_retry")) {
                        Text(stringResource(com.zcode.android.core.ui.R.string.action_retry))
                    }
                }
            }
        }
    }
}

/** §33 user copy for send outcomes; dismissible. */
@Composable
fun SendNoticeBar(notice: SendNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag("send_notice"),
        color = when (notice.kind) {
            SendNotice.Kind.WARNING -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            SendNotice.Kind.ERROR -> MaterialTheme.colorScheme.errorContainer
            SendNotice.Kind.INFO -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
            Text(
                text = sendNoticeText(notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("send_notice_dismiss")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_dismiss), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** §23.2: read a content-URI pick into (name, mime, bytes) — no persistent permission needed. */
private fun android.content.Context.readPickedFile(uri: android.net.Uri): Triple<String, String, ByteArray> {
    val name = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "attachment"
    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("cannot read the picked file")
    return Triple(name, mime, bytes)
}

/** C5-i18n: the notice copy per locale (the view model's [SendNotice.text] is the English fallback). */
@Composable
fun sendNoticeText(notice: SendNotice): String {
    val key = notice.key ?: return notice.text
    val res = when (key) {
        SendNotice.Key.SWITCH_OFFLINE -> R.string.notice_switch_offline
        SendNotice.Key.SESSION_CHANGED -> R.string.notice_session_changed
        SendNotice.Key.SWITCH_REFUSED -> R.string.notice_switch_refused
        SendNotice.Key.MODEL_SWITCH_REFUSED -> R.string.notice_model_switch_refused
        SendNotice.Key.ATTACHMENT_TOO_LARGE -> R.string.notice_attachment_too_large
        SendNotice.Key.ATTACHMENT_OFFLINE -> R.string.notice_attachment_offline
        SendNotice.Key.ATTACHMENT_FAILED -> R.string.notice_attachment_failed
        SendNotice.Key.ATTACHMENT_RETRY_FAILED -> R.string.notice_attachment_retry_failed
        SendNotice.Key.COMPACT_REFUSED -> R.string.notice_compact_refused
        SendNotice.Key.GOAL_REFUSED -> R.string.notice_goal_refused
        SendNotice.Key.GOAL_RESUME_REFUSED -> R.string.notice_goal_resume_refused
    }
    val args = if (key == SendNotice.Key.ATTACHMENT_FAILED) {
        listOf(notice.args.firstOrNull()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.notice_connection_lost))
    } else {
        notice.args
    }
    return stringResource(res, *args.toTypedArray())
}
