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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.domain.model.PluginsView
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
 * GAP-7 (android-native-gaps.md; docs/protocol/v4-wire.md §12.3) — the
 * read-only PLUGINS viewer (Settings → Insights): the installed-plugin list
 * with the enable/disable STATE (§13.3 `plugins`/`plugin-management` R rows)
 * plus diagnostics. The write half — install/uninstall/update/enable/
 * configure, marketplace browsing — is Deferred scope (§11 trust T-item) and
 * exists NOWHERE on this surface (INV-9; pinned by the UI test + the transport
 * typed-surface test).
 */

interface PluginsPort {
    data class Result(val view: PluginsView?, val error: String?)

    suspend fun plugins(): Result

    /**
     * ADR-023 (Phase 5D, A3.19): the ONE sanctioned plugin write — enable/disable an
     * ALREADY-installed plugin. `false` = refused (the viewer re-lists from the wire and
     * keeps the rendered state wire-true; no optimistic flip).
     */
    suspend fun setEnabled(pluginId: String, enabled: Boolean): Boolean = false
}

@Composable
fun PluginsViewerScreen(
    port: PluginsPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<PluginsPort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.plugins(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.plugins_title),
                onBack = onBack,
                backTestTag = "plugins_back",
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
                    result = port.plugins()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(inner).testTag("plugins_pull"),
        ) {
        Column(
            Modifier
                .fillMaxSize(),
        ) {
            when {
                loading && result == null ->
                    LoadingSkeleton(Modifier.fillMaxSize().testTag("plugins_loading"), rows = 4)
                result?.error != null ->
                    ErrorState(
                        title = stringResource(R.string.plugins_unavailable),
                        message = friendlyFaultMessage(result?.error),
                        modifier = Modifier.testTag("plugins_error"),
                        onRetry = { loading = true; scope.launch { result = port.plugins(); loading = false } },
                    )
                result?.view?.plugins?.isEmpty() == true ->
                    EmptyState(title = stringResource(R.string.plugins_empty), modifier = Modifier.fillMaxSize().testTag("plugins_empty"))
                else -> {
                    val view = result?.view ?: return@Column
                    val notifier = com.zcode.android.core.ui.components.LocalNotifier.current
                    val toggleFailedCopy = stringResource(R.string.plugin_toggle_failed)
                    var busyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .testTag("plugins_list"),
                    ) {
                        items(view.plugins, key = { it.id }) { plugin ->
                            // ADR-023: the enable Switch rides the sanctioned word; the
                            // ack drives a RE-LIST (wire-true, no optimistic flip), a second
                            // tap while busy is dropped (B2.11).
                            PluginLine(
                                plugin,
                                busy = plugin.id in busyIds,
                                onToggle = { enabled ->
                                    if (plugin.id in busyIds) return@PluginLine
                                    busyIds = busyIds + plugin.id
                                    scope.launch {
                                        try {
                                            val ok = port.setEnabled(plugin.id, enabled)
                                            if (!ok) notifier.show(toggleFailedCopy, long = true)
                                            result = port.plugins()
                                        } finally {
                                            busyIds = busyIds - plugin.id
                                        }
                                    }
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        view.diagnostics.forEach { diag ->
                            item(key = diag.code + diag.pluginId) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 6.dp).testTag("plugin_diag_${diag.code}"),
                                ) {
                                    Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 6.dp),
                                    )
                                    Text(
                                        "${diag.code} — ${diag.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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
private fun PluginLine(plugin: PluginsView.PluginInfo, busy: Boolean = false, onToggle: ((Boolean) -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("plugin_row_${plugin.id}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                plugin.name + (plugin.version?.let { "  $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            plugin.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // M5.5: the desktop filesystem path FRAGMENT no longer leaks as metadata
            // (INV-16: paths are the desktop's, not display copy).
            Text(
                plugin.marketplace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // ADR-023: the badge keeps the state summary; the Switch is the sanctioned
        // enable affordance (the ack drives the re-list — wire-true, no optimistic flip).
        onToggle?.let { toggle ->
            androidx.compose.material3.Switch(
                checked = plugin.enabled,
                onCheckedChange = toggle,
                enabled = !busy,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag("plugin_switch_${plugin.id}"),
            )
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (plugin.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.testTag("plugin_state_badge"),
        ) {
            Text(
                stringResource(if (plugin.enabled) R.string.plugin_on else R.string.plugin_off),
                style = MaterialTheme.typography.labelSmall,
                color = if (plugin.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
