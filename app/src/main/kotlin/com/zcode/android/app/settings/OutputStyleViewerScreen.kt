package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.zcode.android.domain.model.OutputStylesView
import kotlinx.coroutines.launch

/**
 * pass-0 Phase 10 — G84: the output-style READ-ONLY mirror (`listStyles` +
 * `getActiveStyle`, two of the desktop service's seven verbs). The list renders
 * with the ACTIVE badge; setActiveStyle/addStyle/updateStyle/deleteStyle are
 * authoring and exist nowhere phone-side (INV-9 — no switch, no action).
 */
interface OutputStylePort {
    data class Result(val view: OutputStylesView?, val error: String?)

    suspend fun styles(): Result
}

@Composable
fun OutputStyleViewerScreen(
    port: OutputStylePort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<OutputStylePort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.styles(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.style_title),
                onBack = onBack,
                backTestTag = "style_back",
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
                    result = port.styles()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(inner).testTag("style_pull"),
        ) {
            Column(Modifier.fillMaxSize()) {
                when {
                    loading && result == null ->
                        LoadingSkeleton(Modifier.fillMaxSize().testTag("style_loading"), rows = 4)
                    result?.error != null ->
                        ErrorState(
                            title = stringResource(R.string.style_unavailable),
                            message = friendlyFaultMessage(result?.error),
                            modifier = Modifier.testTag("style_error"),
                            onRetry = { loading = true; scope.launch { result = port.styles(); loading = false } },
                        )
                    else -> {
                        val view = result?.view ?: return@Column
                        if (view.styles.isEmpty()) {
                            EmptyState(
                                title = stringResource(R.string.style_empty),
                                modifier = Modifier.testTag("style_empty"),
                            )
                            return@Column
                        }
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("style_list")) {
                            items(view.styles, key = { it.id }) { style ->
                                StyleLine(style, view.activeStyleId == style.id)
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
private fun StyleLine(style: OutputStylesView.OutputStyle, active: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("style_row_${style.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                style.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            style.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                // Source: built-in vs the user's ~/.claude/output-styles custom (the
                // filePath FRAGMENT is not display copy — INV-16).
                if (style.isBuiltIn) "built-in" else "custom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("style_source_${style.id}"),
            )
        }
        if (active) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag("style_active_${style.id}"),
            ) {
                Text(
                    stringResource(R.string.style_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}
