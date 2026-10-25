package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.domain.model.McpStatuses
import kotlinx.coroutines.launch
import com.zcode.android.core.ui.components.ZCodeButton
import androidx.compose.material3.Scaffold
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.zcode.android.core.ui.components.ZCodeTopBar
import com.zcode.android.core.ui.components.rememberZCodeTopBarScroll
import com.zcode.android.core.ui.components.EmptyState
import com.zcode.android.core.ui.components.ErrorState
import com.zcode.android.core.ui.components.LoadingSkeleton
import com.zcode.android.core.ui.components.friendlyFaultMessage

/**
 * GAP-9 (android-native-gaps.md; docs/protocol/v4-wire.md §12.5) — the
 * read-only MCP SERVERS viewer (Settings → Insights): the `mcp/list` status
 * record rendered VERBATIM (status/transport/toolCount + error/failureKind —
 * the wire's closed enums display as-is, no invented states). The phone reads
 * `mode: "status"` (passive last-known); NO connect/disconnect/enable/add
 * affordance exists (INV-9).
 */
interface McpPort {
    data class Result(val statuses: McpStatuses?, val error: String?)

    suspend fun statuses(): Result
}

@Composable
fun McpViewerScreen(
    port: McpPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<McpPort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.statuses(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.mcp_title),
                onBack = onBack,
                backTestTag = "mcp_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        var refreshing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    result = port.statuses()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(inner).testTag("mcp_pull"),
        ) {
        Column(
            Modifier
                .fillMaxSize(),
        ) {
            when {
                loading && result == null ->
                    LoadingSkeleton(Modifier.fillMaxSize().testTag("mcp_loading"), rows = 4)
                result?.error != null ->
                    ErrorState(
                        title = stringResource(R.string.mcp_unavailable),
                        message = friendlyFaultMessage(result?.error),
                        modifier = Modifier.testTag("mcp_error"),
                        onRetry = { loading = true; scope.launch { result = port.statuses(); loading = false } },
                    )
                result?.statuses?.statuses?.isEmpty() == true ->
                    EmptyState(title = stringResource(R.string.mcp_empty), modifier = Modifier.fillMaxSize().testTag("mcp_empty"))
                else -> {
                    val statuses = result?.statuses?.statuses ?: return@Column
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("mcp_list")) {
                        items(statuses.keys.sorted(), key = { it }) { name ->
                            McpLine(name, statuses[name]!!)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
        }
    }
}

/**
 * M5.4: status COLORS are theme ROLES — the light-weight literal greens/reds computed
 * ≈3.0–3.5:1 against the dark surface (below AA at 12sp); the designed roles pass in
 * both themes (J6's computed table). Unknown future values stay neutral — never guessed.
 */
@Composable
private fun statusColor(status: String): Color? = when (status) {
    "connected" -> MaterialTheme.colorScheme.primary
    "connecting" -> null // neutral
    "failed", "untrusted" -> MaterialTheme.colorScheme.error
    "disabled" -> null
    "disconnected" -> null
    else -> null // unknown future values stay neutral — never guessed
}

/**
 * M5.7: the desktop LOCALIZES these exact enums (`settings.mcp.status.*Reason`) —
 * the phone rendered them verbatim (zh users got English words). Unknown future
 * values render raw (INV-8), never guessed copy.
 */
@Composable
private fun mcpStatusLabel(status: String): String = stringResource(
    when (status) {
        "connected" -> R.string.mcp_status_connected
        "connecting" -> R.string.mcp_status_connecting
        "disconnected" -> R.string.mcp_status_disconnected
        "failed" -> R.string.mcp_status_failed
        "untrusted" -> R.string.mcp_status_untrusted
        "disabled" -> R.string.mcp_status_disabled
        else -> return status
    },
)

@Composable
private fun McpLine(name: String, s: McpStatuses.McpServerStatus) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("mcp_row_$name"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Verbatim payload fields: transport + toolCount + failureKind/error when present.
            val detail = buildString {
                append(s.transport)
                append(" · ")
                append(stringResource(R.string.mcp_tool_count, s.toolCount))
                s.error?.let { append(" — ").append(it) }
                s.failureKind?.let { append(" (").append(it).append(")") }
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val color = statusColor(s.status)
        Text(
            mcpStatusLabel(s.status),
            style = MaterialTheme.typography.labelMedium,
            color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .testTag("mcp_state_$name")
                .padding(start = 8.dp),
        )
    }
}
