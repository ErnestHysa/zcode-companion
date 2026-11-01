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
import com.zcode.android.domain.model.ModelProviderOption
import kotlinx.coroutines.launch

/**
 * G27 (vc51 Phase 4B) — the read-only MODEL-PROVIDER viewer (Settings → Insights, the
 * INV-9 viewer pattern): the same wire view the §19.5 model picker consumes
 * (`model-selection.getView` → provider groups + display badges), rendered as a
 * reference surface. NO credential plane exists or renders here — API keys never cross
 * the v4 wire and provider credential entry stays a DESKTOP-SIDE surface (dated B3
 * boundary, vc51 report). Deeper provider management is desktop-side (INV-9).
 */
interface ProvidersPort {
    data class Result(val providers: List<ModelProviderOption>?, val error: String?)

    suspend fun providers(): Result
}

@Composable
fun ProvidersViewerScreen(
    port: ProvidersPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<ProvidersPort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.providers(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.providers_title),
                onBack = onBack,
                backTestTag = "providers_back",
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
                    result = port.providers()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(inner).testTag("providers_pull"),
        ) {
            Column(Modifier.fillMaxSize()) {
                when {
                    loading && result == null ->
                        LoadingSkeleton(Modifier.fillMaxSize().testTag("providers_loading"), rows = 4)
                    result?.error != null ->
                        ErrorState(
                            title = stringResource(R.string.providers_unavailable),
                            message = friendlyFaultMessage(result?.error),
                            modifier = Modifier.testTag("providers_error"),
                            onRetry = { loading = true; scope.launch { result = port.providers(); loading = false } },
                        )
                    result?.providers?.isEmpty() == true ->
                        EmptyState(title = stringResource(R.string.providers_empty), modifier = Modifier.fillMaxSize().testTag("providers_empty"))
                    else -> {
                        val providers = result?.providers ?: return@Column
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .testTag("providers_list"),
                        ) {
                            providers.forEach { provider ->
                                item(key = "header-${provider.providerId}") {
                                    ProviderHeader(provider)
                                }
                                items(provider.models, key = { "${provider.providerId}-${it.id}" }) { model ->
                                    ProviderModelLine(provider.providerId, model)
                                }
                                item(key = "pad-${provider.providerId}") {
                                    Spacer(Modifier.height(12.dp))
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
private fun ProviderHeader(provider: ModelProviderOption) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
            .testTag("provider_header_${provider.providerId}"),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (provider.providerName ?: provider.providerId).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        provider.badge?.let { badge ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag("provider_badge_${provider.providerId}"),
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderModelLine(providerId: String, model: com.zcode.android.domain.model.ModelOption) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("provider_model_${providerId}_${model.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                model.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            model.badge?.let { badge ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .testTag("provider_model_badge_${providerId}_${model.id}"),
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            model.id,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), modifier = Modifier.padding(top = 4.dp))
    }
}
