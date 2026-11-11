package com.zcode.android.features.conversation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.contextUsageBreakdownRowLabel
import com.zcode.android.core.ui.components.contextUsageLabel
import com.zcode.android.domain.model.SessionUsageState
import com.zcode.android.features.conversation.R
import java.util.Locale
import com.zcode.android.core.ui.components.animateCollapse
import com.zcode.android.core.ui.components.minRowTouchTarget
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription

/**
 * GAP-1/GAP-2 (android-native-gaps.md; docs/protocol/v4-wire.md §11.1) — the
 * conversation-header context panel, ported from the desktop's contextUsage meter
 * (packages/ui/src/chat-input-toolbar/contextUsage.tsx):
 *  - renders NOTHING when the usage key is absent/null, `contextWindow` is null,
 *    or used/size are non-positive (the getRenderableTaskUsage gate — "used=0 或
 *    非法值…不能渲染成可用的 context meter", never a misleading 0%);
 *  - the label is `used/size (pct%)` with compact token numbers;
 *  - the per-source breakdown (GAP-2) is COLLAPSED by default — field feedback
 *    (2026-10-09, vc37): the always-open breakdown consumed ~30% of the chat
 *    view. One compact line renders until tapped; the tap toggles the breakdown
 *    (largest first with the desktop's stable source-order tiebreak; unknown
 *    sources render their raw wire string, INV-8 — retained, never guessed).
 */
@Composable
fun ContextUsagePanel(usage: SessionUsageState?, modifier: Modifier = Modifier) {
    if (usage?.renderable != true) return
    val window = usage.contextWindow ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag("context_usage_panel")
            .semantics {
                contentDescription = contextUsageLabel(
                    window.usedTokens ?: 0,
                    window.maxTokens ?: 0,
                )
            },
    ) {
        Column(Modifier.animateCollapse().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val expandState = stringResource(if (expanded) com.zcode.android.core.ui.R.string.a11y_expanded else com.zcode.android.core.ui.R.string.a11y_collapsed)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minRowTouchTarget()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = expandState }
                    .testTag("context_usage_toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = contextUsageLabel(window.usedTokens ?: 0, window.maxTokens ?: 0),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("context_usage_label"),
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                val segments = usage.contextWindowBreakdownSegments
                if (segments.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    segments.forEach { segment ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = contextUsageBreakdownRowLabel(
                                    sourceLabel = breakdownSourceLabel(segment.source),
                                    chars = segment.chars,
                                    percent = segment.percent,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("context_usage_breakdown_row"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun breakdownSourceLabel(source: String): String = when (source) {
    "messages" -> stringResource(R.string.usage_breakdown_messages)
    "system_prompt" -> stringResource(R.string.usage_breakdown_system_prompt)
    "meta_user_context" -> stringResource(R.string.usage_breakdown_meta_user_context)
    "skills" -> stringResource(R.string.usage_breakdown_skills)
    "tool_prompt" -> stringResource(R.string.usage_breakdown_tool_prompt)
    "system_tool_schemas" -> stringResource(R.string.usage_breakdown_system_tool_schemas)
    "mcp_tool_schemas" -> stringResource(R.string.usage_breakdown_mcp_tool_schemas)
    // Unknown source (INV-8): the raw wire string IS the honest label.
    else -> source
}

/**
 * G60 (vc52 Phase 12): the per-task TOKEN usage section (the desktop tokenDebug
 * plane's totals) — the provider is fetched ONCE per sheet open; null provider /
 * null result renders NOTHING (honest absent face). The rows show the token
 * split (input/output/reasoning/cache read/creation) + the model request/error
 * counts — the usage picture the pass-24 upgrade left open.
 */
@Composable
fun TaskTokenUsageSection(
    provider: (suspend () -> com.zcode.android.domain.model.TaskTokenUsage?)?,
    modifier: Modifier = Modifier,
) {
    if (provider == null) return
    var usage by remember { mutableStateOf<com.zcode.android.domain.model.TaskTokenUsage?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(provider) {
        usage = runCatching { provider() }.getOrNull()
        loading = false
    }
    val u = usage ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("task_usage_panel"),
    ) {
        Column(Modifier.animateCollapse().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minRowTouchTarget()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .testTag("task_usage_toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.task_usage_total, u.totalTokens),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).testTag("task_usage_label"),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loading) {
                Text(
                    stringResource(R.string.task_usage_loading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("task_usage_loading"),
                )
            }
            if (expanded) {
                TaskUsageRow("in", u.inputTokens)
                TaskUsageRow("out", u.outputTokens)
                TaskUsageRow("reasoning", u.reasoningTokens)
                TaskUsageRow("cache read", u.cacheReadTokens)
                TaskUsageRow("cache write", u.cacheCreationTokens)
                Text(
                    stringResource(R.string.task_usage_requests, u.modelRequestCount, u.modelErrorCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (u.modelErrorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .testTag("task_usage_requests"),
                )
            }
        }
    }
}

@Composable
private fun TaskUsageRow(label: String, tokens: Long) {
    Text(
        text = "$label · $tokens",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp)
            .testTag("task_usage_row_$label"),
    )
}
