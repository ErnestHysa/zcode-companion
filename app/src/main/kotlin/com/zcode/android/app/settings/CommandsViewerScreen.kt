package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.zcode.android.core.ui.components.LoadingSkeleton
import com.zcode.android.core.ui.components.ZCodeTopBar
import com.zcode.android.core.ui.components.friendlyFaultMessage
import com.zcode.android.core.ui.components.rememberZCodeTopBarScroll
import com.zcode.android.domain.model.SlashCatalogExtras
import kotlinx.coroutines.launch

/**
 * G74 (vc52 Phase 12): the read-only custom slash-COMMAND viewer (Settings →
 * Insights). The desktop authors commands (markdown files with frontmatter —
 * name/description/argument-hint, user vs plugin sources, import copy|symlink);
 * the phone LISTS what the workspace carries (name · source · description ·
 * argumentHint — the same `commands.list` decode the slash panel consumes) with
 * the DATED authoring boundary note (2026-11-11: command authoring is
 * desktop-side — G21's boundary, restated for the commands plane; the honest
 * mobile slice is visibility, not a form).
 */
interface CommandsPort {
    data class Result(val commands: List<SlashCatalogExtras.CommandEntry>?, val error: String?)

    suspend fun commands(): Result
}

@Composable
fun CommandsViewerScreen(
    port: CommandsPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<CommandsPort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.commands(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.commands_viewer_title),
                onBack = onBack,
                backTestTag = "commands_viewer_back",
                scrollBehavior = topBarScroll,
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            when {
                loading && result == null ->
                    LoadingSkeleton(Modifier.fillMaxSize().testTag("commands_loading"), rows = 3)
                result?.error != null ->
                    com.zcode.android.core.ui.components.ErrorState(
                        title = stringResource(R.string.commands_viewer_unavailable),
                        message = friendlyFaultMessage(result?.error),
                        modifier = Modifier.testTag("commands_error"),
                        onRetry = { loading = true; scope.launch { result = port.commands(); loading = false } },
                    )
                result?.commands?.isEmpty() == true ->
                    EmptyState(
                        title = stringResource(R.string.commands_viewer_empty),
                        modifier = Modifier.fillMaxSize().testTag("commands_empty"),
                    )
                else -> {
                    val commands = result?.commands ?: return@Column
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("commands_list")) {
                        items(commands, key = { it.source + "/" + it.name }) { cmd ->
                            CommandLine(cmd)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        item {
                            Text(
                                stringResource(R.string.commands_boundary_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp).testTag("commands_boundary_note"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandLine(cmd: SlashCatalogExtras.CommandEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("command_row_${cmd.source}_${cmd.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "/${cmd.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (cmd.source == "plugin") R.string.commands_source_plugin else R.string.commands_source_user),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cmd.source == "plugin") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).testTag("command_source_${cmd.source}_${cmd.name}"),
                )
            }
            if (cmd.description.isNotBlank()) {
                Text(
                    cmd.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            cmd.inputHint?.let {
                Text(
                    "/${cmd.name} $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
