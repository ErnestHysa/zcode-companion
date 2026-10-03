package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    var confirmOption by remember { mutableStateOf<PermissionOption?>(null) }
    var refineText by remember { mutableStateOf("") }

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
                    text = "Approval needed",
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
                        Button(
                            onClick = { onAnswer(option.optionId, null) },
                            enabled = !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approval_${option.optionId}")
                                .semantics { contentDescription = label },
                        ) { Text(label) }
                    }
                    PermissionOption.Semantics.ALLOW_ALWAYS, PermissionOption.Semantics.DENY -> {
                        // §20.2: confirm step for allowAlways-class; Deny ALWAYS confirms.
                        OutlinedButton(
                            onClick = { confirmOption = option },
                            enabled = !busy,
                            colors = if (option.semantics == PermissionOption.Semantics.DENY) {
                                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approval_${option.optionId}")
                                .semantics { contentDescription = label },
                        ) { Text(label) }
                    }
                    PermissionOption.Semantics.REFINE -> {
                        OutlinedTextField(
                            value = refineText,
                            onValueChange = { refineText = it },
                            placeholder = { Text("Refinement (required)") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approval_refine_text"),
                        )
                        Button(
                            onClick = { onAnswer(option.optionId, refineText) },
                            enabled = !busy && refineText.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approval_refine_send"),
                        ) { Text("Send refinement") }
                    }
                    // §20.4 row 2: unknown/custom ids render inert — NEVER sent.
                    PermissionOption.Semantics.OTHER -> {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approval_unsupported_${option.optionId}"),
                        ) { Text("Unsupported option — answer on the desktop") }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    confirmOption?.let { option ->
        AlertDialog(
            onDismissRequest = { confirmOption = null },
            title = { Text(if (option.semantics == PermissionOption.Semantics.DENY) "Deny this request?" else "Allow always?") },
            text = {
                Text(
                    if (option.semantics == PermissionOption.Semantics.DENY) {
                        "The agent is told no for this request."
                    } else {
                        "Future identical requests will run WITHOUT asking again."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmOption = null
                        onAnswer(option.optionId, null)
                    },
                    modifier = Modifier.testTag("approval_confirm_yes"),
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOption = null }, modifier = Modifier.testTag("approval_confirm_no")) {
                    Text("Cancel")
                }
            },
        )
    }
}

/** §20.2: every option renders its consequence, not just its protocol label. */
private fun consequenceLabel(option: PermissionOption, payload: PendingInteraction.PermissionPayload): String =
    when (option.semantics) {
        PermissionOption.Semantics.ALLOW_ONCE -> "Allow once — this request only"
        PermissionOption.Semantics.ALLOW_ALWAYS -> "Allow always — identical requests stop asking"
        PermissionOption.Semantics.ALLOW_PROJECT -> "Allow for this project"
        PermissionOption.Semantics.DENY -> "Deny — the agent is told no"
        PermissionOption.Semantics.REFINE -> "Refine instead"
        PermissionOption.Semantics.OTHER -> "Unsupported option"
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
    var selected by remember { mutableStateOf<String?>(null) }
    var freeText by remember { mutableStateOf("") }
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
                text = "Agent needs your input",
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("userinput_q${qIndex}_opt_${option.value.take(24)}")
                                .clickable(enabled = !busy) {
                                    val current = qSelections[q.question].orEmpty()
                                    qSelections[q.question] = if (q.multiSelect == true) {
                                        if (checked) current.filter { it != option.value } else current + option.value
                                    } else {
                                        listOf(option.value)
                                    }
                                },
                        ) {
                            RadioButton(
                                selected = checked,
                                onClick = {
                                    val current = qSelections[q.question].orEmpty()
                                    qSelections[q.question] = if (checked) current.filter { it != option.value } else current + option.value
                                },
                                enabled = !busy,
                            )
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
                        placeholder = { Text("Or type your own answer") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("userinput_q${qIndex}_custom"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
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
                ) { Text("Send answers") }
            } else if (payload.options.isNotEmpty()) {
                for (option in payload.options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("userinput_option_${option.optionId}"),
                    ) {
                        RadioButton(
                            selected = selected == option.optionId,
                            onClick = { selected = option.optionId },
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
                Button(
                    onClick = { selected?.let { onAnswer(it, null) } },
                    enabled = !busy && selected != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("userinput_submit"),
                ) { Text("Send answer") }
            } else if (payload.freeText == true) {
                OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    placeholder = { Text(if (payload.sensitive == true) "Answer (sensitive — not saved to drafts)" else "Your answer") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("userinput_free_text"),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onAnswer("", freeText) },
                    enabled = !busy && freeText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("userinput_submit"),
                ) { Text("Send answer") }
            } else {
                Text(
                    text = "Answer this prompt on the desktop.",
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
            Text(
                text = buildString {
                    append("Queued (${queue.items.size})")
                    if (queue.autoDrain == false) append(" · paused")
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
                    ) { Text("Send now") }
                    TextButton(
                        onClick = { editing = item.queueItemId to item.text },
                        enabled = !busy,
                        modifier = Modifier.testTag("queue_edit_${item.queueItemId}"),
                    ) { Text("Edit") }
                }
            }
        }
    }
    editing?.let { (id, text) ->
        var newText by remember(id) { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit queued message") },
            text = {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("queue_edit_text"),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEdit(id, newText)
                        editing = null
                    },
                    modifier = Modifier.testTag("queue_edit_save"),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
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
) {
    val control = state.sessionState?.control
    val routing = state.sessionState?.inputRouting
    var dispositionDialog by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Routing hint (verified inputRouting modes, §9).
            if (routing?.mode == com.zcode.android.domain.model.InputRouting.Mode.ENQUEUE) {
                Text(
                    text = "Busy — this message will be queued on the desktop.",
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text("Message the agent…") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("composer_field"),
                    maxLines = 5,
                )
                Spacer(Modifier.width(8.dp))
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
                        .testTag("composer_attach")
                        .semantics { contentDescription = "Attach an image" },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
                IconButton(
                    onClick = { docPicker.launch(arrayOf("*/*")) },
                    enabled = state.connected,
                    modifier = Modifier
                        .testTag("composer_attach_doc")
                        .semantics { contentDescription = "Attach a file" },
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null)
                }
                // §7.5 stop: visible only from the AUTHORITATIVE control block.
                if (control?.canStop == true && control.stopState == com.zcode.android.domain.model.SessionControl.StopState.STOPPABLE) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .testTag("composer_stop")
                            .semantics { contentDescription = "Stop the agent" },
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                IconButton(
                    onClick = {
                        if (routing?.mode == com.zcode.android.domain.model.InputRouting.Mode.CHOICE) {
                            dispositionDialog = true
                        } else {
                            onSend(null)
                        }
                    },
                    enabled = state.draft.isNotBlank(),
                    modifier = Modifier
                        .testTag("composer_send")
                        .semantics { contentDescription = "Send message" },
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null)
                }
            }
            state.sendNotice?.let { SendNoticeBar(it, onDismiss = onDismissNotice, modifier = Modifier.fillMaxWidth()) }
        }
    }

    if (dispositionDialog) {
        // Verified `choice` routing (held queue): the desktop asks the client to
        // disposition the queue — clear it or keep it (sendText.heldQueueDisposition).
        AlertDialog(
            onDismissRequest = { dispositionDialog = false },
            title = { Text("Queue is held") },
            text = { Text("Send now and clear the queue, or keep the queued messages?") },
            confirmButton = {
                Button(
                    onClick = {
                        dispositionDialog = false
                        onSend("clearQueueAndSend")
                    },
                    modifier = Modifier.testTag("composer_disposition_clear"),
                ) { Text("Clear queue & send") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        dispositionDialog = false
                        onSend("keepQueueAndSend")
                    },
                    modifier = Modifier.testTag("composer_disposition_keep"),
                ) { Text("Keep queue & send") }
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
                        "SENDING" -> "Sending…"
                        "SENT_UNKNOWN" -> "Send uncertain — connection lost (will not duplicate)"
                        else -> "Not sent"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pending_send_${send.commandId.take(12)}"),
                )
                if (send.state != "SENDING") {
                    TextButton(onClick = { onRetry(send.commandId) }, modifier = Modifier.testTag("pending_send_retry")) {
                        Text("Retry")
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
                text = notice.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("send_notice_dismiss")) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
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
