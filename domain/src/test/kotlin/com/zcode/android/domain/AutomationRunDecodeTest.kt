package com.zcode.android.domain

import com.zcode.android.domain.model.AutomationRunRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A3.2+B2.11 (gaps-and-polish Phase 4B): `listAutomationRuns` → ZCodeAutomationRun[]
 * (automation-types.ts: runId `automationId:scheduledAt` | `automationId:manual:uuid`,
 * trigger schedule|manual, dispatchStatus claimed|dispatched|failed_to_dispatch|skipped,
 * outcome running|succeeded|failed|stopped, sessionId back-filled on dispatch for
 * open-session-from-run). Enums stay RAW (closed sets, INV-8); optional fields → null.
 */
class AutomationRunDecodeTest {

    private val payload = """
      {"runs":[
        {"runId":"auto-1:1760000000000","automationId":"auto-1","workspaceKey":"/ws","scheduledAt":1760000000000,
         "trigger":"schedule","dispatchStatus":"dispatched","outcome":"succeeded","sessionId":"sess-run-1",
         "attempts":1,"createdAt":1760000000100,"updatedAt":1760000060000},
        {"runId":"auto-1:manual:abc","automationId":"auto-1","workspaceKey":"/ws",
         "trigger":"manual","dispatchStatus":"failed_to_dispatch","error":"runtime unavailable",
         "attempts":3,"createdAt":1760000100000,"updatedAt":1760000100500}
      ]}
    """.trimIndent()

    @Test
    fun `runs decode with trigger status outcome and session`() {
        val runs = AutomationRunRow.decodeList(Json.parseToJsonElement(payload).jsonObject)
        assertEquals(2, runs.size)
        val scheduled = runs[0]
        assertEquals("auto-1:1760000000000", scheduled.runId)
        assertEquals("schedule", scheduled.trigger)
        assertEquals("dispatched", scheduled.dispatchStatus)
        assertEquals("succeeded", scheduled.outcome)
        assertEquals("sess-run-1", scheduled.sessionId)
        assertEquals(1760000000000L, scheduled.scheduledAt)
        assertEquals(1, scheduled.attempts)
        val manual = runs[1]
        assertEquals("manual", manual.trigger)
        assertNull(manual.outcome)
        assertNull(manual.sessionId)
        assertNull(manual.scheduledAt)
        assertEquals("runtime unavailable", manual.error)
        assertEquals(3, manual.attempts)
    }

    @Test
    fun `display status folds dispatch and outcome the desktop way`() {
        // automations.runs.status.*: outcome wins when present; a failed/skipped dispatch
        // is its own terminal; claimed/dispatched without outcome = running.
        assertEquals("succeeded", AutomationRunRow.displayStatus(dispatchStatus = "dispatched", outcome = "succeeded"))
        assertEquals("failed", AutomationRunRow.displayStatus(dispatchStatus = "failed_to_dispatch", outcome = null))
        assertEquals("skipped", AutomationRunRow.displayStatus(dispatchStatus = "skipped", outcome = null))
        assertEquals("running", AutomationRunRow.displayStatus(dispatchStatus = "dispatched", outcome = null))
        assertEquals("running", AutomationRunRow.displayStatus(dispatchStatus = "claimed", outcome = null))
        assertEquals("stopped", AutomationRunRow.displayStatus(dispatchStatus = "dispatched", outcome = "stopped"))
    }

    @Test
    fun `duration is updatedAt minus createdAt only for terminal runs`() {
        val runs = AutomationRunRow.decodeList(Json.parseToJsonElement(payload).jsonObject)
        assertEquals(59_900L, runs[0].durationMs)
        assertEquals(500L, runs[1].durationMs)
    }
}
