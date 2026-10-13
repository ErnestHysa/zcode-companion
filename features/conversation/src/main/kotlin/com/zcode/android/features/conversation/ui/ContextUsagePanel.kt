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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
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
