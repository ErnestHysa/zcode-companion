package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pass-18 mock-rig self-tests — the debts this pass fixes:
 *  - debt #19 (new, closed in-pass): sendText with `requestedDelivery:"queue"` is
 *    desktop-faithful — the input HOLDS as a queue item (inputAccepted{delivery:
 *    "queue"} → task waiting) instead of appending user/assistant rows; the G49
 *    one-shot "Queue this send" override is now observable on the rig (strip grows),
 *    and the clear disposition composes with it (empty → exactly the new item);
 *  - debt #8: seedOffPeakRigList is the shared seeding arm (MOCK_AUTOMATIONS implies
 *    the off-peak plane — the desktop's automations ARE scheduled/off-peak tasks).
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass18RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 18 }), expectedSid = "d_p18")
        m.activeSessionForTest("sess-p18")
        block?.let { m.script.it() }
        return m
    }

    private fun envelope(type: String, payload: JsonObject) = buildJsonObject {
        put("type", type)
        put("commandId", "cmd-1")
        put("payload", payload)
    }

    private fun sendTextPayload(
        text: String,
        requestedDelivery: String? = null,
        heldDisposition: String? = null,
    ): JsonObject = buildJsonObject {
        put("text", JsonPrimitive(text))
        requestedDelivery?.let { put("requestedDelivery", JsonPrimitive(it)) }
        heldDisposition?.let { put("heldQueueDisposition", JsonPrimitive(it)) }
    }

    private fun queueIds(m: MockZCode): List<String> =
        m.script.queueItems.orEmpty().map { it.queueItemId }

    // ---- debt #19: requestedDelivery=queue HOLDS the input ----

    @Test
    fun `queued delivery appends a queue item and NO rows (debt #19)`() {
        val m = mock {
            commandEffects = true
            queueItems = listOf(MockZCode.QueueItem("q-rig-1", "queued follow-up one"))
            autoDrain = false
        }
        val before = m.revisionForTest()
        m.applyCommandEffectForTest("sendText", envelope("sendText", sendTextPayload("the queued send", requestedDelivery = "queue")))
        assertEquals(listOf("q-rig-1", "q-rig-101"), queueIds(m), "the queued send appends past the seeds")
        assertTrue(m.appendedRowsForTest().isEmpty(), "a held input appends NO user/assistant rows")
        assertTrue(m.revisionForTest() > before, "every effect bumps the CAS revision")
    }

    @Test
    fun `keep disposition + queue override grows the kept queue (debt #19)`() {
        val m = mock {
            commandEffects = true
            queueItems = listOf(MockZCode.QueueItem("q-rig-1", "one"), MockZCode.QueueItem("q-rig-2", "two"))
            autoDrain = false
        }
        m.applyCommandEffectForTest(
            "sendText",
            envelope("sendText", sendTextPayload("third", requestedDelivery = "queue", heldDisposition = "keepQueueAndSend")),
        )
        assertEquals(listOf("q-rig-1", "q-rig-2", "q-rig-101"), queueIds(m), "keep leaves the held ids and appends")
    }

    @Test
    fun `clear disposition + queue override yields exactly the new item (debt #19)`() {
        val m = mock {
            commandEffects = true
            queueItems = listOf(MockZCode.QueueItem("q-rig-1", "one"), MockZCode.QueueItem("q-rig-2", "two"))
            autoDrain = false
        }
        m.applyCommandEffectForTest(
            "sendText",
            envelope("sendText", sendTextPayload("fresh", requestedDelivery = "queue", heldDisposition = "clearQueueAndSend")),
        )
        assertEquals(listOf("q-rig-101"), queueIds(m), "clear first, then the queued send")
    }

    @Test
    fun `immediate send (no override) still appends rows (regression guard)`() {
        val m = mock { commandEffects = true }
        m.applyCommandEffectForTest("sendText", envelope("sendText", sendTextPayload("right now")))
        assertTrue(m.appendedRowsForTest().isNotEmpty(), "the immediate path keeps its user/assistant rows")
        assertEquals(null, m.script.queueItems, "no queue mutation on the immediate path")
    }

    // ---- debt #8: the shared off-peak seeding arm ----

    @Test
    fun `seedOffPeakRigList serves the three-status list with the workspace key (debt #8)`() {
        val m = mock()
        seedOffPeakRigList(m)
        val list = assertNotNull(m.script.offPeakTaskList)
        assertEquals(3, list.size)
        assertEquals(listOf("queued", "paused", "completed"), list.map { (it.jsonObject["status"] as JsonPrimitive).content })
        assertTrue(list.all { (it.jsonObject["workspaceKey"] as JsonPrimitive).content == "/synthetic/ws-one" })
    }
}
