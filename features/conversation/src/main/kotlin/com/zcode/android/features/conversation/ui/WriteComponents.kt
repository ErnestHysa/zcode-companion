package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import com.zcode.android.core.ui.components.minRowTouchTarget

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
    /** G6 (vc51): keep-waiting — pauses the interaction's auto-resolution (null = hidden). */
    onSnooze: (() -> Unit)? = null,
    /** G77 pin seam — injected clock for deterministic countdown assertions. */
    clockNow: () -> Long = System::currentTimeMillis,
) {
    val payload = interaction.permission ?: return
    // B2.13: keyed on the interaction — a second request landing in the same card
    // slot must not inherit the first one's armed confirm or typed refine text.
    var confirmOption by remember(interaction.interactionId) { mutableStateOf<PermissionOption?>(null) }
    var refineText by remember(interaction.interactionId) { mutableStateOf("") }
    // G77 (vc52 Phase 7): the desktop's auto-resolution fuse. A live visibleCountdown
    // ticks on the card; an expired fuse renders the auto-continued face and strips
    // the actions (no actionable card); a snoozed fuse says so; hiddenGrace shows
    // nothing (the desktop hides its countdown there too).
    val auto = interaction.autoResolution
    var fuseNowMs by remember(interaction.interactionId) { mutableLongStateOf(clockNow()) }
    androidx.compose.runtime.LaunchedEffect(interaction.interactionId, auto) {
        if (auto?.state == com.zcode.android.domain.model.AutoResolution.State.VISIBLE_COUNTDOWN) {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                fuseNowMs = clockNow()
            }
        }
    }
    val fuseExpired = auto?.expiredAt(fuseNowMs) == true

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
            // G77: the fuse face — countdown while live, the auto-continued marker once
            // expired, the snoozed confirmation when "Keep waiting" was used.
            AutoResolutionFuseFace(
                interactionId = interaction.interactionId,
                auto = auto,
                fuseNowMs = fuseNowMs,
                fuseExpired = fuseExpired,
            )
            // G6 + G77: "Keep waiting" is state-aware — offered only while the fuse is
            // LIVE (the desktop offers nothing in hiddenGrace and "Snoozed" once snoozed).
            val snoozeLive = auto == null || auto.state == com.zcode.android.domain.model.AutoResolution.State.VISIBLE_COUNTDOWN
            if (onSnooze != null && snoozeLive && !fuseExpired) {
                TextButton(
                    onClick = onSnooze,
                    enabled = !busy,
                    modifier = Modifier.testTag("interaction_snooze_${interaction.interactionId}"),
                ) {
                    Text(stringResource(R.string.interaction_snooze))
                }
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
            // G77: an expired fuse renders NO action buttons — the desktop already
            // auto-continued; answering is meaningless (and the coordinator typed the
            // interaction EXPIRED, so a tap could only ever be AlreadyResolved).
            if (!fuseExpired) {
                // §20.4: EXACTLY the protocol's options — recognized ids actionable with
                // their consequence spelled out; unknown ids INERT ("unsupported option").
                for (option in payload.options) {
                    // G76: the runtime-named option renders ITS OWN label (the scope
                    // rides the option); legacy ids keep the consequence copy.
                    val label = option.label?.takeIf { it.isNotBlank() && option.semantics != PermissionOption.Semantics.OTHER }
                        ?: consequenceLabel(option, payload)
                    val description = permissionDescription(option, payload)
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
                    description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("approval_desc_${option.optionId}"),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            } // G77: end of the not-expired action plane
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

/**
 * G77 (vc52 Phase 7): the fuse face under the card header — the desktop's
 * AskUserQuestion auto-resolution made visible on the phone:
 *  - live `visibleCountdown` → the ticking "Auto-continues in {n}s" line
 *    (the desktop's `chat.elicitation.countdownSeconds` "{seconds}s");
 *  - expired → the desktop's auto-continue marker verbatim
 *    (`chat.askQuestion.autoContinued`: "No answer; continued automatically") —
 *    the card is non-actionable from here on;
 *  - `snoozed` → the snoozed confirmation ("Keep waiting" was used);
 *  - `hiddenGrace`/absent → nothing (the desktop hides the countdown there too).
 */
@Composable
private fun AutoResolutionFuseFace(
    interactionId: String,
    auto: com.zcode.android.domain.model.AutoResolution?,
    fuseNowMs: Long,
    fuseExpired: Boolean,
) {
    when {
        auto == null -> Unit
        fuseExpired -> Text(
            text = stringResource(R.string.interaction_auto_continued),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag("interaction_expired_$interactionId"),
        )
        auto.state == com.zcode.android.domain.model.AutoResolution.State.VISIBLE_COUNTDOWN &&
            auto.deadlineAtMs != null -> {
            val seconds = countdownSecondsRemaining(auto.deadlineAtMs!!, fuseNowMs)
            if (seconds > 0) {
                Text(
                    text = stringResource(R.string.interaction_auto_continue_in, seconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .testTag("interaction_countdown_$interactionId"),
                )
            }
        }
        auto.state == com.zcode.android.domain.model.AutoResolution.State.SNOOZED -> Text(
            text = stringResource(R.string.interaction_snoozed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag("interaction_snoozed_$interactionId"),
        )
        // hiddenGrace / UNKNOWN: the fuse exists but shows nothing yet — never guessed.
        else -> Unit
    }
}

/** G77: the fuse's remaining whole seconds at [nowMs] (never negative — 0 = fired). */
internal fun countdownSecondsRemaining(deadlineAtMs: Long, nowMs: Long): Long =
    ((deadlineAtMs - nowMs) / 1000L).coerceAtLeast(0)

/**
 * G76 (vc52 Phase 12): the SCOPE / per-kind description under an allow option —
 * the desktop's chat.permission.*.description tables. The scope copy wins (the
 * runtime NAMED the scope); the unscoped always falls to the tool family
 * (command | file | generic — the desktop's A.scope derivation).
 */
@Composable
private fun permissionDescription(option: PermissionOption, payload: PendingInteraction.PermissionPayload): String? = when {
    option.scope == com.zcode.android.domain.model.PermissionScope.PROJECT -> stringResource(R.string.perm_desc_project)
    option.scope == com.zcode.android.domain.model.PermissionScope.CUA_PROJECT -> stringResource(R.string.perm_desc_cua)
    option.scope == com.zcode.android.domain.model.PermissionScope.SESSION -> stringResource(R.string.perm_desc_session)
    option.scope == com.zcode.android.domain.model.PermissionScope.FULL_ACCESS -> stringResource(R.string.perm_desc_full_access)
    option.semantics == PermissionOption.Semantics.ALLOW_ALWAYS -> {
        val tool = payload.toolName.lowercase()
        when {
            tool.contains("bash") || tool.contains("terminal") || tool.contains("exec") -> stringResource(R.string.perm_desc_command)
            tool.contains("edit") || tool.contains("write") || tool.contains("file") -> stringResource(R.string.perm_desc_file)
            else -> stringResource(R.string.perm_desc_generic)
        }
    }
    else -> null
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
 *
 * N-BG-1 (vc52 Phase 9; carries N-BF-3): the answer BODY scrolls in its own
 * viewport (the caller caps the card's height inside the pinned budget) and the
 * submit/decline row is PINNED at the card's bottom edge — the three-group card
 * used to bury Submit four inner scrolls deep behind the pinned stack's own
 * scroll surface (the gesture boundary between "scroll the card" and "cycle the
 * stack" was invisible and usually wrong).
 */
@Composable
fun UserInputCard(
    interaction: PendingInteraction,
    busy: Boolean,
    onAnswer: (optionId: String, freeText: String?) -> Unit,
    modifier: Modifier = Modifier,
    onAnswerQuestions: (Map<String, List<String>>) -> Unit = {},
    /** A3.14 (Phase 5B): decline — the resolveInteraction `action: "decline"` arm. */
    onDecline: (() -> Unit)? = null,
    /** G6 (vc51): keep-waiting — pauses the interaction's auto-resolution (null = hidden). */
    onSnooze: (() -> Unit)? = null,
    /** G77 pin seam — injected clock for deterministic countdown assertions. */
    clockNow: () -> Long = System::currentTimeMillis,
) {
    val payload = interaction.userInput ?: return
    // B2.13: keyed on the interaction — selections never leak across requests.
    var selected by remember(interaction.interactionId) { mutableStateOf<String?>(null) }
    var freeText by remember(interaction.interactionId) { mutableStateOf("") }
    // G77: the same fuse face the approval card renders (see ApprovalCard).
    val auto = interaction.autoResolution
    var fuseNowMs by remember(interaction.interactionId) { mutableLongStateOf(clockNow()) }
    androidx.compose.runtime.LaunchedEffect(interaction.interactionId, auto) {
        if (auto?.state == com.zcode.android.domain.model.AutoResolution.State.VISIBLE_COUNTDOWN) {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                fuseNowMs = clockNow()
            }
        }
    }
    val fuseExpired = auto?.expiredAt(fuseNowMs) == true
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.userinput_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                // G6 + G77: "Keep waiting" only while the fuse is LIVE (or unset).
                val snoozeLive = auto == null || auto.state == com.zcode.android.domain.model.AutoResolution.State.VISIBLE_COUNTDOWN
                if (onSnooze != null && snoozeLive && !fuseExpired) {
                    TextButton(
                        onClick = onSnooze,
                        enabled = !busy,
                        modifier = Modifier.testTag("interaction_snooze_${interaction.interactionId}"),
                    ) {
                        Text(stringResource(R.string.interaction_snooze))
                    }
                }
            }
            // G77: the fuse face — countdown / auto-continued / snoozed / nothing.
            AutoResolutionFuseFace(
                interactionId = interaction.interactionId,
                auto = auto,
                fuseNowMs = fuseNowMs,
                fuseExpired = fuseExpired,
            )
            Spacer(Modifier.height(6.dp))
            // G77: an expired fuse renders NO answer plane (the desktop auto-continued).
            if (!fuseExpired) {
                // N-BG-1: the scrollable answer body — capped by the card's own height
                // (the pinned budget share); short content lays out at its intrinsic.
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .testTag("userinput_body"),
                ) {
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
                    } else if (payload.freeText == true) {
                        OutlinedTextField(
                            value = freeText,
                            onValueChange = { freeText = it },
                            placeholder = { Text(stringResource(if (payload.sensitive == true) R.string.userinput_sensitive_placeholder else R.string.userinput_own_placeholder)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("userinput_free_text"),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.userinput_desktop_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // N-BG-1: the PINNED action row — never scrolls away with the body.
                // The decline arm (A3.14) shares the row (one compact footer — the
                // pinned budget must fit header + footer + a real body viewport).
                if (payload.questions.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                .weight(1f)
                                .testTag("userinput_questions_submit"),
                        ) { Text(stringResource(R.string.userinput_send_answers)) }
                        if (onDecline != null) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = onDecline,
                                enabled = !busy,
                                modifier = Modifier
                                    .testTag("userinput_decline"),
                            ) { Text(stringResource(R.string.userinput_decline)) }
                        }
                    }
                } else if (payload.options.isNotEmpty() || payload.freeText == true) {
                    ZCodeButton(
                        onClick = {
                            if (payload.freeText == true) onAnswer("", freeText)
                            else selected?.let { onAnswer(it, null) }
                        },
                        enabled = !busy && if (payload.freeText == true) freeText.isNotBlank() else selected != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("userinput_submit"),
                    ) { Text(stringResource(R.string.userinput_send_answer)) }
                }
            } // G77: end of the not-expired answer plane
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
    // ---- I1 queue (Phase 5A): reorder (move up/down → reorderQueueItem), drain toggle, follow-up mode ----
    onMove: ((queueItemId: String, delta: Int) -> Unit)? = null,
    onSetAutoDrain: ((Boolean) -> Unit)? = null,
    followupMode: String? = null,
    onSetFollowupMode: ((String) -> Unit)? = null,
    /** G4 (vc51): `deleteQueueItem` — the desktop ConversationQueuePanel's onDeleteItem. */
    onDelete: ((queueItemId: String) -> Unit)? = null,
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = buildString {
                        append(stringResource(R.string.queue_label, queue.items.size))
                        if (queue.autoDrain == false) append(pausedSuffix)
                        queue.pauseReason?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // I1: the desktop's auto-drain toggle (setAutoDrain) — the wire echo (`autoDrain`) is the truth.
                // N-BC-1 (vc52 Phase 9): the label + switch share ONE toggleable row —
                // the switch had no label association (TalkBack read nothing).
                val autoDrain = queue.autoDrain
                if (onSetAutoDrain != null && autoDrain != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .toggleable(
                                value = autoDrain,
                                enabled = !busy,
                                role = Role.Switch,
                                onValueChange = { onSetAutoDrain(it) },
                            )
                            .testTag("queue_auto_drain_row"),
                    ) {
                        Text(stringResource(R.string.queue_auto_send), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        androidx.compose.material3.Switch(
                            checked = autoDrain,
                            onCheckedChange = null,
                            enabled = !busy,
                            modifier = Modifier.testTag("queue_auto_drain"),
                        )
                    }
                }
            }
            // I2.6: follow-up steering — Queue (hold follow-ups) vs Guide (steer the running turn).
            if (onSetFollowupMode != null && followupMode != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                    for (mode in listOf("queue", "guide")) {
                        androidx.compose.material3.FilterChip(
                            selected = followupMode == mode,
                            onClick = { if (followupMode != mode) onSetFollowupMode(mode) },
                            enabled = !busy,
                            label = { Text(stringResource(if (mode == "queue") R.string.queue_followup_queue else R.string.queue_followup_guide), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("queue_followup_$mode"),
                        )
                    }
                }
            }
            queue.items.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    // I1: reorder — up/down produce the exact `reorderQueueItem` a drag-drop would.
                    onMove?.let { move ->
                        androidx.compose.material3.IconButton(
                            onClick = { move(item.queueItemId, -1) },
                            enabled = !busy && index > 0,
                            // N-BC-3: the reorder buttons were 32dp — under the 48dp width floor.
                            modifier = Modifier.size(48.dp).testTag("queue_move_up_${item.queueItemId}"),
                        ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.queue_move_up), modifier = Modifier.size(18.dp)) }
                        androidx.compose.material3.IconButton(
                            onClick = { move(item.queueItemId, +1) },
                            enabled = !busy && index < queue.items.lastIndex,
                            modifier = Modifier.size(48.dp).testTag("queue_move_down_${item.queueItemId}"),
                        ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.queue_move_down), modifier = Modifier.size(18.dp)) }
                    }
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
                    // G4 (vc51): the queue row's Delete — ConversationQueuePanel.tsx:33-38 wires
                    // onDeleteItem to the deleteQueueItem command; the phone's other four verbs
                    // went live at pass 11 and delete stayed modeled-never-sent.
                    onDelete?.let { remove ->
                        TextButton(
                            onClick = { remove(item.queueItemId) },
                            enabled = !busy,
                            modifier = Modifier.testTag("queue_delete_${item.queueItemId}"),
                        ) { Text(stringResource(R.string.queue_delete)) }
                    }
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
    /** G49+G50: the send trio — the held-queue disposition (CHOICE routing), the
     *  ONE-SHOT requestedDelivery override, and the CAS queue ids the user saw. */
    onSend: (heldQueueDisposition: String?, requestedDelivery: String?, expectedHeldQueueItemIds: List<String>?) -> Unit,
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
    /** Phase 4C (C2): fired when the "/" menu becomes visible — the extras load lazily then. */
    onSlashMenuOpen: () -> Unit = {},
    /** Phase 4C (C7): the @-mention file search (the port's workspace file search).
     *  GAP-21-A: [refresh] mirrors the desktop's lastMissQuery REFRESH re-query. */
    fileSearchProvider: suspend (query: String, refresh: Boolean) -> List<String> = { _, _ -> emptyList() },
    /** G65 (vc52 Phase 12): the non-file mention categories (sessions/skills/plugins/
     * agents) — (category, query) -> options; null = the files-only face (unchanged). */
    mentionCategoriesProvider: (suspend (category: String, query: String) -> List<MentionOption>)? = null,
    /** G9 (vc51): the shared-context chip's discard (null = hidden). */
    onDiscardSharedContext: (() -> Unit)? = null,
) {
    val haptics = rememberZCodeHaptics()
    val control = state.sessionState?.control
    val routing = state.sessionState?.inputRouting
    var dispositionDialog by remember { mutableStateOf(false) }
    // N2.2: the queue state rides TWO places like the desktop — the placeholder swaps
    // to the follow-up-queue text AND an inline pill marks the queued send. The old
    // standalone busy strip is gone (replaced by this pair).
    val enqueue = routing?.mode == com.zcode.android.domain.model.InputRouting.Mode.ENQUEUE

    // G49 (vc52 Phase 13): the per-send delivery override — the desktop's
    // sendText.requestedDelivery ("startNow"|"queue"|"guide"). ONE-SHOT: it rides
    // the NEXT send (button or IME) and clears — the session's own routing mode
    // decides every other send.
    var deliveryOverride by remember { mutableStateOf<String?>(null) }
    var deliveryMenuOpen by remember { mutableStateOf(false) }

    /** G50: the queued ids beside the disposition choice (the CAS set). */
    fun heldQueueIds(): List<String>? =
        state.sessionState?.queue?.items?.map { it.queueItemId }?.takeIf { it.isNotEmpty() }

    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        // F-CHAT-FOCUS (2026-10-27): vertical 8→5 — the composer is pinned always-on;
        // two padding steps at 440 dpi were ~21 px of chat viewport for nothing.
        Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
            if (state.pendingSends.isNotEmpty()) {
                PendingSendsStrip(pending = state.pendingSends, onRetry = onRetrySend, modifier = Modifier.fillMaxWidth())
            }
            // G9: the shared-context handover chip — the session's import rides the
            // next send as context_refs (max 1); ✕ dispatches discardSharedContext.
            val sharedImport = state.sessionState?.sharedContextImport
            if (sharedImport != null && sharedImport.sendable) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .testTag("shared_context_chip"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.shared_context_chip, sharedImport.title.take(48)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    onDiscardSharedContext?.let { cb ->
                        TextButton(
                            onClick = cb,
                            modifier = Modifier.testTag("shared_context_discard"),
                        ) {
                            Text(stringResource(R.string.shared_context_discard))
                        }
                    }
                }
            }
            // §23.2 composer attachments (Phase 9): picker → v4 transaction (begin/
            // chunks/commit with progress; abort on remove). Media picker + document
            // picker only — NO broad storage permission (§27).
            // K3: photo / file / camera / paste / voice fold into ONE add-menu.
            // B2.14: the bytes are read withContext(Dispatchers.IO) — the picker
            // callback ran them on the MAIN thread (ANR class).
            val scope = rememberCoroutineScope()
            val context = androidx.compose.ui.platform.LocalContext.current
            fun onPicked(uri: android.net.Uri?) {
                if (uri == null) return
                scope.launch {
                    val (name, mime, bytes) = context.readPickedAttachment(uri)
                    onUploadAttachment(name, mime, bytes)
                }
            }
            // G31 (vc51 Phase 4C): the sketch sheet rides the SAME upload transaction
            // as the pickers — Done hands PNG bytes to onUploadAttachment.
            var showSketch by remember { mutableStateOf(false) }
            if (showSketch) {
                SketchSheet(
                    onDismiss = { showSketch = false },
                    onDone = { bytes ->
                        showSketch = false
                        scope.launch {
                            onUploadAttachment(
                                "sketch-${System.currentTimeMillis()}.png",
                                "image/png",
                                bytes,
                            )
                        }
                    },
                )
            }
            // GAP-A9: one dispatch path for the send BUTTON and the IME send action.
            fun dispatchSend() {
                if (routing?.mode == com.zcode.android.domain.model.InputRouting.Mode.CHOICE) {
                    // G50: the disposition dialog; the queued ids the user sees ride
                    // the choice as the desktop's expectedHeldQueueItemIds CAS set,
                    // alongside any G49 one-shot override.
                    dispositionDialog = true
                } else {
                    haptics.confirm()
                    onSend(null, deliveryOverride, null)
                    deliveryOverride = null // one-shot
                }
            }

            // A8 (vc51 rig, exit gate): the registry fix un-blocked the launches, and the
            // Take-photo arm then exposed the SECOND crash class — strict camera apps
            // (com.android.camera2 on atd images) deny callers without android.permission
            // .CAMERA, and launch() throws SYNCHRONOUSLY (SecurityException /
            // ActivityNotFoundException). A picker arm must never kill the process:
            // every launch is guarded and degrades to the inline camera-unavailable copy.
            var attachError by remember { mutableStateOf(false) }
            fun launchPick(launch: () -> Unit) {
                attachError = false
                runCatching { launch() }.onFailure { attachError = true }
            }
            val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
            ) { uri -> onPicked(uri) }
            val docPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri -> onPicked(uri) }
            // I5: camera capture via the TakePicture contract — the JPEG lands in the
            // FileProvider-shared cache dir (file_paths "shared"; never raw file://).
            val cameraUri = remember { java.util.concurrent.atomic.AtomicReference<android.net.Uri?>(null) }
            val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
            ) { ok -> if (ok) cameraUri.get()?.let { onPicked(it) } }
            val cameraItem = remember {
                runCatching {
                    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                    val out = java.io.File(dir, "camera-${System.currentTimeMillis()}.jpg")
                    androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
                }.getOrNull()
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
            // Phase 4C (C7): @-mentions — a trailing "@token" (no space yet) queries the
            // workspace file search; picking inserts the path in place of the token.
            // GAP-21-A (vc52 Phase 10): the trigger is the DESKTOP's parsed regex pair
            // (MentionTrigger — first-token/@-after-Han widened, empty query allowed)
            // over the whole draft; the old `substringAfterLast(' ', "")` returned the
            // EMPTY missing-default for any draft without a space, so a first-token
            // mention (`@re`) computed no token and the panel could never appear.
            val mention = MentionTrigger.parse(state.draft)?.takeIf { it.trigger == '@' }
            var mentionHits by remember { mutableStateOf<List<String>>(emptyList()) }
            // Keyed on the DRAFT (not the token value): re-entering the identical token
            // re-queries (the pass-21 staleness papercut). An EMPTY result retries ONCE
            // with refresh=true before the miss is accepted (the desktop's
            // lastMissQuery REFRESH retry, BA10.5.2) — bounded, never a re-trigger loop.
            LaunchedEffect(state.draft) {
                if (mention == null) {
                    mentionHits = emptyList()
                } else {
                    kotlinx.coroutines.delay(150)
                    var hits = runCatching { fileSearchProvider(mention.query, false) }.getOrDefault(emptyList())
                    if (hits.isEmpty()) {
                        kotlinx.coroutines.delay(250)
                        hits = runCatching { fileSearchProvider(mention.query, true) }.getOrDefault(emptyList())
                    }
                    mentionHits = hits
                }
            }
            // G65 (vc52 Phase 12): the desktop's category-FIRST mention menu — Files
            // keeps the existing provider verbatim (GAP-21-A behavior + pins); the
            // other four ride the category provider with the desktop's insert shapes.
            var mentionCategory by remember { mutableStateOf("files") }
            var categoryHits by remember { mutableStateOf<List<MentionOption>>(emptyList()) }
            LaunchedEffect(state.draft, mentionCategory) {
                val provider = mentionCategoriesProvider
                if (mention == null || provider == null || mentionCategory == "files") {
                    categoryHits = emptyList()
                } else {
                    kotlinx.coroutines.delay(120)
                    categoryHits = runCatching { provider(mentionCategory, mention.query) }.getOrDefault(emptyList())
                }
            }
            if (mention != null && mentionCategoriesProvider != null) {
                MentionCategoryBar(
                    active = mentionCategory,
                    onSelect = { mentionCategory = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (mention != null && mentionCategory != "files" && categoryHits.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("mention_menu"),
                ) {
                    Column {
                        categoryHits.take(6).forEach { opt ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minRowTouchTarget()
                                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                        onDraftChange(
                                            state.draft.removeRange(mention.start, state.draft.length) + "${opt.insert} ",
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("mention_item_${mentionCategory}_${opt.label}"),
                            ) {
                                Column {
                                    Text(
                                        opt.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    opt.detail?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (mention != null && mentionCategory != "files" && categoryHits.isEmpty()) {
                Unit // the category miss: no rows (the desktop's empty face is per-category copy)
            }
            if (mention != null && mentionCategory == "files" && mentionHits.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("mention_menu"),
                ) {
                    Column {
                        mentionHits.take(6).forEach { path ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minRowTouchTarget()
                                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                        // GAP-21-A: replace from the parsed trigger index —
                                        // exact for the first-token and Han-prefixed shapes
                                        // (substringBeforeLast("@") also worked, but only
                                        // by accident of the single-char trigger).
                                        onDraftChange(
                                            state.draft.removeRange(mention.start, state.draft.length) + "$path ",
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("mention_file_$path"),
                            ) {
                                Text(
                                    path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            val slashMenuVisible = state.draft.startsWith("/") && !state.draft.contains(' ')
            if (slashMenuVisible) {
                // C2: the extras (skills / agents / custom commands) load on FIRST open.
                LaunchedEffect(Unit) { onSlashMenuOpen() }
                // N-BD-1 (vc52 Phase 9): the palette is an ANCHORED OVERLAY — in-flow
                // it consumed the composer's remaining height (under the armed pinned
                // stack the shell measured to zero: Send unreachable while the draft
                // matched "/"). The anchor is a zero-size Box at the shell's top; the
                // popup is TopStart-aligned with an offset of its own MEASURED height,
                // so its bottom lands EXACTLY on the anchor = the composer's top — the
                // palette floats ABOVE the composer at any stack weight (deterministic:
                // BottomStart alignment mis-anchored on device). The draft losing the
                // "/" prefix dismisses it; outside taps pass through.
                val density = androidx.compose.ui.platform.LocalDensity.current
                var paletteHeightPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                Box(Modifier.testTag("slash_menu_anchor")) {
                    androidx.compose.ui.window.Popup(
                        alignment = Alignment.TopStart,
                        offset = androidx.compose.ui.unit.IntOffset(0, -paletteHeightPx),
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnClickOutside = false,
                            dismissOnBackPress = false,
                        ),
                    ) {
                        SlashCommandMenu(
                            commands = state.slashCommands,
                            query = state.draft,
                            onPick = onDraftChange,
                            extras = state.slashExtras,
                            modifier = Modifier.onSizeChanged { paletteHeightPx = it.height },
                        )
                    }
                }
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
            // F-34-D: WHICH voice failure — a missing microphone grant is not "unavailable
            // on this device" (the false blame the recognizer's permission refusal produced).
            var voiceError by remember { mutableStateOf<VoiceError?>(null) }
            // B2.2: dictation merges into the CURRENT field value — the callback must
            // not append to the draft captured when the mic was tapped (text typed
            // while listening was discarded).
            val currentDraft by androidx.compose.runtime.rememberUpdatedState(state.draft)
            fun startDictation(v: com.zcode.android.features.conversation.voice.VoiceInputPort) {
                voiceError = null
                v.start(
                    onPartial = {},
                    onFinal = { spoken ->
                        if (spoken.isNotBlank()) {
                            val base = currentDraft
                            onDraftChange(base + (if (base.isBlank()) "" else " ") + spoken)
                        }
                    },
                    onError = { code ->
                        voiceError = if (code == com.zcode.android.features.conversation.voice.VoiceInputPort.ERROR_INSUFFICIENT_PERMISSIONS) {
                            VoiceError.MIC_PERMISSION
                        } else {
                            VoiceError.UNAVAILABLE
                        }
                    },
                )
            }
            // F-34-D: the microphone grant is requested AT the Dictate tap (the direct-request
            // pattern the camera uses — ask at the moment of use, never upfront). The
            // registry launcher is safe since GAP-A8's fragment bump (16-bit request codes).
            val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { granted ->
                val v = voice
                if (granted && v != null) startDictation(v) else voiceError = VoiceError.MIC_PERMISSION
            }
            val conductor = stringResource(R.string.composer_add_desc)
            var addMenu by remember { mutableStateOf(false) }
            // I5: paste-image chip — an image-typed clipboard offers the last capture.
            var pasteImage by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                pasteImage = clipboardHasImage(context)
            }
            if (pasteImage) {
                ComposerPasteChip(visible = true, onPaste = {
                    pasteImage = false
                    scope.launch {
                        clipboardImageUri(context)?.let { uri ->
                            val (name, mime, bytes) = context.readPickedAttachment(uri)
                            onUploadAttachment(name, mime, bytes)
                        }
                    }
                })
            }
            // K3: the ONE designed input shell — the field, the add-menu and the
            // single primary control live inside one bordered surface (the desktop's
            // ChatPromptEditor shape), not five flat icon buttons on a flat bar.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(com.zcode.android.core.ui.theme.ZCodeRadius.xl),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = onDraftChange,
                        keyboardOptions = composerKeyboardOptions(state.draft),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = { dispatchSend() },
                        ),
                        placeholder = {
                            Text(
                                stringResource(
                                    if (enqueue) R.string.composer_placeholder_queue else R.string.composer_placeholder,
                                ),
                            )
                        },
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            // N-BE-3 (vc52 Phase 10): the desktop's composerSend binding
                            // ported to hardware keyboards — Ctrl/Cmd+Enter dispatches;
                            // plain Enter keeps its newline. onPreviewKeyEvent sees the
                            // key BEFORE the field's default Enter-newline handling; the
                            // IME Send action (GAP-A9) is untouched.
                            .onPreviewKeyEvent { event ->
                                handleComposerSendKey(event) { dispatchSend() }
                            }
                            .testTag("composer_field"),
                        maxLines = 5,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // ONE add affordance: photo / file / camera / paste / voice.
                        Box {
                            androidx.compose.material3.FilledTonalIconButton(
                                onClick = { addMenu = true },
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("composer_attach")
                                    .semantics { contentDescription = conductor },
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                            }
                            androidx.compose.material3.DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.composer_menu_mention_file)) },
                                    onClick = {
                                        addMenu = false
                                        onDraftChange(currentDraft + if (currentDraft.endsWith(" ") || currentDraft.isEmpty()) "@" else " @")
                                    },
                                    modifier = Modifier.testTag("composer_menu_mention"),
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.composer_menu_sketch)) },
                                    onClick = {
                                        addMenu = false
                                        showSketch = true
                                    },
                                    modifier = Modifier.testTag("composer_menu_sketch"),
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.composer_menu_photo)) },
                                    onClick = {
                                        addMenu = false
                                        launchPick {
                                            photoPicker.launch(
                                                androidx.activity.result.PickVisualMediaRequest(
                                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                                                ),
                                            )
                                        }
                                    },
                                    modifier = Modifier.testTag("composer_attach_photo"),
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(R.string.composer_attach_file)) },
                                    onClick = {
                                        addMenu = false
                                        launchPick { docPicker.launch(arrayOf("*/*")) }
                                    },
                                    modifier = Modifier.testTag("composer_attach_doc"),
                                )
                                if (cameraItem != null) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.composer_menu_camera)) },
                                        onClick = {
                                            addMenu = false
                                            cameraUri.set(cameraItem)
                                            launchPick { cameraLauncher.launch(cameraItem) }
                                        },
                                        modifier = Modifier.testTag("composer_attach_camera"),
                                    )
                                }
                                if (voice != null) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(stringResource(R.string.composer_menu_voice)) },
                                        onClick = {
                                            addMenu = false
                                            voiceError = null
                                            when {
                                                voice.listening -> voice.stop()
                                                voice.requiresMicPermission &&
                                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                                        context,
                                                        android.Manifest.permission.RECORD_AUDIO,
                                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                                                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                                                else -> startDictation(voice)
                                            }
                                        },
                                        modifier = Modifier.testTag("composer_voice_menu"),
                                    )
                                }
                            }
                        }
                        if (voice != null && voice.listening) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.composer_voice_listening),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("composer_voice_listening"),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // N2.2 place 2: the inline queued pill (placeholder is place 1).
                        if (enqueue) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.testTag("composer_queue_pill"),
                            ) {
                                Text(
                                    text = stringResource(R.string.composer_queue_pill),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        // §7.5 K3: ONE primary control that MORPHS — the authoritative
                        // `control.canStop` gating is preserved verbatim: the slot shows
                        // STOP only while the wire says stoppable, else SEND.
                        // GAP-A9 (desktop ConversationComposer.tsx:1136 contract):
                        // showStopControl = canStop && !hasDraftToSubmit — a typed draft
                        // ALWAYS keeps the send path, even mid-run (queue mode exists
                        // precisely to dispatch one); the Stop control owns the slot
                        // only for a blank draft.
                        val hasDraftToSubmit = currentDraft.isNotBlank() || state.composerAttachments.isNotEmpty()
                        val stoppable = control?.canStop == true &&
                            control.stopState == com.zcode.android.domain.model.SessionControl.StopState.STOPPABLE &&
                            !hasDraftToSubmit
                        if (stoppable) {
                            val stopDesc = stringResource(R.string.composer_stop)
                            androidx.compose.material3.FilledIconButton(
                                onClick = onStop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("composer_stop")
                                    .semantics { contentDescription = stopDesc },
                                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = null)
                            }
                        } else {
                            val sendDesc = stringResource(R.string.composer_send)
                            // N-BH-1: the Send gate matches the morph's hasDraftToSubmit
                            // contract — a READY staged attachment is submittable with a
                            // blank draft (the sketch flow); the morph itself keeps the
                            // broader isNotEmpty rule (GAP-A9 pin unchanged).
                            val sendable = state.draft.isNotBlank() ||
                                state.composerAttachments.any { it.state == "ready" && it.ref != null }
                            // G49: the per-send delivery menu — a one-shot override
                            // ("send this one NOW even though the session queues", and
                            // the reverse). The icon tint marks an armed override.
                            androidx.compose.foundation.layout.Box {
                                androidx.compose.material3.IconButton(
                                    onClick = { deliveryMenuOpen = true },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("composer_delivery_menu"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Send,
                                        contentDescription = stringResource(R.string.composer_delivery_desc),
                                        tint = if (deliveryOverride != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = deliveryMenuOpen,
                                    onDismissRequest = { deliveryMenuOpen = false },
                                ) {
                                    for ((value, tag, labelRes) in listOf(
                                        Triple("startNow", "composer_delivery_startnow", R.string.composer_delivery_startnow),
                                        Triple("queue", "composer_delivery_queue", R.string.composer_delivery_queue),
                                        Triple("guide", "composer_delivery_guide", R.string.composer_delivery_guide),
                                    )) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(labelRes),
                                                    fontWeight = if (deliveryOverride == value) FontWeight.Bold else FontWeight.Normal,
                                                )
                                            },
                                            onClick = {
                                                deliveryMenuOpen = false
                                                deliveryOverride = if (deliveryOverride == value) null else value
                                            },
                                            modifier = Modifier.testTag(tag),
                                        )
                                    }
                                }
                            }
                            androidx.compose.material3.FilledIconButton(
                                onClick = { dispatchSend() },
                                enabled = sendable,
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("composer_send")
                                    .semantics { contentDescription = sendDesc },
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = null)
                            }
                        }
                    }
                }
            }
            if (attachError) {
                Text(
                    text = stringResource(R.string.composer_camera_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("composer_attach_error"),
                )
            }
            when (voiceError) {
                VoiceError.MIC_PERMISSION -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("composer_voice_error"),
                ) {
                    Text(
                        text = stringResource(R.string.composer_voice_mic_permission),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f, fill = false).testTag("composer_voice_permission"),
                    )
                    // A "don't ask again" denial never re-prompts — the app's own system
                    // page is the way back to a working microphone.
                    androidx.compose.material3.TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", context.packageName, null),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.testTag("composer_voice_settings"),
                    ) {
                        Text(stringResource(R.string.composer_voice_open_settings))
                    }
                }
                VoiceError.UNAVAILABLE -> Text(
                    text = stringResource(R.string.composer_voice_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("composer_voice_error"),
                )
                null -> Unit
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
                        onSend("clearQueueAndSend", deliveryOverride, heldQueueIds())
                        deliveryOverride = null
                    },
                    modifier = Modifier.testTag("composer_disposition_clear"),
                ) { Text(stringResource(R.string.queue_clear_send)) }
            },
            dismissButton = {
                ZCodeOutlinedButton(
                    onClick = {
                        dispositionDialog = false
                        haptics.confirm()
                        onSend("keepQueueAndSend", deliveryOverride, heldQueueIds())
                        deliveryOverride = null
                    },
                    modifier = Modifier.testTag("composer_disposition_keep"),
                ) { Text(stringResource(R.string.queue_keep_send)) }
            },
        )
    }
}

/** §14.2/§14.3 pending sends — truthful state; retry is user-gated (INV-4). */
/**
 * G12 (vc51): the pending-command recovery banner — the desktop's
 * PendingCommandRecoveryBanner.tsx shape. The persisted outbox's replayable set
 * (UNKNOWN input-class entries that survive process death and conversation
 * exits) surfaces here with a working replay action and honest copy; the
 * in-memory pending overlay can never be this surface. A dismissal is
 * view-local and per-set: a NEW pending command re-raises the banner.
 */
@Composable
fun PendingCommandRecoveryBanner(
    pending: List<ConversationPort.RecoverableCommand>,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Dismiss view-local: the set's commandIds at dismiss time; a changed set re-raises.
    var dismissedSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    val visible = pending.filter { it.commandId !in dismissedSet }
    if (visible.isEmpty()) return
    Surface(
        modifier = modifier.testTag("pending_recovery_banner"),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (visible.size == 1)
                        stringResource(R.string.recovery_banner_one)
                    else
                        stringResource(R.string.recovery_banner_many, visible.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("pending_recovery_copy"),
                )
                Text(
                    text = visible.first().text.take(80),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onReplay, modifier = Modifier.testTag("pending_recovery_replay")) {
                Text(stringResource(R.string.recovery_replay))
            }
            TextButton(onClick = { dismissedSet = pending.map { it.commandId }.toSet() }) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }
}

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

/** N-BE-4: the notice CONTENT colour by kind — INFO is NOT an error. Pure + pinnable. */
internal fun sendNoticeContentColor(kind: SendNotice.Kind, scheme: androidx.compose.material3.ColorScheme): androidx.compose.ui.graphics.Color =
    when (kind) {
        SendNotice.Kind.WARNING, SendNotice.Kind.ERROR -> scheme.onErrorContainer
        SendNotice.Kind.INFO -> scheme.onSurfaceVariant
    }

/** §33 user copy for send outcomes; dismissible. */
@Composable
fun SendNoticeBar(notice: SendNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // N-BE-4 (vc52 Phase 10): the text + dismiss used onErrorContainer for EVERY
    // kind — the INFO success "Trusted N hook(s)" rendered in error red on the
    // neutral surface (GAP-A15 fixed the container only). Colour by KIND.
    val contentColor = sendNoticeContentColor(notice.kind, MaterialTheme.colorScheme)
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
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("send_notice_dismiss")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_dismiss), tint = contentColor)
            }
        }
    }
}

/**
 * §23.2: read a content-URI pick into (name, mime, bytes) — no persistent permission
 * needed. B2.14: ALWAYS [Dispatchers.IO] — the picker callback used to read the bytes
 * on the MAIN thread (ANR class).
 */
internal suspend fun android.content.Context.readPickedAttachment(uri: android.net.Uri): Triple<String, String, ByteArray> =
    withContext(Dispatchers.IO) { readPickedFileSync(uri) }

internal fun android.content.Context.readPickedFileSync(uri: android.net.Uri): Triple<String, String, ByteArray> {
    val name = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "attachment"
    val mime = contentResolver.getType(uri)
        ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.')?.lowercase() ?: "")
        ?: "application/octet-stream"
    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("cannot read the picked file")
    return Triple(name, mime, bytes)
}

/**
 * I5: does the clipboard carry an image? F-33-F: probes the clip DESCRIPTION only — never
 * the data. Reading another app's clip pulls it (passwords included) into this process,
 * notes READ_CLIPBOARD and, on Android 12+, raises the system "pasted from your clipboard"
 * toast; this runs on every conversation open. The data is read only on the Paste tap
 * ([clipboardImageUri]).
 */
internal fun clipboardHasImage(context: android.content.Context): Boolean = runCatching {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        ?: return false
    val description = cm.primaryClipDescription ?: return false
    // Manual prefix match (hasMimeType's wildcard path varies by platform/shadow).
    (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }
}.getOrDefault(false)

/** The clipboard image's URI — reads the clip DATA, so only an explicit Paste tap calls it (F-33-F). */
internal fun clipboardImageUri(context: android.content.Context): android.net.Uri? = runCatching {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    // Manual prefix match (hasMimeType's wildcard path varies by platform/shadow).
    var hasImage = false
    for (i in 0 until clip.description.mimeTypeCount) {
        if (clip.description.getMimeType(i).startsWith("image/")) hasImage = true
    }
    if (!hasImage) return null
    clip.getItemAt(0)?.uri
}.getOrNull()

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
        SendNotice.Key.GOAL_PAUSE_REFUSED -> R.string.notice_goal_pause_refused
        // Phase 4A row actions
        SendNotice.Key.ROW_ACTION_OFFLINE -> R.string.notice_row_action_offline
        SendNotice.Key.RETRY_REFUSED -> R.string.notice_retry_refused
        SendNotice.Key.FORK_REFUSED -> R.string.notice_fork_refused
        // N-BD-3: the LOCAL-gate verdicts (no wire was sent — never "refused").
        SendNotice.Key.ROW_ACTION_LOCAL_GATE -> R.string.notice_row_action_local_gate
        SendNotice.Key.FORK_LOCAL_GATE -> R.string.notice_fork_local_gate
        SendNotice.Key.REWIND_APPLIED -> R.string.notice_rewind_applied
        SendNotice.Key.REWIND_REFUSED -> R.string.notice_rewind_refused
        SendNotice.Key.FEEDBACK_REFUSED -> R.string.notice_feedback_refused
        SendNotice.Key.EFFORT_REFUSED -> R.string.notice_effort_refused
        // Phase 5A (L6.4 + I1)
        SendNotice.Key.WORKFLOW_STARTED -> R.string.notice_workflow_started
        SendNotice.Key.WORKFLOW_REFUSED -> R.string.notice_workflow_refused
        // T2: the resolved-elsewhere answer, per locale
        SendNotice.Key.ANSWER_ALREADY_RESOLVED -> R.string.notice_answer_already_resolved
        // GAP-A15 (pass 13): the hook-trust SUCCESS copy (the old ROW_ACTION_OFFLINE key
        // rendered the fixed offline banner for a successful trust).
        SendNotice.Key.HOOK_TRUST_APPLIED -> R.string.notice_hook_trust_applied
        // G52 (vc52 Phase 11): the registry-fallback model transition (typed INFO).
        SendNotice.Key.MODEL_TRANSITION -> R.string.notice_model_transition
    }
    val args = if (key == SendNotice.Key.ATTACHMENT_FAILED) {
        listOf(notice.args.firstOrNull()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.notice_connection_lost))
    } else {
        notice.args
    }
    return stringResource(res, *args.toTypedArray())
}

/**
 * I5: one-tap paste of the clipboard image (the picker pipeline reused). Shown only
 * while the clipboard carries an image (the composer probes it on open).
 */
@Composable
fun ComposerPasteChip(visible: Boolean, onPaste: () -> Unit, modifier: Modifier = Modifier) {
    if (!visible) return
    val desc = stringResource(R.string.composer_menu_paste_image)
    TextButton(
        onClick = onPaste,
        modifier = modifier
            .testTag("composer_attach_paste_chip")
            .semantics { contentDescription = desc },
    ) {
        Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.composer_menu_paste_image))
    }
}

/**
 * L5-slash (Phase 6C): the composer's IME options derive from the draft — a LEADING
 * slash is command context, not prose: no word-suggestions (autocorrect off), no
 * capitalization, ASCII (the slash catalog is ASCII). Everything else keeps the
 * platform defaults (bilingual prose typing must not regress).
 */
/** N-BE-3 (vc52 Phase 10): Ctrl/Cmd+Enter dispatches (the desktop composerSend binding);
 *  plain Enter is NOT consumed — the newline is kept. See the composer field's wiring. */
internal fun handleComposerSendKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    dispatchSend: () -> Unit,
): Boolean {
    if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return false
    if (event.key != androidx.compose.ui.input.key.Key.Enter &&
        event.key != androidx.compose.ui.input.key.Key.NumPadEnter
    ) return false
    if (!event.isCtrlPressed && !event.isMetaPressed) return false
    dispatchSend()
    return true
}

fun composerKeyboardOptions(draft: String): androidx.compose.foundation.text.KeyboardOptions =
    if (draft.startsWith("/")) {
        androidx.compose.foundation.text.KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
            imeAction = androidx.compose.ui.text.input.ImeAction.Send,
        )
    } else {
        androidx.compose.foundation.text.KeyboardOptions(
            // GAP-A9: the IME's send action dispatches (the desktop's Enter-to-send);
            // the recorded rig artifact was Enter inserting a raw newline instead.
            imeAction = androidx.compose.ui.text.input.ImeAction.Send,
        )
    }

/** The autocorrect flag across compose-ui's autoCorrect → autoCorrectEnabled rename. */
fun composerAutoCorrectEnabled(options: androidx.compose.foundation.text.KeyboardOptions): Boolean =
    options.autoCorrectEnabled ?: options.autoCorrect

/** F-34-D: why dictation did not start/finish — the copy must name the real cause. */
internal enum class VoiceError { MIC_PERMISSION, UNAVAILABLE }

/** G65 (vc52 Phase 12): one non-file mention hit — the insert text is the desktop's
 * serialization (session `#id`, skill `$name`, agent `@name`, plugin markdown link). */
data class MentionOption(val label: String, val detail: String?, val insert: String)

/** The desktop's mention categories in value order (chat.mention.*); Files first. */
private val MENTION_CATEGORIES = listOf("files", "sessions", "skills", "plugins", "agents")

@Composable
private fun MentionCategoryBar(active: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(bottom = 4.dp)
            .testTag("mention_categories"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MENTION_CATEGORIES.forEach { category ->
            Surface(
                color = if (category == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .minRowTouchTarget()
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { onSelect(category) }
                    .testTag("mention_category_$category"),
            ) {
                Text(
                    category.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (category == active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}
