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
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner),
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
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .testTag("plugins_list"),
                    ) {
                        items(view.plugins, key = { it.id }) { plugin ->
                            PluginLine(plugin)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        view.diagnostics.forEach { diag ->
                            item(key = diag.code + diag.pluginId) {
                                Text(
                                    "⚠ ${diag.code} — ${diag.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 6.dp).testTag("plugin_diag_${diag.code}"),
                                )
                            }
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginLine(plugin: PluginsView.PluginInfo) {
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
            Text(
                plugin.marketplace + " · " + plugin.rootPath.substringAfterLast('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Display-only state chip (§13.3 "enable/disable state DISPLAY") — the
        // onClick is a no-op and no enabled-write method exists anywhere.
        AssistChip(
            onClick = {},
            enabled = false,
            label = {
                Text(
                    stringResource(if (plugin.enabled) R.string.plugin_on else R.string.plugin_off),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledLabelColor = if (plugin.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.testTag("plugin_state_${plugin.id}"),
        )
    }
}
