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
import kotlinx.coroutines.launch

/**
 * G30 (vc51 Phase 4B) — the read-only BOTS viewer (Settings → Insights): the INV-9 seed.
 * The phone previously had NO bot surface; the viewer renders the wire's bot inventory
 * read-only (provider/mode badges, workspace + active task). Deeper bot management
 * (create/edit/credentials — the desktop's BotsDialog plane) is a DATED B3 boundary.
 * The shipped 3.14.5 host has no bots list RPC (fs-backed provider runtimes), so against
 * the real desktop the viewer renders its honest-fault face; the rig serves the
 * scripted inventory.
 */
interface BotsPort {
    data class Result(val bots: List<com.zcode.android.domain.model.SlashCatalogExtras.BotEntry>?, val error: String?)

    suspend fun bots(): Result
}

@Composable
fun BotsViewerScreen(
    port: BotsPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<BotsPort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.bots(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.bots_title),
                onBack = onBack,
                backTestTag = "bots_back",
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
                    result = port.bots()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(inner).testTag("bots_pull"),
        ) {
            Column(Modifier.fillMaxSize()) {
                when {
                    loading && result == null ->
                        LoadingSkeleton(Modifier.fillMaxSize().testTag("bots_loading"), rows = 4)
                    result?.error != null ->
                        ErrorState(
                            title = stringResource(R.string.bots_unavailable),
                            message = friendlyFaultMessage(result?.error),
                            modifier = Modifier.testTag("bots_error"),
                            onRetry = { loading = true; scope.launch { result = port.bots(); loading = false } },
                        )
                    result?.bots?.isEmpty() == true ->
                        EmptyState(title = stringResource(R.string.bots_empty), modifier = Modifier.fillMaxSize().testTag("bots_empty"))
                    else -> {
                        val bots = result?.bots ?: return@Column
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .testTag("bots_list"),
                        ) {
                            items(bots, key = { it.botId }) { bot ->
                                BotLine(bot)
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
private fun BotLine(bot: com.zcode.android.domain.model.SlashCatalogExtras.BotEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("bot_row_${bot.botId}"),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                bot.botId,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                bot.workspacePath ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            bot.activeTaskId?.let { active ->
                Text(
                    active,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.testTag("bot_provider_badge_${bot.botId}"),
            ) {
                Text(
                    bot.provider,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (bot.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("bot_state_badge_${bot.botId}"),
            ) {
                Text(
                    bot.mode + if (bot.enabled) "" else " · off",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bot.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}
