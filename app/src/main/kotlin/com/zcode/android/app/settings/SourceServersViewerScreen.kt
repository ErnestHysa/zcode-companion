package com.zcode.android.app.settings

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
import com.zcode.android.domain.model.SourceServersView
import kotlinx.coroutines.launch

/**
 * pass-0 Phase 10 — G98: the source-server READ-ONLY mirror. The desktop's
 * `source-server-management.getSnapshot` directory (SSH/WSL/Docker compute
 * hosts) rendered as a list: display name, host/os-user, the connection state
 * (the raw 10-value vocabulary — unknown states display truthfully), and the
 * declared-workspace count. Authoring (connect/rename/remove, asset installs)
 * stays desktop-only — the mirror has no affordance (INV-9).
 */
interface SourceServersPort {
    data class Result(val view: SourceServersView?, val error: String?)

    suspend fun servers(): Result
}

@Composable
fun SourceServersViewerScreen(
    port: SourceServersPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<SourceServersPort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.servers(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.srcsrv_title),
                onBack = onBack,
                backTestTag = "srcsrv_back",
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
                    result = port.servers()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(inner).testTag("srcsrv_pull"),
        ) {
            Column(Modifier.fillMaxSize()) {
                when {
                    loading && result == null ->
                        LoadingSkeleton(Modifier.fillMaxSize().testTag("srcsrv_loading"), rows = 3)
                    result?.error != null ->
                        ErrorState(
                            title = stringResource(R.string.srcsrv_unavailable),
                            message = friendlyFaultMessage(result?.error),
                            modifier = Modifier.testTag("srcsrv_error"),
                            onRetry = { loading = true; scope.launch { result = port.servers(); loading = false } },
                        )
                    else -> {
                        val view = result?.view ?: return@Column
                        if (view.servers.isEmpty()) {
                            EmptyState(
                                title = stringResource(R.string.srcsrv_empty),
                                modifier = Modifier.testTag("srcsrv_empty"),
                            )
                            return@Column
                        }
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("srcsrv_list")) {
                            items(view.servers, key = { it.sourceServerId }) { server ->
                                SourceServerLine(server, view.states.firstOrNull { it.sourceServerId == server.sourceServerId })
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
private fun SourceServerLine(
    server: SourceServersView.SourceServerInfo,
    state: SourceServersView.SourceServerState?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("srcsrv_row_${server.sourceServerId}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                server.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            state?.let {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (it.state == "online") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.testTag("srcsrv_state_${server.sourceServerId}"),
                ) {
                    Text(
                        it.state,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.state == "online") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        val host = listOfNotNull(server.hostname, server.osUser?.let { "· $it" }).joinToString(" ")
        if (host.isNotBlank()) {
            Text(
                host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state?.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("srcsrv_error_${server.sourceServerId}"),
            )
        }
        Text(
            stringResource(R.string.srcsrv_workspaces, server.declaredWorkspaces.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("srcsrv_ws_${server.sourceServerId}"),
        )
    }
}
