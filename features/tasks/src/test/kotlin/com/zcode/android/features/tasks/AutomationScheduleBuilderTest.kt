package com.zcode.android.features.tasks

import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.features.tasks.port.AutomationSchedule
import com.zcode.android.features.tasks.port.AutomationSchedule.CustomUnit
import com.zcode.android.features.tasks.port.AutomationSchedule.Frequency
import com.zcode.android.features.tasks.port.AutomationSchedule.MonthlyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * J2-target (gaps-and-polish Phase 4B): the phone's schedule builder is a PORT of the
 * desktop's automationFormat.ts (buildCronExpr / parseCronToBuilder /
 * describeCronBuilder) + automationCardSchedule.ts (scheduleRuleToBuilder). Pins:
 * every kind round-trips UNTOUCHED through parse→build (the J2 corruption class —
 * "opening any non-minute schedule silently rewrites it as Daily 09:00"), custom
 * repeats serialize the authoritative scheduleRule, cron compat obeys croner's
 * step limits (a minute step of 61 would fail the desktop's save validation), and the preview
 * line is the desktop's own vocabulary.
 */
class AutomationScheduleBuilderTest {

    private val labels = AutomationSchedule.Labels(
        weekday = { d -> listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[d] },
        separator = ", ",
        hourly = { m -> "Every hour at :$m" },
        daily = { t -> "Daily at $t" },
        weekdays = { t -> "Every weekday at $t" },
        weekly = { days, t -> "Weekly on $days at $t" },
        monthly = { day, t -> "Monthly on day $day at $t" },
        customMinutes = { n -> "Every $n minutes" },
        customHourly = { n, m -> "Every $n hours at :$m" },
        customDaily = { n, t -> "Every $n days at $t" },
        customWeekly = { n, days, t -> "Every $n weeks on $days at $t" },
        customMonthlyDates = { n, days, t -> "Every $n months on day $days at $t" },
        customMonthlyWeekday = { n, day, t -> "Every $n months on the first $day at $t" },
        customYearly = { n, month, day, t -> "Every $n year(s) on $month/$day at $t" },
        once = "One-time",
        raw = { expr -> expr },
    )

    private fun roundTrip(expr: String): String = AutomationSchedule.parseCron(expr).buildCronExpr()

    // ------------------------------------------------------------ parse→build round-trips (desktop regexes)

    @Test
    fun `hourly M-star round-trips`() { assertEquals("15 * * * *", roundTrip("15 * * * *")) }

    @Test
    fun `daily M H round-trips`() { assertEquals("0 9 * * *", roundTrip("0 9 * * *")) }

    @Test
    fun `weekdays 1-5 round-trips`() { assertEquals("30 8 * * 1-5", roundTrip("30 8 * * 1-5")) }

    @Test
    fun `weekly day list round-trips sorted`() {
        assertEquals("30 5 * * 1", roundTrip("30 5 * * 1"))
        assertEquals("0 9 * * 1,3,5", roundTrip("0 9 * * 1,3,5"))
    }

    @Test
    fun `monthly by date round-trips`() { assertEquals("0 9 15 * *", roundTrip("0 9 15 * *")) }

    @Test
    fun `yearly M H DOM MON round-trips as custom yearly`() {
        val b = AutomationSchedule.parseCron("0 9 25 12 *")
        assertEquals(Frequency.CUSTOM, b.frequency)
        assertEquals(CustomUnit.YEARLY, b.customUnit)
        assertEquals(12, b.customMonth)
        assertEquals(listOf(25), b.customMonthDays)
        assertEquals("0 9 25 12 *", b.buildCronExpr())
    }

    @Test
    fun `custom minute step round-trips`() {
        val b = AutomationSchedule.parseCron("*/15 * * * *")
        assertEquals(Frequency.CUSTOM, b.frequency); assertEquals(CustomUnit.MINUTE, b.customUnit); assertEquals(15, b.customInterval)
        assertEquals("*/15 * * * *", b.buildCronExpr())
    }

    @Test
    fun `custom hourly and daily steps round-trip`() {
        assertEquals("0 */6 * * *", roundTrip("0 */6 * * *"))
        assertEquals("0 9 */3 * *", roundTrip("0 9 */3 * *"))
    }

    @Test
    fun `unknown expressions fall to RAW and round-trip byte-identical`() {
        val weird = "5 4 L * 5L"
        val b = AutomationSchedule.parseCron(weird)
        assertEquals(Frequency.RAW, b.frequency)
        assertEquals(weird, b.buildCronExpr())
    }

    // ------------------------------------------------------------ scheduleRule authority

    @Test
    fun `a carried scheduleRule seeds the custom builder and re-serializes the SAME rule`() {
        val rule = AutomationRow.ScheduleRule(
            unit = "weekly", interval = 2, hour = 5, minute = 30, anchorAt = 1_700_000_000_000L,
            weekdays = listOf(1, 3), monthDays = emptyList(),
        )
        val b = AutomationSchedule.fromRule(rule, cronExpr = "30 5 * * 1,3")
        assertEquals(Frequency.CUSTOM, b.frequency)
        assertEquals(CustomUnit.WEEKLY, b.customUnit)
        assertEquals(2, b.customInterval)
        assertEquals(listOf(1, 3), b.customWeekdays)
        val out = b.toScheduleRule(anchorAt = rule.anchorAt)!!
        assertEquals("weekly", out.unit); assertEquals(2, out.interval); assertEquals(5, out.hour); assertEquals(30, out.minute)
        assertEquals(listOf(1, 3), out.weekdays)
        assertEquals(emptyList(), out.monthDays)
        assertEquals(1_700_000_000_000L, out.anchorAt, "anchorAt is preserved on edit (desktop: editing?.scheduleRule?.anchorAt)")
    }

    @Test
    fun `non-custom frequencies carry NO scheduleRule (desktop: plain cron path)`() {
        assertNull(AutomationSchedule.parseCron("0 9 * * *").toScheduleRule(anchorAt = 1L))
        assertNull(AutomationSchedule.parseCron("30 5 * * 1").toScheduleRule(anchorAt = 1L))
    }

    @Test
    fun `a minute-unit rule is the J2 legacy one-shot carrier and seeds custom minutes`() {
        val rule = AutomationRow.ScheduleRule("minute", 30, 0, 0, 1L, emptyList(), emptyList())
        val b = AutomationSchedule.fromRule(rule, cronExpr = "*/30 * * * *")
        assertEquals(CustomUnit.MINUTE, b.customUnit); assertEquals(30, b.customInterval)
    }

    // ------------------------------------------------------------ cron compat limits (croner)

    @Test
    fun `custom steps beyond croner limits fall back to legal compat crons`() {
        val minutes = AutomationSchedule.Builder(frequency = Frequency.CUSTOM, customUnit = CustomUnit.MINUTE, customInterval = 61)
        assertEquals("* * * * *", minutes.buildCronExpr(), "*/61 fails save validation; the rule carries the interval")
        val hours = AutomationSchedule.Builder(frequency = Frequency.CUSTOM, customUnit = CustomUnit.HOURLY, customInterval = 31, minute = 49)
        assertEquals("49 * * * *", hours.buildCronExpr())
        val days = AutomationSchedule.Builder(frequency = Frequency.CUSTOM, customUnit = CustomUnit.DAILY, customInterval = 40, hour = 9, minute = 0)
        assertEquals("0 9 * * *", days.buildCronExpr())
    }

    @Test
    fun `custom monthly by weekday emits the first-weekday compat cron`() {
        val b = AutomationSchedule.Builder(
            frequency = Frequency.CUSTOM, customUnit = CustomUnit.MONTHLY, customInterval = 1,
            hour = 9, minute = 0, customMonthlyMode = MonthlyMode.WEEKDAY, customWeekdays = listOf(2),
        )
        assertEquals("0 9 * * 2#1", b.buildCronExpr())
        assertEquals("weekday", b.toScheduleRule(1L)!!.monthlyMode)
    }

    @Test
    fun `custom yearly serializes months on the rule`() {
        val b = AutomationSchedule.Builder(
            frequency = Frequency.CUSTOM, customUnit = CustomUnit.YEARLY, customInterval = 1,
            hour = 9, minute = 0, customMonth = 7, customMonthDays = listOf(4),
        )
        assertEquals("0 9 4 7 *", b.buildCronExpr())
        assertEquals(listOf(7), b.toScheduleRule(1L)!!.months)
    }

    // ------------------------------------------------------------ preview line (desktop vocabulary)

    @Test
    fun `describe renders the desktop's schedule copy per kind`() {
        assertEquals("Every hour at :15", AutomationSchedule.parseCron("15 * * * *").describe(labels))
        assertEquals("Daily at 09:00", AutomationSchedule.parseCron("0 9 * * *").describe(labels))
        assertEquals("Every weekday at 08:30", AutomationSchedule.parseCron("30 8 * * 1-5").describe(labels))
        assertEquals("Weekly on Mon, Wed at 09:00", AutomationSchedule.parseCron("0 9 * * 1,3").describe(labels))
        assertEquals("Monthly on day 15 at 09:00", AutomationSchedule.parseCron("0 9 15 * *").describe(labels))
        assertEquals("Every 15 minutes", AutomationSchedule.parseCron("*/15 * * * *").describe(labels))
        assertEquals("Every 6 hours at :00", AutomationSchedule.parseCron("0 */6 * * *").describe(labels))
        assertEquals("Every 3 days at 09:00", AutomationSchedule.parseCron("0 9 */3 * *").describe(labels))
        assertEquals("Every 1 year(s) on 12/25 at 09:00", AutomationSchedule.parseCron("0 9 25 12 *").describe(labels))
        assertEquals("5 4 L * 5L", AutomationSchedule.parseCron("5 4 L * 5L").describe(labels), "unknown cron falls back to the raw text — never misleads")
    }

    @Test
    fun `weekly days follow the Mon-first display order`() {
        assertEquals("Weekly on Mon, Sun at 09:00", AutomationSchedule.parseCron("0 9 * * 0,1").describe(labels))
    }

    @Test
    fun `a non-recurring single-run automation previews as One-time`() {
        val row = AutomationSchedule.parseCron("0 9 1 1 *")
        assertEquals("One-time", AutomationSchedule.describeRow(recurring = false, maxRuns = 1, builder = row, labels = labels))
        assertTrue(AutomationSchedule.describeRow(recurring = true, maxRuns = null, builder = row, labels = labels).startsWith("Every 1 year"))
    }

    // ------------------------------------------------------------ cron validation

    @Test
    fun `isWellFormedCron keeps the B2-1 permissive gate`() {
        assertTrue(AutomationSchedule.isWellFormedCron("30 5 * * 1"))
        assertTrue(!AutomationSchedule.isWellFormedCron(""))
        assertTrue(!AutomationSchedule.isWellFormedCron("not a cron"))
        assertTrue(!AutomationSchedule.isWellFormedCron("1 2 3 4"))
    }
}
