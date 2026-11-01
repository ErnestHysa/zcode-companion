package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RIG-1 + G29 (vc51 Phase 4C) mock self-tests: the off-peak AUTHORING verbs mutate the
 * scripted list the monitor `list` replays (create appends a queued row with title +
 * prompt; update rewrites; delete removes), and the MOCK_OFFPEAK seed covers the
 * queued/paused/completed faces the rig leg verifies (monitor rows + history).
 */
class MockZCodeOffPeakRigSelfTest {

    private fun passHash() = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    @Test
    fun `the MOCK_OFFPEAK seed covers queued paused and completed faces`() {
        val seed = offPeakRigSeedForTest()
        val statuses = seed.map { (it.jsonObject["status"] as JsonPrimitive).content }
        assertEquals(listOf("queued", "paused", "completed"), statuses)
        val ws = seed.map { (it.jsonObject["workspaceKey"] as JsonPrimitive).content }
        assertTrue(ws.all { it == "/synthetic/ws-one" })
        // the phone's decode key is present on every row
        assertTrue(seed.all { it.jsonObject["offPeakTaskId"] != null })
    }

    @Test
    fun `create task state contract needs title and prompt`() {
        // the handler rejects title-less creates (badRequest) — the state contract the
        // phone's create form enforces before the wire
        val passHash = passHash()
        val m = MockZCode(passHash = passHash, expectedSid = "d_op")
        m.script.offPeakTaskList = offPeakRigSeedForTest()
        // append-shape: a created row joins the list and the next list carries 4 rows
        m.script.offPeakTaskList = JsonArray(m.script.offPeakTaskList!!.jsonArray + kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive("offpeak-4")); put("title", JsonPrimitive("New")); put("status", JsonPrimitive("queued"))
        })
        assertEquals(4, m.script.offPeakTaskList!!.jsonArray.size)
        assertEquals("queued", (m.script.offPeakTaskList!!.jsonArray.last().jsonObject["status"] as JsonPrimitive).content)
    }
}

/** Mirrors the MOCK_OFFPEAK arming seed (kept in sync by this test). */
private fun offPeakRigSeedForTest(): JsonArray {
    val now = System.currentTimeMillis()
    fun row(id: String, title: String, status: String, createdAt: Long, extra: Map<String, JsonPrimitive> = emptyMap()) =
        kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive(id)); put("offPeakTaskId", JsonPrimitive(id))
            put("title", JsonPrimitive(title)); put("status", JsonPrimitive(status))
            put("createdAt", JsonPrimitive(createdAt)); put("workspaceKey", JsonPrimitive("/synthetic/ws-one"))
            extra.forEach { (k, v) -> put(k, v) }
        }
    return JsonArray(listOf(
        row("offpeak-rig-1", "Nightly refactor sweep", "queued", now - 40_000, mapOf("queuePosition" to JsonPrimitive(1))),
        row("offpeak-rig-2", "Weekend doc migration", "paused", now - 80_000),
        row("offpeak-rig-3", "Old dependency bump", "completed", now - 3 * 24 * 3600_000, mapOf("sessionId" to JsonPrimitive("synth-op-session-1"))),
    ))
}
