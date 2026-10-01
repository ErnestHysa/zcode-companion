package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.AttentionStrip
import com.zcode.android.core.ui.components.ConnectionStatusLine
import com.zcode.android.core.ui.components.SkeletonBar
import com.zcode.android.core.ui.components.StatusTone
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ConversationUiState
import com.zcode.android.features.conversation.taskStateLabel
import kotlinx.coroutines.launch

/**
 * Conversation surface (§43.2-6.3/6.4 + Phase 7 write path; ui-ux 06/07/09/10/12).
 * Tail-first render, `rowsRange` scroll-back with page-edge prefetch, stream-follow
 * anchoring with jump-to-latest + unread count, mandatory state strips (INV-11),
 * and the Phase 7 write surface: pinned approval/user-input cards (G2a "approval
 * first"), queue strip, and the §17.4/§17.6 composer.
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    phase: ConnectionPhase,
    onBack: () -> Unit,
    onLoadOlder: (Long, (Int) -> Unit) -> Unit,
    onDismissHistoryNotice: () -> Unit,
    onRetryOlder: (Long) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: (heldQueueDisposition: String?) -> Unit,
    onStop: () -> Unit,
    onRetrySend: (commandId: String) -> Unit,
    onDismissSendNotice: () -> Unit,
    onAnswer: (String, String, String?, Boolean) -> Unit,
    onSendQueuedNow: (queueItemId: String) -> Unit,
    onEditQueueItem: (queueItemId: String, newText: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // FIELD (2026-10-01, vc21 device): edge-to-edge draws the IME as an
                // overlay — without imePadding the keyboard covered the pinned
                // composer entirely ("typing but nothing shows up": the field was
                // UNDER the keyboard).
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Header(state = state, phase = phase, onBack = onBack)
            // Pinned state strips (§15-S1/S9, mockups 09/10/12) — content stays visible.
            if (state.stale) {
                AttentionStrip(
                    title = "Showing cached projection · recovering",
                    tone = StatusTone.WAITING,
                    modifier = Modifier.testTag("conversation_stale_strip"),
                )
            }
            // §20.2 "approval first": the open interaction cards are PINNED above the
            // timeline (the agent is blocked until answered — G2a ≤ 3 interactions).
            for (interaction in state.pendingPermissions) {
                ApprovalCard(
                    interaction = interaction,
                    busy = interaction.interactionId in state.busyInteractions,
                    onAnswer = { optionId, freeText ->
                        onAnswer(interaction.interactionId, optionId, freeText, false)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            for (interaction in state.pendingUserInputs) {
                UserInputCard(
                    interaction = interaction,
                    busy = interaction.interactionId in state.busyInteractions,
                    onAnswer = { optionId, freeText ->
                        onAnswer(interaction.interactionId, optionId, freeText, true)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (state.taskState == TaskStateMachine.State.WAITING_FOR_PERMISSION ||
                state.taskState == TaskStateMachine.State.WAITING_FOR_USER
            ) {
                AttentionStrip(
                    title = "Needs attention",
                    message = "The agent is waiting — answer above.",
                    tone = StatusTone.WAITING,
                    modifier = Modifier.testTag("conversation_attention_strip"),
                )
            }
            // §7.5 queue display + edit (sendQueuedNow / editQueueItem, CAS upstream).
            state.sessionState?.queue?.let { queue ->
                QueueStrip(
                    queue = queue,
                    busy = false,
                    onSendNow = onSendQueuedNow,
                    onEdit = onEditQueueItem,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
                        // §19.4 FIELD (2026-09-30, vc11): the desktop truncates the initial snapshot
            // to a tail window — make the remaining history DISCOVERABLE and tappable,
            // not just a scroll-prefetch hint. Scalable: pages of PAGE_MAX_ROWS until
            // the window's firstRowId is reached (no hardcoded total).
            if (state.hasMoreHistory && !state.loadingOlder && state.rows.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("conversation_load_older_strip")
                        .clickable { onLoadOlder(state.rows.first().rowId) { /* page fetched; list updates via the store */ } },
                ) {
                    Text(
                        text = buildString {
                            append("Load earlier messages")
                            state.totalRows?.let { total ->
                                append("  ·  showing latest ${state.rows.size} of $total")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
if (state.historyChanged) {
                AttentionStrip(
                    title = "History changed on desktop — jumped to latest",
                    tone = StatusTone.INFO,
                    actionLabel = "Dismiss",
                    onAction = onDismissHistoryNotice,
                    modifier = Modifier.testTag("conversation_history_strip"),
                )
            }
            when {
                state.loading && state.rows.isEmpty() -> LoadingBody()
                state.empty -> EmptyBody()
                else -> TimelineBody(
                    state = state,
                    onLoadOlder = onLoadOlder,
                    onRetryOlder = onRetryOlder,
                )
            }
            // §17.4/§17.6 composer (pinned bottom; drafting allowed offline, send
            // blocked with explicit copy — never a silent queue).
            ComposerBar(
                state = state,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                onRetrySend = onRetrySend,
                onDismissNotice = onDismissSendNotice,
            )
        }
    }
}

@Composable
private fun Header(state: ConversationUiState, phase: ConnectionPhase, onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("conversation_back")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.task?.title ?: "Conversation",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ConnectionStatusLine(
            phase = phase,
            trailing = taskStateLabel(state.taskState),
            modifier = Modifier.padding(horizontal = 16.dp).testTag("conversation_status_line"),
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LoadingBody() {
    // weight(1f), not fillMaxSize — the composer (Phase 7) stays pinned below.
    Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 16.dp)) {
        repeat(4) {
            SkeletonBar(Modifier.fillMaxWidth(if (it % 2 == 0) 0.85f else 0.6f).testTag("conversation_skeleton"))
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EmptyBody() {
    Column(
        // weight(1f), not fillMaxSize — the composer (Phase 7) stays pinned below.
        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp).testTag("conversation_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No messages yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Agent activity will stream here when the conversation starts.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** §19.2: render window + anchoring + pagination trigger + unread FAB. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TimelineBody(
    state: ConversationUiState,
    onLoadOlder: (Long, (Int) -> Unit) -> Unit,
    onRetryOlder: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var unread by remember { mutableIntStateOf(0) }
    var lastKnownCount by remember { mutableIntStateOf(0) }

    // Anchor: following the tail is the DEFAULT; a user scroll-up releases it (§19.2).
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 80
        }
    }
    var followBottom by remember { mutableStateOf(true) }

    // FIELD-KILLER #10 (2026-09-30, vc14 field + repro'd on the emulator): the FIRST
    // non-empty layout must anchor to the tail UNCONDITIONALLY. The old
    // `followBottom && atBottom` guard skipped the scroll on RE-ENTRY with cached rows:
    // the list lays out at index 0 first, `atBottom` reads false, and the conversation
    // opened at the TOP of the window — firing the prefetch spuriously ("loading
    // earlier messages" flash) and showing "jump to newest". Cold opens worked (rows
    // arrived while the list was empty), which is why it was intermittent.
    var anchoredToTail by remember { mutableStateOf(false) }

    // Track item-count growth: advance or count unread depending on the anchor state.
    LaunchedEffect(state.rows.size) {
        if (!anchoredToTail && state.rows.isNotEmpty()) {
            // Header items ("loading earlier…"/error strips) occupy index 0+.
            val headerItems =
                (if (state.loadingOlder) 1 else 0) + (if (state.loadOlderError != null) 1 else 0)
            listState.scrollToItem(headerItems + state.rows.size - 1)
            anchoredToTail = true
            lastKnownCount = state.rows.size
            return@LaunchedEffect
        }
        val grew = state.rows.size > lastKnownCount
        if (grew) {
            if (followBottom && atBottom) {
                if (state.rows.isNotEmpty()) listState.scrollToItem(state.rows.size - 1)
            } else if (lastKnownCount > 0) {
                unread += state.rows.size - lastKnownCount
            }
        }
        lastKnownCount = state.rows.size
    }
    // Streaming growth also counts when released from the tail.
    LaunchedEffect(state.streaming.values.sumOf { it.length }) {
        if (!followBottom || !atBottom) {
            // coarse: any stream delta while scrolled up marks unread
            unread += 1
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { atBottom }.collect { at ->
            if (at) {
                followBottom = true
                unread = 0
            }
        }
    }

    // §19.2 prefetch: near the top edge with more history → fetch the previous page.
    LaunchedEffect(state.hasMoreHistory) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (anchoredToTail && state.hasMoreHistory && !state.loadingOlder && index <= PAGE_EDGE && state.rows.isNotEmpty()) {
                onLoadOlder(state.rows.first().rowId) { rowsAdded ->
                    if (rowsAdded > 0) {
                        // Preserve the reader's position across the prepend (§19.4).
                        scope.launch { listState.scrollToItem(index + rowsAdded) }
                    }
                }
            }
        }
    }

    // weight(1f), NOT fillMaxSize — the pinned composer (§17.4) lives BELOW this
    // box in the Column; fillMaxSize consumed the entire column and pushed the
    // composer off-screen the moment rows loaded (field: 2026-10-01, vc20).
    Box(Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("conversation_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.loadingOlder) {
                item(key = "older-loading") {
                    Row(Modifier.fillMaxWidth().testTag("conversation_older_loading"), horizontalArrangement = Arrangement.Center) {
                        Text("Loading earlier messages…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.loadOlderError != null) {
                item(key = "older-error") {
                    AttentionStrip(
                        title = "Couldn't load earlier messages",
                        message = state.loadOlderError.userMessage,
                        tone = StatusTone.ERROR,
                        actionLabel = "Retry",
                        onAction = { state.rows.firstOrNull()?.let { onRetryOlder(it.rowId) } },
                        modifier = Modifier.testTag("conversation_older_error"),
                    )
                }
            }
            items(
                count = state.rows.size,
                key = { state.rows[it].rowId },
                contentType = { state.rows[it].kind },
            ) { index ->
                TimelineRow(
                    row = state.rows[index],
                    state = state,
                    modifier = Modifier.testTag("conv-row-${state.rows[index].rowId}"),
                )
            }
        }
        if (!atBottom) {
            FloatingActionButton(
                onClick = {
                    followBottom = true
                    unread = 0
                    scope.launch {
                        if (state.rows.isNotEmpty()) listState.animateScrollToItem(state.rows.size - 1)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("conversation_jump_latest"),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box {
                    Icon(imageVector = Icons.Filled.ArrowDownward, contentDescription = "Jump to latest")
                    if (unread > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(16.dp)
                                .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                                .testTag("conversation_unread_badge"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(row: ConversationRow, state: ConversationUiState, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
    when (row) {
        is ConversationRow.UserInput -> YouBlock(row.text)
        is ConversationRow.AssistantText -> {
            // §19.2 merge: the store row accumulates appends IMMEDIATELY (authoritative),
            // while the coalesced streaming buffer may LEAD it between delta and flush.
            // If the persisted text already ends with the buffered suffix, the store is
            // flushed — display it; otherwise append the suffix to the persisted base.
            val streamed = state.streaming[row.rowId]
            val text = when {
                streamed == null -> row.text
                row.text.endsWith(streamed) -> row.text
                else -> row.text + streamed
            }
            AgentProse(text = text, streaming = row.state == ConversationRow.TextState.STREAMING || streamed != null)
        }
        is ConversationRow.ToolCall -> ToolCard(row)
        is ConversationRow.Reasoning -> ReasoningRow(row)
        is ConversationRow.TurnHeader -> TurnSeparator(row)
        is ConversationRow.Artifact -> QuietMetaRow(
            label = row.displayName.ifBlank { "Artifact" },
            detail = formatBytes(row.sizeBytes),
        )
        is ConversationRow.Subagent -> QuietMetaRow(label = "Subagent · ${row.subagentType}", detail = row.summaryText.take(80))
        is ConversationRow.HookInvocation -> QuietMetaRow(label = "Hook · ${row.hookEventName}", detail = null)
        is ConversationRow.TimelineMarker -> QuietMetaRow(label = row.markerType, detail = null)
        is ConversationRow.Unknown -> UnknownKindRow(row)
    }
    }

}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    bytes > 0 -> "$bytes B"
    else -> ""
}

private const val PAGE_EDGE = 12
