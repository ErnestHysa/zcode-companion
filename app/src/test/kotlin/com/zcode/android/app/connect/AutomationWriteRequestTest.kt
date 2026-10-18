package com.zcode.android.app.connect

import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.features.tasks.port.AutomationsPort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J2-target (gaps-and-polish Phase 4B): the automation write request is the desktop's
 * ZCodeAutomationCreateParams/UpdateParams shape (automation-types.ts): a CUSTOM
 * repeat carries the authoritative `scheduleRule` beside the compat `cronExpr`;
 * plain-cron kinds carry NO rule; `maxRuns`/`endAt` ride when set; update sends
 * `scheduleRule: null` to clear a rule the user replaced with a plain cron.
 */
class AutomationWriteRequestTest {

    private fun write(
        schedule: AutomationsPort.ScheduleSpec = AutomationsPort.ScheduleSpec.cron("0 9 * * *"),
        rule: AutomationRow.ScheduleRule? = null,
        recurring: Boolean = true,
        maxRuns: Int? = null,
        endAt: Long? = null,
        clearRule: Boolean = false,
    ) = AutomationsPort.AutomationWrite(
        title = "T", prompt = "P", schedule = schedule,
        scheduleRule = rule, recurring = recurring, maxRuns = maxRuns, endAt = endAt, clearScheduleRule = clearRule,
    )

    @Test
    fun `plain cron kinds send cronExpr and no scheduleRule`() {
        val req = automationWriteRequest(write())
        assertEquals("0 9 * * *", (req["cronExpr"] as JsonPrimitive).content)
        assertNull(req["scheduleRule"])
        assertEquals("true", (req["recurring"] as JsonPrimitive).content)
        assertNull(req["maxRuns"]); assertNull(req["endAt"]); assertNull(req["relativeDelayMinutes"])
    }

    @Test
    fun `a custom repeat sends the authoritative scheduleRule with months and monthlyMode`() {
        val rule = AutomationRow.ScheduleRule(
            unit = "yearly", interval = 1, hour = 9, minute = 0, anchorAt = 1_700_000_000_000L,
            weekdays = listOf(1), monthDays = listOf(25), months = listOf(12), monthlyMode = "date",
        )
        val req = automationWriteRequest(write(schedule = AutomationsPort.ScheduleSpec.cron("0 9 25 12 *"), rule = rule))
        val r = req["scheduleRule"]!!.jsonObject
        assertEquals("yearly", (r["unit"] as JsonPrimitive).content)
        assertEquals("1", (r["interval"] as JsonPrimitive).content)
        assertEquals("1700000000000", (r["anchorAt"] as JsonPrimitive).content)
        assertEquals(listOf("25"), (r["monthDays"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(listOf("12"), (r["months"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals("date", (r["monthlyMode"] as JsonPrimitive).content)
    }

    @Test
    fun `maxRuns and endAt ride when set`() {
        val req = automationWriteRequest(write(maxRuns = 5, endAt = 1_800_000_000_000L))
        assertEquals("5", (req["maxRuns"] as JsonPrimitive).content)
        assertEquals("1800000000000", (req["endAt"] as JsonPrimitive).content)
    }

    @Test
    fun `relative one-shot keeps the relativeDelayMinutes carrier and recurring=false`() {
        val req = automationWriteRequest(write(schedule = AutomationsPort.ScheduleSpec.everyNMinutes(30), recurring = false))
        assertEquals("30", (req["relativeDelayMinutes"] as JsonPrimitive).content)
        assertEquals("false", (req["recurring"] as JsonPrimitive).content)
    }

    @Test
    fun `clearScheduleRule sends an explicit null (update: rule replaced by a plain cron)`() {
        val req = automationWriteRequest(write(clearRule = true))
        assertTrue(req["scheduleRule"] is JsonNull)
    }
}
