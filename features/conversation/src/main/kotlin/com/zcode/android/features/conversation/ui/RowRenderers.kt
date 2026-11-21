package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.Article
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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.filled.Chat
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
 * Row renderers — the agent-timeline grammar (the ui-ux design package is ARCHIVED,
 * M1.1 Phase 6D; the in-repo authority is core/ui/theme + the gate tests): YOU
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
    /** G28 (vc51 Phase 4D): tap-to-zoom on image thumbnails (null = dead-end preserved). */
    onOpenAttachment: ((ConversationRow.UserInput.AttachmentMeta) -> Unit)? = null,
    /** G28: PDF/video chips hand off to an external viewer (null = dead-end preserved). */
    onOpenAttachmentExternally: ((ConversationRow.UserInput.AttachmentMeta) -> Unit)? = null,
    /** G5 (vc51): find-in-conversation — every query occurrence highlights; the ACTIVE
     *  match's row gets the stronger fill. */
    findQuery: String? = null,
    findActive: Boolean = false,
    /** G7+G8 (vc51): the selection actions — the phone's selection tooltip IS this
     *  long-press sheet; "Ask in a side session" dispatches createSelectionSideSession
     *  and "Share selection" rides ACTION_SEND (null = hidden). */
    onSideSession: (() -> Unit)? = null,
    onShareSelection: (() -> Unit)? = null,
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
            // W1 (verification pass 3, 2026-10-22): the text and the attachments were
            // BOTH direct Box children — stacked at the same top-start origin (the
            // Spacer between them is meaningless in a Box), so the thumbnail drew over
            // the message text until only its last glyph escaped the image's right
            // edge. Chat-bubble order: the text first, the attachments BELOW it.
            Column {
                // G5: find highlight — plain text renders occurrences; the active match's
                // row carries the accent fill (assistant prose keeps its markdown pipeline).
                val rowText = if (findQuery.isNullOrBlank()) {
                    androidx.compose.ui.text.AnnotatedString(text)
                } else {
                    annotatedWithHighlights(
                        text,
                        findQuery,
                        androidx.compose.ui.text.SpanStyle(
                            background = if (findActive) LocalZCodeColors.current.accent.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
                Text(
                    text = rowText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                // A3.1: attachments render in-conversation (images as thumbnails, loaded
                // once through the attachment-read channel; other files as named chips).
                if (attachments.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        for (att in attachments) {
                            AttachmentView(
                                att = att,
                                load = loadAttachment,
                                onOpenFull = onOpenAttachment?.let { cb -> { cb(att) } },
                                onOpenExternally = onOpenAttachmentExternally?.let { cb -> { cb(att) } },
                            )
                        }
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
            // G8: the selection side session (createSelectionSideSession).
            if (onSideSession != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.row_side_session)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            showActions = false
                            onSideSession()
                        }
                        .testTag("row_side_session_action"),
                )
            }
            // G7: share the selection through the system sheet.
            if (onShareSelection != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.row_share_selection)) },
                    leadingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            showActions = false
                            onShareSelection()
                        }
                        .testTag("row_share_selection_action"),
                )
            }
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
    /**
     * G36 (vc51): the presentation-mode gate (lib/interfaceMode.ts — the desktop's
     * 31+ consumers across the timeline; asar "Office mode focuses on summaries
     * and results. Coding mode shows commands, output, and code changes."). OFFICE
     * collapses the tool card to its summary (name/status/meta — no expand into
     * INPUT/output); CODING (default) renders everything.
     */
    showDetails: Boolean = true,
    /**
     * G66 (vc52 Phase 13): the typed error card's recovery actions — rendered
     * under the raw error block when the row carries an error and the caller
     * wired the mobile-reachable actions (retry/switch-model/report + the two
     * copies). Null (unwired) keeps the historical raw-block face.
     */
    errorActions: ToolErrorActions? = null,
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
            .clickable { if (showDetails) expanded = !expanded }
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
            if (showDetails) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // G80 (vc52 Phase 13): the kernel's kind subtitle — TaskUpdate reads
        // "Task control" instead of a bare name; unknown names keep no subtitle.
        ToolKindTaxonomy.kindOf(row.toolName)?.let { kind ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = toolKindLabel(kind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("tool_kind_${row.rowId}"),
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
            // G18 (vc51 Phase 4D): a CUA tool call's SCREENSHOT frame renders INSIDE the
            // expanded card (the mock/desktop serves a base64 PNG in the output payload;
            // INV-11: a missing or undecodable frame renders nothing, never a hole).
            val screenshotBytes = remember(row.rowId, row.outputPreview) {
                extractScreenshotPng(row.outputPreview?.toString())
                    ?: extractScreenshotPng(row.inputText)
            }
            screenshotBytes?.let { png ->
                val frame = remember(row.rowId, png.size) {
                    runCatching { android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }.getOrNull()
                }
                frame?.let { bmp ->
                    androidx.compose.foundation.Image(
                        bitmap = bmp,
                        contentDescription = stringResource(R.string.tool_screenshot_frame),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .testTag("tool_screenshot_${row.rowId}"),
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            MonospaceDetail(
                label = stringResource(R.string.row_input),
                content = row.inputText.ifBlank { "—" },
                tag = "tool_input_${row.rowId}",
            )
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
                // G66: the typed recovery actions ride the raw block (mobile-reachable
                // subset — retry/switch-model/report + the two copies).
                errorActions?.let { ea ->
                    ErrorRecoveryCard(code = err.code, message = err.message, actions = ea)
                }
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
            // G72: the sheet content SCROLLS (desktop `max-h-[50vh] overflow-y-auto`) —
            // a many-file summary can no longer push the Confirm below the fold.
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag("rewind_sheet_scroll"),
            ) {
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

/** I2.8: the preview-card kind, re-exported for the renderer's exhaustive `when`s. */
typealias GeneratedArtifactPreviewKind = com.zcode.android.features.conversation.GeneratedArtifactPreview.Kind

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
                            // G81 (pass-0 Phase 10): the gen-ui carrier kind carries the
                            // honest desktopOnly face — the desktop renders generated
                            // interactive UI in its plugin sandbox; the phone accepts
                            // zero files (the full 4-part genUiContext consume is a
                            // recorded no-action; this face is the roadmap minimum).
                            GeneratedArtifactPreviewKind.HTML -> R.string.preview_html_desktop_only
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

/**
 * G71 (vc52 Phase 12): the per-turn hooks monitor (desktop `chat.hooks.*`) — the
 * `hookInvocation` row's executions grouped under the event name, each execution
 * showing its displayName, source (user/project/plugin), monitor state
 * (running/completed/failed/blocked/timed-out/cancelled), duration and blockReason.
 * `didExecute=false` entries stay hidden (the desktop's sTn filter); the group is
 * collapsed by default and expands in place.
 */
@Composable
fun HookInvocationRow(
    row: ConversationRow.HookInvocation,
    modifier: Modifier = Modifier,
) {
    val visible = remember(row.raw) { row.executions.filter { it.didExecute }.sortedBy { it.hookIndex } }
    if (visible.isEmpty()) {
        QuietMetaRow(label = stringResource(R.string.row_hook, row.hookEventName), detail = null)
        return
    }
    var expanded by rememberSaveable(row.rowId) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag("row_hooks"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minRowTouchTarget()
                .clickable(role = Role.Button) { expanded = !expanded }
                .testTag("row_hooks_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(20.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.hooks_monitor_label, row.hookEventName, visible.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            val failed = visible.any { it.monitorState == "failed" || it.monitorState == "blocked" || it.monitorState == "timedOut" }
            val running = visible.any { it.monitorState == "running" }
            val stateColor = when {
                running -> MaterialTheme.colorScheme.onSurfaceVariant
                failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = stateColor,
            )
        }
        if (expanded) {
            visible.forEach { exec ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, top = 2.dp)
                        .testTag("hook_exec_${exec.hookRunId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = exec.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(hookSourceLabel(exec.sourceKind)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    exec.monitorState?.let { st ->
                        Text(
                            text = stringResource(hookStateLabel(st)),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (st) {
                                "failed", "blocked", "timedOut" -> MaterialTheme.colorScheme.error
                                "running" -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.testTag("hook_state_${exec.hookRunId}"),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    exec.durationMs?.let { ms ->
                        Text(
                            text = if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                exec.blockReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 28.dp)
                            .testTag("hook_block_${exec.hookRunId}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun hookStateLabel(state: String): Int = when (state) {
    "running" -> R.string.hook_state_running
    "failed" -> R.string.hook_state_failed
    "blocked" -> R.string.hook_state_blocked
    "timedOut" -> R.string.hook_state_timed_out
    "cancelled" -> R.string.hook_state_cancelled
    else -> R.string.hook_state_completed
}

@Composable
private fun hookSourceLabel(source: String): Int = when (source) {
    "user" -> R.string.hook_source_user
    "plugin" -> R.string.hook_source_plugin
    else -> R.string.hook_source_project
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
                is MarkdownParser.Block.Paragraph -> {
                    val images = remember(block.spans) { block.spans.filter { it.imageUrl != null } }
                    Column {
                        SpannedText(block.spans, MaterialTheme.typography.bodyMedium)
                        images.forEach { img -> MarkdownImage(img.imageUrl!!, img.text) }
                    }
                }
                is MarkdownParser.Block.Heading -> SpannedText(
                    block.spans,
                    // N-BI-1: H1–H6 map to DISTINCT type — the old 2-level map
                    // flattened H2–H6 into one size (agent output numbers past H3).
                    headingTextStyle(MaterialTheme.typography, block.level),
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
                        if (block.task != null) {
                            // N-BI-2: GFM task list — a read-only glyph (the desktop's
                            // task lists are read-only in chat renderers too).
                            Text(
                                text = if (block.task) "☑" else "☐",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(22.dp).testTag(
                                    if (block.task) "md_task_checked" else "md_task_unchecked",
                                ),
                            )
                        } else {
                            // N-BI-1: the marker column grows with the digits — the
                            // fixed 18.dp column wrapped "10."'s period onto its own
                            // line (agent output routinely numbers past ten).
                            val digits = block.number.toString().length
                            Text(
                                text = if (block.ordered) "${block.number}." else listMarker(block.level),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width((9.dp * digits + 8.dp).coerceAtLeast(18.dp)),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            SpannedText(block.spans, MaterialTheme.typography.bodyMedium)
                            block.spans.filter { it.imageUrl != null }.forEach { img ->
                                MarkdownImage(img.imageUrl!!, img.text)
                            }
                        }
                    }
                }
                is MarkdownParser.Block.FootnoteDefinition -> {
                    // N-BI-2: the `[^label]: body` definition — small, labeled.
                    Row(modifier = Modifier.testTag("md_footnote_def")) {
                        Text(
                            text = "[${block.label}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(48.dp),
                        )
                        SpannedText(
                            block.spans,
                            MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            Modifier.weight(1f),
                        )
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

/**
 * N-BI-2 (vc52 Phase 10): a `![alt](url)` markdown image as a bounded THUMBNAIL
 * (max 160dp tall) — the old path parsed it as a LINK and leaked the "!" literally.
 * The fetch is bounded (HttpURLConnection — java.base, no jlink-runtime gap; 8s
 * timeouts; a 2 MiB payload cap) and memory-cached per URL. A failed load degrades
 * to the alt text styled as a link (the deterministic Robolectric face — no network).
 */
@Composable
private fun MarkdownImage(url: String, alt: String) {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(MarkdownImageCache.get(url)) }
    LaunchedEffect(url) {
        if (bitmap == null) {
            val fetched = withContext(Dispatchers.IO) { fetchMarkdownImage(url) }
            if (fetched != null) {
                MarkdownImageCache.put(url, fetched)
                bitmap = fetched
            }
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        androidx.compose.foundation.Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = alt,
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .testTag("md_image"),
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("md_image_unavailable"),
        ) {
            Text(
                text = stringResource(R.string.row_md_image_alt, alt.take(64)),
                style = MaterialTheme.typography.labelSmall,
                color = LocalZCodeColors.current.accent,
                textDecoration = TextDecoration.Underline,
            )
        }
    }
}

private suspend fun fetchMarkdownImage(url: String): android.graphics.Bitmap? = runCatching {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return null
    withContext(kotlinx.coroutines.Dispatchers.IO) {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return@withContext null
            val length = conn.contentLengthLong
            if (length > IMAGE_MAX_BYTES) return@withContext null
            val bytes = conn.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > IMAGE_MAX_BYTES) return@withContext null
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
            // Downscale: decode bounds first, then sample to the thumbnail budget.
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } finally {
            conn.disconnect()
        }
    }
}.getOrNull()

private const val IMAGE_MAX_BYTES = 2L * 1024 * 1024

/** Bounded per-process cache for markdown thumbnails (8 entries, INV-5). */
private object MarkdownImageCache {
    private val lru = java.util.LinkedHashMap<String, android.graphics.Bitmap>(8, 0.75f, true)
    fun get(url: String): android.graphics.Bitmap? = synchronized(lru) { lru[url] }
    fun put(url: String, bitmap: android.graphics.Bitmap) = synchronized(lru) {
        lru[url] = bitmap
        while (lru.size > 8) lru.remove(lru.keys.first())
    }
}

/**
 * N-BI-1 (vc52 Phase 10): H1–H6 map to DISTINCT styles — the old renderer mapped
 * level 1 vs everything-else, so H2–H6 all rendered at one size. Pure + pinnable
 * (Robolectric's fallback font metrics clamp rendered heights to near-equal boxes,
 * so the pin asserts the STYLE ASSIGNMENT, not painted pixels).
 */
internal fun headingTextStyle(typography: androidx.compose.material3.Typography, level: Int): androidx.compose.ui.text.TextStyle =
    // Descending by THIS app's Type.kt sizes (24/18/15/14/13/12 sp) — the M3 default
    // mapping (H4=titleSmall 13 < H5=bodyLarge 14) would invert the scale here.
    when (level.coerceIn(1, 6)) {
        1 -> typography.headlineSmall
        2 -> typography.titleLarge
        3 -> typography.titleMedium
        4 -> typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
        5 -> typography.titleSmall
        else -> typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
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
                // N-BI-2: a footnote reference — superscript, accent.
                span.footnoteRef != null -> addStyle(
                    SpanStyle(
                        color = LocalZCodeColors.current.accent,
                        fontSize = style.fontSize * 0.75f,
                        baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript,
                    ),
                    start,
                    length,
                )
                // N-BI-2: `![alt](url)` — the alt reads as a link to the image; the
                // thumbnail itself renders below (MarkdownImage).
                span.imageUrl != null -> {
                    addStyle(
                        SpanStyle(color = LocalZCodeColors.current.accent, textDecoration = TextDecoration.Underline),
                        start,
                        length,
                    )
                    linkRanges.add(Triple(start, length, span.imageUrl))
                }
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
internal fun CopyIconButton(text: String, tag: String) {
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

@Composable
fun toolKindLabel(kind: com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind): String =
    stringResource(
        when (kind) {
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.FILE_READ -> R.string.tool_kind_file_read
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.FILE_WRITE -> R.string.tool_kind_file_write
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.SHELL -> R.string.tool_kind_shell
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.SEARCH -> R.string.tool_kind_search
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.TODO -> R.string.tool_kind_todo
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.GOAL -> R.string.tool_kind_goal
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.SESSION_CONTEXT -> R.string.tool_kind_session_context
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.ASK_USER_QUESTION -> R.string.tool_kind_ask_user_question
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.MESSAGE -> R.string.tool_kind_message
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.TASK_CONTROL -> R.string.tool_kind_task_control
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.NODE_REPL -> R.string.tool_kind_node_repl
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.AGENT -> R.string.tool_kind_agent
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.SKILL -> R.string.tool_kind_skill
            com.zcode.android.features.conversation.ui.ToolKindTaxonomy.ToolKind.WORKFLOW -> R.string.tool_kind_workflow
        },
    )

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
 * N-BJ-1 (pass-0 Phase 7): the CAPPED thumbnail decode. The report's red block was
 * the render size (G28-era fixed 132×96dp frame); the remaining uncapped half was
 * the DECODE — a 4000×3000 photo decoded at full resolution (a ~46 MB ARGB bitmap)
 * to paint a 132dp window. The sampler bounds the max dimension via inSampleSize
 * (powers of two, aspect preserved by construction; the 1×1 seed stays exact —
 * never upscaled). Garbage/empty bytes decode to null (the honest-empty family).
 */
internal fun decodeAttachmentThumbnail(bytes: ByteArray, maxDim: Int = 512): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
    android.graphics.BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

/**
 * F-P11-A (pass-0 Phase 7): the image load is TRI-STATE — Loading / Ready /
 * Unavailable. produceState's `runCatching { load(att) }.getOrNull()` used to
 * collapse a FAILED read into the same `null` as a still-loading one, so a failed
 * chip claimed "Loading…" forever (no unavailable state, no retry). The
 * unavailable face is a TAP TARGET: it re-invokes the loader (the retryEpoch
 * re-keys the produceState).
 */
private sealed interface AttachmentLoad {
    data object Loading : AttachmentLoad
    data class Ready(val bytes: ByteArray) : AttachmentLoad
    data object Unavailable : AttachmentLoad
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
    onOpenFull: (() -> Unit)? = null,
    onOpenExternally: (() -> Unit)? = null,
) {
    val isImage = att.mime.startsWith("image/")
    // F-P11-A: the tap-to-retry epoch — bumping it re-keys the produceState.
    var retryEpoch by androidx.compose.runtime.remember(att.ref) {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    val loadState by androidx.compose.runtime.produceState<AttachmentLoad>(
        initialValue = AttachmentLoad.Loading,
        key1 = att.ref,
        key2 = retryEpoch,
    ) {
        if (isImage && load != null) {
            val cached = AttachmentImageCache.get(att.ref)
            if (cached != null) {
                value = AttachmentLoad.Ready(cached)
            } else {
                val loaded = runCatching { load(att) }.getOrNull()
                if (loaded != null) AttachmentImageCache.put(att.ref, loaded)
                value = loaded?.let { AttachmentLoad.Ready(it) } ?: AttachmentLoad.Unavailable
            }
        } else {
            value = AttachmentLoad.Unavailable
        }
    }
    if (isImage) {
        val bitmap = (loadState as? AttachmentLoad.Ready)?.bytes?.let { b ->
            // N-BJ-1: the capped sampler — never a full-resolution decode for the
            // 132×96dp window.
            decodeAttachmentThumbnail(b)?.asImageBitmap()
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier
                .testTag("row_attachment_${att.ref}")
                .size(width = 132.dp, height = 96.dp)
                .then(
                    when {
                        // F-P11-A: the unavailable face IS the retry affordance.
                        loadState is AttachmentLoad.Unavailable ->
                            Modifier.clickable { retryEpoch += 1 }.testTag("row_attachment_unavailable_${att.ref}")
                        onOpenFull != null -> Modifier.clickable(onClick = onOpenFull)
                        else -> Modifier
                    },
                ),
        ) {
            when {
                bitmap != null -> {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = att.fileName,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier,
                    )
                }
                loadState is AttachmentLoad.Unavailable -> {
                    // F-P11-A: the face's own tag (a node carries ONE TestTag — the
                    // Surface keeps the base row_attachment_<ref> tag; the tap-to-retry
                    // clickable is the Surface's).
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("row_attachment_unavailable_${att.ref}"),
                    ) {
                        Text(
                            text = stringResource(R.string.row_attachment_unavailable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                else -> {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.row_attachment_loading),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    } else {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier
                .heightIn(min = 32.dp)
                .testTag("row_attachment_${att.ref}")
                // G28: PDF/video chips offer the external viewer.
                .then(
                    if (onOpenExternally != null) {
                        Modifier.clickable(onClick = onOpenExternally)
                    } else {
                        Modifier
                    },
                ),
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

/**
 * G18: a CUA tool call's screenshot payload — a base64 PNG embedded in the output/input
 * JSON (`{"screenshot":"iVBORw…"}` or a data URI). Returns the DECODED PNG bytes or null.
 */
internal fun extractScreenshotPng(payload: String?): ByteArray? {
    if (payload.isNullOrBlank()) return null
    val marker = "\"screenshot\""
    val i = payload.indexOf(marker)
    if (i < 0) return null
    val tail = payload.substring(i)
    val b64 = Regex("[A-Za-z0-9+/=]{60,}").find(tail)?.value ?: return null
    val cleaned = b64.removePrefix("data:image/png;base64,")
    return runCatching { java.util.Base64.getMimeDecoder().decode(cleaned) }.getOrNull()
        ?.takeIf { it.size > 8 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte() }
}

/**
 * G28 (vc51 Phase 4D): the TAP-TO-ZOOM viewer — a fullscreen black surface showing the
 * full image (loaded through [loader], the attachment-read channel's full chunked path),
 * Fit-scaled. Close consumes the dialog.
 */
@Composable
fun AttachmentViewerDialog(
    att: ConversationRow.UserInput.AttachmentMeta,
    loader: (suspend (ConversationRow.UserInput.AttachmentMeta) -> ByteArray?)?,
    onDismiss: () -> Unit,
) {
    // F-P11-A: the load is tri-state here too — a FAILED read renders the honest
    // unavailable face, never an eternal "Loading…" (reopening the dialog is the retry).
    var loadFailed by androidx.compose.runtime.remember(att.ref) { androidx.compose.runtime.mutableStateOf(false) }
    val bytesState = androidx.compose.runtime.produceState<ByteArray?>(initialValue = null, key1 = att.ref) {
        loadFailed = false
        value = if (loader != null) {
            runCatching { loader(att) }.getOrNull().also { if (it == null) loadFailed = true }
        } else {
            null
        }
    }
    val bytes = bytesState.value
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .testTag("attachment_viewer"),
        ) {
            val decoded = bytes?.let { b ->
                runCatching {
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) sample *= 2
                    val o2 = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o2)?.asImageBitmap()
                }.getOrNull()
            }
            if (decoded != null) {
                androidx.compose.foundation.Image(
                    bitmap = decoded,
                    contentDescription = att.fileName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().testTag("attachment_viewer_image"),
                )
            } else {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loadFailed) {
                        // F-P11-A: the honest unavailable face (reopening = the retry).
                        Text(
                            stringResource(R.string.row_attachment_unavailable),
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("attachment_viewer_unavailable"),
                        )
                    } else {
                        Text(
                            stringResource(R.string.row_attachment_loading),
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 32.dp, end = 12.dp)
                    .testTag("attachment_viewer_close"),
            ) {
                Text(stringResource(R.string.attachment_viewer_close), color = androidx.compose.ui.graphics.Color.White)
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

/**
 * N-BK-1(a) (vc52 Phase 12): a subagent row carrying a childSessionId — the
 * WHOLE row opens the child session (the desktop's session drill-in); the
 * open affordance is labeled. Without a child id the caller renders the
 * quiet fallback instead of this composable.
 */
@Composable
fun SubagentRow(
    row: ConversationRow.Subagent,
    onOpenChild: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val child = row.childSessionId ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenChild(child) }
            .padding(vertical = 4.dp)
            .testTag("subagent_row_${row.rowId}")
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.row_subagent, row.subagentType),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "· ${row.summaryText.take(80)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.subagent_open_child),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("subagent_open_${row.rowId}"),
        )
    }
}

/**
 * G72 (vc52 Phase 12): the desktop's rewindDialog change summary (chat.changeSummary
 * .rewindDialog.*) — THREE titled sections with counts: safe (path + "{n} edits" per
 * file), unsafe (path + the LOCALIZED reason per file + edits), ignored (bash_ignored,
 * per-file). Each empty list renders its desktop refusal face (noSafeFiles /
 * noUnsafeFiles); !canApply keeps the cannotApply face and the Confirm disabled
 * (the restore only ever sends from the sheet's Confirm button).
 */
@Composable
private fun RewindPreviewBody(preview: com.zcode.android.domain.model.RewindPreview) {
    Column(Modifier.padding(horizontal = 16.dp).testTag("rewind_preview")) {
        // Safe section (desktop safeTitle + per-file rows).
        Text(
            stringResource(R.string.rewind_safe_count, preview.safeFiles.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp).testTag("rewind_safe_title"),
        )
        if (preview.safeFiles.isEmpty()) {
            Text(
                stringResource(R.string.rewind_no_safe_files),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rewind_no_safe_files"),
            )
        } else {
            preview.safeFiles.take(8).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (f.action == "delete") "−" else "↩",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("rewind_safe_${f.path.hashCode()}"),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        f.path,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.rewind_ops_count, f.operationCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (preview.safeFiles.size > 8) {
                Text(
                    "… +${preview.safeFiles.size - 8}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Unsafe section (desktop unsafeTitle + per-file reason attribution).
        Text(
            stringResource(R.string.rewind_unsafe_count, preview.unsafeFiles.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 10.dp).testTag("rewind_unsafe_title"),
        )
        if (preview.unsafeFiles.isEmpty()) {
            Text(
                stringResource(R.string.rewind_no_unsafe_files),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("rewind_no_unsafe_files"),
            )
        } else {
            preview.unsafeFiles.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            f.message ?: rewindReasonLabel(f.reason),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("rewind_unsafe_${f.reason}"),
                        )
                    }
                    Text(
                        stringResource(R.string.rewind_ops_count, f.operationCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Ignored section (desktop ignoredTitle — only when non-empty).
        if (preview.ignoredFiles.isNotEmpty()) {
            Text(
                stringResource(R.string.rewind_ignored_title, preview.ignoredFiles.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 10.dp).testTag("rewind_ignored_title"),
            )
            preview.ignoredFiles.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("rewind_ignored_file")) {
                    Text("~", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        f.path,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        rewindReasonLabel(f.reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("rewind_ignored_reason"),
                    )
                }
            }
        }
        if (!preview.canApply) {
            Text(
                stringResource(R.string.rewind_cannot_apply),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp).testTag("rewind_cannot_apply"),
            )
        }
    }
}

/** G72: the six recorded unsafe/ignored reasons localized (desktop jnn map); unknown renders raw. */
@Composable
private fun rewindReasonLabel(reason: String): String = when (reason) {
    "bash_ignored" -> stringResource(R.string.rewind_reason_bash_ignored)
    "checkpoint_missing" -> stringResource(R.string.rewind_reason_checkpoint_missing)
    "checkpoint_unreadable" -> stringResource(R.string.rewind_reason_checkpoint_unreadable)
    "external_modified" -> stringResource(R.string.rewind_reason_external_modified)
    "file_read_failed" -> stringResource(R.string.rewind_reason_file_read_failed)
    "unsupported_checkpoint" -> stringResource(R.string.rewind_reason_unsupported_checkpoint)
    else -> reason
}
