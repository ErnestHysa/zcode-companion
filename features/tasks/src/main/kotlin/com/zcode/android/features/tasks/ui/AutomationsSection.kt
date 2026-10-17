package com.zcode.android.features.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.relativeTimeLabelLive
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.OffPeakTaskRow
import com.zcode.android.features.tasks.R
import com.zcode.android.features.tasks.port.AutomationsPort
import kotlinx.coroutines.launch
import com.zcode.android.core.ui.components.ZCodeOutlinedButton
import com.zcode.android.core.ui.components.ZCodeButton
import com.zcode.android.core.ui.components.friendlyFaultMessage

/**
 * GAP-6 + FIELD (2026-10-12): the Tasks-tab AUTOMATIONS + OFF-PEAK section, now
 * with FULL CRUD (desktop parity — zcodeAgent.ts:677-688: createAutomation/
 * updateAutomation/deleteAutomation/setAutomationEnabled/runAutomationNow are
 * phone-reachable zcodeAgent methods). Ernest's ruling: "add automations as well,
 * exactly how it's done in the macos app". Read-only display of the off-peak list
 * is unchanged; the automations list gains: "+ New automation" (schedule form),
 * per-row run-now / enable-toggle / delete (confirm), and tap-to-edit.
 */
@Composable
fun AutomationsSection(
    port: AutomationsPort,
    modifier: Modifier = Modifier,
    modelPicker: @Composable (
        current: Pair<String, String>?,
        onPick: (provider: String, model: String) -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit = { _, _, _ -> },
) {
    var loading by remember { mutableStateOf(true) }
    var lists by remember { mutableStateOf<AutomationsPort.Lists?>(null) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AutomationRow?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<AutomationRow?>(null) }

    suspend fun load() {
        lists = port.lists()
        loading = false
    }
    LaunchedEffect(port) { scope.launch { load() } }

    fun refresh() {
        scope.launch { load() }
    }

    // The workspace key the port resolves (first active workspace; the section is
    // workspace-scoped like the list). The port re-resolves from the hub target —
    // the form only needs a non-blank marker for the create guard.
    fun workspaceKeyFor(): String = lists?.automations?.firstOrNull()?.workspaceKey ?: "active"

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("automations_section"),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.automations_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(
                onClick = {
                    creating = true
                },
                modifier = Modifier.testTag("automations_new"),
            ) { Text(stringResource(R.string.automations_new)) }
        }
        Spacer(Modifier.height(4.dp))
        when {
            loading -> Text(
                stringResource(R.string.automations_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // F5: an errored channel is not a capability statement — classify it
            // (unsupported/unscripted → "not available on this desktop"; transport → connection).
            lists?.error != null -> Text(
                friendlyFaultMessage(lists?.error),
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
                val autos = lists?.automations.orEmpty()
                autos.forEachIndexed { index, automation ->
                    AutomationLine(
                        automation,
                        onRunNow = {
                            scope.launch {
                                busy = true
                                port.runNow(automation.automationId)
                                busy = false
                                refresh()
                            }
                        },
                        onToggle = { enabled ->
                            scope.launch {
                                busy = true
                                port.setEnabled(automation.automationId, enabled)
                                busy = false
                                refresh()
                            }
                        },
                        onEdit = { editing = automation },
                        onDelete = { confirmDelete = automation },
                    )
                    // B2.18: dividers BETWEEN rows — no dangling divider before the
                    // off-peak section (the doubled-line rot).
                    if (index != autos.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
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
            peaks.forEachIndexed { index, task ->
                OffPeakLine(task)
                if (index != peaks.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    if (creating || editing != null) {
        AutomationFormDialog(
            existing = editing,
            busy = busy,
            modelPicker = modelPicker,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { title, prompt, schedule, model, mode ->
                val target = editing
                creating = false
                editing = null
                scope.launch {
                    busy = true
                    if (target != null) {
                        port.update(target.automationId, AutomationsPort.AutomationWrite(title, prompt, schedule, model?.first, model?.second, mode))
                    } else {
                        port.create(workspaceKeyFor(), AutomationsPort.AutomationWrite(title, prompt, schedule, model?.first, model?.second, mode))
                    }
                    busy = false
                    refresh()
                }
            },
        )
    }

    confirmDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.automations_delete_title)) },
            text = { Text(stringResource(R.string.automations_delete_body, doomed.title)) },
            confirmButton = {
                ZCodeButton(onClick = {
                    scope.launch {
                        busy = true
                        port.delete(doomed.automationId)
                        busy = false
                        confirmDelete = null
                        refresh()
                    }
                }) { Text(stringResource(R.string.automations_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.automations_delete_cancel)) }
            },
        )
    }
}

/**
 * B2.1+J2: the editor seeds from the row's REAL schedule carrier — the old form
 * opened every non-minute schedule as Daily with a hardcoded 09:00, dropped the
 * carried model, and silently saved a blank cron as "0 9 * * *".
 */
private data class AutomationFormSeed(
    val kind: Int,
    val minutes: String,
    val dailyTime: String,
    val cron: String,
    val model: Pair<String, String>?,
) {
    companion object {
        /** "M H * * *" (the daily carrier's own placeholder shape) → the Daily picker. */
        private val DAILY_CRON = Regex("^(\\d{1,2}) (\\d{1,2}) \\* \\* \\*$")

        fun of(row: AutomationRow?): AutomationFormSeed {
            if (row == null) return AutomationFormSeed(0, "30", "09:00", "", null)
            val rule = row.scheduleRule
            if (rule?.unit == "minute") {
                return AutomationFormSeed(0, rule.interval.toString(), "09:00", "", carriedModel(row))
            }
            DAILY_CRON.find(row.cronExpr.trim())?.let { m ->
                val minute = m.groupValues[1].toIntOrNull() ?: 0
                val hour = m.groupValues[2].toIntOrNull() ?: 0
                return AutomationFormSeed(1, "30", "%02d:%02d".format(hour, minute), "", carriedModel(row))
            }
            // Any other carrier (weekly/monthly/yearly/once/custom cron) opens as the
            // CRON kind with the row's own expression — a bare Save round-trips it.
            return AutomationFormSeed(2, "30", "09:00", row.cronExpr, carriedModel(row))
        }

        private fun carriedModel(row: AutomationRow): Pair<String, String>? {
            val provider = row.modelProvider?.trim().orEmpty()
            val model = row.modelId?.trim().orEmpty()
            return if (provider.isNotEmpty() && model.isNotEmpty()) provider to model else null
        }
    }
}

/** FIELD (2026-10-12): the automation create/edit form (desktop parity). */
@Composable
private fun AutomationFormDialog(
    existing: AutomationRow?,
    busy: Boolean,
    modelPicker: @Composable (
        current: Pair<String, String>?,
        onPick: (provider: String, model: String) -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit,
    onDismiss: () -> Unit,
    onSave: (title: String, prompt: String, schedule: AutomationsPort.ScheduleSpec, model: Pair<String, String>?, mode: String?) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var prompt by remember { mutableStateOf(existing?.prompt ?: "") }
    // schedule: 0=every N min, 1=daily, 2=custom cron — SEEDED from the row (B2.1+J2).
    val seed = remember(existing) { AutomationFormSeed.of(existing) }
    var scheduleKind by remember(existing) { mutableStateOf(seed.kind) }
    var everyMinutes by remember(existing) { mutableStateOf(seed.minutes) }
    var dailyTime by remember(existing) { mutableStateOf(seed.dailyTime) }
    var cronExpr by remember(existing) { mutableStateOf(seed.cron) }
    var pickedModel by remember(existing) { mutableStateOf(seed.model) }
    var modelSheet by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(existing?.mode ?: "build") }

    val schedule = when (scheduleKind) {
        0 -> AutomationsPort.ScheduleSpec(everyMinutes = everyMinutes.toIntOrNull() ?: 30)
        1 -> {
            val parts = dailyTime.split(":")
            AutomationsPort.ScheduleSpec(
                dailyHour = parts.getOrNull(0)?.toIntOrNull() ?: 9,
                dailyMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            )
        }
        // B2.1: a blank cron is REFUSED by the gate below — never silently "0 9 * * *".
        else -> AutomationsPort.ScheduleSpec(cronExpr = cronExpr.trim())
    }
    val valid = title.isNotBlank() && prompt.isNotBlank() &&
        (scheduleKind != 0 || (everyMinutes.toIntOrNull() ?: 0) > 0) &&
        (scheduleKind != 2 || AutomationsPort.ScheduleSpec.isWellFormedCron(cronExpr))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing != null) R.string.automations_edit_title else R.string.automations_create_title,
                ),
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.automations_field_title)) },
                    modifier = Modifier.fillMaxWidth().testTag("automation_form_title"),
                    maxLines = 1,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.automations_field_prompt)) },
                    modifier = Modifier.fillMaxWidth().testTag("automation_form_prompt"),
                    minLines = 2,
                    maxLines = 5,
                )
                Text(
                    stringResource(R.string.automations_field_schedule),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        0 to stringResource(R.string.automations_sched_minutes),
                        1 to stringResource(R.string.automations_sched_daily),
                        2 to stringResource(R.string.automations_sched_cron),
                    ).forEach { (kind, label) ->
                        AssistChip(
                            onClick = { scheduleKind = kind },
                            label = { Text(label, maxLines = 1) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (scheduleKind == kind) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        )
                    }
                }
                when (scheduleKind) {
                    0 -> OutlinedTextField(
                        value = everyMinutes,
                        onValueChange = { everyMinutes = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.automations_sched_minutes)) },
                        modifier = Modifier.fillMaxWidth().testTag("automation_form_minutes"),
                        maxLines = 1,
                    )
                    1 -> OutlinedTextField(
                        value = dailyTime,
                        onValueChange = { dailyTime = it },
                        label = { Text(stringResource(R.string.automations_sched_daily_time)) },
                        modifier = Modifier.fillMaxWidth().testTag("automation_form_daily"),
                        maxLines = 1,
                    )
                    else -> OutlinedTextField(
                        value = cronExpr,
                        onValueChange = { cronExpr = it },
                        label = { Text(stringResource(R.string.automations_sched_cron)) },
                        modifier = Modifier.fillMaxWidth().testTag("automation_form_cron"),
                        maxLines = 1,
                    )
                }
                ZCodeOutlinedButton(
                    onClick = { modelSheet = true },
                    modifier = Modifier.fillMaxWidth().testTag("automation_form_model"),
                ) {
                    Text(
                        pickedModel?.let { (p, m) -> "$p/$m" }
                            ?: stringResource(R.string.create_model_default),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.automations_field_mode, mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            ZCodeButton(
                onClick = { onSave(title, prompt, schedule, pickedModel, mode) },
                enabled = valid && !busy,
                modifier = Modifier.testTag("automation_form_save"),
            ) { Text(stringResource(R.string.create_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel)) }
        },
    )
    if (modelSheet) {
        modelPicker(
            pickedModel,
            { p, m ->
                pickedModel = p to m
                modelSheet = false
            },
            { modelSheet = false },
        )
    }
}

@Composable
private fun AutomationLine(
    a: AutomationRow,
    onRunNow: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("automation_row_${a.automationId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).clickable { onEdit() },
        ) {
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
                        stringResource(R.string.automation_next_run, relativeTimeLabelLive(next) ?: ""),
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
            onClick = { onToggle(!a.enabled) },
            label = {
                Text(
                    stringResource(if (a.enabled) R.string.automation_on else R.string.automation_off) +
                        " · " + a.lifecycleStatus,
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = if (a.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.testTag("automation_state_${a.automationId}"),
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onRunNow, modifier = Modifier.testTag("automation_run_${a.automationId}")) {
            Text(stringResource(R.string.automation_run_now), style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onEdit, modifier = Modifier.testTag("automation_edit_${a.automationId}")) {
            Text(stringResource(R.string.automation_edit), style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.testTag("automation_delete_${a.automationId}"),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.automation_delete), style = MaterialTheme.typography.labelSmall)
        }
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
                relativeTimeLabelLive(t.createdAt) ?: "",
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
