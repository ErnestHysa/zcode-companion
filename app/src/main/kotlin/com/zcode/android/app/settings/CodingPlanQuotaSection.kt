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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.zcode.android.core.ui.components.CodingPlanQuotaResetState
import com.zcode.android.core.ui.components.ResetUiStatus
import com.zcode.android.domain.model.CodingPlanResetStatus
import com.zcode.android.domain.model.UsageEntitlement
import com.zcode.android.domain.model.UsageQuotaLimit
import com.zcode.android.domain.model.accountAccessFromProviderId
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
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
 * reproduce).
 *
 * GAP-4 (§13.2; ADR-020) — the reset flow: FIVE_HOUR and WEEK reset chips render
 * the desktop's opportunity/completion states (CodingPlanQuotaResetState machine);
 * the destructive-adjacent `useCodingPlanReset` word fires ONLY behind the
 * typed-confirm dialog — the exact word RESET must be typed before the confirm
 * enables (T19; pinned by CodingPlanQuotaResetUiTest: zero calls without it).
 * The post-use reconciliation polls the status read on the desktop's schedule
 * ([0, 250, 750, 1500] ms — injectable as [useRetryDelaysMs] for tests).
 * [nowMs]/[zone] are injectable for deterministic labels.
 */
interface CodingPlanPort {
    data class Snapshot(val entitlement: UsageEntitlement?, val error: String?)

    data class ResetStatusResult(val status: CodingPlanResetStatus?, val error: String? = null)

    /** One-shot R read (`usage-stats.getEntitlementSnapshot`, v4-wire.md §13.1). */
    suspend fun entitlement(): Snapshot

    /** `getCodingPlanResetStatus` (read) — the reset windows + histories. */
    suspend fun resetStatus(providerId: String, entitled: Boolean): ResetStatusResult

    /** `requestCodingPlanResetOpportunity` — server-side grant evaluation. */
    suspend fun requestOpportunity(
        providerId: String,
        entitled: Boolean,
        idempotencyKey: String,
    ): com.zcode.android.domain.model.ResetOpportunityResult?

    /**
     * `useCodingPlanReset` — the quota plane's ONLY destructive-adjacent word
     * (ADR-020/T19); the UI reaches it ONLY behind the typed-confirm dialog.
     */
    suspend fun useReset(
        providerId: String,
        entitled: Boolean,
        idempotencyKey: String,
        resetType: String,
    ): com.zcode.android.domain.model.ResetUseResult

    /** `markCodingPlanResetHistoryRead` — clears the desktop's unread cursor. */
    suspend fun markHistoryRead(providerId: String, entitled: Boolean)
}

private const val TAG_SECTION = "quota_section"
private const val TAG_LOADING = "quota_loading"
private const val TAG_UNAVAILABLE = "quota_unavailable"

/** One resettable window kind (the desktop's FIVE_HOUR | WEEK pair). */
internal data class ResettableWindow(
    val resetType: String,
    val meterKey: String,
    val labelRes: Int,
)

@Composable
fun CodingPlanQuotaSection(
    port: CodingPlanPort,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
    useRetryDelaysMs: List<Long> = listOf(0, 250, 750, 1_500),
) {
    var entitlement by remember { mutableStateOf<UsageEntitlement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var fiveHourEntry by remember { mutableStateOf<CodingPlanQuotaResetState.Entry?>(null) }
    var weekEntry by remember { mutableStateOf<CodingPlanQuotaResetState.Entry?>(null) }
    var dialogWindow by remember { mutableStateOf<ResettableWindow?>(null) }
    val scope = rememberCoroutineScope()
    val zoneId = remember(zone) { ZoneId.of(zone.id) }

    fun entryFor(resetType: String) = if (resetType == "WEEK") weekEntry else fiveHourEntry

    fun setEntryFor(resetType: String, entry: CodingPlanQuotaResetState.Entry?) {
        if (resetType == "WEEK") weekEntry = entry else fiveHourEntry = entry
    }

    fun refreshResets(ent: UsageEntitlement) {
        val providerId = ent.providerId ?: return
        // The reset surface exists ONLY for a derivable builtin provider id
        // (ADR-020: unknown ids derive NOTHING — never guessed).
        if (accountAccessFromProviderId(providerId, entitled = true) == null) return
        val entitled = ent.authenticated
        scope.launch {
            val previous5 = fiveHourEntry
            val previousW = weekEntry
            fun justCompleted(previous: CodingPlanQuotaResetState.Entry?, next: CodingPlanQuotaResetState.Entry?) =
                next?.status == ResetUiStatus.COMPLETED &&
                    next.completedAt != null &&
                    next.completedAt != previous?.completedAt
            suspend fun fold(status: CodingPlanResetStatus?) {
                if (status == null) return
                fiveHourEntry = CodingPlanQuotaResetState.applyResetStatus(
                    fiveHourEntry, status, nowMs, resetType = "FIVE_HOUR",
                )
                weekEntry = CodingPlanQuotaResetState.applyResetStatus(
                    weekEntry, status, nowMs, resetType = "WEEK",
                )
            }
            // status first ("/status 只查询已发放机会" — desktop comment)
            runCatching { port.resetStatus(providerId, entitled) }.onSuccess { fold(it.status) }
            // opportunity evaluation skips while a reset is in flight or just
            // completed (desktop guard — "插入 /opportunity 会撞发卡锁触发 429")
            val anyInFlight = listOf(previous5, previousW).any { it?.status == ResetUiStatus.PROCESSING } ||
                justCompleted(previous5, fiveHourEntry) ||
                justCompleted(previousW, weekEntry)
            if (!anyInFlight) {
                runCatching {
                    port.requestOpportunity(providerId, entitled, java.util.UUID.randomUUID().toString())
                }.onSuccess { opp ->
                    if (opp?.granted == true) {
                        runCatching { port.resetStatus(providerId, entitled) }.onSuccess { fold(it.status) }
                    }
                }
            }
        }
    }

    fun load() {
        scope.launch {
            val result = port.entitlement()
            entitlement = result.entitlement
            error = result.error
            loaded = true
            result.entitlement?.let { refreshResets(it) }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun confirmReset(window: ResettableWindow) {
        val ent = entitlement ?: return
        val providerId = ent.providerId ?: return
        val entitled = ent.authenticated
        val entry = entryFor(window.resetType) ?: return
        val candidateKey = java.util.UUID.randomUUID().toString()
        val processing = CodingPlanQuotaResetState.startManualUse(entry, candidateKey, nowMs)
        if (processing == null || processing.status != ResetUiStatus.PROCESSING) return
        val idempotencyKey = processing.idempotencyKey ?: candidateKey
        setEntryFor(window.resetType, processing)
        scope.launch {
            try {
                port.useReset(providerId, entitled, idempotencyKey, window.resetType)
                // post-use reconciliation: poll until the SERVER's usedAt lands
                var confirmed: CodingPlanQuotaResetState.Entry? = null
                for (delayMs in useRetryDelaysMs) {
                    delay(delayMs)
                    val status = runCatching { port.resetStatus(providerId, entitled) }.getOrNull()?.status
                    if (status != null) {
                        confirmed = CodingPlanQuotaResetState.applyResetStatus(
                            entryFor(window.resetType), status, nowMs,
                            manualStartedAt = processing.startedAt,
                            resetType = window.resetType,
                        )
                        setEntryFor(window.resetType, confirmed)
                        if (confirmed?.status == ResetUiStatus.COMPLETED) break
                    }
                }
                if (confirmed?.status != ResetUiStatus.COMPLETED) {
                    throw IllegalStateException("coding_plan_reset_status_not_confirmed")
                }
                // honest completion: clear the desktop's unread cursor + refresh
                runCatching { port.markHistoryRead(providerId, entitled) }
                load()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                setEntryFor(
                    window.resetType,
                    CodingPlanQuotaResetState.failManualUse(
                        entryFor(window.resetType),
                        t.message ?: "reset failed",
                    ),
                )
            }
        }
    }

    // Hidden panel: a failed/absent read (channel refused, teardown, transport
    // down) renders NOTHING — the §13.3 "Hidden" fallback, desktop null-state parity.
    if (loaded && entitlement == null && error != null) {
        return
    }

    val current = entitlement
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
            isUnavailableFace(current) -> UnavailableFace(current!!, onRefresh = { load() })
            else -> QuotaMeterList(
                entitlement = current!!,
                nowMs = nowMs,
                zoneId = zoneId,
                fiveHourEntry = fiveHourEntry,
                weekEntry = weekEntry,
                onResetClick = { window -> dialogWindow = window },
            )
        }
    }

    dialogWindow?.let { window ->
        val full = current?.let { ent ->
            buildMeters(ent).firstOrNull { it.key == window.meterKey }
                ?.let { CodingPlanQuotaPresentation.isQuotaLimitFull(it.limit) }
        } == true
        ResetConfirmDialog(
            window = window,
            entry = if (window.resetType == "WEEK") weekEntry else fiveHourEntry,
            meterFull = full,
            onConfirm = {
                confirmReset(window)
                dialogWindow = null
            },
            onDismiss = { dialogWindow = null },
        )
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

/** One desktop quota meter row's data: key/label/limit + which reset-time face. */
internal data class QuotaMeter(
    val key: String,
    val labelRes: Int,
    val limit: UsageQuotaLimit,
    val fiveHourFace: Boolean,
)

/** The desktop's four fixed meter lookups over one entitlement (§13.1). */
internal fun buildMeters(e: UsageEntitlement): List<QuotaMeter> =
    listOfNotNull(
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

@Composable
private fun QuotaMeterList(
    entitlement: UsageEntitlement,
    nowMs: Long,
    zoneId: ZoneId,
    fiveHourEntry: CodingPlanQuotaResetState.Entry?,
    weekEntry: CodingPlanQuotaResetState.Entry?,
    onResetClick: (ResettableWindow) -> Unit,
) {
    val locale = Locale.getDefault()
    val meters = remember(entitlement) { buildMeters(entitlement) }
    val resettables = listOf(
        ResettableWindow("FIVE_HOUR", "fiveHour", R.string.quota_five_hour),
        ResettableWindow("WEEK", "weekly", R.string.quota_weekly),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        meters.forEach { meter ->
            val entry = when (meter.key) {
                "fiveHour" -> fiveHourEntry
                "weekly" -> weekEntry
                else -> null
            }
            // 额度剩余 100% 时重置没有收益：隐藏重置入口（desktop
            // isCodingPlanQuotaLimitFull gate — display-only，不影响服务端发放）。
            val quotaFull = CodingPlanQuotaPresentation.isQuotaLimitFull(meter.limit)
            val window = resettables.firstOrNull { it.meterKey == meter.key }
            val showReset = window != null && entry != null && (
                (entry.status == ResetUiStatus.AVAILABLE && !quotaFull) ||
                    entry.status == ResetUiStatus.PROCESSING ||
                    entry.status == ResetUiStatus.COMPLETED
                )
            val remaining = CodingPlanQuotaPresentation.quotaRemainingPercentage(meter.limit)
            val resetLabel = if (meter.fiveHourFace) {
                CodingPlanQuotaPresentation.formatFiveHourResetTime(meter.limit.nextResetTime, nowMs, zoneId, locale)
            } else {
                CodingPlanQuotaPresentation.formatQuotaResetTime(meter.limit.nextResetTime, nowMs, zoneId, locale)
            }
            Column(Modifier.fillMaxWidth().testTag("quota_meter_${meter.key}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(meter.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (showReset && window != null && entry != null) {
                        ResetChip(window, entry, onResetClick)
                    }
                }
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
                entry?.let { e ->
                    when (e.status) {
                        ResetUiStatus.PROCESSING -> Text(
                            stringResource(R.string.quota_reset_processing),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("quota_reset_processing"),
                        )
                        ResetUiStatus.COMPLETED -> Text(
                            stringResource(R.string.quota_reset_done),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("quota_reset_done"),
                        )
                        ResetUiStatus.AVAILABLE -> e.error?.let { err ->
                            Text(
                                stringResource(R.string.quota_reset_error) + " · " + err,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("quota_reset_error"),
                            )
                        }
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

/** The desktop's reset entry chip: available (count) · processing · completed.
 *  Chip tags carry the `chip` infix — the reset-TIME labels own
 *  `quota_reset_<key>` (the GAP-3 pins). */
@Composable
private fun ResetChip(
    window: ResettableWindow,
    entry: CodingPlanQuotaResetState.Entry,
    onResetClick: (ResettableWindow) -> Unit,
) {
    when (entry.status) {
        ResetUiStatus.AVAILABLE -> TextButton(
            onClick = { onResetClick(window) },
            modifier = Modifier.testTag("quota_reset_chip_${window.meterKey}"),
        ) {
            Text(
                stringResource(R.string.quota_reset_action) +
                    (if (entry.opportunityCount > 1) " (${entry.opportunityCount})" else ""),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        ResetUiStatus.PROCESSING -> Text(
            stringResource(R.string.quota_reset_processing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("quota_reset_processing_chip"),
        )
        ResetUiStatus.COMPLETED -> Unit // the completion note renders under the meter
    }
}

/** GAP-4's typed-confirm dialog (T19): the exact word RESET must be typed before
 *  the confirm enables — the ONLY path to `useCodingPlanReset`. */
@Composable
private fun ResetConfirmDialog(
    window: ResettableWindow,
    entry: CodingPlanQuotaResetState.Entry?,
    meterFull: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("quota_reset_dialog"),
        title = {
            Text(
                stringResource(R.string.quota_reset_dialog_title, stringResource(window.labelRes)),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.quota_reset_dialog_body, stringResource(window.labelRes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry?.status == ResetUiStatus.AVAILABLE && entry.opportunityCount > 0) {
                    Text(
                        stringResource(R.string.quota_reset_opportunity_note, entry.opportunityCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.quota_reset_confirm_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("quota_reset_confirm_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                // THE gate: exact word + a live AVAILABLE entry + not quota-full.
                enabled = confirmText == "RESET" && entry?.status == ResetUiStatus.AVAILABLE && !meterFull,
                modifier = Modifier.testTag("quota_reset_confirm"),
            ) {
                Text(stringResource(R.string.quota_reset_confirm))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("quota_reset_cancel")) {
                Text(stringResource(R.string.quota_reset_cancel))
            }
        },
    )
}
