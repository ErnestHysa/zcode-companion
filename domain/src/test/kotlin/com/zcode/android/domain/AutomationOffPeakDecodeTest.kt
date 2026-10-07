package com.zcode.android.domain

import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.OffPeakTaskRow
import com.zcode.android.domain.model.RowDecodeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GAP-6 (android-native-gaps.md; docs/protocol/v4-wire.md §12.2) — the automations
 * + off-peak list decodes, pinned to the GENERATED fixtures (rule 5). Shape
 * fidelity: the automation protocol face (scheduleRule display fields, closed
 * lifecycle enum kept RAW, recurring/maxRuns) + the channel-type extension fields
 * (workspaceKey for the client-side filter), and the off-peak snapshot face
 * (status enum raw, queuePosition, filesChanged/failureReason when present).
 */
class AutomationOffPeakDecodeTest {

    private fun fixture(name: String) = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
        .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
        .firstOrNull { it.isFile }
        ?.let { file ->
            val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
                as kotlinx.serialization.json.JsonArray
            cases.map { it.jsonObject }
                .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        }
        ?.let { Json.parseToJsonElement(it).jsonObject }
        ?: error("phase3-wire-fixtures.json not found")

    @Test
    fun decodesAutomations_matchingFixtureValues() {
        val automations = AutomationRow.decodeList(fixture("automation-list-mixed"))
        assertEquals(3, automations.size)
        val nightly = automations[0]
        assertEquals("auto-1", nightly.automationId)
        assertEquals("Nightly dependency review", nightly.title)
        assertEquals("30 2 * * *", nightly.cronExpr)
        assertEquals(true, nightly.enabled)
        assertEquals("active", nightly.lifecycleStatus)
        assertEquals(12, nightly.runCount)
        assertEquals(true, nightly.recurring)
        assertNull(nightly.maxRuns)
        assertEquals(1791500000000L, nightly.nextRunAt)
        assertEquals("/synthetic/ws", nightly.workspaceKey)
        assertEquals("build", nightly.mode)
        assertEquals("task-77", nightly.targetTaskId)
        val rule = nightly.scheduleRule!!
        assertEquals("daily", rule.unit)
        assertEquals(1, rule.interval)
        assertEquals(2, rule.hour)
        assertEquals(30, rule.minute)
        assertTrue(rule.weekdays.isEmpty())
        val weekly = automations[1]
        assertEquals("paused", weekly.lifecycleStatus)
        assertEquals(false, weekly.enabled)
        assertEquals(20, weekly.maxRuns)
        assertEquals("weekly", weekly.scheduleRule!!.unit)
        assertEquals(listOf(1), weekly.scheduleRule!!.weekdays)
        assertEquals("remote", weekly.locationKind)
        val oneShot = automations[2]
        assertEquals(false, oneShot.recurring)
        assertEquals(1, oneShot.maxRuns)
        assertEquals("completed", oneShot.lifecycleStatus)
    }

    @Test
    fun decodesEmptyAutomationList() {
        assertTrue(AutomationRow.decodeList(fixture("automation-list-empty")).isEmpty())
    }

    @Test
    fun missingAutomationId_throwsTypedFault() {
        val broken = fixture("automation-list-mixed").toMutableMap().apply {
            val arr = remove("automations") as kotlinx.serialization.json.JsonArray
            val first = (arr[0] as kotlinx.serialization.json.JsonObject).toMutableMap()
            first.remove("automationId")
            put("automations", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject(first))))
        }
        try {
            AutomationRow.decodeList(kotlinx.serialization.json.JsonObject(broken))
            throw AssertionError("expected RowDecodeException")
        } catch (e: RowDecodeException) {
            assertTrue(e.message!!.contains("automationId"))
        }
    }

    @Test
    fun unknownAutomationFields_areTolerated() {
        val withFuture = kotlinx.serialization.json.buildJsonObject {
            fixture("automation-list-mixed").forEach { (k, v) -> put(k, v) }
            put("futureField", JsonPrimitive(1))
        }
        assertEquals(3, AutomationRow.decodeList(withFuture).size)
    }

    @Test
    fun decodesOffPeakTasks_matchingFixtureValues() {
        val tasks = OffPeakTaskRow.decodeList(fixture("offpeak-list-mixed"))
        assertEquals(4, tasks.size)
        val queued = tasks.first { it.offPeakTaskId == "opt-1" }
        assertEquals("queued", queued.status)
        assertEquals(3, queued.queuePosition)
        assertNull(queued.sessionId)
        assertEquals("/synthetic/ws", queued.workspaceKey)
        val running = tasks.first { it.offPeakTaskId == "opt-2" }
        assertEquals("running", running.status)
        assertEquals("sess-9", running.sessionId)
        val failed = tasks.first { it.offPeakTaskId == "opt-4" }
        assertEquals("failed", failed.status)
        assertEquals("workspace unavailable", failed.failureReason)
        val other = tasks.first { it.offPeakTaskId == "opt-3" }
        assertEquals("completed", other.status)
        assertEquals("/other/ws", other.workspaceKey)
    }

    @Test
    fun decodesEmptyOffPeakList() {
        assertTrue(OffPeakTaskRow.decodeList(fixture("offpeak-list-empty")).isEmpty())
    }
}
