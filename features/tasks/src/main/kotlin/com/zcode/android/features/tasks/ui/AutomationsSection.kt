package com.zcode.android.features.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.relativeTimeLabel
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.OffPeakTaskRow
import com.zcode.android.features.tasks.R
import com.zcode.android.features.tasks.port.AutomationsPort
import kotlinx.coroutines.launch

/**
 * GAP-6 (android-native-gaps.md; docs/protocol/v4-wire.md §12.2) — the Tasks-tab
 * AUTOMATIONS + OFF-PEAK read-only section. Desktop parity: the automation
 * management list shows title, enable state, lifecycle, the REAL schedule (the
 * scheduleRule — "会话卡片必须读到本字段才能展示 cron 无法表达的真实间隔") with the
 * cron fallback, run count and next/last run; the off-peak list shows title,
 * status (verbatim payload value) and queue position. READ-ONLY: the section
 * exposes NO create/edit/delete/enable affordance — the write half is Deferred
 * scope (roadmap) and exists nowhere (INV-9).
 */
@Composable
fun AutomationsSection(
    port: AutomationsPort,
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var lists by remember { mutableStateOf<AutomationsPort.Lists?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        lists = port.lists()
        loading = false
    }
    LaunchedEffect(port) { scope.launch { load() } }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("automations_section"),
    ) {
        Text(
            stringResource(R.string.automations_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        when {
            loading -> Text(
                stringResource(R.string.automations_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lists?.error != null -> Text(
                stringResource(R.string.automations_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("automations_error"),
            )
            lists?.automations?.isEmpty() == true -> Text(
                stringResource(R.string.automations_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("automations_empty"),
            )
            else -> {
                lists?.automations?.forEach { automation ->
                    AutomationLine(automation)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }

        lists?.offPeak?.takeIf { it.isNotEmpty() }?.let { peaks ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.automations_offpeak),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            peaks.forEach { task ->
                OffPeakLine(task)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun AutomationLine(a: AutomationRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("automation_row_${a.automationId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                a.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                scheduleLine(a),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.nextRunAt?.let { next ->
                    Text(
                        stringResource(R.string.automation_next_run, relativeTimeLabel(next) ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.automation_runs, a.runCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AssistChip(
            onClick = {}, // display-only chip — no write affordance rides this surface
            enabled = false,
            label = {
                Text(
                    stringResource(if (a.enabled) R.string.automation_on else R.string.automation_off) +
                        " · " + a.lifecycleStatus,
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledLabelColor = if (a.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.testTag("automation_state_${a.automationId}"),
        )
    }
}

/** The REAL interval when scheduleRule carries it; the cron expression is the fallback. */
private fun scheduleLine(a: AutomationRow): String {
    val rule = a.scheduleRule ?: return a.cronExpr
    val hm = "%02d:%02d".format(rule.hour, rule.minute)
    return when (rule.unit) {
        "minute" -> "every ${rule.interval} min"
        "hourly" -> "every ${rule.interval} h"
        "daily" -> "daily $hm"
        "weekly" -> "weekly $hm"
        "monthly" -> "monthly $hm"
        "yearly" -> "yearly $hm"
        else -> a.cronExpr
    }
}

@Composable
private fun OffPeakLine(t: OffPeakTaskRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("offpeak_row_${t.offPeakTaskId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                relativeTimeLabel(t.createdAt) ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            buildString {
                append(t.status)
                t.queuePosition?.let { append(" · #").append(it) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
