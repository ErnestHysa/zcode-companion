package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.core.ui.components.EmptyState
import com.zcode.android.core.ui.components.ErrorState
import com.zcode.android.core.ui.components.LoadingSkeleton
import com.zcode.android.core.ui.components.ZCodeTopBar
import com.zcode.android.core.ui.components.friendlyFaultMessage
import com.zcode.android.core.ui.components.rememberZCodeTopBarScroll
import kotlinx.coroutines.launch

/**
 * G25 (vc51 Phase 4B) — the read-only SUBAGENTS viewer (Settings → Insights), the INV-9
 * viewer pattern (plugins/skills precedent): the phone already consumes `subagents.list`
 * for the slash panel (provider-filtered, disabled filtered); the viewer renders the FULL
 * scripted inventory with scope + enable STATE badges and NO write affordance (deeper
 * subagent management is desktop-side, INV-9).
 */
interface SubagentsPort {
    data class Result(val agents: List<com.zcode.android.domain.model.SlashCatalogExtras.AgentEntryFull>?, val error: String?)

    suspend fun subagents(): Result
}

@Composable
fun SubagentsViewerScreen(
    port: SubagentsPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<SubagentsPort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.subagents(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.subagents_title),
                onBack = onBack,
                backTestTag = "subagents_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        var refreshing by remember { mutableStateOf(false) }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    result = port.subagents()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(inner).testTag("subagents_pull"),
        ) {
            Column(Modifier.fillMaxSize()) {
                when {
                    loading && result == null ->
                        LoadingSkeleton(Modifier.fillMaxSize().testTag("subagents_loading"), rows = 4)
                    result?.error != null ->
                        ErrorState(
                            title = stringResource(R.string.subagents_unavailable),
                            message = friendlyFaultMessage(result?.error),
                            modifier = Modifier.testTag("subagents_error"),
                            onRetry = { loading = true; scope.launch { result = port.subagents(); loading = false } },
                        )
                    result?.agents?.isEmpty() == true ->
                        EmptyState(title = stringResource(R.string.subagents_empty), modifier = Modifier.fillMaxSize().testTag("subagents_empty"))
                    else -> {
                        val agents = result?.agents ?: return@Column
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .testTag("subagents_list"),
                        ) {
                            items(agents, key = { it.name }) { agent ->
                                SubagentLine(agent)
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

@Composable
private fun SubagentLine(agent: com.zcode.android.domain.model.SlashCatalogExtras.AgentEntryFull) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("subagent_row_${agent.name}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                agent.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (agent.description.isNotEmpty()) {
                Text(
                    agent.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            agent.scope?.let { scopeLabel ->
                Text(
                    scopeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("subagent_scope_${agent.name}"),
                )
            }
        }
        // INV-9: state BADGES only — no enable/disable affordance on this surface.
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (agent.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .padding(start = 8.dp)
                .testTag("subagent_state_badge_${agent.name}"),
        ) {
            Text(
                stringResource(if (agent.enabled) R.string.agent_on else R.string.agent_off),
                style = MaterialTheme.typography.labelSmall,
                color = if (agent.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
