package com.zcode.android.features.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.android.core.ui.components.ZCodeButton
import com.zcode.android.core.ui.components.ZCodeOutlinedButton
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.features.tasks.R
import com.zcode.android.features.tasks.port.AutomationSchedule
import com.zcode.android.features.tasks.port.AutomationSchedule.CustomUnit
import com.zcode.android.features.tasks.port.AutomationSchedule.Frequency
import com.zcode.android.features.tasks.port.AutomationSchedule.MonthlyMode
import com.zcode.android.features.tasks.port.AutomationsPort

/**
 * J2-target (gaps-and-polish Phase 4B): the automation editor at the DESKTOP's shape
 * (AutomationEditView.tsx + automationFormat.ts): frequency hourly / daily / weekdays /
 * weekly / monthly / custom (every N × unit, weekday picker, monthly by date|weekday,
 * yearly month+day) / raw cron — plus the relative one-shot ("once in N minutes",
 * the legacy carrier), a live schedule PREVIEW line, ends (never | on date), maxRuns,
 * cron validation and the unsaved-changes guard. A kind the builder does not model
 * opens as RAW and a bare Save round-trips it byte-identical (the J2 contract).
 */
internal data class AutomationEditorResult(
    val title: String,
    val prompt: String,
    val schedule: AutomationsPort.ScheduleSpec,
    val scheduleRule: AutomationRow.ScheduleRule?,
    val clearScheduleRule: Boolean,
    val recurring: Boolean,
    val maxRuns: Int?,
    val endAt: Long?,
    val model: Pair<String, String>?,
    val mode: String?,
)

/** The editor's whole state — compared against its seed for the unsaved-changes guard. */
private data class EditorState(
    val title: String,
    val prompt: String,
    val onceMinutes: String,
    val builder: AutomationSchedule.Builder,
    val time: String,
    val cron: String,
    val intervalText: String,
    val domText: String,
    val monthDaysText: String,
    val monthText: String,
    val endsOnDate: Boolean,
    val endDate: String,
    val maxRuns: String,
    val model: Pair<String, String>?,
    val once: Boolean,
)

private fun pad2(v: Int) = v.toString().padStart(2, '0')

private val TIME_RE = Regex("^(\\d{1,2}):(\\d{2})$")
private val DATE_RE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

private fun parseTime(s: String): Pair<Int, Int>? {
    val m = TIME_RE.find(s.trim()) ?: return null
    val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) h to min else null
}

/** The desktop's localDateEndTimestamp: the chosen local date's 23:59:59.999. */
internal fun localDateEndTimestamp(date: String): Long? {
    val m = DATE_RE.find(date.trim()) ?: return null
    val (y, mo, d) = m.groupValues.drop(1).map { it.toInt() }
    if (mo !in 1..12 || d !in 1..31) return null
    return java.util.Calendar.getInstance().apply {
        clear(); set(y, mo - 1, d, 23, 59, 59); set(java.util.Calendar.MILLISECOND, 999)
    }.timeInMillis
}

private fun seed(existing: AutomationRow?): EditorState {
    val builder = existing?.let { AutomationSchedule.forRow(it) } ?: AutomationSchedule.Builder()
    val once = existing?.scheduleRule?.unit == "minute" && existing.recurring == false
    val endAt = existing?.endAt
    return EditorState(
        title = existing?.title ?: "",
        prompt = existing?.prompt ?: "",
        onceMinutes = existing?.scheduleRule?.takeIf { it.unit == "minute" }?.interval?.toString() ?: "30",
        builder = builder,
        time = "${pad2(builder.hour)}:${pad2(builder.minute)}",
        cron = if (builder.frequency == Frequency.RAW) builder.rawExpr else "",
        intervalText = builder.customInterval.toString(),
        domText = builder.dayOfMonth.toString(),
        monthDaysText = builder.customMonthDays.joinToString(","),
        monthText = builder.customMonth.toString(),
        endsOnDate = endAt != null,
        endDate = endAt?.let { ms ->
            java.util.Calendar.getInstance().apply { timeInMillis = ms }.let { c ->
                "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
            }
        } ?: "",
        maxRuns = existing?.maxRuns?.toString() ?: "",
        model = existing?.let { row ->
            val p = row.modelProvider?.trim().orEmpty(); val m = row.modelId?.trim().orEmpty()
            if (p.isNotEmpty() && m.isNotEmpty()) p to m else null
        },
        once = once,
    )
}

@Composable
internal fun scheduleLabels(): AutomationSchedule.Labels {
    val weekdayNames = listOf(
        stringResource(R.string.automations_weekday_0), stringResource(R.string.automations_weekday_1),
        stringResource(R.string.automations_weekday_2), stringResource(R.string.automations_weekday_3),
        stringResource(R.string.automations_weekday_4), stringResource(R.string.automations_weekday_5),
        stringResource(R.string.automations_weekday_6),
    )
    val sep = stringResource(R.string.automations_weekday_separator)
    val hourly = stringResource(R.string.automations_schedule_hourly)
    val daily = stringResource(R.string.automations_schedule_daily)
    val weekdays = stringResource(R.string.automations_schedule_weekdays)
    val weekly = stringResource(R.string.automations_schedule_weekly)
    val monthly = stringResource(R.string.automations_schedule_monthly)
    val cMin = stringResource(R.string.automations_schedule_custom_minutes)
    val cHour = stringResource(R.string.automations_schedule_custom_hourly)
    val cDay = stringResource(R.string.automations_schedule_custom_daily)
    val cWeek = stringResource(R.string.automations_schedule_custom_weekly)
    val cMonthD = stringResource(R.string.automations_schedule_custom_monthly_dates)
    val cMonthW = stringResource(R.string.automations_schedule_custom_monthly_weekday)
    val cYear = stringResource(R.string.automations_schedule_custom_yearly)
    val once = stringResource(R.string.automations_schedule_once)
    return remember(weekdayNames, sep, hourly, daily, weekdays, weekly, monthly, cMin, cHour, cDay, cWeek, cMonthD, cMonthW, cYear, once) {
        AutomationSchedule.Labels(
            weekday = { weekdayNames.getOrElse(it) { "?" } },
            separator = sep,
            hourly = { m -> hourly.replace("{minute}", m) },
            daily = { t -> daily.replace("{time}", t) },
            weekdays = { t -> weekdays.replace("{time}", t) },
            weekly = { d, t -> weekly.replace("{days}", d).replace("{time}", t) },
            monthly = { d, t -> monthly.replace("{day}", d).replace("{time}", t) },
            customMinutes = { n -> cMin.replace("{interval}", n) },
            customHourly = { n, m -> cHour.replace("{interval}", n).replace("{time}", m) },
            customDaily = { n, t -> cDay.replace("{interval}", n).replace("{time}", t) },
            customWeekly = { n, d, t -> cWeek.replace("{interval}", n).replace("{days}", d).replace("{time}", t) },
            customMonthlyDates = { n, d, t -> cMonthD.replace("{interval}", n).replace("{days}", d).replace("{time}", t) },
            customMonthlyWeekday = { n, d, t -> cMonthW.replace("{interval}", n).replace("{day}", d).replace("{time}", t) },
            customYearly = { n, mo, d, t -> cYear.replace("{interval}", n).replace("{month}", mo).replace("{day}", d).replace("{time}", t) },
            once = once,
            raw = { it },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutomationEditorDialog(
    existing: AutomationRow?,
    busy: Boolean,
    modelPicker: @Composable (
        current: Pair<String, String>?,
        onPick: (provider: String, model: String) -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit,
    onDismiss: () -> Unit,
    onSave: (AutomationEditorResult) -> Unit,
) {
    val initial = remember(existing) { seed(existing) }
    var s by remember(existing) { mutableStateOf(initial) }
    var modelSheet by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val mode = existing?.mode ?: "build"
    val labels = scheduleLabels()
    val dirty = s != initial

    // ---- derive the builder from the text fields (time / interval / dom / monthDays / month) ----
    val time = parseTime(s.time)
    val interval = s.intervalText.toIntOrNull()
    val dom = s.domText.toIntOrNull()
    val monthDays = s.monthDaysText.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..31 }
    val month = s.monthText.toIntOrNull()
    val builder = s.builder.copy(
        hour = time?.first ?: s.builder.hour,
        minute = time?.second ?: s.builder.minute,
        customInterval = interval ?: s.builder.customInterval,
        dayOfMonth = dom ?: s.builder.dayOfMonth,
        customMonthDays = if (s.builder.frequency == Frequency.CUSTOM && s.builder.customUnit in setOf(CustomUnit.MONTHLY, CustomUnit.YEARLY)) monthDays.ifEmpty { s.builder.customMonthDays } else s.builder.customMonthDays,
        customMonth = month ?: s.builder.customMonth,
        rawExpr = if (s.builder.frequency == Frequency.RAW) s.cron else s.builder.rawExpr,
    )
    val f = builder.frequency
    val needsTime = !s.once && (
        f in setOf(Frequency.DAILY, Frequency.WEEKDAYS, Frequency.WEEKLY, Frequency.MONTHLY) ||
            (f == Frequency.CUSTOM && builder.customUnit != CustomUnit.MINUTE && builder.customUnit != CustomUnit.HOURLY)
        )
    val scheduleValid = when {
        s.once -> (s.onceMinutes.toIntOrNull() ?: 0) > 0
        f == Frequency.HOURLY -> builder.minute in 0..59 && (time != null || s.time.isBlank())
        f == Frequency.RAW -> AutomationSchedule.isWellFormedCron(s.cron)
        f == Frequency.WEEKLY -> time != null && builder.weekdays.isNotEmpty()
        f == Frequency.MONTHLY -> time != null && (dom ?: 0) in 1..31
        f == Frequency.CUSTOM -> (interval ?: 0) in 1..200 && when (builder.customUnit) {
            CustomUnit.MINUTE -> true
            CustomUnit.HOURLY -> true
            CustomUnit.WEEKLY -> time != null && builder.customWeekdays.isNotEmpty()
            CustomUnit.MONTHLY -> time != null && (builder.customMonthlyMode == MonthlyMode.WEEKDAY || monthDays.isNotEmpty())
            CustomUnit.YEARLY -> time != null && (month ?: 0) in 1..12 && monthDays.isNotEmpty()
            CustomUnit.DAILY -> time != null
        }
        else -> time != null
    }
    val endsValid = !s.endsOnDate || localDateEndTimestamp(s.endDate) != null
    val maxRunsValid = s.maxRuns.isBlank() || (s.maxRuns.toIntOrNull() ?: 0) > 0
    val valid = s.title.isNotBlank() && s.prompt.isNotBlank() && scheduleValid && endsValid && maxRunsValid

    val preview = if (s.once) labels.once else builder.describe(labels)

    fun requestDismiss() { if (dirty) confirmDiscard = true else onDismiss() }

    AlertDialog(
        onDismissRequest = { requestDismiss() },
        title = { Text(stringResource(if (existing != null) R.string.automations_edit_title else R.string.automations_create_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = s.title, onValueChange = { s = s.copy(title = it) },
                    label = { Text(stringResource(R.string.automations_field_title)) },
                    modifier = Modifier.fillMaxWidth().testTag("automation_form_title"), maxLines = 1,
                )
                OutlinedTextField(
                    value = s.prompt, onValueChange = { s = s.copy(prompt = it) },
                    label = { Text(stringResource(R.string.automations_field_prompt)) },
                    modifier = Modifier.fillMaxWidth().testTag("automation_form_prompt"), minLines = 2, maxLines = 5,
                )
                Text(stringResource(R.string.automations_field_schedule), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // ---- frequency chips (desktop CronFrequency + once + raw cron) ----
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FreqChip(stringResource(R.string.automations_freq_once), s.once, "automation_freq_once") { s = s.copy(once = true) }
                    for ((freq, label, tag) in listOf(
                        Triple(Frequency.HOURLY, R.string.automations_freq_hourly, "hourly"),
                        Triple(Frequency.DAILY, R.string.automations_freq_daily, "daily"),
                        Triple(Frequency.WEEKDAYS, R.string.automations_freq_weekdays, "weekdays"),
                        Triple(Frequency.WEEKLY, R.string.automations_freq_weekly, "weekly"),
                        Triple(Frequency.MONTHLY, R.string.automations_freq_monthly, "monthly"),
                        Triple(Frequency.CUSTOM, R.string.automations_freq_custom, "custom"),
                        Triple(Frequency.RAW, R.string.automations_sched_cron, "cron"),
                    )) {
                        FreqChip(stringResource(label), !s.once && f == freq, "automation_freq_$tag") {
                            s = s.copy(once = false, builder = s.builder.copy(frequency = freq))
                        }
                    }
                }
                // ---- per-kind controls ----
                when {
                    s.once -> OutlinedTextField(
                        value = s.onceMinutes, onValueChange = { v -> s = s.copy(onceMinutes = v.filter { it.isDigit() }) },
                        label = { Text(stringResource(R.string.automations_sched_minutes)) },
                        modifier = Modifier.fillMaxWidth().testTag("automation_form_minutes"), maxLines = 1,
                    )
                    f == Frequency.RAW -> OutlinedTextField(
                        value = s.cron, onValueChange = { s = s.copy(cron = it) },
                        label = { Text(stringResource(R.string.automations_sched_cron)) },
                        modifier = Modifier.fillMaxWidth().testTag("automation_form_cron"), maxLines = 1,
                        isError = s.cron.isNotBlank() && !AutomationSchedule.isWellFormedCron(s.cron),
                    )
                    f == Frequency.HOURLY -> OutlinedTextField(
                        value = s.time.substringAfter(":", s.time), onValueChange = { v -> s = s.copy(time = "0:" + v.filter { it.isDigit() }.take(2)) },
                        label = { Text(stringResource(R.string.automations_field_minute)) },
                        modifier = Modifier.fillMaxWidth().testTag("automation_form_minute"), maxLines = 1,
                    )
                    else -> {
                        if (f == Frequency.CUSTOM) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = s.intervalText, onValueChange = { v -> s = s.copy(intervalText = v.filter { it.isDigit() }.take(3)) },
                                    label = { Text(stringResource(R.string.automations_field_every)) },
                                    modifier = Modifier.weight(1f).testTag("automation_form_interval"), maxLines = 1,
                                    isError = (interval ?: 0) !in 1..200,
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for ((unit, label) in listOf(
                                    CustomUnit.MINUTE to R.string.automations_unit_minute, CustomUnit.HOURLY to R.string.automations_unit_hourly,
                                    CustomUnit.DAILY to R.string.automations_unit_daily, CustomUnit.WEEKLY to R.string.automations_unit_weekly,
                                    CustomUnit.MONTHLY to R.string.automations_unit_monthly, CustomUnit.YEARLY to R.string.automations_unit_yearly,
                                )) {
                                    FreqChip(stringResource(label), builder.customUnit == unit, "automation_unit_${unit.wire}") {
                                        s = s.copy(builder = s.builder.copy(customUnit = unit))
                                    }
                                }
                            }
                            if (builder.customUnit == CustomUnit.MONTHLY) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FreqChip(stringResource(R.string.automations_monthly_date), builder.customMonthlyMode == MonthlyMode.DATE, "automation_monthly_date") {
                                        s = s.copy(builder = s.builder.copy(customMonthlyMode = MonthlyMode.DATE))
                                    }
                                    FreqChip(stringResource(R.string.automations_monthly_weekday), builder.customMonthlyMode == MonthlyMode.WEEKDAY, "automation_monthly_weekday") {
                                        s = s.copy(builder = s.builder.copy(customMonthlyMode = MonthlyMode.WEEKDAY))
                                    }
                                }
                            }
                            if (builder.customUnit == CustomUnit.MONTHLY && builder.customMonthlyMode == MonthlyMode.DATE || builder.customUnit == CustomUnit.YEARLY) {
                                OutlinedTextField(
                                    value = s.monthDaysText, onValueChange = { v -> s = s.copy(monthDaysText = v.filter { it.isDigit() || it == ',' }) },
                                    label = { Text(stringResource(R.string.automations_field_month_days)) },
                                    modifier = Modifier.fillMaxWidth().testTag("automation_form_monthdays"), maxLines = 1,
                                )
                            }
                            if (builder.customUnit == CustomUnit.YEARLY) {
                                OutlinedTextField(
                                    value = s.monthText, onValueChange = { v -> s = s.copy(monthText = v.filter { it.isDigit() }.take(2)) },
                                    label = { Text(stringResource(R.string.automations_field_month)) },
                                    modifier = Modifier.fillMaxWidth().testTag("automation_form_month"), maxLines = 1,
                                )
                            }
                        }
                        if (f == Frequency.WEEKLY || (f == Frequency.CUSTOM && (builder.customUnit == CustomUnit.WEEKLY || (builder.customUnit == CustomUnit.MONTHLY && builder.customMonthlyMode == MonthlyMode.WEEKDAY)))) {
                            val single = f == Frequency.CUSTOM && builder.customUnit == CustomUnit.MONTHLY
                            val selected = if (f == Frequency.WEEKLY) builder.weekdays else builder.customWeekdays
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (d in AutomationSchedule.WEEKDAY_ORDER) {
                                    FreqChip(labels.weekday(d), d in selected, "automation_wd_$d") {
                                        val next = when {
                                            single -> listOf(d)
                                            d in selected -> selected - d
                                            else -> (selected + d).sorted()
                                        }
                                        s = if (f == Frequency.WEEKLY) s.copy(builder = s.builder.copy(weekdays = next)) else s.copy(builder = s.builder.copy(customWeekdays = next))
                                    }
                                }
                            }
                        }
                        if (f == Frequency.MONTHLY) {
                            OutlinedTextField(
                                value = s.domText, onValueChange = { v -> s = s.copy(domText = v.filter { it.isDigit() }.take(2)) },
                                label = { Text(stringResource(R.string.automations_field_day_of_month)) },
                                modifier = Modifier.fillMaxWidth().testTag("automation_form_dom"), maxLines = 1,
                                isError = (dom ?: 0) !in 1..31,
                            )
                        }
                        if (needsTime) {
                            OutlinedTextField(
                                value = s.time, onValueChange = { s = s.copy(time = it) },
                                label = { Text(stringResource(R.string.automations_sched_daily_time)) },
                                modifier = Modifier.fillMaxWidth().testTag("automation_form_time"), maxLines = 1,
                                isError = time == null,
                            )
                        }
                    }
                }
                // ---- the preview line (desktop describeCronBuilder) ----
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("automation_form_preview"),
                )
                // ---- ends + maxRuns (recurring schedules only) ----
                if (!s.once) {
                    Text(stringResource(R.string.automations_field_ends), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FreqChip(stringResource(R.string.automations_ends_never), !s.endsOnDate, "automation_ends_never") { s = s.copy(endsOnDate = false) }
                        FreqChip(stringResource(R.string.automations_ends_on_date), s.endsOnDate, "automation_ends_date") { s = s.copy(endsOnDate = true) }
                    }
                    if (s.endsOnDate) {
                        OutlinedTextField(
                            value = s.endDate, onValueChange = { s = s.copy(endDate = it) },
                            label = { Text(stringResource(R.string.automations_field_end_date)) },
                            modifier = Modifier.fillMaxWidth().testTag("automation_form_enddate"), maxLines = 1,
                            isError = !endsValid,
                        )
                    }
                    OutlinedTextField(
                        value = s.maxRuns, onValueChange = { v -> s = s.copy(maxRuns = v.filter { it.isDigit() }.take(4)) },
                        label = { Text(stringResource(R.string.automations_field_max_runs)) },
                        modifier = Modifier.fillMaxWidth().testTag("automation_form_maxruns"), maxLines = 1,
                    )
                }
                ZCodeOutlinedButton(onClick = { modelSheet = true }, modifier = Modifier.fillMaxWidth().testTag("automation_form_model")) {
                    Text(
                        s.model?.let { (p, m) -> "$p/$m" } ?: stringResource(R.string.create_model_default),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(R.string.automations_field_mode, mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            ZCodeButton(
                onClick = {
                    val once = s.once
                    val spec = if (once) AutomationsPort.ScheduleSpec.everyNMinutes(s.onceMinutes.toIntOrNull() ?: 30)
                    else AutomationsPort.ScheduleSpec.cron(builder.buildCronExpr())
                    val rule = if (once) null else builder.toScheduleRule(anchorAt = existing?.scheduleRule?.anchorAt ?: System.currentTimeMillis())
                    onSave(
                        AutomationEditorResult(
                            title = s.title.trim(), prompt = s.prompt.trim(), schedule = spec,
                            scheduleRule = rule,
                            // Update: a carried rule replaced by a plain kind → explicit null on the wire.
                            clearScheduleRule = existing?.scheduleRule != null && rule == null && !once,
                            recurring = if (once) false else (existing?.recurring ?: true),
                            maxRuns = if (once) null else s.maxRuns.toIntOrNull(),
                            endAt = if (!once && s.endsOnDate) localDateEndTimestamp(s.endDate) else null,
                            model = s.model, mode = mode,
                        ),
                    )
                },
                enabled = valid && !busy,
                modifier = Modifier.testTag("automation_form_save"),
            ) { Text(stringResource(R.string.create_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { requestDismiss() }, modifier = Modifier.testTag("automation_form_cancel")) {
                Text(stringResource(com.zcode.android.core.ui.R.string.action_cancel))
            }
        },
    )
    if (modelSheet) {
        modelPicker(s.model, { p, m -> s = s.copy(model = p to m); modelSheet = false }, { modelSheet = false })
    }
    if (confirmDiscard) {
        // Desktop automations.unsaved.*: "Discard scheduled task draft?"
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.automations_unsaved_title)) },
            text = { Text(stringResource(R.string.automations_unsaved_description)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }, modifier = Modifier.testTag("automation_discard_confirm")) {
                    Text(stringResource(R.string.automations_unsaved_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.testTag("automation_discard_keep")) {
                    Text(stringResource(R.string.automations_unsaved_keep))
                }
            },
        )
    }
}

@Composable
private fun FreqChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.testTag(tag),
    )
}
