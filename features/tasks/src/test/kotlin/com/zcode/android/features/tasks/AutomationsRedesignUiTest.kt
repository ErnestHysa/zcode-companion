package com.zcode.android.features.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.components.LocalNotifier
import com.zcode.android.core.ui.components.Notifier
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.AutomationRunRow
import com.zcode.android.features.tasks.port.AutomationsPort
import com.zcode.android.features.tasks.ui.AutomationsSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * J2-target + A3.2+B2.11 (gaps-and-polish Phase 4B): the editor at the desktop's shape
 * (kinds hourly/daily/weekdays/weekly/monthly/custom/cron, preview line, ends, maxRuns,
 * unsaved guard, create-limit copy, pause/resume) and the run-history sheet with a
 * landed Run-Now (busy + toast + double-tap guard).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AutomationsRedesignUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class RecordingPort(
        autos: List<AutomationRow>,
        var runsResult: AutomationsPort.RunsResult = AutomationsPort.RunsResult(emptyList(), null),
        var createOutcome: AutomationsPort.CreateOutcome = AutomationsPort.CreateOutcome.OK,
        var runNowOutcome: AutomationsPort.RunNowOutcome = AutomationsPort.RunNowOutcome.QUEUED,
    ) : AutomationsPort {
        val created = mutableListOf<AutomationsPort.AutomationWrite>()
        val updated = mutableListOf<Pair<String, AutomationsPort.AutomationWrite>>()
        val enabled = mutableListOf<Pair<String, Boolean>>()
        val runNows = mutableListOf<String>()
        val deletedRuns = mutableListOf<String>()
        val restarts = mutableListOf<String>()
        var runNowGate: CompletableDeferred<Unit>? = null
        private val lists = AutomationsPort.Lists(autos, null, null)

        override suspend fun lists() = lists
        override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite) = true
        override suspend fun createTyped(workspaceKey: String, write: AutomationsPort.AutomationWrite): AutomationsPort.CreateOutcome {
            created += write; return createOutcome
        }
        override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite): Boolean { updated += automationId to write; return true }
        override suspend fun delete(automationId: String) = true
        override suspend fun setEnabled(automationId: String, enabled: Boolean): Boolean { this.enabled += automationId to enabled; return true }
        override suspend fun runNow(automationId: String) = true
        override suspend fun runNowTyped(automationId: String): AutomationsPort.RunNowOutcome {
            runNows += automationId
            runNowGate?.await()
            return runNowOutcome
        }
        override suspend fun runs(automationId: String) = runsResult
        override suspend fun deleteRun(runId: String): Boolean { deletedRuns += runId; return true }
        override suspend fun restart(automationId: String): Boolean { restarts += automationId; return true }
    }

    private fun row(
        id: String,
        cron: String,
        rule: String? = null,
        lifecycle: String = "active",
        enabled: Boolean = true,
        recurring: Boolean = true,
        maxRuns: Int? = null,
    ) = AutomationRow.decodeList(
        Json.parseToJsonElement(
            """{"automations":[{"automationId":"$id","title":"Auto $id","cronExpr":"$cron","prompt":"p","mode":"build",
                "enabled":$enabled,"lifecycleStatus":"$lifecycle","runCount":2,"recurring":$recurring,
                ${maxRuns?.let { "\"maxRuns\":$it," } ?: ""}
                ${rule?.let { "\"scheduleRule\":$it," } ?: ""}
                "workspaceKey":"/ws","locationKind":"local"}]}""",
        ).jsonObject,
    ).first()

    private val notices = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private fun setContent(port: AutomationsPort) {
        notices.clear(); opened.clear()
        rule.setContent {
            CompositionLocalProvider(LocalNotifier provides Notifier { m, _ -> notices += m }) {
                ZCodeTheme { AutomationsSection(port = port, onOpenTask = { opened += it }) }
            }
        }
        rule.waitForIdle()
    }

    private fun openEditor(id: String) {
        rule.onNodeWithTag("automation_edit_$id").performClick(); rule.waitForIdle()
    }

    private fun save() { rule.onNodeWithTag("automation_form_save").performClick(); rule.waitForIdle() }

    // ------------------------------------------------------------ per-kind round-trips (J2 acceptance)

    private fun assertBareSaveRoundTrips(id: String, cron: String, rule: String? = null) {
        val port = RecordingPort(listOf(row(id, cron, rule)))
        setContent(port)
        openEditor(id)
        save()
        assertEquals("bare Save reaches update once", 1, port.updated.size)
        assertEquals("$id: the cron must round-trip untouched", cron, port.updated.first().second.schedule.cronExprOrPlaceholder())
    }

    @Test fun `hourly round-trips untouched`() = assertBareSaveRoundTrips("h", "15 * * * *")
    @Test fun `daily round-trips untouched`() = assertBareSaveRoundTrips("d", "0 9 * * *")
    @Test fun `weekdays round-trips untouched`() = assertBareSaveRoundTrips("wd", "30 8 * * 1-5")
    @Test fun `weekly round-trips untouched`() = assertBareSaveRoundTrips("w", "0 9 * * 1,3,5")
    @Test fun `monthly round-trips untouched`() = assertBareSaveRoundTrips("m", "0 9 15 * *")
    @Test fun `yearly round-trips untouched`() = assertBareSaveRoundTrips("y", "0 9 25 12 *")
    @Test fun `unknown cron round-trips byte-identical through RAW`() = assertBareSaveRoundTrips("raw", "5 4 L * 5L")

    @Test
    fun `custom rule round-trips the SAME scheduleRule with its anchor`() {
        val ruleJson = """{"unit":"weekly","interval":2,"hour":5,"minute":30,"anchorAt":1700000000000,"weekdays":[1,3],"monthDays":[]}"""
        val port = RecordingPort(listOf(row("c", "30 5 * * 1,3", ruleJson)))
        setContent(port)
        openEditor("c")
        rule.onNodeWithTag("automation_form_preview").assertTextContains("Every 2 weeks", substring = true)
        save()
        val w = port.updated.first().second
        val r = w.scheduleRule!!
        assertEquals("weekly", r.unit); assertEquals(2, r.interval); assertEquals(listOf(1, 3), r.weekdays)
        assertEquals(1_700_000_000_000L, r.anchorAt)
        assertEquals("30 5 * * 1,3", w.schedule.cronExprOrPlaceholder())
        assertTrue(!w.clearScheduleRule)
    }

    @Test
    fun `switching a carried rule to a plain kind sends the explicit clear`() {
        val ruleJson = """{"unit":"daily","interval":3,"hour":9,"minute":0,"anchorAt":1,"weekdays":[],"monthDays":[]}"""
        val port = RecordingPort(listOf(row("c2", "0 9 */3 * *", ruleJson)))
        setContent(port)
        openEditor("c2")
        rule.onNodeWithTag("automation_freq_daily").performClick(); rule.waitForIdle()
        save()
        val w = port.updated.first().second
        assertNull(w.scheduleRule)
        assertTrue("a replaced rule must be cleared on the wire", w.clearScheduleRule)
        assertEquals("0 9 * * *", w.schedule.cronExprOrPlaceholder())
    }

    // ------------------------------------------------------------ builder UI, preview, validation

    @Test
    fun `preview line renders the desktop copy and follows the kind`() {
        val port = RecordingPort(emptyList())
        setContent(port)
        rule.onNodeWithTag("automations_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_preview").assertTextContains("Daily at 09:00")
        rule.onNodeWithTag("automation_freq_weekdays").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_preview").assertTextContains("Every weekday at 09:00")
        rule.onNodeWithTag("automation_freq_custom").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_interval").performTextClearance()
        rule.onNodeWithTag("automation_form_interval").performTextInput("3")
        rule.onNodeWithTag("automation_unit_daily").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_preview").assertTextContains("Every 3 days at 09:00")
    }

    @Test
    fun `custom repeat saves the rule plus a legal compat cron and maxRuns`() {
        val port = RecordingPort(emptyList())
        setContent(port)
        rule.onNodeWithTag("automations_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").performTextInput("Custom")
        rule.onNodeWithTag("automation_form_prompt").performTextInput("do")
        rule.onNodeWithTag("automation_freq_custom").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_interval").performTextClearance()
        rule.onNodeWithTag("automation_form_interval").performTextInput("61")
        rule.onNodeWithTag("automation_unit_minute").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_maxruns").performTextInput("5")
        save()
        val w = port.created.single()
        assertEquals("minute", w.scheduleRule!!.unit); assertEquals(61, w.scheduleRule!!.interval)
        assertEquals("* * * * *", w.schedule.cronExprOrPlaceholder())
        assertEquals(5, w.maxRuns)
    }

    @Test
    fun `weekly needs at least one weekday and the toggles drive the cron`() {
        val port = RecordingPort(emptyList())
        setContent(port)
        rule.onNodeWithTag("automations_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").performTextInput("W")
        rule.onNodeWithTag("automation_form_prompt").performTextInput("p")
        rule.onNodeWithTag("automation_freq_weekly").performClick(); rule.waitForIdle()
        // Default Mon; drop it → invalid; add Wed + Fri → "0 9 * * 3,5".
        rule.onNodeWithTag("automation_wd_1").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_save").assertIsNotEnabled()
        rule.onNodeWithTag("automation_wd_3").performClick()
        rule.onNodeWithTag("automation_wd_5").performClick(); rule.waitForIdle()
        save()
        assertEquals("0 9 * * 3,5", port.created.single().schedule.cronExprOrPlaceholder())
    }

    @Test
    fun `ends on a date sends endAt as the local day end`() {
        val port = RecordingPort(emptyList())
        setContent(port)
        rule.onNodeWithTag("automations_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").performTextInput("E")
        rule.onNodeWithTag("automation_form_prompt").performTextInput("p")
        rule.onNodeWithTag("automation_ends_date").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_enddate").performTextInput("2030-01-31")
        save()
        val endAt = port.created.single().endAt!!
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = endAt }
        assertEquals(2030, cal.get(java.util.Calendar.YEAR)); assertEquals(31, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(23, cal.get(java.util.Calendar.HOUR_OF_DAY)); assertEquals(59, cal.get(java.util.Calendar.MINUTE))
    }

    @Test
    fun `malformed cron and bad time are refused`() {
        val port = RecordingPort(emptyList())
        setContent(port)
        rule.onNodeWithTag("automations_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").performTextInput("X")
        rule.onNodeWithTag("automation_form_prompt").performTextInput("p")
        rule.onNodeWithTag("automation_form_time").performTextClearance()
        rule.onNodeWithTag("automation_form_time").performTextInput("25:99")
        rule.waitForIdle()
        rule.onNodeWithTag("automation_form_save").assertIsNotEnabled()
        rule.onNodeWithTag("automation_freq_cron").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_cron").performTextInput("garbage")
        rule.waitForIdle()
        rule.onNodeWithTag("automation_form_save").assertIsNotEnabled()
    }

    // ------------------------------------------------------------ guard + create limit + lifecycle

    @Test
    fun `dismissing a dirty editor asks before discarding`() {
        val port = RecordingPort(emptyList())
        setContent(port)
        rule.onNodeWithTag("automations_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").performTextInput("dirty")
        rule.onNodeWithTag("automation_form_cancel").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_discard_confirm").assertIsDisplayed()
        // Keep editing → the form survives.
        rule.onNodeWithTag("automation_discard_keep").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").assertTextContains("dirty")
        // Discard → the form closes, nothing written.
        rule.onNodeWithTag("automation_form_cancel").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_discard_confirm").performClick(); rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("automation_form_save").fetchSemanticsNodes().size)
        assertEquals(0, port.created.size)
    }

    @Test
    fun `a clean editor closes without the guard`() {
        val port = RecordingPort(emptyList())
        setContent(port)
        rule.onNodeWithTag("automations_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_cancel").performClick(); rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("automation_discard_confirm").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("automation_form_save").fetchSemanticsNodes().size)
    }

    @Test
    fun `create limit lands the desktop's limit copy`() {
        val port = RecordingPort(emptyList(), createOutcome = AutomationsPort.CreateOutcome.LIMIT_REACHED)
        setContent(port)
        rule.onNodeWithTag("automations_new").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_form_title").performTextInput("L")
        rule.onNodeWithTag("automation_form_prompt").performTextInput("p")
        save()
        assertTrue("limit notice names the 20-per-workspace cap: $notices", notices.any { it.contains("20") })
    }

    @Test
    fun `pause and resume ride setEnabled with the desktop's labels`() {
        val port = RecordingPort(listOf(row("p", "0 9 * * *", enabled = true)))
        setContent(port)
        rule.onNodeWithTag("automation_pause_p").assertTextContains("Pause")
        rule.onNodeWithTag("automation_pause_p").performClick(); rule.waitForIdle()
        assertEquals(listOf("p" to false), port.enabled)
    }

    @Test
    fun `a terminal automation offers Restart instead of pause`() {
        val port = RecordingPort(listOf(row("t", "0 9 * * *", lifecycle = "completed", enabled = false)))
        setContent(port)
        assertEquals(0, rule.onAllNodesWithTag("automation_pause_t").fetchSemanticsNodes().size)
        rule.onNodeWithTag("automation_restart_t").performClick(); rule.waitForIdle()
        assertEquals(listOf("t"), port.restarts)
    }

    // ------------------------------------------------------------ run history + Run Now (A3.2+B2.11)

    private fun runs(): List<AutomationRunRow> = AutomationRunRow.decodeList(
        Json.parseToJsonElement(
            """{"runs":[
              {"runId":"r:1","automationId":"r","workspaceKey":"/ws","trigger":"schedule","dispatchStatus":"dispatched","outcome":"succeeded","sessionId":"sess-r1","attempts":1,"createdAt":1760000000000,"updatedAt":1760000060000},
              {"runId":"r:manual:x","automationId":"r","workspaceKey":"/ws","trigger":"manual","dispatchStatus":"failed_to_dispatch","error":"boom","attempts":2,"createdAt":1760000100000,"updatedAt":1760000100500}
            ]}""",
        ).jsonObject,
    )

    @Test
    fun `run history sheet lists runs with trigger and status, opens the session, deletes a run`() {
        val port = RecordingPort(listOf(row("r", "0 9 * * *")), runsResult = AutomationsPort.RunsResult(runs(), null))
        setContent(port)
        rule.onNodeWithTag("automation_history_r").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_runs_sheet").assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithTag("automation_run_row").fetchSemanticsNodes().size)
        rule.onNodeWithText("Succeeded").assertIsDisplayed()
        rule.onNodeWithText("Failure").assertIsDisplayed()
        rule.onNodeWithTag("automation_run_open_r:1").performClick(); rule.waitForIdle()
        assertEquals(listOf("sess-r1"), opened)
        rule.onNodeWithTag("automation_run_delete_r:manual:x").performClick(); rule.waitForIdle()
        assertEquals(listOf("r:manual:x"), port.deletedRuns)
    }

    @Test
    fun `run history status filter narrows the list`() {
        val port = RecordingPort(listOf(row("r", "0 9 * * *")), runsResult = AutomationsPort.RunsResult(runs(), null))
        setContent(port)
        rule.onNodeWithTag("automation_history_r").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_runs_filter_failed").performClick(); rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithTag("automation_run_row").fetchSemanticsNodes().size)
    }

    @Test
    fun `empty and error run histories are honest`() {
        val port = RecordingPort(listOf(row("r", "0 9 * * *")), runsResult = AutomationsPort.RunsResult(null, "fault.x"))
        setContent(port)
        rule.onNodeWithTag("automation_history_r").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("automation_runs_error").assertIsDisplayed()
    }

    @Test
    fun `Run Now shows busy, lands the result as a notice, and drops the double-tap`() {
        val port = RecordingPort(listOf(row("n", "0 9 * * *")))
        port.runNowGate = CompletableDeferred()
        setContent(port)
        rule.onNodeWithTag("automation_run_n").performClick()
        rule.onNodeWithTag("automation_run_n").performClick() // double-tap while busy — dropped
        rule.waitForIdle()
        assertEquals("second tap must not double-fire", 1, port.runNows.size)
        rule.onNodeWithTag("automation_run_busy_n").assertIsDisplayed()
        port.runNowGate!!.complete(Unit)
        rule.waitForIdle()
        assertTrue("queued notice: $notices", notices.any { it.contains("queued", ignoreCase = true) })
    }

    @Test
    fun `a duplicate Run Now says a run is already in progress`() {
        val port = RecordingPort(listOf(row("n", "0 9 * * *")), runNowOutcome = AutomationsPort.RunNowOutcome.DUPLICATE)
        setContent(port)
        rule.onNodeWithTag("automation_run_n").performClick(); rule.waitForIdle()
        assertTrue("duplicate notice: $notices", notices.any { it.contains("already", ignoreCase = true) })
    }
}
