package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.features.conversation.R

/**
 * I2.3 (Phase 6A): the session summary panel (desktop `chat.summaryPanel.*`) —
 * session duration (turn span), goal iteration, running background tasks
 * (`backgroundWorks[].status == "running"`), running subagents. Collapsed it is a
 * capsule; expanded it is one labeled row per metric. INV-8 absence: nothing to
 * summarize → nothing renders.
 */
@Composable
fun SessionSummaryPanel(
    sessionState: SessionProjectionState?,
    rows: List<ConversationRow>,
    modifier: Modifier = Modifier,
) {
    val spanMs = remember(rows) { turnSpanMs(rows) }
    val goalIteration = sessionState?.goal?.takeIf { it.objective.isNotBlank() }?.iteration
    val background = sessionState?.backgroundWorks?.count { it.status == "running" } ?: 0
    val subagents = countRunningAgents(rows)
    if (spanMs == null && goalIteration == null && background == 0 && subagents == 0) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val duration = spanMs?.let { span ->
        val (h, m, s) = splitDuration(span)
        when {
            h > 0 -> stringResource(R.string.summary_duration_h, h) + " " + stringResource(R.string.summary_duration_m, m)
            else -> stringResource(R.string.summary_duration_m, m) + " " + stringResource(R.string.summary_duration_s, s)
        }
    }

    // One root use of the modifier parameter (ComposeModifierReused); the capsule/
    // panel tags live on the inner elements so the pins stay put.
    Column(modifier = modifier) {
        if (!expanded) {
            val expandLabel = stringResource(R.string.summary_show_panel)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("summary_capsule")
                    .semantics(mergeDescendants = true) { contentDescription = expandLabel }
                    .clickable { expanded = true },
            ) {
                Text(
                    text = listOfNotNull(
                        stringResource(R.string.summary_title),
                        duration,
                        goalIteration?.let { stringResource(R.string.summary_goal_iteration, it) },
                        background.takeIf { it > 0 }?.let { stringResource(R.string.summary_background_tasks, it) },
                        subagents.takeIf { it > 0 }?.let { stringResource(R.string.summary_subagents, it) },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            return@Column
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("summary_panel"),
        ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.summary_show_mini),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .testTag("summary_collapse")
                    .clickable { expanded = false }
                    .padding(6.dp),
            )
        }
        duration?.let {
            SummaryRow(stringResource(R.string.summary_duration_label), it, tag = "summary_duration")
        }
        goalIteration?.let {
            SummaryRow(stringResource(R.string.summary_goal_iteration, it), "", tag = "summary_iteration")
        }
        if (background > 0) {
            SummaryRow(
                stringResource(R.string.summary_background_label),
                stringResource(R.string.summary_background_tasks, background),
                tag = "summary_background",
            )
        }
        if (subagents > 0) {
            SummaryRow(
                stringResource(R.string.summary_subagent_label),
                stringResource(R.string.summary_subagents, subagents),
                tag = "summary_subagents",
            )
        }
    }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, tag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

/**
 * The turn span (desktop summaryPanel duration): first `turnHeader.startedAt` to the
 * last known end (`endedAt`, else `startedAt + activeMs`). Purely row-derived — no
 * clock read, so the label is deterministic for a settled session.
 */
fun turnSpanMs(rows: List<ConversationRow>): Long? {
    val turns = rows.filterIsInstance<ConversationRow.TurnHeader>()
    val start = turns.minOfOrNull { it.startedAt } ?: return null
    val end = turns.maxOf { turn ->
        turn.endedAt ?: (turn.startedAt + (turn.activeMs?.toDouble() ?: 0.0))
    }
    return (end - start).toLong().coerceAtLeast(0L)
}

fun splitDuration(spanMs: Long): Triple<Int, Int, Int> {
    val totalSeconds = spanMs / 1000
    return Triple(
        (totalSeconds / 3600).toInt(),
        ((totalSeconds % 3600) / 60).toInt(),
        (totalSeconds % 60).toInt(),
    )
}
