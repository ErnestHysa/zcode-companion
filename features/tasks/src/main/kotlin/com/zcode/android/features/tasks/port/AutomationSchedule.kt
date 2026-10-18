package com.zcode.android.features.tasks.port

import com.zcode.android.domain.model.AutomationRow

/**
 * J2-target (gaps-and-polish Phase 4B): the desktop's cron builder, ported from
 * packages/ui/src/settings/automationFormat.ts (buildCronExpr / parseCronToBuilder /
 * describeCronBuilder) + automationCardSchedule.ts (scheduleRuleToBuilder,
 * describeAutomationCardSchedule). The desktop's frequency set is hourly / daily /
 * weekdays / weekly / monthly / custom (every N × minute|hourly|daily|weekly|monthly|
 * yearly, with monthly-by-date / by-weekday and a month for yearly); anything the
 * parser does not recognise is RAW and round-trips byte-identical (the J2 contract:
 * "unsupported kinds round-trip untouched").
 *
 * Authority rules (automation-types.ts): a CUSTOM repeat serializes the
 * `scheduleRule` (the scheduler's truth; `cronExpr` is only the compat display and
 * must stay croner-legal — minute steps ≤ 59, hour steps ≤ 24, day steps ≤ 31);
 * every other frequency is a plain cron with NO scheduleRule.
 */
object AutomationSchedule {

    enum class Frequency { HOURLY, DAILY, WEEKDAYS, WEEKLY, MONTHLY, CUSTOM, RAW }

    /** automation-types.ts ZCodeAutomationScheduleRule.unit (the wire strings). */
    enum class CustomUnit(val wire: String) {
        MINUTE("minute"), HOURLY("hourly"), DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly"), YEARLY("yearly");

        companion object {
            fun fromWire(v: String?): CustomUnit? = entries.firstOrNull { it.wire == v }
        }
    }

    enum class MonthlyMode(val wire: String) { DATE("date"), WEEKDAY("weekday") }

    /** Mon-first display order (desktop WEEKDAY_ORDER). */
    val WEEKDAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)

    data class Builder(
        val frequency: Frequency = Frequency.DAILY,
        /** daily/weekly/monthly: 0-23 */
        val hour: Int = 9,
        /** 0-59 */
        val minute: Int = 0,
        /** weekly: 0 (Sun) – 6 (Sat) */
        val weekdays: List<Int> = listOf(1),
        /** monthly: 1-31 */
        val dayOfMonth: Int = 1,
        /** RAW: the untouched 5-field expression */
        val rawExpr: String = "",
        val customInterval: Int = 1,
        val customUnit: CustomUnit = CustomUnit.DAILY,
        val customWeekdays: List<Int> = listOf(1),
        val customMonthDays: List<Int> = listOf(1),
        /** yearly: 1-12 */
        val customMonth: Int = 1,
        val customMonthlyMode: MonthlyMode = MonthlyMode.DATE,
    ) {
        /** buildCronExpr (desktop, verbatim incl. the croner step ceilings). */
        fun buildCronExpr(): String = when (frequency) {
            Frequency.HOURLY -> "$minute * * * *"
            Frequency.DAILY -> "$minute $hour * * *"
            Frequency.WEEKDAYS -> "$minute $hour * * 1-5"
            Frequency.WEEKLY -> {
                val days = if (weekdays.isNotEmpty()) weekdays.sorted().joinToString(",") else "*"
                "$minute $hour * * $days"
            }
            Frequency.MONTHLY -> "$minute $hour $dayOfMonth * *"
            Frequency.CUSTOM -> {
                val interval = maxOf(1, customInterval)
                when (customUnit) {
                    CustomUnit.MINUTE -> if (interval <= 59) "*/$interval * * * *" else "* * * * *"
                    CustomUnit.HOURLY -> if (interval <= 24) "$minute */$interval * * *" else "$minute * * * *"
                    CustomUnit.DAILY -> if (interval <= 31) "$minute $hour */$interval * *" else "$minute $hour * * *"
                    CustomUnit.WEEKLY -> {
                        val days = if (customWeekdays.isNotEmpty()) customWeekdays.joinToString(",") else "1"
                        "$minute $hour * * $days"
                    }
                    CustomUnit.MONTHLY -> if (customMonthlyMode == MonthlyMode.WEEKDAY) {
                        "$minute $hour * * ${customWeekdays.firstOrNull() ?: 1}#1"
                    } else {
                        val days = if (customMonthDays.isNotEmpty()) customMonthDays.joinToString(",") else "1"
                        "$minute $hour $days * *"
                    }
                    CustomUnit.YEARLY -> {
                        val month = customMonth.coerceIn(1, 12)
                        val day = customMonthDays.firstOrNull() ?: 1
                        "$minute $hour $day $month *"
                    }
                }
            }
            Frequency.RAW -> rawExpr.trim()
        }

        /**
         * The authoritative scheduleRule for CUSTOM repeats; null for every plain-cron
         * frequency (the desktop sends `scheduleRule` only when `frequency === "custom"`).
         * `anchorAt` is preserved on edit (`editing?.scheduleRule?.anchorAt ?? Date.now()`).
         */
        fun toScheduleRule(anchorAt: Long): AutomationRow.ScheduleRule? {
            if (frequency != Frequency.CUSTOM) return null
            return AutomationRow.ScheduleRule(
                unit = customUnit.wire,
                interval = maxOf(1, customInterval),
                hour = hour,
                minute = minute,
                anchorAt = anchorAt,
                weekdays = customWeekdays,
                monthDays = customMonthDays,
                months = if (customUnit == CustomUnit.YEARLY) listOf(customMonth.coerceIn(1, 12)) else null,
                monthlyMode = customMonthlyMode.wire,
            )
        }

        /** describeCronBuilder (desktop) — the preview line; RAW falls back to the expression itself. */
        fun describe(labels: Labels): String {
            val time = "${pad2(hour)}:${pad2(minute)}"
            return when (frequency) {
                Frequency.HOURLY -> labels.hourly(pad2(minute))
                Frequency.DAILY -> labels.daily(time)
                Frequency.WEEKDAYS -> labels.weekdays(time)
                Frequency.WEEKLY -> labels.weekly(
                    weekdays.sortedBy { WEEKDAY_ORDER.indexOf(it) }.joinToString(labels.separator) { labels.weekday(it) },
                    time,
                )
                Frequency.MONTHLY -> labels.monthly(dayOfMonth.toString(), time)
                Frequency.CUSTOM -> when (customUnit) {
                    CustomUnit.MINUTE -> labels.customMinutes(customInterval.toString())
                    CustomUnit.HOURLY -> labels.customHourly(customInterval.toString(), pad2(minute))
                    CustomUnit.DAILY -> labels.customDaily(customInterval.toString(), time)
                    CustomUnit.WEEKLY -> labels.customWeekly(
                        customInterval.toString(),
                        customWeekdays.joinToString(labels.separator) { labels.weekday(it) },
                        time,
                    )
                    CustomUnit.MONTHLY -> if (customMonthlyMode == MonthlyMode.WEEKDAY) {
                        labels.customMonthlyWeekday(customInterval.toString(), labels.weekday(customWeekdays.firstOrNull() ?: 1), time)
                    } else {
                        labels.customMonthlyDates(customInterval.toString(), customMonthDays.joinToString(", "), time)
                    }
                    CustomUnit.YEARLY -> labels.customYearly(
                        customInterval.toString(),
                        customMonth.toString(),
                        (customMonthDays.firstOrNull() ?: 1).toString(),
                        time,
                    )
                }
                Frequency.RAW -> labels.raw(rawExpr.trim())
            }
        }
    }

    /** Locale-neutral label table (the UI binds string resources; tests bind plain English). */
    class Labels(
        val weekday: (Int) -> String,
        val separator: String,
        val hourly: (minute: String) -> String,
        val daily: (time: String) -> String,
        val weekdays: (time: String) -> String,
        val weekly: (days: String, time: String) -> String,
        val monthly: (day: String, time: String) -> String,
        val customMinutes: (interval: String) -> String,
        val customHourly: (interval: String, minute: String) -> String,
        val customDaily: (interval: String, time: String) -> String,
        val customWeekly: (interval: String, days: String, time: String) -> String,
        val customMonthlyDates: (interval: String, days: String, time: String) -> String,
        val customMonthlyWeekday: (interval: String, day: String, time: String) -> String,
        val customYearly: (interval: String, month: String, day: String, time: String) -> String,
        val once: String,
        val raw: (expr: String) -> String,
    )

    private fun pad2(v: Int) = v.toString().padStart(2, '0')

    private val NUM = Regex("^\\d+$")
    private val STEP = Regex("^\\*/(\\d+)$")

    /** parseCronToBuilder (desktop, verbatim order): unknown shapes → RAW. */
    fun parseCron(expr: String): Builder {
        val fallback = Builder(frequency = Frequency.RAW, rawExpr = expr, customMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1)
        val parts = expr.trim().split(Regex("\\s+"))
        if (parts.size != 5) return fallback
        val (min, hr, dom, mon, dow) = parts
        val isNum = { v: String -> NUM.matches(v) }
        val minNum = min.toIntOrNull() ?: -1
        val hrNum = hr.toIntOrNull() ?: -1

        STEP.find(min)?.let { m ->
            if (hr == "*" && dom == "*" && mon == "*" && dow == "*") {
                val n = m.groupValues[1].toInt()
                if (n >= 1) return fallback.copy(frequency = Frequency.CUSTOM, customInterval = n, customUnit = CustomUnit.MINUTE, rawExpr = "")
            }
        }
        STEP.find(hr)?.let { m ->
            if (isNum(min) && dom == "*" && mon == "*" && dow == "*") {
                return fallback.copy(
                    frequency = Frequency.CUSTOM, minute = minNum, rawExpr = "",
                    customInterval = maxOf(1, m.groupValues[1].toInt()), customUnit = CustomUnit.HOURLY,
                )
            }
        }
        STEP.find(dom)?.let { m ->
            if (isNum(min) && isNum(hr) && mon == "*" && dow == "*") {
                return fallback.copy(
                    frequency = Frequency.CUSTOM, hour = hrNum, minute = minNum, rawExpr = "",
                    customInterval = maxOf(1, m.groupValues[1].toInt()), customUnit = CustomUnit.DAILY,
                )
            }
        }
        if (isNum(min) && hr == "*" && dom == "*" && mon == "*" && dow == "*") {
            return fallback.copy(frequency = Frequency.HOURLY, minute = minNum, rawExpr = "")
        }
        if (isNum(min) && isNum(hr) && dom == "*" && mon == "*" && dow == "*") {
            return fallback.copy(frequency = Frequency.DAILY, hour = hrNum, minute = minNum, rawExpr = "")
        }
        if (isNum(min) && isNum(hr) && dom == "*" && mon == "*" && dow == "1-5") {
            return fallback.copy(frequency = Frequency.WEEKDAYS, hour = hrNum, minute = minNum, rawExpr = "")
        }
        if (isNum(min) && isNum(hr) && dom == "*" && mon == "*" && dow != "*") {
            val days = dow.split(",").mapNotNull { it.toIntOrNull() }.filter { it in 0..6 }
            if (days.isNotEmpty() && days.size == dow.split(",").size) {
                return fallback.copy(frequency = Frequency.WEEKLY, hour = hrNum, minute = minNum, weekdays = days, rawExpr = "")
            }
        }
        if (isNum(min) && isNum(hr) && isNum(dom) && isNum(mon) && dow == "*") {
            val monthNum = mon.toInt()
            if (monthNum in 1..12) {
                return fallback.copy(
                    frequency = Frequency.CUSTOM, hour = hrNum, minute = minNum, rawExpr = "",
                    customUnit = CustomUnit.YEARLY, customMonth = monthNum, customMonthDays = listOf(dom.toInt()),
                )
            }
        }
        if (isNum(min) && isNum(hr) && isNum(dom) && mon == "*" && dow == "*") {
            return fallback.copy(frequency = Frequency.MONTHLY, hour = hrNum, minute = minNum, dayOfMonth = dom.toInt(), rawExpr = "")
        }
        return fallback
    }

    /**
     * scheduleRuleToBuilder (desktop): the authoritative rule seeds a CUSTOM builder
     * (never the cron re-parse — "避免自定义规则经过 cron 反解析后丢失 UI 语义"). An
     * unknown unit (older/foreign records) falls back to the cron parse so the row
     * still round-trips through RAW untouched.
     */
    fun fromRule(rule: AutomationRow.ScheduleRule, cronExpr: String): Builder {
        val unit = CustomUnit.fromWire(rule.unit) ?: return parseCron(cronExpr)
        val anchorMonth = java.util.Calendar.getInstance().apply { timeInMillis = rule.anchorAt }.get(java.util.Calendar.MONTH) + 1
        return Builder(
            frequency = Frequency.CUSTOM,
            hour = rule.hour,
            minute = rule.minute,
            weekdays = rule.weekdays.ifEmpty { listOf(1) },
            dayOfMonth = rule.monthDays.firstOrNull() ?: 1,
            rawExpr = "",
            customInterval = rule.interval,
            customUnit = unit,
            customWeekdays = rule.weekdays.ifEmpty { listOf(1) },
            customMonthDays = rule.monthDays.ifEmpty { listOf(1) },
            customMonth = rule.months?.firstOrNull() ?: anchorMonth,
            customMonthlyMode = if (rule.monthlyMode == MonthlyMode.WEEKDAY.wire) MonthlyMode.WEEKDAY else MonthlyMode.DATE,
        )
    }

    /** The editor seed for a row: rule first (authority), cron parse otherwise. */
    fun forRow(row: AutomationRow): Builder =
        row.scheduleRule?.let { fromRule(it, row.cronExpr) } ?: parseCron(row.cronExpr)

    /**
     * describeAutomationCardSchedule (desktop): a non-recurring single-run automation
     * (relative one-shots, calendar one-shots) reads "One-time" — its minute rule would
     * otherwise mislead as "every N minutes"; the real moment is the next-run line.
     */
    fun describeRow(recurring: Boolean, maxRuns: Int?, builder: Builder, labels: Labels): String {
        if (!recurring && (maxRuns ?: 1) <= 1) return labels.once
        return builder.describe(labels)
    }

    /** B2.1's permissive 5-field syntax gate (semantics stay the desktop's concern). */
    fun isWellFormedCron(expr: String): Boolean = AutomationsPort.ScheduleSpec.isWellFormedCron(expr)
}
