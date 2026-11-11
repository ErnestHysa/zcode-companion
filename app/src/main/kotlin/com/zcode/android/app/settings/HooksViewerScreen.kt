package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.core.ui.components.EmptyState
import com.zcode.android.core.ui.components.LoadingSkeleton
import com.zcode.android.core.ui.components.ZCodeButton
import com.zcode.android.core.ui.components.ZCodeTopBar
import com.zcode.android.core.ui.components.friendlyFaultMessage
import com.zcode.android.core.ui.components.rememberZCodeTopBarScroll
import com.zcode.android.domain.model.WorkspaceHookReview
import kotlinx.coroutines.launch

/**
 * G71 (vc52 Phase 12): the read-only workspace HOOKS viewer (Settings → Insights).
 *
 * No hooks-list wire verb exists on the desktop (fresh asar 4a6080ed…): hook items
 * only cross the wire inside `workspaceHookReview` interactions, so the viewer reads
 * the LAST review each live session has seen (retained by the session manager after
 * the trust card resolves — ConversationSessionManager.lastHookReview) and offers
 * Refresh, which sends `requestWorkspaceHookReview` so the desktop re-issues the
 * review (the trust card re-surfaces in the conversation; the viewer updates when
 * the fresh interaction arrives). Hook AUTHORING stays desktop-side (Settings →
 * Hooks there) — the pointer below is the honest boundary note.
 */
interface HooksPort {
    data class Entry(val taskId: String, val review: WorkspaceHookReview)

    /** The retained reviews of every live session (empty = none seen yet). */
    suspend fun reviews(): List<Entry>

    /**
     * Ask the desktop to re-issue the review for [taskId]. Null = no live session;
     * false = the wire refused; true = requested.
     */
    suspend fun refresh(taskId: String): Boolean?
}

@Composable
fun HooksViewerScreen(
    port: HooksPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<HooksPort.Entry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    // Per-entry refresh feedback: taskId -> note key (requested / refused / offline).
    var refreshNotes by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        error = runCatching { port.reviews() }
            .onSuccess { entries = it }
            .exceptionOrNull()?.let { it.message ?: "error" }
        loading = false
    }

    androidx.compose.runtime.LaunchedEffect(port) { scope.launch { load() } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.hooks_viewer_title),
                onBack = onBack,
                backTestTag = "hooks_viewer_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            when {
                loading && entries.isEmpty() && error == null ->
                    LoadingSkeleton(Modifier.fillMaxSize().testTag("hooks_viewer_loading"), rows = 3)
                entries.isEmpty() && error != null ->
                    com.zcode.android.core.ui.components.ErrorState(
                        title = stringResource(R.string.hooks_viewer_unavailable),
                        message = friendlyFaultMessage(error),
                        modifier = Modifier.testTag("hooks_viewer_error"),
                        onRetry = { loading = true; scope.launch { load() } },
                    )
                entries.isEmpty() -> EmptyState(
                    title = stringResource(R.string.hooks_viewer_empty),
                    modifier = Modifier.fillMaxSize().testTag("hooks_viewer_empty"),
                )
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("hooks_viewer_list")) {
                    items(entries, key = { it.taskId + "/" + it.review.reviewFlowId }) { entry ->
                        HookReviewGroup(
                            entry = entry,
                            note = refreshNotes[entry.taskId],
                            onRefresh = {
                                scope.launch {
                                    val outcome = runCatching { port.refresh(entry.taskId) }.getOrNull()
                                    refreshNotes = refreshNotes + (entry.taskId to when (outcome) {
                                        true -> R.string.hooks_viewer_refresh_requested
                                        false -> R.string.hooks_viewer_refresh_refused
                                        null -> R.string.hooks_viewer_refresh_offline
                                    })
                                    load()
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                    item {
                        Text(
                            stringResource(R.string.hooks_viewer_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp).testTag("hooks_viewer_note"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HookReviewGroup(
    entry: HooksPort.Entry,
    note: Int?,
    onRefresh: () -> Unit,
) {
    val review = entry.review
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("hooks_group_${entry.taskId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    review.workspaceLabel.ifBlank { review.workspaceIdentity },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.hooks_viewer_summary,
                        review.summary.hookCount,
                        review.summary.pendingCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ZCodeButton(
                onClick = onRefresh,
                modifier = Modifier.testTag("hooks_refresh_${entry.taskId}"),
            ) {
                Text(stringResource(R.string.hooks_viewer_refresh))
            }
        }
        note?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).testTag("hooks_refresh_note_${entry.taskId}"),
            )
        }
        review.items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("hook_item_${item.reviewItemId}")) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(item.event)
                            item.matcher?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.displayCommand.isNotBlank()) {
                        Text(
                            item.displayCommand,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.sourcePath.isNotBlank()) {
                        Text(
                            item.sourcePath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    hookTrustLabel(item.trustState),
                    style = MaterialTheme.typography.labelMedium,
                    color = hookTrustColor(item.trustState),
                    modifier = Modifier.testTag("hook_trust_${item.reviewItemId}"),
                )
            }
        }
    }
}

/** M5.7 discipline: the desktop-localized trust states; unknown values render raw. */
@Composable
private fun hookTrustLabel(state: String): String = stringResource(
    when (state) {
        "not_applicable" -> R.string.hook_trust_not_applicable
        "pending_trust" -> R.string.hook_trust_pending
        "trusted_persistent" -> R.string.hook_trust_trusted
        "blocked_untrusted" -> R.string.hook_trust_blocked_untrusted
        "blocked_policy" -> R.string.hook_trust_blocked_policy
        "revoked" -> R.string.hook_trust_revoked
        "stale_digest" -> R.string.hook_trust_stale
        else -> return state
    },
)

/** M5.4 discipline: theme ROLES, not literal greens/reds; unknown states stay neutral. */
@Composable
private fun hookTrustColor(state: String): androidx.compose.ui.graphics.Color = when (state) {
    "trusted_persistent", "not_applicable" -> MaterialTheme.colorScheme.primary
    "pending_trust", "stale_digest" -> MaterialTheme.colorScheme.onSurfaceVariant
    "blocked_untrusted", "blocked_policy", "revoked" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
