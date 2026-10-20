package com.zcode.android.features.conversation.ui

/** I2.8: the preview-card kind, re-exported for the renderer's exhaustive `when`s. */
typealias GeneratedArtifactPreviewKind = com.zcode.android.features.conversation.GeneratedArtifactPreview.Kind

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import com.zcode.android.features.conversation.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.messageTimeLabel
import com.zcode.android.core.ui.theme.LocalZCodeColors
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.markdown.MarkdownParser
import com.zcode.android.core.ui.theme.ZCodeRadius
import com.zcode.android.core.ui.components.animateCollapse
import com.zcode.android.core.ui.components.rememberZCodeHaptics
import com.zcode.android.core.ui.components.LocalNotifier
import androidx.compose.material3.ListItem
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.ui.draw.clip
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import androidx.compose.ui.res.pluralStringResource
import com.zcode.android.core.ui.components.minTouchTarget
import com.zcode.android.core.ui.components.minRowTouchTarget
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import kotlinx.coroutines.launch

/**
 * Row renderers — the agent-timeline grammar (ui-ux component-library v2): YOU
 * blocks, AGENT prose, tool blocks with internal status rails, code blocks, quiet
 * separators. §19.5: unknown tool/kind → generic fallback, NEVER crash/blank (INV-8,
 * §48 U6). Content is untrusted (T15): markdown is rendered through the restrictive
 * parser; links render styled but never auto-open.
 */

private val YouBubbleRadius = ZCodeRadius.xl
private val ToolCardRadius = ZCodeRadius.lg

@Composable
fun YouBlock(
    text: String,
    modifier: Modifier = Modifier,
    createdAtMs: Double? = null,
    /** Desktop gate (ConversationRowView.tsx:1295): the edit entry needs entityId. */
    editable: Boolean = false,
    onEdit: (() -> Unit)? = null,
    /** A3.1 (Phase 3D): the row's own attachments — the phone sends images it could
     *  never see again; images render as thumbnails through [loadAttachment]. */
    attachments: List<ConversationRow.UserInput.AttachmentMeta> = emptyList(),
    loadAttachment: (suspend (ConversationRow.UserInput.AttachmentMeta) -> ByteArray?)? = null,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showActions by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().testTag("row_user")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(stringResource(R.string.row_you), color = MaterialTheme.colorScheme.onSurfaceVariant)
            RowTimeLabel(createdAtMs = createdAtMs, tag = "row_time_user")
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(YouBubbleRadius))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                // GAP-10 (§11.4): long-press opens the row action sheet — Copy
                // (always, the GAP-11 affordance) + Edit (only when the row carries
                // an entityId). User-initiated only (§27).
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { showActions = true },
                    )
                }
                .semantics(mergeDescendants = true) {},
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            // A3.1: attachments render in-conversation (images as thumbnails, loaded
            // once through the attachment-read channel; other files as named chips).
            if (attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    for (att in attachments) {
                        AttachmentView(att = att, load = loadAttachment)
                    }
                }
            }
        }
    }
    val haptics = rememberZCodeHaptics()
    if (showActions) {
        // B3-sheets: the row-action menu is a bottom sheet (copy / edit), not a dialog.
        ZCodeModalBottomSheet(onDismissRequest = { showActions = false }) {
            Text(
                text = stringResource(R.string.row_actions_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.action_copy)) },
                leadingContent = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable {
                        showActions = false
                        clipboard.setText(AnnotatedString(text))
                        haptics.tick()
                    }
                    .testTag("row_copy_action"),
            )
            if (editable && onEdit != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_edit)) },
                    leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            showActions = false
                            onEdit()
                        }
                        .testTag("row_edit_action"),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun AgentProse(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    createdAtMs: Double? = null,
    /** Phase 4A (E3.2+I1+N5): the row's OWN capability flags gate Fork/Retry — desktop
     *  `latestAssistantTextRow.actions?.canRetry === true` (ConversationTurnGroup.tsx:867). */
    canFork: Boolean = false,
    canRetry: Boolean = false,
    /** rows.ts `feedback` echo (like|dislike|null) — the Liked/Disliked state of record. */
    feedback: String? = null,
    onFork: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    /** like | dislike | null (clear) — null callback = feedback unavailable on this row. */
    onFeedback: ((String?) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("row_agent")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(stringResource(R.string.row_agent), color = LocalZCodeColors.current.accent)
            RowTimeLabel(createdAtMs = createdAtMs, tag = "row_time_agent")
            Spacer(Modifier.width(6.dp))
            // GAP-11: the desktop's assistant-row CopyRowAction (ConversationRowView.tsx:1339).
            CopyIconButton(text = text, tag = "row_copy_agent")
        }
        Spacer(Modifier.height(4.dp))
        // GAP-11: per-row selection (desktop = native text selection). Scoped to THIS
        // row's prose — never the whole timeline (roadmap acceptance).
        SelectionContainer(modifier = Modifier.testTag("row_selection_agent")) {
            MarkdownText(
                text = text,
                // N8: a STREAMING row is a polite live region so TalkBack announces the
                // arriving text; a completed row is not (no re-announce on scroll-back).
                modifier = Modifier.semantics(mergeDescendants = true) {
                    if (streaming) liveRegion = LiveRegionMode.Polite
                },
            )
        }
        if (streaming) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 8.dp, height = 14.dp)
                        .background(LocalZCodeColors.current.accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.row_generating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (onFeedback != null || (canFork && onFork != null) || (canRetry && onRetry != null)) {
            // Phase 4A: the completed row's action rail — the desktop's hover bar as a
            // persistent compact row (no hover on touch). Every entry is flag-gated;
            // Like/Dislike always exist on a completed row (rows.ts carries `feedback`).
            AssistantActionRow(
                feedback = feedback,
                onFeedback = onFeedback,
                onFork = if (canFork) onFork else null,
                onRetry = if (canRetry) onRetry else null,
            )
        }
    }
}

/**
 * Phase 4A (E3.2+I1+N5): Like / Dislike / Fork / Retry on a completed assistant row.
 * Labels are the desktop's chat.message.* keys (Like/Liked, Dislike/Disliked, Fork,
 * Retry). A set feedback renders FILLED + announces its state; tapping the set one
 * clears it (explicit null on the wire).
 */
@Composable
private fun AssistantActionRow(
    feedback: String?,
    onFeedback: ((String?) -> Unit)?,
    onFork: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    val haptics = rememberZCodeHaptics()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp).testTag("row_agent_actions"),
    ) {
        if (onFeedback != null) {
            val liked = feedback == "like"
            val disliked = feedback == "dislike"
            androidx.compose.material3.IconButton(
                onClick = { haptics.tick(); onFeedback(if (liked) null else "like") },
                modifier = Modifier.minTouchTarget().testTag("row_like"),
            ) {
                Icon(
                    imageVector = if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = stringResource(if (liked) R.string.row_liked else R.string.row_like),
                    tint = if (liked) LocalZCodeColors.current.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            androidx.compose.material3.IconButton(
                onClick = { haptics.tick(); onFeedback(if (disliked) null else "dislike") },
                modifier = Modifier.minTouchTarget().testTag("row_dislike"),
            ) {
                Icon(
                    imageVector = if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                    contentDescription = stringResource(if (disliked) R.string.row_disliked else R.string.row_dislike),
                    tint = if (disliked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (onFork != null) {
            TextButton(onClick = { onFork() }, modifier = Modifier.testTag("row_fork")) {
                Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.row_fork), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (onRetry != null) {
            TextButton(onClick = { onRetry() }, modifier = Modifier.testTag("row_retry")) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.row_retry), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Generic tool card (§19.5 anatomy); expandable input/output previews.
 * N2.4: a metadata line (duration) on the status row. C7: the output preview is capped
 * at [TOOL_PREVIEW_CAP] and "show full output" loads LAZILY through [fullOutputProvider]
 * (the port's slice call; null → the row's own uncapped payload).
 */
@Composable
fun ToolCard(
    row: ConversationRow.ToolCall,
    modifier: Modifier = Modifier,
    fullOutputProvider: (suspend (ConversationRow.ToolCall) -> String?)? = null,
) {
    val tokens = toolStatusPresentation(row.status)
    var expanded by rememberSaveable(row.rowId) { mutableStateOf(false) }
    var fullOutput by rememberSaveable(row.rowId) { mutableStateOf<String?>(null) }
    var loadingFull by remember(row.rowId) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val expandWord = stringResource(if (expanded) R.string.a11y_collapse else R.string.a11y_expand)
    val toolA11y = stringResource(R.string.row_tool_a11y, row.toolName, tokens.first, expandWord)
    val railColor = tokens.second
    val meta = toolMetaLine(row)
    val duration = toolDurationSeconds(row)?.let { stringResource(R.string.tool_duration_seconds, formatSeconds(it)) }
    val previewText = row.outputPreview?.toString()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalZCodeColors.current.toolSurface, RoundedCornerShape(ToolCardRadius))
            .drawBehind { drawRect(railColor, size = size.copy(width = 3.dp.toPx())) }
            .animateCollapse()
            .clickable { expanded = !expanded }
            .padding(start = 15.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            .testTag("row_tool_${row.toolName}")
            .semantics(mergeDescendants = true) {
                contentDescription = toolA11y
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.toolName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("tool_meta_${row.rowId}")) {
            Text(
                text = tokens.first,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.second,
            )
            if (duration != null) {
                Text(
                    text = " · $duration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 6 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            MonospaceDetail(label = stringResource(R.string.row_input), content = row.inputText.ifBlank { "—" })
            val full = fullOutput
            if (full != null) {
                Spacer(Modifier.height(6.dp))
                MonospaceDetail(
                    label = stringResource(R.string.tool_full_output_label),
                    content = full,
                    tag = "tool_full_output_text_${row.rowId}",
                )
            } else if (previewText != null) {
                Spacer(Modifier.height(6.dp))
                MonospaceDetail(label = stringResource(R.string.row_output), content = previewText.take(TOOL_PREVIEW_CAP))
                if (previewText.length > TOOL_PREVIEW_CAP) {
                    TextButton(
                        onClick = {
                            if (loadingFull) return@TextButton
                            loadingFull = true
                            scope.launch {
                                val fromSlice = runCatching { fullOutputProvider?.invoke(row) }.getOrNull()
                                fullOutput = fromSlice ?: previewText
                                loadingFull = false
                            }
                        },
                        modifier = Modifier.testTag("tool_full_output_${row.rowId}"),
                    ) {
                        Text(stringResource(if (loadingFull) R.string.tool_loading_full_output else R.string.tool_show_full_output))
                    }
                }
            }
            row.error?.let { err ->
                Spacer(Modifier.height(6.dp))
                MonospaceDetail(label = stringResource(R.string.row_error), content = "${err.code}: ${err.message}".take(2_000))
            }
        }
    }
}

/** C7: the on-row preview cap; anything longer loads lazily on request. */
const val TOOL_PREVIEW_CAP = 2_000

/** N2.4: wall-clock duration from the row's own timestamps (ms → s); null when absent. */
fun toolDurationSeconds(row: ConversationRow.ToolCall): Double? {
    val s = row.startedAt ?: return null
    val e = row.endedAt ?: return null
    if (e < s) return null
    return (e - s) / 1000.0
}

@Composable
fun ReasoningRow(
    row: ConversationRow.Reasoning,
    modifier: Modifier = Modifier,
) {
    // macOS parity: the thinking block streams EXPANDED while the agent is
    // thinking and collapses to a one-line summary when the block closes.
    val streaming = row.state == ConversationRow.TextState.STREAMING
    var expanded by rememberSaveable(row.rowId) { mutableStateOf(streaming) }
    LaunchedEffect(row.state) {
        if (row.state == ConversationRow.TextState.STREAMING) expanded = true
    }
    val reasoningWord = stringResource(if (expanded) R.string.a11y_collapse else R.string.a11y_expand)
    val reasoningA11y = stringResource(R.string.row_reasoning_a11y, reasoningWord)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateCollapse()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp)
            .testTag("row_reasoning")
            .semantics(mergeDescendants = true) {
                contentDescription = reasoningA11y
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.row_thinking),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = if (expanded) row.text else row.text.lineSequence().firstOrNull().orEmpty().ifBlank { "…" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) 12 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Quiet turn separator with authoritative per-turn file summary (verified turnHeader).
 * Phase 9.1: the file summary is TAPPABLE — it opens the §22 diff viewer for the turn. */
@Composable
fun TurnSeparator(
    row: ConversationRow.TurnHeader,
    modifier: Modifier = Modifier,
    onOpenChanges: () -> Unit = {},
    /** Phase 4A: applyFileRewind on this turn — offered only under the desktop's gate. */
    onRewindFiles: (() -> Unit)? = null,
    /**
     * A3.11 (Phase 5B): the rewind PREVIEW loader (`conversationFileRewindPreviewV4`) —
     * null = unavailable, the confirm falls back to the copy-only sheet (the Phase-4 pin).
     */
    rewindPreviewProvider: (suspend (rowId: Long, entityId: String) -> com.zcode.android.domain.model.RewindPreview?)? = null,
) {
    // I2.1: a turn that changed files renders as the change summary CARD (desktop
    // chat.changeSummary.*); quiet turns keep the bare separator.
    val hasChanges = (row.fileChanges?.files ?: 0) > 0
    // Desktop editWorkspaceRewindAvailability (ConversationTurnGroup.tsx:1227-1235):
    // files > 0 AND not reverted AND not running AND actions.canRewindFiles === true.
    val fcState = row.fileChanges?.state
    val rewindAvailable = onRewindFiles != null && hasChanges && fcState != "reverted" &&
        row.state != ConversationRow.TurnState.RUNNING && row.base.actions?.canRewindFiles == true
    var confirmRewind by rememberSaveable { mutableStateOf(false) }
    // A3.11: the preview loads once per open (row-anchored); loading renders its own state.
    var preview by remember { mutableStateOf<com.zcode.android.domain.model.RewindPreview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewUnavailable by remember { mutableStateOf(false) }
    val openPreview: () -> Unit = {
        confirmRewind = true
        val provider = rewindPreviewProvider
        val entityId = row.base.entityId
        if (provider != null && entityId != null && preview == null && !previewLoading && !previewUnavailable) {
            previewLoading = true
            rowScopePreviewJob(provider, row.rowId, entityId) { result ->
                previewLoading = false
                if (result == null) previewUnavailable = true else preview = result
            }
        }
    }
    val body: @Composable (Modifier) -> Unit = { m ->
        Column(modifier = m.testTag("row_turn")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(20.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(8.dp))
                val fc = row.fileChanges
                val bits = buildList {
                    add(stringResource(R.string.row_turn))
                    val activeMs = row.activeMs
                    if (activeMs != null && activeMs > 0) add("${activeMs / 1000}s")
                }
                Text(
                    text = bits.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fc != null && fc.files > 0) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .testTag("row_turn_changes")
                            .minRowTouchTarget()
                            .clickable(role = Role.Button) { onOpenChanges() },
                    ) {
                        Text(
                            text = stringResource(
                                R.string.row_turn_changes,
                                pluralStringResource(R.plurals.row_files_count, fc.files.toInt(), fc.files.toInt()),
                                fc.additions,
                                fc.deletions,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (rewindAvailable) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = openPreview, modifier = Modifier.testTag("row_turn_rewind")) {
                        Text(stringResource(R.string.row_rewind_files), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if (confirmRewind && onRewindFiles != null) {
        // B3-sheets: the destructive confirm is a bottom sheet (the architecture pin:
        // RowRenderers hosts no AlertDialog); desktop rewindDialog copy preserved.
        // A3.11: the PREVIEW upgrades the sheet — safe/unsafe/ignored files with the
        // desktop's canApply gating the Confirm; unavailable falls back to the copy-only form.
        ZCodeModalBottomSheet(onDismissRequest = { confirmRewind = false }) {
            Text(
                stringResource(R.string.rewind_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                stringResource(
                    R.string.rewind_dialog_body,
                    pluralStringResource(R.plurals.row_files_count, (row.fileChanges?.files ?: 0).toInt(), (row.fileChanges?.files ?: 0).toInt()),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            when {
                previewLoading -> LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("rewind_preview_loading"),
                )
                preview != null -> RewindPreviewBody(preview!!)
                previewUnavailable -> Text(
                    stringResource(R.string.rewind_preview_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("rewind_preview_unavailable"),
                )
                else -> Unit
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { confirmRewind = false }) {
                    Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
                }
                TextButton(
                    onClick = { confirmRewind = false; onRewindFiles() },
                    // A3.11: the desktop's canApply gates the destructive Confirm (a loaded
                    // preview with canApply=false renders the reasons and refuses to fire).
                    enabled = preview?.canApply != false,
                    modifier = Modifier.testTag("rewind_confirm"),
                ) { Text(stringResource(R.string.rewind_dialog_confirm)) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (hasChanges) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                .padding(12.dp)
                .testTag("row_turn_card"),
        ) { body(Modifier.fillMaxWidth()) }
    } else {
        body(modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
}

/**
 * I2.4 (Phase 6A): the context-compaction lifecycle notice (desktop
 * `chat.contextCompaction.*`) — a quiet centered marker line, phase copy localized,
 * token counts when the host sent them, and a Retry affordance on the failed phase
 * (the desktop's `chat.contextCompaction.retry` button).
 */
@Composable
fun CompactionNoticeRow(
    notice: com.zcode.android.domain.model.CompactionNotice,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val label = stringResource(
        when (notice.phase) {
            com.zcode.android.domain.model.CompactionNotice.Phase.STARTED -> R.string.compaction_started
            com.zcode.android.domain.model.CompactionNotice.Phase.SKIPPED -> R.string.compaction_skipped
            com.zcode.android.domain.model.CompactionNotice.Phase.INTERRUPTED -> R.string.compaction_interrupted
            com.zcode.android.domain.model.CompactionNotice.Phase.FAILED -> R.string.compaction_failed
            com.zcode.android.domain.model.CompactionNotice.Phase.COMPLETED_AUTO -> R.string.compaction_completed_auto
            com.zcode.android.domain.model.CompactionNotice.Phase.COMPLETED -> R.string.compaction_completed
        },
    )
    val failed = notice.phase == com.zcode.android.domain.model.CompactionNotice.Phase.FAILED
    val before = notice.tokensBefore
    val after = notice.tokensAfter
    val tokenDetail = if (before != null && after != null) {
        stringResource(
            R.string.compaction_tokens,
            com.zcode.android.core.ui.components.formatCompactTokenCount(before),
            com.zcode.android.core.ui.components.formatCompactTokenCount(after),
        )
    } else null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("row_compaction")
            .semantics(mergeDescendants = true) {
                contentDescription = if (tokenDetail == null) label else "$label, $tokenDetail"
                if (notice.running || failed) liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = when (notice.phase) {
                com.zcode.android.domain.model.CompactionNotice.Phase.STARTED -> Icons.Outlined.Autorenew
                com.zcode.android.domain.model.CompactionNotice.Phase.SKIPPED -> Icons.Outlined.Check
                com.zcode.android.domain.model.CompactionNotice.Phase.INTERRUPTED -> Icons.Outlined.Pause
                com.zcode.android.domain.model.CompactionNotice.Phase.FAILED -> Icons.Outlined.ErrorOutline
                else -> Icons.Outlined.Check
            },
            contentDescription = null,
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (tokenDetail != null) {
                Text(
                    text = tokenDetail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (failed) {
            TextButton(onClick = onRetry, modifier = Modifier.testTag("compaction_retry")) {
                Text(stringResource(R.string.compaction_retry))
            }
        }
        Box(Modifier.width(20.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/**
 * I2.8 (Phase 6A): the generated-artifact preview card (desktop `chat.previewCards.*`)
 * — kind icon + the desktop's subtitle copy + display name, with the open-external
 * action (download via the file channel, then the system viewer chooser).
 */
@Composable
fun ArtifactPreviewCard(
    rowId: Long,
    name: String,
    kind: GeneratedArtifactPreviewKind,
    ref: String,
    mime: String,
    modifier: Modifier = Modifier,
    /** Multiple cards on one assistant row disambiguate their tags ("_1", "_2", …). */
    tagSuffix: String = "",
    onOpen: () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("preview_card_$rowId$tagSuffix")
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (kind) {
                    GeneratedArtifactPreviewKind.MARKDOWN -> Icons.Outlined.Article
                    GeneratedArtifactPreviewKind.HTML -> Icons.Outlined.Language
                    GeneratedArtifactPreviewKind.DOCX -> Icons.Outlined.Description
                    GeneratedArtifactPreviewKind.XLSX -> Icons.Outlined.TableChart
                    GeneratedArtifactPreviewKind.PPTX -> Icons.Outlined.Slideshow
                    GeneratedArtifactPreviewKind.PDF -> Icons.Outlined.PictureAsPdf
                    GeneratedArtifactPreviewKind.AUDIO -> Icons.Outlined.GraphicEq
                    GeneratedArtifactPreviewKind.VIDEO -> Icons.Outlined.Movie
                    GeneratedArtifactPreviewKind.TEXT -> Icons.Outlined.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        when (kind) {
                            GeneratedArtifactPreviewKind.MARKDOWN -> R.string.preview_markdown
                            GeneratedArtifactPreviewKind.HTML -> R.string.preview_html
                            GeneratedArtifactPreviewKind.DOCX -> R.string.preview_docx
                            GeneratedArtifactPreviewKind.XLSX -> R.string.preview_xlsx
                            GeneratedArtifactPreviewKind.PPTX -> R.string.preview_pptx
                            GeneratedArtifactPreviewKind.PDF -> R.string.preview_pdf
                            GeneratedArtifactPreviewKind.AUDIO -> R.string.preview_audio
                            GeneratedArtifactPreviewKind.VIDEO -> R.string.preview_video
                            GeneratedArtifactPreviewKind.TEXT -> R.string.preview_text
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpen, modifier = Modifier.testTag("preview_open_$rowId$tagSuffix")) {
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.preview_open_a11y),
                )
            }
        }
    }
}

/**
 * INV-8 generic fallback for the non-MVP row kinds (subagent/hookInvocation/
 * timelineMarker/artifact) and unknown kinds: quiet, informative, never blank.
 */
@Composable
fun QuietMetaRow(
    label: String,
    detail: String?,
    modifier: Modifier = Modifier,
    tag: String = "row_other",
) {    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(tag)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (detail.isNullOrBlank()) label else "$label · $detail",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Never-crash fallback for unknown row kinds (§19.1). */
@Composable
fun UnknownKindRow(row: ConversationRow.Unknown, modifier: Modifier = Modifier) {
    QuietMetaRow(
        label = stringResource(R.string.row_unsupported),
        detail = row.unknownKind,
        modifier = modifier,
        tag = "row_unknown",
    )
}

@Composable
fun CodeBlock(code: String, lang: String? = null, modifier: Modifier = Modifier, complete: Boolean = true) {
    // H1.3: wrap toggle (desktop `codeBlock.wrapLongLines`) — default = scroll, like the
    // desktop; remembered per block so a long reply keeps each block's choice.
    var wrapped by rememberSaveable(code.hashCode(), lang) { mutableStateOf(false) }
    val highlighted = highlightedCode(code, lang)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalZCodeColors.current.codeBg, RoundedCornerShape(ToolCardRadius))
            .padding(1.dp)
            .testTag("row_code"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(LocalZCodeColors.current.codeBg, RoundedCornerShape(ZCodeRadius.md))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.row_code) + if (lang != null) " · $lang" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.IconButton(
                    onClick = { wrapped = !wrapped },
                    modifier = Modifier.minTouchTarget().testTag("row_code_wrap_toggle"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.WrapText,
                        contentDescription = stringResource(if (wrapped) R.string.row_code_scroll_lines else R.string.row_code_wrap_lines),
                        tint = if (wrapped) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                // GAP-11: per-code-block copy affordance (desktop code-block copy).
                CopyIconButton(text = code, tag = "row_copy_code")
            }
            Spacer(Modifier.height(6.dp))
            val codeStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
            )
            if (wrapped) {
                Box(Modifier.fillMaxWidth().testTag("row_code_wrapped")) {
                    Text(
                        text = highlighted,
                        style = codeStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = true,
                        modifier = Modifier.testTag("row_code_text"),
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("row_code_scroll")) {
                    Text(
                        text = highlighted,
                        style = codeStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        modifier = Modifier.testTag("row_code_text"),
                    )
                }
            }
            if (!complete) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.row_writing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * H1.3: the bounded highlighter's tokens → an AnnotatedString in theme roles (no literal
 * ARGB — J6/lint discipline): keywords = accent, strings = success, comments = muted
 * variant, numbers = info. Memoized per (code, lang) — B2.15's per-frame budget.
 */
@Composable
private fun highlightedCode(code: String, lang: String?): AnnotatedString {
    val c = LocalZCodeColors.current
    val keyword = c.accent
    val string = c.statusSuccess
    val comment = MaterialTheme.colorScheme.onSurfaceVariant
    val number = c.statusInfo
    val tokens = remember(code, lang) { com.zcode.android.features.conversation.markdown.CodeHighlighter.tokenize(lang, code) }
    return remember(tokens, keyword, string, comment, number) {
        buildAnnotatedString {
            for (t in tokens) {
                val start = length
                append(t.text)
                val color = when (t.kind) {
                    com.zcode.android.features.conversation.markdown.CodeHighlighter.Kind.KEYWORD -> keyword
                    com.zcode.android.features.conversation.markdown.CodeHighlighter.Kind.STRING -> string
                    com.zcode.android.features.conversation.markdown.CodeHighlighter.Kind.COMMENT -> comment
                    com.zcode.android.features.conversation.markdown.CodeHighlighter.Kind.NUMBER -> number
                    com.zcode.android.features.conversation.markdown.CodeHighlighter.Kind.PLAIN -> null
                }
                if (color != null) {
                    addStyle(
                        SpanStyle(
                            color = color,
                            fontStyle = if (t.kind == com.zcode.android.features.conversation.markdown.CodeHighlighter.Kind.COMMENT) androidx.compose.ui.text.font.FontStyle.Italic else null,
                        ),
                        start,
                        length,
                    )
                }
            }
        }
    }
}

/**
 * Markdown renderer over the bounded parser (ADR-015); spans are style-only.
 * B2.15: the parse is memoized per blank-line segment in ONE [MarkdownParseCache] per
 * row — a streamed delta re-parses only the segment it touched, not the whole message.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    cache: com.zcode.android.features.conversation.markdown.MarkdownParseCache =
        remember { com.zcode.android.features.conversation.markdown.MarkdownParseCache() },
) {
    val blocks = remember(text, cache) { cache.parse(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var listIndex = 0
        blocks.forEach { block ->
            when (block) {
                is MarkdownParser.Block.Paragraph -> SpannedText(block.spans, MaterialTheme.typography.bodyMedium)
                is MarkdownParser.Block.Heading -> SpannedText(
                    block.spans,
                    when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is MarkdownParser.Block.CodeFence -> CodeBlock(code = block.code, lang = block.lang, complete = block.complete)
                // H3.3/K5: nesting level → indent + a per-level marker; each item is its own row.
                is MarkdownParser.Block.ListItem -> {
                    val index = listIndex++
                    Row(
                        modifier = Modifier
                            .padding(start = (block.level * 16).dp)
                            .testTag("md_list_item_$index"),
                    ) {
                        Text(
                            text = if (block.ordered) "${block.number}." else listMarker(block.level),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(18.dp),
                        )
                        SpannedText(block.spans, MaterialTheme.typography.bodyMedium, Modifier.weight(1f))
                    }
                }
                is MarkdownParser.Block.Quote -> Row {
                    Box(Modifier.width(3.dp).height(18.dp).background(LocalZCodeColors.current.railDone))
                    Spacer(Modifier.width(8.dp))
                    SpannedText(block.spans, MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
                is MarkdownParser.Block.Table -> MarkdownTable(block)
                is MarkdownParser.Block.Rule -> androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.fillMaxWidth().testTag("md_rule"),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

private fun listMarker(level: Int): String = when (level % 3) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

/**
 * H1.3/K5: a pipe table as a real grid. Column widths are the max intrinsic cell width
 * per column (capped), rows the max cell height — one custom Layout, horizontally
 * scrollable when wider than the reader. Header row bold on a tonal band, hairlines
 * between rows. The band and the hairlines are measured CHILDREN of the same layout
 * (no draw-time geometry hand-off).
 */
@Composable
private fun MarkdownTable(table: MarkdownParser.Block.Table, modifier: Modifier = Modifier) {
    val cols = table.header.size
    if (cols == 0) return
    val allRows = listOf(table.header) + table.rows
    val rowCount = allRows.size
    val cellPad = 8.dp
    val maxCellWidth = 240.dp
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Box(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("md_table"),
    ) {
        androidx.compose.ui.layout.Layout(
            content = {
                // [0] header band, [1..rowCount] hairlines, then the cells row-major.
                Box(Modifier.background(headerBg))
                repeat(rowCount) { Box(Modifier.background(hairline)) }
                allRows.forEachIndexed { r, row ->
                    for (c in 0 until cols) {
                        val spans = row.getOrNull(c) ?: emptyList()
                        val style = if (r == 0) {
                            MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodySmall
                        }
                        Box(Modifier.padding(horizontal = cellPad, vertical = 6.dp).testTag("md_table_cell_${r}_$c")) {
                            SpannedText(spans, style)
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val cap = maxCellWidth.roundToPx()
            val cellStart = 1 + rowCount
            val cells = measurables.drop(cellStart).map { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = cap)) }
            val colWidths = IntArray(cols)
            val rowHeights = IntArray(rowCount)
            cells.forEachIndexed { idx, p ->
                val r = idx / cols
                val c = idx % cols
                if (p.width > colWidths[c]) colWidths[c] = p.width
                if (p.height > rowHeights[r]) rowHeights[r] = p.height
            }
            val width = colWidths.sum().coerceAtLeast(1)
            val height = rowHeights.sum().coerceAtLeast(1)
            val band = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(width, rowHeights.firstOrNull() ?: 0))
            val lines = (1 until cellStart).map { measurables[it].measure(androidx.compose.ui.unit.Constraints.fixed(width, 1)) }
            layout(width, height) {
                band.placeRelative(0, 0)
                var y = 0
                for (r in 0 until rowCount) {
                    y += rowHeights[r]
                    lines[r].placeRelative(0, (y - 1).coerceAtLeast(0))
                }
                y = 0
                for (r in 0 until rowCount) {
                    var x = 0
                    for (c in 0 until cols) {
                        val p = cells[r * cols + c]
                        val dx = when (table.align.getOrNull(c)) {
                            MarkdownParser.Align.RIGHT -> colWidths[c] - p.width
                            MarkdownParser.Align.CENTER -> (colWidths[c] - p.width) / 2
                            else -> 0
                        }
                        p.placeRelative(x + dx, y)
                        x += colWidths[c]
                    }
                    y += rowHeights[r]
                }
            }
        }
    }
}

@Composable
private fun SpannedText(
    spans: List<MarkdownParser.Span>,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    val codeBg = LocalZCodeColors.current.codeBorder.copy(alpha = 0.35f)
    val context = LocalContext.current
    val notifier = LocalNotifier.current
    val linkFailedText = stringResource(R.string.row_link_open_failed)
    val linkRanges = mutableListOf<Triple<Int, Int, String>>()
    val annotated = buildAnnotatedString {
        for (span in spans) {
            val start = length
            append(span.text)
            when {
                span.code -> addStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = codeBg), start, length)
                span.bold -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                span.italic -> addStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, length)
                // K5: ~~strikethrough~~ (rendered with literal tildes before).
                span.strikethrough -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
                span.linkUrl != null -> {
                    addStyle(
                        SpanStyle(color = LocalZCodeColors.current.accent, textDecoration = TextDecoration.Underline),
                        start,
                        length,
                    )
                    linkRanges.add(Triple(start, length, span.linkUrl))
                }
            }
        }
    }
    // FIELD (2026-10-12): links rendered styled but never opened — hit-test taps
    // against the link ranges and hand the URL to the browser (user-initiated
    // ACTION_VIEW; no in-app webview, no silent egress).
    var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style,
        color = style.color.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(annotated, linkRanges.toList()) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val up = waitForUpOrCancellation()
                if (up != null) {
                    val pos = up.position
                    val lr = layoutResult
                    if (lr != null) {
                        val offset = lr.getOffsetForPosition(pos)
                        linkRanges.firstOrNull { (s, e, _) -> offset in s until e }?.third?.let { url ->
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                notifier.show(linkFailedText)
                            }
                        }
                    }
                }
                down // consume gesture scope
            }
        },
    )
}

@Composable
private fun Overline(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
}

/**
 * GAP-13: the desktop renders the message time as a READ-ONLY derivation of
 * row.createdAt (ConversationRowView.tsx:1469-1472 — subtle label, no renderer
 * state). Same here: one labelSmall text in the row meta line; invalid/absent
 * timestamps render nothing (the formatter's null branch).
 */
/**
 * GAP-11: the desktop CopyRowAction as an icon button — copies the exact payload to
 * the clipboard, then flips to a check for a moment (desktop "chat.message.copy.copied"
 * feedback). User-initiated only (§27).
 */
@Composable
private fun CopyIconButton(text: String, tag: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500)
            copied = false
        }
    }
    androidx.compose.material3.IconButton(onClick = {
        clipboard.setText(AnnotatedString(text))
        copied = true
    }, modifier = Modifier.minTouchTarget().testTag(tag)) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = stringResource(if (copied) R.string.row_copied else R.string.row_copy),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RowTimeLabel(createdAtMs: Double?, tag: String) {
    if (createdAtMs == null) return
    val yesterday = stringResource(com.zcode.android.core.ui.R.string.relative_yesterday)
    // B2.15/J4: keyed on the ticking clock so "2 min ago" becomes "3 min ago" unprompted.
    val now = com.zcode.android.core.ui.components.LocalRelativeNow.current
    val label = remember(createdAtMs, yesterday, now) {
        messageTimeLabel(createdAtMs, now, yesterdayWord = yesterday)
    } ?: return
    Spacer(Modifier.width(8.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun MonospaceDetail(label: String, content: String, tag: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalZCodeColors.current.codeBg, MaterialTheme.shapes.medium)
            .padding(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
            )
        }
    }
}

/** (label, rail color) for a tool-call status — §19.5 + state-specifications.md. */
@Composable
fun toolStatusPresentation(status: ConversationRow.ToolCallStatus): Pair<String, androidx.compose.ui.graphics.Color> {
    val c = LocalZCodeColors.current
    return when (status) {
        ConversationRow.ToolCallStatus.RUNNING,
        ConversationRow.ToolCallStatus.INPUT_STREAMING,
        -> stringResource(R.string.tool_state_running) to c.railRunning
        ConversationRow.ToolCallStatus.PENDING_APPROVAL -> stringResource(R.string.tool_state_needs_approval) to c.railWaiting
        ConversationRow.ToolCallStatus.SUCCESS -> stringResource(R.string.tool_state_completed) to c.statusSuccess
        ConversationRow.ToolCallStatus.ERROR -> stringResource(R.string.tool_state_failed) to c.statusError
        ConversationRow.ToolCallStatus.CANCELLED -> stringResource(R.string.tool_state_cancelled) to c.railDone
        ConversationRow.ToolCallStatus.UNKNOWN -> stringResource(R.string.tool_state_unknown) to c.railDone
    }
}

/** One-line meta for a tool card (protocol titles when present, else input summary). */
fun toolMetaLine(row: ConversationRow.ToolCall): String {
    val input = row.inputText.trim()
    if (input.isNotBlank()) return input.lineSequence().firstOrNull().orEmpty().take(160)
    val output = row.outputPreview
    if (output != null) return output.toString().take(160)
    return ""
}

/**
 * A3.1: one attachment — an image (thumbnail, bytes fetched once and cached in the
 * small in-memory LRU) or a named file chip. A failed/absent loader still renders the
 * chip (INV-11: never a blank hole).
 */
@Composable
private fun AttachmentView(
    att: ConversationRow.UserInput.AttachmentMeta,
    load: (suspend (ConversationRow.UserInput.AttachmentMeta) -> ByteArray?)?,
    modifier: Modifier = Modifier,
) {
    val isImage = att.mime.startsWith("image/")
    val bytes by androidx.compose.runtime.produceState<ByteArray?>(initialValue = null, key1 = att.ref) {
        if (isImage && load != null) {
            value = AttachmentImageCache.get(att.ref) ?: run {
                val loaded = runCatching { load(att) }.getOrNull()
                if (loaded != null) AttachmentImageCache.put(att.ref, loaded)
                loaded
            }
        }
    }
    if (isImage) {
        val bitmap = bytes?.let { b ->
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)
            }.getOrNull()?.asImageBitmap()
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier
                .testTag("row_attachment_${att.ref}")
                .size(width = 132.dp, height = 96.dp),
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = att.fileName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier,
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.row_attachment_loading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier
                .heightIn(min = 32.dp)
                .testTag("row_attachment_${att.ref}"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = att.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Small LRU for in-conversation attachment thumbnails (refs are stable per row). */
private object AttachmentImageCache {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val map = LinkedHashMap<String, ByteArray>(16, 0.75f, true)

    @Synchronized
    fun get(ref: String): ByteArray? = map[ref]

    @Synchronized
    fun put(ref: String, bytes: ByteArray) {
        map[ref] = bytes
        var total = map.values.sumOf { it.size }
        val it2 = map.entries.iterator()
        while (total > MAX_BYTES && it2.hasNext()) {
            total -= it2.next().value.size
            it2.remove()
        }
    }
}

/** A3.11: fire-and-track the preview load without holding a coroutine scope in the row. */
private fun rowScopePreviewJob(
    provider: suspend (Long, String) -> com.zcode.android.domain.model.RewindPreview?,
    rowId: Long,
    entityId: String,
    onResult: (com.zcode.android.domain.model.RewindPreview?) -> Unit,
) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
        onResult(runCatching { provider(rowId, entityId) }.getOrNull())
    }
}

@Composable
private fun RewindPreviewBody(preview: com.zcode.android.domain.model.RewindPreview) {
    Column(Modifier.padding(horizontal = 16.dp).testTag("rewind_preview")) {
        preview.safeFiles.take(6).forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (f.action == "delete") "−" else "↩",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("rewind_safe_${f.path.hashCode()}"),
                )
                Spacer(Modifier.width(6.dp))
                Text(f.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (preview.safeFiles.size > 6) {
            Text("… +${preview.safeFiles.size - 6}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        preview.unsafeFiles.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        f.message ?: f.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("rewind_unsafe_${f.reason}"),
                    )
                }
            }
        }
        if (preview.ignoredFiles.isNotEmpty()) {
            Text(
                stringResource(R.string.rewind_ignored_count, preview.ignoredFiles.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rewind_ignored"),
            )
        }
        if (!preview.canApply) {
            Text(
                stringResource(R.string.rewind_cannot_apply),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp).testTag("rewind_cannot_apply"),
            )
        }
    }
}
