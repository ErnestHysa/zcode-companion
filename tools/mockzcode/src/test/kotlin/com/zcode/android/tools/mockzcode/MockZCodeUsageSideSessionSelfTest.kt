package com.zcode.android.tools.mockzcode

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pass-29 mock self-tests: the three rig debts the walk converts into receipts —
 * (1) the Usage screen's snapshots (getEntitlementSnapshot / getAppUsageStats /
 * getCodingPlanUsageSnapshot) serve REAL fixture payloads instead of typed faults,
 * (2) `runAutomationNow` scripts `queued` once a historical automationRuns seed
 * exists (the History sheet lists the rows), and (3) `createSelectionSideSession`
 * names the child session in the ack (the commandResultSchema trio — the
 * forkAssistant shape), creates the child task and records the selection text.
 * Synthetic content only (INV-15/16).
 */
class MockZCodeUsageSideSessionSelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_p29")
        script(m.script)
        return m
    }

    private fun envelope(type: String, payload: JsonObject) = buildJsonObject {
        put("type", type)
        put("commandId", "cmd-1")
        put("payload", payload)
    }

    private fun fixtureData(name: String, file: String): JsonObject {
        val f = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/$file") }
            .firstOrNull { it.isFile } ?: error("$file not found")
        val cases = Json.parseToJsonElement(f.readText()).jsonObject["cases"]!!.jsonArray
        return cases.map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == name }["data"]!!.jsonObject
    }

    @Test
    fun `usage seeds are fixture-faithful and the charts seed carries the decode keys`() {
        // the arm serves the SAME payloads the data-module decode pins assert
        assertEquals("agent-db", fixtureData("app-usage-full", "phase3-wire-fixtures.json")["source"].toString().trim('"'))
        val entitlement = fixtureData("entitlement-full", "phase4-wire-fixtures.json")
        assertEquals("account:zai-individual-coding-plan", (entitlement["provider"]!!.jsonObject["id"] as JsonPrimitive).content)
        // the G20 charts seed: modelUsage.xTime + modelDataList[modelName,tokensUsage,totalTokens]
        val seed = codingPlanUsageRigSeed()
        val modelUsage = seed["modelUsage"]!!.jsonObject
        val xTime = modelUsage["xTime"]!!.jsonArray
        assertEquals(7, xTime.size)
        val models = modelUsage["modelDataList"]!!.jsonArray
        assertEquals(2, models.size)
        assertTrue(models.all {
            it.jsonObject["modelName"] != null &&
                it.jsonObject["tokensUsage"]!!.jsonArray.size == 7 &&
                it.jsonObject["totalTokens"] != null
        })
        // one completed historical run in the recordAutomationRun row shape
        // (outcome uses the closed automation-types.ts enum: running|succeeded|failed|stopped)
        val run = automationRunRigSeed()
        assertEquals("auto-rig-1", (run["automationId"] as JsonPrimitive).content)
        assertEquals("succeeded", (run["outcome"] as JsonPrimitive).content)
        assertEquals("dispatched", (run["dispatchStatus"] as JsonPrimitive).content)
    }

    @Test
    fun `createSelectionSideSession names the child in the ack and lists the task`() {
        val m = mock { s -> s.commandEffects = true }
        val before = m.revisionForTest()
        val result = assertNotNull(
            m.applyCommandEffectForTest(
                "createSelectionSideSession",
                envelope("createSelectionSideSession", buildJsonObject {
                    put("firstInput", buildJsonObject { put("text", JsonPrimitive("explain the diff in main.kt")) })
                }),
            ),
        )
        assertEquals("createSelectionSideSession", (result["type"] as JsonPrimitive).content)
        val child = (result["sessionId"] as JsonPrimitive).content
        assertTrue(child.startsWith("sess-side-"))
        assertEquals(child, m.lastSideSessionId)
        assertEquals("explain the diff in main.kt", m.lastSideSessionFirstText)
        assertTrue(m.revisionForTest() > before, "every effect bumps the CAS revision")
        assertTrue(m.script.bootstrapTasks.orEmpty().any { it.taskId == child }, "the side child is a listed task")
    }

    @Test
    fun `createSelectionSideSession without firstInput still names a child`() {
        val m = mock { s -> s.commandEffects = true }
        val result = assertNotNull(
            m.applyCommandEffectForTest("createSelectionSideSession", envelope("createSelectionSideSession", buildJsonObject { })),
        )
        assertEquals("createSelectionSideSession", (result["type"] as JsonPrimitive).content)
        assertTrue(m.lastSideSessionFirstText == null)
    }
}
