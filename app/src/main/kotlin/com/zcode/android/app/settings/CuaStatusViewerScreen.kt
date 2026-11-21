package com.zcode.android.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.core.ui.components.ErrorState
import com.zcode.android.core.ui.components.LoadingSkeleton
import com.zcode.android.core.ui.components.ZCodeTopBar
import com.zcode.android.core.ui.components.friendlyFaultMessage
import com.zcode.android.core.ui.components.rememberZCodeTopBarScroll
import com.zcode.android.domain.model.CuaPermissionStatus
import kotlinx.coroutines.launch

/**
 * pass-0 Phase 10 — G101: the CUA permission-status read mirror. ONE card fed
 * by `cua-permission.getStatus` rendering the desktop's Computer Use health
 * (the grant owner, the accessibility/screen-recording probes, idle) — the
 * plane the phone previously saw only through PUSHED observations. restartHelper
 * is macOS-only authoring and has NO phone affordance (the dated no-action
 * decision rides the ledger).
 */
interface CuaStatusPort {
    data class Result(val status: CuaPermissionStatus?, val error: String?)

    suspend fun status(): Result
}

@Composable
fun CuaStatusViewerScreen(
    port: CuaStatusPort,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<CuaStatusPort.Result?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(port) { scope.launch { result = port.status(); loading = false } }

    val topBarScroll = rememberZCodeTopBarScroll()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(topBarScroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ZCodeTopBar(
                title = stringResource(R.string.cua_title),
                onBack = onBack,
                backTestTag = "cua_status_back",
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
                    result = port.status()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(inner).testTag("cua_status_pull"),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                when {
                    loading && result == null ->
                        LoadingSkeleton(Modifier.fillMaxSize().testTag("cua_status_loading"), rows = 3)
                    result?.error != null ->
                        ErrorState(
                            title = stringResource(R.string.cua_unavailable),
                            message = friendlyFaultMessage(result?.error),
                            modifier = Modifier.testTag("cua_status_error"),
                            onRetry = { loading = true; scope.launch { result = port.status(); loading = false } },
                        )
                    else -> {
                        val s = result?.status ?: return@Column
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .testTag("cua_status_card"),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(
                                            if (s.available == true) R.string.cua_available else R.string.cua_not_available,
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (s.idle == true) {
                                        Text(
                                            stringResource(R.string.cua_idle),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                s.reason?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp).testTag("cua_status_reason"),
                                    )
                                }
                                s.grantOwnerDisplayName?.let { owner ->
                                    StatusField(
                                        label = stringResource(R.string.cua_owner),
                                        value = owner,
                                        tag = "cua_status_owner",
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                s.accessibility?.let {
                                    ProbeField(stringResource(R.string.cua_accessibility), it, it.ok(s.accessibilityProbeOk))
                                }
                                s.screenRecording?.let {
                                    ProbeField(stringResource(R.string.cua_screen_recording), it, s.screenCaptureProbeOk)
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** accessibilityProbeOk refines the plain accessibility grant (desktop `===!0`). */
private fun Boolean.ok(probeOk: Boolean?): Boolean = if (probeOk == null) this else this && probeOk

@Composable
private fun StatusField(label: String, value: String, tag: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(0.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ProbeField(label: String, granted: Boolean, probeOk: Boolean?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = when {
                granted && probeOk != null && !probeOk -> "granted (probe failed)"
                granted -> "✓"
                else -> "✗"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("cua_probe_${label.take(3)}"),
        )
    }
}
