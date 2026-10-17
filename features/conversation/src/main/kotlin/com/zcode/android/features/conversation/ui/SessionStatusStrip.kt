package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.theme.LocalZCodeColors
import com.zcode.android.core.ui.theme.ZCodeRadius
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.features.conversation.R

/**
 * I2.2 (gaps-and-polish Phase 3C): the session status strip — branch · clean/changes ·
 * agents (desktop `chat.statusPanel.*`) — inside the conversation header area. Data the
 * phone already receives: the git summary channel ([GitSummary]; refused or not a
 * repository → null → NO strip, honest absence INV-11) and the timeline's own running
 * subagents.
 */
@Composable
fun SessionStatusStrip(
    summary: GitSummary?,
    runningAgents: Int,
    modifier: Modifier = Modifier,
) {
    // A non-repository (or refused) workspace has no branch to name — no strip.
    val branch = summary?.branchName?.takeIf { it.isNotBlank() } ?: return
    if (!summary.isRepository) return
    val a11y = listOfNotNull(
        summary.branchName,
        if (summary.isDirty) stringResource(R.string.session_status_dirty) else stringResource(R.string.session_status_clean),
        if (runningAgents > 0) pluralStringResource(R.plurals.session_status_agents, runningAgents, runningAgents) else null,
    ).joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(ZCodeRadius.lg))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .heightIn(min = 32.dp)
            .testTag("session_status_strip")
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.CallSplit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = branch,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (summary.ahead > 0 || summary.behind > 0) {
            Text(
                text = listOfNotNull(
                    "↑${summary.ahead}".takeIf { summary.ahead > 0 },
                    "↓${summary.behind}".takeIf { summary.behind > 0 },
                ).joinToString(" "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary.isDirty) {
            Text(
                text = stringResource(R.string.session_status_dirty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("session_status_dirty"),
            )
        } else {
            Text(
                text = stringResource(R.string.session_status_clean),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("session_status_clean"),
            )
        }
        if (runningAgents > 0) {
            Text(
                text = pluralStringResource(R.plurals.session_status_agents, runningAgents, runningAgents),
                style = MaterialTheme.typography.labelSmall,
                color = LocalZCodeColors.current.statusRunning,
            )
        }
    }
}

/** Running subagent rows in the timeline (the strip's agent count). */
fun countRunningAgents(rows: List<com.zcode.android.domain.model.ConversationRow>): Int =
    rows.count { it is com.zcode.android.domain.model.ConversationRow.Subagent && it.status == com.zcode.android.domain.model.ConversationRow.SubagentStatus.RUNNING }
