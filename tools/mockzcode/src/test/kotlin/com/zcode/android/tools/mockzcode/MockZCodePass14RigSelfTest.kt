package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass-14 mock-fidelity debts #10–#13 self-tests — the four anchored/scripted arms
 * the pass-13 legs were blocked on:
 *  - #10: MOCK_RESET_SUCCESS — the G90 reset happy path ({used:true} + the CONFIRMED
 *    status progression the phone's post-use reconciliation polls until COMPLETED);
 *  - #11: MOCK_OFFPEAK_COMPLETE_ANCHOR=first-list — the flip arms at the G63
 *    notifier's baseline seed (the relay-start anchor outran CONNECTED);
 *  - #12: MOCK_DISCONNECT_AFTER_DATA_FRAMES — the frame-CLASS filter (data pushes
 *    only; poll frames never counted);
 *  - #13: MOCK_RUN_DELTA_ANCHOR=subscribe — the delta schedule arms at the FIRST
 *    conversation subscribe (the relay-start anchor raced every reconnect).
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass14RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 13 }), expectedSid = "d_p14")
        block?.let { m.script.it() }
        return m
    }

    /** The fault a usage-stats reply carried, or null on success (the response seam). */
    private fun usageStatsFault(m: MockZCode, name: String, arg: JsonObject): String? {
        val before = m.promiseResponsesForTest().size
        m.usageStatsPromiseForTest(7, name, com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj(arg))
        val last = m.promiseResponsesForTest().drop(before).lastOrNull() ?: return null
        val obj = (last.third as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json as? JsonObject
            ?: return null
        val msg = (obj["message"] as? JsonPrimitive)?.content
        return if (last.second == com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseError.id) msg else null
    }

    /** The SUCCESS payload a usage-stats reply carried (the response seam, success side). */
    private fun usageStatsPayload(m: MockZCode, name: String, arg: JsonObject): JsonObject? {
        val before = m.promiseResponsesForTest().size
        m.usageStatsPromiseForTest(7, name, com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj(arg))
        val last = m.promiseResponsesForTest().drop(before).lastOrNull() ?: return null
        if (last.second != com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseSuccess.id) return null
        return (last.third as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json as? JsonObject
    }

    private val resetStatusBase = JsonObject(mapOf(
        "availableFiveHourResets" to JsonArray(listOf(JsonObject(mapOf("expireAt" to JsonPrimitive(System.currentTimeMillis() + 3_600_000))))),
        "availableWeekResets" to JsonArray(listOf(JsonObject(mapOf("expireAt" to JsonPrimitive(System.currentTimeMillis() + 7 * 24 * 3_600_000))))),
        "latestFiveHourResetHistory" to JsonObject(mapOf("usedAt" to JsonPrimitive(System.currentTimeMillis() - 86_400_000))),
        "hasUnreadHistory" to JsonPrimitive(false),
    ))

    // -------------------------------------------------------------- debt #10

    @Test
    fun `unscripted use still faults (the pass-13 shape preserved)`() {
        val m = mock()
        assertEquals(
            "fault.mock.unscripted:useCodingPlanReset",
            usageStatsFault(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("WEEK")))),
            "unscripted → the typed fault the pass-13 receipt recorded",
        )
        assertNull(m.script.codingPlanResetUsedAtMs, "no stamp on the fault path")
    }

    @Test
    fun `scripted WEEK use stamps and the status read serves the confirmed progression`() {
        val m = mock {
            resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
            codingPlanResetStatus = resetStatusBase
        }
        val use = usageStatsPayload(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("WEEK"))))
        assertEquals(true, (use?.get("used") as? JsonPrimitive)?.content?.toBooleanStrictOrNull(), "{used:true}")
        val usedAt = m.script.codingPlanResetUsedAtMs
        assertTrue(usedAt != null && usedAt > 0, "the use stamped")
        assertEquals("WEEK", m.script.codingPlanResetUsedType)

        val status = usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))
        assertTrue(status != null)
        // The WEEK opportunity consumed + the usedAt history + the unread cursor —
        // applyResetStatus's completion rule (no NEW opportunity keeps completion sticky).
        assertEquals(0, (status!!["availableWeekResets"] as JsonArray).size, "the WEEK opportunity consumed")
        assertEquals(1, (status["availableFiveHourResets"] as JsonArray).size, "the FIVE_HOUR side untouched")
        val weekUsedAt = ((status["latestWeekResetHistory"] as? JsonObject)?.get("usedAt") as? JsonPrimitive)?.content?.toLongOrNull()
        assertEquals(usedAt, weekUsedAt, "the served usedAt IS the use stamp")
        assertEquals(true, (status["hasUnreadHistory"] as JsonPrimitive).content.toBooleanStrictOrNull())
    }

    @Test
    fun `scripted FIVE_HOUR use confirms on the five-hour keys`() {
        val m = mock {
            resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
            codingPlanResetStatus = resetStatusBase
        }
        usageStatsPayload(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("FIVE_HOUR"))))
        val status = usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))
        assertEquals(0, (status!!["availableFiveHourResets"] as JsonArray).size, "the FIVE_HOUR opportunity consumed")
        assertEquals(1, (status["availableWeekResets"] as JsonArray).size, "the WEEK side untouched")
    }

    @Test
    fun `a pre-use status read serves the base shape (no confirmation before the use)`() {
        val m = mock { codingPlanResetStatus = resetStatusBase }
        val status = usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))
        assertEquals(1, (status!!["availableWeekResets"] as JsonArray).size, "pre-use: opportunities intact")
        assertEquals(false, (status["hasUnreadHistory"] as JsonPrimitive).content.toBooleanStrictOrNull())
    }

    // -------------------------------------------------------------- debt #11

    /** Mirrors the MOCK_OFFPEAK rig seed (offpeak-rig-1 queued first). */
    private fun offPeakSeed(): JsonArray {
        val now = System.currentTimeMillis()
        fun row(id: String, status: String) = JsonObject(mapOf(
            "id" to JsonPrimitive(id), "offPeakTaskId" to JsonPrimitive(id),
            "title" to JsonPrimitive("Rig row $id"), "status" to JsonPrimitive(status),
            "createdAt" to JsonPrimitive(now), "workspaceKey" to JsonPrimitive("/synthetic/ws-one"),
        ))
        return JsonArray(listOf(row("offpeak-rig-1", "queued"), row("offpeak-rig-2", "paused")))
    }

    private fun listOffPeak(m: MockZCode) {
        m.offPeakTaskPromiseForTest(3, "list", com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj(JsonObject(emptyMap())))
    }

    @Test
    fun `first-list anchor flips offpeak-rig-1 to completed after the delay`() {
        val m = mock {
            offPeakTaskList = offPeakSeed()
            offPeakCompleteOnFirstListMs = 150L
        }
        listOffPeak(m)
        assertTrue(m.offPeakCompleteScheduledForTest(), "the first list armed the flip")
        val deadline = System.currentTimeMillis() + 5_000
        var status: String? = null
        while (System.currentTimeMillis() < deadline) {
            status = (m.script.offPeakTaskList!!.first().jsonObject["status"] as JsonPrimitive).content
            if (status == "completed") break
            Thread.sleep(50)
        }
        assertEquals("completed", status, "the flip fired from the list anchor")
    }

    @Test
    fun `anchor unset schedules nothing (the legacy relay-start thread owns the flip)`() {
        val m = mock { offPeakTaskList = offPeakSeed() }
        listOffPeak(m)
        assertFalse(m.offPeakCompleteScheduledForTest(), "no anchor → the in-handler schedule stays off")
    }

    // -------------------------------------------------------------- debt #12

    @Test
    fun `data-frame arm fires on the Nth data frame and consumes itself`() {
        val m = mock { disconnectAfterDataFrames = 2 }
        assertNull(m.noteDataFrameSent(), "frame 1: keep the socket")
        assertEquals("mock disconnect storm (data)", m.noteDataFrameSent(), "frame 2: the close reason")
        assertNull(m.noteDataFrameSent(), "the arm consumed itself — frame 3 keeps the socket")
        assertEquals(3L, m.dataFramesSentForTest())
    }

    @Test
    fun `no data-frame arm keeps every frame`() {
        val m = mock()
        repeat(5) { assertNull(m.noteDataFrameSent(), "unarmed: frame ${it + 1} never closes") }
        assertEquals(5L, m.dataFramesSentForTest())
    }

    // -------------------------------------------------------------- debt #13

    private fun declareDeltas(m: MockZCode) {
        m.lastClientHelloCapabilitiesForTest(
            JsonObject(mapOf("workflowRunDeltas" to JsonPrimitive(true))),
        )
        m.activeSessionForTest("sess-p14")
    }

    private fun awaitRunDelta(m: MockZCode, op: String, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = m.opLogForTest("sess-p14").any { frame ->
                frame.ops.any { (it as? JsonObject)?.get("op")?.toString()?.contains(op) == true }
            }
            if (found) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun `anchor off never arms the subscribe schedule (legacy relay-start owner)`() {
        val m = mock { enforceWorkflowRunDeltas = true }
        declareDeltas(m)
        m.armWorkflowRunDeltasOnSubscribe()
        assertFalse(m.runDeltasArmedForTest(), "no anchor → the subscribe handler is a no-op")
    }

    @Test
    fun `anchor without the enforce arm never arms`() {
        val m = mock { runDeltasAnchorSubscribe = true }
        declareDeltas(m)
        m.armWorkflowRunDeltasOnSubscribe()
        assertFalse(m.runDeltasArmedForTest(), "enforce off → nothing to schedule")
    }

    @Test
    fun `anchor on arms once and lands the update + remove from the subscribe`() {
        val m = mock {
            enforceWorkflowRunDeltas = true
            runDeltasAnchorSubscribe = true
            runDeltaUpdateMs = 200L
            runDeltaRemoveMs = 1_200L
        }
        declareDeltas(m)
        m.armWorkflowRunDeltasOnSubscribe()
        m.armWorkflowRunDeltasOnSubscribe() // the double-subscribe guard must no-op
        assertTrue(m.runDeltasArmedForTest(), "armed from the subscribe call")
        assertTrue(awaitRunDelta(m, "workflowRun.updated"), "the update landed (anchored, not relay-start)")
        assertTrue(awaitRunDelta(m, "workflowRun.removed"), "the remove followed")
        val updates = m.opLogForTest("sess-p14").count { frame ->
            frame.ops.any { it.jsonObject["op"]?.toString()?.contains("workflowRun.updated") == true }
        }
        assertEquals(1, updates, "the once-per-process guard: no double schedule")
    }
}
