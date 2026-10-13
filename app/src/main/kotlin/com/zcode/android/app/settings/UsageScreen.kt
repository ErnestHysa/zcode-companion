package com.zcode.android.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.app.R
import com.zcode.android.core.ui.components.formatCompactTokenCount
import com.zcode.android.core.ui.components.SkeletonBar
import com.zcode.android.core.ui.components.StatusTone
import com.zcode.android.core.ui.components.toneColor
import com.zcode.android.domain.model.AppUsageSnapshot
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * GAP-5 (android-native-gaps.md; docs/protocol/v4-wire.md §12.1) — Settings →
 * Usage screen, the desktop AppUsagePanel.tsx port: lifetime summary strip
 * (totalTokens / peak day / longest session / current + longest streak,
 * AppUsagePanel.tsx:138-156), the weekly heatmap when weeks exist (:86), the
 * daily-model trend, the per-model list and the tools list, range tabs default
 * "7d" (:39), and the honest faces: loading / error / zero-activity empty
 * state (:52-97). Read-only (§13.3 usage-stats R) — no write affordance exists.
 */

/** Consumer-side port (INV-2/INV-6): the composition root implements against the hub. */
interface UsagePort {
    data class Result(val snapshot: AppUsageSnapshot?, val error: String?)

    /** One-shot R read; range = "all"|"7d"|"30d" (verified enum, v4UsageStatsParamsSchema). */
    suspend fun appUsage(range: String): Result
}

private val USAGE_RANGES = listOf("7d", "30d", "all")

@Composable
fun UsageScreen(
    port: UsagePort,
    quotaPort: CodingPlanPort? = null,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var range by remember { mutableStateOf("7d") }
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<AppUsageSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun load(rangeKey: String) {
        scope.launch {
            loading = true
            error = null
            val result = port.appUsage(rangeKey)
            snapshot = result.snapshot
            error = result.error
            loading = false
        }
    }
    LaunchedEffect(Unit) { load(range) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("usage_back")) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(com.zcode.android.core.ui.R.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    stringResource(R.string.usage_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (loading && snapshot == null && error == null) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonBar(Modifier.fillMaxWidth())
                    SkeletonBar(Modifier.fillMaxWidth(0.7f))
                    SkeletonBar(Modifier.fillMaxWidth(0.85f))
                    Text(
                        stringResource(R.string.usage_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("usage_loading"),
                    )
                }
                return@Column
            }
            error?.let { message ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.usage_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("usage_error"),
                    )
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { load(range) }, modifier = Modifier.testTag("usage_retry")) {
                        Text(stringResource(R.string.usage_retry))
                    }
                }
                return@Column
            }
            val current = snapshot
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // GAP-3 (ADR-020, v4-wire.md §13.1): the coding-plan quota meters
                // sit above the app-usage sections — the desktop's own settings
                // pairing (quota panel + usage dashboard on one surface). A failed
                // read degrades to a HIDDEN section (the section's own contract).
                quotaPort?.let { qp ->
                    CodingPlanQuotaSection(port = qp, modifier = Modifier.padding(bottom = 8.dp))
                }
                RangeTabs(range = range, enabled = !loading, onSelect = { key ->
                    range = key
                    load(key)
                })
                if (current == null || current.summary.totalTokens == 0L) {
                    // Honest empty state (desktop UsageEmptyState, AppUsagePanel.tsx:71-97):
                    // zero activity renders an explicit empty card, never zeros-as-charts.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.usage_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("usage_empty"),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.usage_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@Column
                }
                SummaryStrip(current.summary)
                if (current.heatmap.weeks.isNotEmpty()) HeatmapSection(current.heatmap)
                DailySection(current.dailyModelUsage, current.heatmap.maxTokens)
                ModelsSection(current.models)
                ToolsSection(current.tools)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RangeTabs(range: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        USAGE_RANGES.forEach { key ->
            FilterChip(
                selected = range == key,
                onClick = { onSelect(key) },
                enabled = enabled,
                label = {
                    Text(
                        stringResource(
                            when (key) {
                                "7d" -> R.string.usage_range_7d
                                "30d" -> R.string.usage_range_30d
                                else -> R.string.usage_range_all
                            },
                        ),
                    )
                },
                modifier = Modifier.testTag("usage_range_$key"),
            )
        }
    }
}

@Composable
private fun SummaryStrip(summary: AppUsageSnapshot.Summary) {
    val locale = Locale.getDefault()
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("usage_summary"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCard(
                Modifier.weight(1f),
                label = stringResource(R.string.usage_card_total),
                value = formatCompactTokenCount(summary.totalTokens, locale),
                valueTag = "usage_total_tokens",
            )
            SummaryCard(
                Modifier.weight(1f),
                label = stringResource(R.string.usage_card_peak),
                value = formatCompactTokenCount(summary.peakDayTokens, locale),
                valueTag = "usage_peak_day",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryCard(
                Modifier.weight(1f),
                label = stringResource(R.string.usage_card_longest_session),
                value = formatUsageDuration(summary.longestSessionMs),
            )
            SummaryCard(
                Modifier.weight(1f),
                label = stringResource(R.string.usage_card_current_streak),
                value = formatUsageDays(summary.currentStreakDays, locale),
            )
            SummaryCard(
                Modifier.weight(1f),
                label = stringResource(R.string.usage_card_longest_streak),
                value = formatUsageDays(summary.longestStreakDays, locale),
            )
        }
    }
}

@Composable
private fun SummaryCard(modifier: Modifier, label: String, value: String, valueTag: String? = null) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = valueTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** ms → compact human duration ("1h 30m" / "45m"), the formatAppUsageDuration face. */
private fun formatUsageDuration(ms: Long): String {
    if (ms <= 0L) return "--"
    val minutes = ms / 60_000
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> "${ms / 1000}s"
    }
}

/** Desktop formatAppUsageDays face: N whole days ("3d"); zero/absent renders "--". */
private fun formatUsageDays(days: Int, locale: Locale): String =
    if (days <= 0) "--" else "${days}d"

@Composable
private fun HeatmapSection(heatmap: AppUsageSnapshot.Heatmap) {
    SectionTitle(stringResource(R.string.usage_section_heatmap), tag = "usage_heatmap")
    val maxLevel = 4
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        heatmap.weeks.forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.days.forEach { day ->
                    val level = day?.level ?: 0
                    val tone = when {
                        day == null -> MaterialTheme.colorScheme.surfaceVariant
                        level <= 0 -> MaterialTheme.colorScheme.surfaceVariant
                        else -> toneColor(StatusTone.SUCCESS).copy(alpha = 0.15f + 0.2f * level.coerceAtMost(maxLevel))
                    }
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(tone),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.usage_heatmap_range, heatmap.startDate ?: "—", heatmap.endDate ?: "—"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DailySection(days: List<AppUsageSnapshot.DailyModelUsage>, maxTokens: Long) {
    SectionTitle(stringResource(R.string.usage_section_daily), tag = "usage_daily")
    if (days.isEmpty()) {
        Text(
            stringResource(R.string.usage_section_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val peak = maxOf(maxTokens, days.maxOf { it.models.sumOf { m -> m.totalTokens } }).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { day ->
            val total = day.models.sumOf { it.totalTokens }
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(day.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatCompactTokenCount(total, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(total.toFloat() / peak)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelsSection(models: List<AppUsageSnapshot.ModelUsage>) {
    SectionTitle(stringResource(R.string.usage_section_models), tag = "usage_models")
    if (models.isEmpty()) {
        Text(
            stringResource(R.string.usage_section_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val locale = Locale.getDefault()
    Column {
        models.forEach { model ->
            val id = model.modelId ?: stringResource(R.string.usage_unknown_model)
            val share = java.text.NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }
                .format(model.share)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("usage_model_row_$id"),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(id, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatCompactTokenCount(model.totalTokens, locale)} · $share",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun ToolsSection(tools: List<AppUsageSnapshot.ToolUsage>) {
    SectionTitle(stringResource(R.string.usage_section_tools), tag = "usage_tools")
    if (tools.isEmpty()) {
        Text(
            stringResource(R.string.usage_section_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val locale = Locale.getDefault()
    Column {
        tools.forEach { tool ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("usage_tool_row_${tool.toolName}"),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tool.toolName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    // calls + error rate (desktop toolErrorRate face) — verbatim payload values.
                    "${locale.let { java.text.NumberFormat.getIntegerInstance(it).format(tool.callCount) }} · " +
                        java.text.NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }
                            .format(tool.errorRate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun SectionTitle(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(top = 16.dp, bottom = 6.dp)
            .testTag(tag),
    )
}
