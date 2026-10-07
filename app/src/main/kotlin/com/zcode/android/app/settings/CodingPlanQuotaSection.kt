package com.zcode.android.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.core.ui.components.CodingPlanQuotaPresentation
import com.zcode.android.domain.model.UsageEntitlement
import com.zcode.android.domain.model.UsageQuotaLimit
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch

/**
 * GAP-3 (android-native-gaps.md; docs/protocol/v4-wire.md §13.1; ADR-020) — the
 * Coding-Plan quota section, the desktop ChatCodingPlanUsageRemainingPanel port:
 * four meters (5-hour / weekly / tool-calls / MCP — the desktop's EXACT
 * `codingPlanQuotaPresentation` lookups) each rendering remaining-% + reset time,
 * the desktop's unavailable-face mapping (unavailableReason → message, raw wire
 * value kept visible per the §9 raw-enum policy), and the roadmap's degradation
 * rule — channel absence/teardown renders NOTHING (the desktop's own panel
 * returns null without state, CodingPlanContextUsage.tsx:206-208). Client-polled:
 * one read per mount + explicit refresh (ADR-020 — the desktop's 60 s shared
 * cache is a four-surface optimization the single-surface phone does not
 * reproduce). [nowMs]/[zone] are injectable for deterministic tests; production
 * defaults are the wall clock and the device zone.
 */
interface CodingPlanPort {
    data class Snapshot(val entitlement: UsageEntitlement?, val error: String?)

    /** One-shot R read (`usage-stats.getEntitlementSnapshot`, v4-wire.md §13.1). */
    suspend fun entitlement(): Snapshot
}

private const val TAG_SECTION = "quota_section"
private const val TAG_LOADING = "quota_loading"
private const val TAG_UNAVAILABLE = "quota_unavailable"

@Composable
fun CodingPlanQuotaSection(
    port: CodingPlanPort,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
) {
    var entitlement by remember { mutableStateOf<UsageEntitlement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        scope.launch {
            val result = port.entitlement()
            entitlement = result.entitlement
            error = result.error
            loaded = true
        }
    }
    LaunchedEffect(Unit) { load() }

    // Hidden panel: a failed/absent read (channel refused, teardown, transport
    // down) renders NOTHING — the §13.3 "Hidden" fallback, desktop null-state parity.
    if (loaded && entitlement == null && error != null) {
        return
    }

    Column(modifier = modifier.fillMaxWidth().testTag(TAG_SECTION)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.quota_section_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { load() }, modifier = Modifier.testTag("quota_refresh")) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.quota_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            !loaded -> Row(
                Modifier.fillMaxWidth().testTag(TAG_LOADING).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.quota_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            isUnavailableFace(entitlement) -> UnavailableFace(entitlement!!, onRefresh = { load() })
            else -> QuotaMeters(entitlement!!, nowMs, ZoneId.of(zone.id))
        }
    }
}

/** Desktop `unavailable` gate (CodingPlanContextUsage.tsx:262): no remaining block,
 *  no limits, no level — with a real payload face rendered, not hidden. */
private fun isUnavailableFace(e: UsageEntitlement?): Boolean =
    e != null && e.remainingCount == null && e.limits.isEmpty() && e.planLevel == null

@Composable
private fun UnavailableFace(e: UsageEntitlement, onRefresh: () -> Unit) {
    val message = when (e.unavailableReason) {
        "not_configured" -> stringResource(R.string.quota_not_configured)
        "not_authenticated" -> stringResource(R.string.quota_login_required)
        "no_plan" -> stringResource(R.string.quota_no_plan)
        else -> stringResource(R.string.quota_unavailable_generic)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(TAG_UNAVAILABLE)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.quota_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // §9 raw-enum policy: the wire's unavailableReason displays verbatim.
        e.unavailableReason?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** One desktop quota meter: label · remaining-% · reset time + the remaining bar
 *  (ChatCodingPlanUsageMeter, CodingPlanContextUsage.tsx:128-169). */
private data class QuotaMeter(
    val key: String,
    val labelRes: Int,
    val limit: UsageQuotaLimit,
    val fiveHourFace: Boolean,
)

@Composable
private fun QuotaMeters(e: UsageEntitlement, nowMs: Long, zone: ZoneId) {
    val locale = Locale.getDefault()
    val meters = listOfNotNull(
        CodingPlanQuotaPresentation.findQuotaLimit(
            e.limits, CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 3.0, 5.0,
        )?.let { QuotaMeter("fiveHour", R.string.quota_five_hour, it, fiveHourFace = true) },
        CodingPlanQuotaPresentation.findQuotaLimit(
            e.limits, CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 6.0, null,
        )?.let { QuotaMeter("weekly", R.string.quota_weekly, it, fiveHourFace = false) },
        CodingPlanQuotaPresentation.findQuotaLimit(
            e.limits, CodingPlanQuotaPresentation.TOOL_LIMIT_TYPES, 5.0, 1.0,
        )?.let { QuotaMeter("toolCalls", R.string.quota_tool_calls, it, fiveHourFace = false) },
        // MCP rides the SEPARATE mcpQuota.aggregate field (never in limits[])
        e.mcpAggregate?.let { QuotaMeter("mcp", R.string.quota_mcp, it, fiveHourFace = false) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        meters.forEach { meter ->
            val remaining = CodingPlanQuotaPresentation.quotaRemainingPercentage(meter.limit)
            val resetLabel = if (meter.fiveHourFace) {
                CodingPlanQuotaPresentation.formatFiveHourResetTime(meter.limit.nextResetTime, nowMs, zone, locale)
            } else {
                CodingPlanQuotaPresentation.formatQuotaResetTime(meter.limit.nextResetTime, nowMs, zone, locale)
            }
            Column(Modifier.fillMaxWidth().testTag("quota_meter_${meter.key}")) {
                Text(
                    stringResource(meter.labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        CodingPlanQuotaPresentation.formatQuotaRemainingPercentage(remaining, locale),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("quota_value_${meter.key}"),
                    )
                    if (resetLabel != null) {
                        Text(
                            resetLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("quota_reset_${meter.key}"),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (remaining != null && remaining > 0.0) {
                        Box(
                            Modifier
                                .fillMaxWidth((remaining / 100.0).toFloat())
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}
