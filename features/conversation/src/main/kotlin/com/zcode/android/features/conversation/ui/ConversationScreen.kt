package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Conversation surface (§43.2-6.3/6.4; ui-ux 06/07/09/10/12). Tail-first render,
 * `rowsRange` scroll-back with page-edge prefetch, stream-follow anchoring with
 * jump-to-latest + unread count, and the mandatory state strips (INV-11).
 * Phase 6: NO composer — the write path is Phase 7.
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    phase: ConnectionPhase,
    onBack: () -> Unit,
    onLoadOlder: (beforeRowId: Long, onResult: (Int) -> Unit) -> Unit,
    onDismissHistoryNotice: () -> Unit,
    onRetryOlder: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
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
            if (state.taskState == TaskStateMachine.State.WAITING_FOR_PERMISSION ||
                state.taskState == TaskStateMachine.State.WAITING_FOR_USER
            ) {
                AttentionStrip(
                    title = "Needs attention",
                    message = "Agent is waiting — respond on desktop for now.",
                    tone = StatusTone.WAITING,
                    modifier = Modifier.testTag("conversation_attention_strip"),
                )
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
private fun LoadingBody() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp)) {
        repeat(4) {
            SkeletonBar(Modifier.fillMaxWidth(if (it % 2 == 0) 0.85f else 0.6f).testTag("conversation_skeleton"))
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun EmptyBody() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).testTag("conversation_empty"),
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
private fun TimelineBody(
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

    // Track item-count growth: advance or count unread depending on the anchor state.
    LaunchedEffect(state.rows.size) {
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
            if (state.hasMoreHistory && !state.loadingOlder && index <= PAGE_EDGE && state.rows.isNotEmpty()) {
                onLoadOlder(state.rows.first().rowId) { rowsAdded ->
                    if (rowsAdded > 0) {
                        // Preserve the reader's position across the prepend (§19.4).
                        scope.launch { listState.scrollToItem(index + rowsAdded) }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                TimelineRow(row = state.rows[index], state = state)
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
private fun TimelineRow(row: ConversationRow, state: ConversationUiState) {
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    bytes > 0 -> "$bytes B"
    else -> ""
}

private const val PAGE_EDGE = 12
