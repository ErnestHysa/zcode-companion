package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.ZCodeModalBottomSheet
import com.zcode.android.domain.model.TrajectoryRecord
import com.zcode.android.domain.model.TrajectoryResult
import com.zcode.android.features.conversation.ConversationViewModel
import com.zcode.android.features.conversation.R

/**
 * A3.13 (gaps-and-polish Phase 5B): the trajectory viewer v1 + conversation search —
 * the model-call timeline (`getModelTrajectory`: kind · model · duration · finish/error ·
 * text head · tool names) with a search field that filters BOTH the timeline AND the
 * session's messages (`readSessionMessages`, client-side contains). The honest
 * unavailable / loading / empty faces render per INV-11.
 */
@Composable
fun TrajectorySheet(
    trajectory: TrajectoryResult?,
    loading: Boolean,
    searchResults: List<ConversationViewModel.SessionSearchResult>?,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ZCodeModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("trajectory_sheet")) {
        Text(
            stringResource(R.string.trajectory_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.trajectory_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("trajectory_search"),
        )
        searchResults?.takeIf { it.isNotEmpty() }?.let { hits ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("trajectory_search_hits")) {
                hits.take(5).forEach { hit ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            hit.role,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("trajectory_hit_${hit.role}"),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(hit.head, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        }
        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp).testTag("trajectory_loading"))
            trajectory == null -> Text(
                stringResource(R.string.trajectory_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).testTag("trajectory_unavailable"),
            )
            trajectory.records.isEmpty() -> Text(
                stringResource(R.string.trajectory_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).testTag("trajectory_empty"),
            )
            else -> {
                val needle = query.trim().lowercase()
                val filtered = if (needle.isEmpty()) trajectory.records else trajectory.records.filter { rec ->
                    listOfNotNull(rec.modelId, rec.textHead, rec.error, rec.finishReason, rec.requestId, rec.callKind)
                        .any { it.lowercase().contains(needle) } ||
                        rec.toolNames.any { it.lowercase().contains(needle) }
                }
                if (trajectory.truncated) {
                    Text(
                        stringResource(R.string.trajectory_truncated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag("trajectory_truncated"),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().height(340.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(filtered, key = { it.requestId + it.attempt }) { rec -> TrajectoryLine(rec) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun TrajectoryLine(rec: TrajectoryRecord) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("trajectory_${rec.requestId}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                rec.callKind,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("trajectory_kind_${rec.callKind}"),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(rec.modelId, rec.durationMs?.let { "${it}ms" }, rec.finishReason).joinToString(" · ").ifBlank { stringResource(R.string.trajectory_call) },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        rec.error?.let { err ->
            Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        rec.textHead?.let { head ->
            Text(head, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (rec.toolNames.isNotEmpty()) {
            Text(
                rec.toolNames.joinToString(", ", prefix = stringResource(R.string.trajectory_tools) + " "),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}
