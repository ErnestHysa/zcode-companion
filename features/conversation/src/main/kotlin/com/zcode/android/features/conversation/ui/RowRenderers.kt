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
import androidx.compose.material.icons.filled.WrapText
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
            .padding(8.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
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
