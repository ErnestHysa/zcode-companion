package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.zcode.android.domain.model.ClientScenesView
import kotlinx.coroutines.launch

/**
 * pass-0 Phase 10 — G83: the client-scenes ONE-VERB read mirror (the smallest
 * consume on the board). The desktop's `client-scenes.list` serves its cloud
 * draft-suggestion feed; the phone renders the prompts READ-ONLY (tapping one
 * does nothing — no send path exists from this viewer, INV-9).
 */
interface ClientScenesPort {
    data class Result(val view: ClientScenesView?, val error: String?)

    suspend fun scenes(): Result
}

@Composable
fun ClientScenesViewerScreen(
    port: ClientScenesPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<ClientScenesPort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.scenes(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.scenes_title),
                onBack = onBack,
                backTestTag = "scenes_back",
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
                    result = port.scenes()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(inner).testTag("scenes_pull"),
        ) {
            Column(Modifier.fillMaxSize()) {
                when {
                    loading && result == null ->
                        LoadingSkeleton(Modifier.fillMaxSize().testTag("scenes_loading"), rows = 4)
                    result?.error != null ->
                        ErrorState(
                            title = stringResource(R.string.scenes_unavailable),
                            message = friendlyFaultMessage(result?.error),
                            modifier = Modifier.testTag("scenes_error"),
                            onRetry = { loading = true; scope.launch { result = port.scenes(); loading = false } },
                        )
                    else -> {
                        val view = result?.view ?: return@Column
                        if (view.businessError || view.suggestedPrompts.isEmpty()) {
                            EmptyState(
                                title = stringResource(R.string.scenes_empty),
                                modifier = Modifier.testTag("scenes_empty"),
                            )
                            return@Column
                        }
                        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("scenes_list")) {
                            items(view.suggestedPrompts, key = { it.id }) { prompt ->
                                ScenePromptLine(prompt)
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
private fun ScenePromptLine(prompt: ClientScenesView.SuggestedPrompt) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("scenes_row_${prompt.id}"),
    ) {
        Text(
            prompt.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
