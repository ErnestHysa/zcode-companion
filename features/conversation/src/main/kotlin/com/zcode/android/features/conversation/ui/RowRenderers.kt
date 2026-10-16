package com.zcode.android.features.conversation.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.draw.clip
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import androidx.compose.ui.res.pluralStringResource

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
    createdAtMs: Double? = null,
    /** Desktop gate (ConversationRowView.tsx:1295): the edit entry needs entityId. */
    editable: Boolean = false,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
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
fun AgentProse(text: String, streaming: Boolean, createdAtMs: Double? = null, modifier: Modifier = Modifier) {
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
                modifier = Modifier.semantics(mergeDescendants = true) {},
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
        }
    }
}

/** Generic tool card (§19.5 anatomy); expandable input/output previews. */
@Composable
fun ToolCard(
    row: ConversationRow.ToolCall,
    modifier: Modifier = Modifier,
) {
    val tokens = toolStatusPresentation(row.status)
    var expanded by rememberSaveable(row.rowId) { mutableStateOf(false) }
    val expandWord = stringResource(if (expanded) R.string.a11y_collapse else R.string.a11y_expand)
    val toolA11y = stringResource(R.string.row_tool_a11y, row.toolName, tokens.first, expandWord)
    val railColor = tokens.second
    val meta = toolMetaLine(row)
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
        Text(
            text = tokens.first,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = tokens.second,
        )
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
            row.outputPreview?.let { preview ->
                Spacer(Modifier.height(6.dp))
                MonospaceDetail(label = stringResource(R.string.row_output), content = preview.toString().take(2_000))
            }
            row.error?.let { err ->
                Spacer(Modifier.height(6.dp))
                MonospaceDetail(label = stringResource(R.string.row_error), content = "${err.code}: ${err.message}".take(2_000))
            }
        }
    }
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
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("row_turn")) {
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
                        .clickable { onOpenChanges() },
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
) {
    Row(
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
                // GAP-11: per-code-block copy affordance (desktop code-block copy).
                CopyIconButton(text = code, tag = "row_copy_code")
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
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

/** Markdown renderer over the bounded parser (ADR-015); spans are style-only. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                is MarkdownParser.Block.ListItem -> Row {
                    Text(
                        text = if (block.ordered) "${block.number}." else "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(18.dp),
                    )
                    SpannedText(block.spans, MaterialTheme.typography.bodyMedium, Modifier.weight(1f))
                }
                is MarkdownParser.Block.Quote -> Row {
                    Box(Modifier.width(3.dp).height(18.dp).background(LocalZCodeColors.current.railDone))
                    Spacer(Modifier.width(8.dp))
                    SpannedText(block.spans, MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
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
    val linkRanges = mutableListOf<Triple<Int, Int, String>>()
    val annotated = buildAnnotatedString {
        for (span in spans) {
            val start = length
            append(span.text)
            when {
                span.code -> addStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = codeBg), start, length)
                span.bold -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, length)
                span.italic -> addStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, length)
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
                                notifier.show(context.getString(R.string.row_link_open_failed))
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
    }, modifier = Modifier.size(24.dp).testTag(tag)) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = stringResource(if (copied) R.string.row_copied else R.string.row_copy),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun RowTimeLabel(createdAtMs: Double?, tag: String) {
    if (createdAtMs == null) return
    val yesterday = stringResource(com.zcode.android.core.ui.R.string.relative_yesterday)
    val label = remember(createdAtMs, yesterday) {
        messageTimeLabel(createdAtMs, System.currentTimeMillis(), yesterdayWord = yesterday)
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
private fun MonospaceDetail(label: String, content: String) {
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
