package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass-19 mock-rig self-tests — debt #20 (new, closed in-pass): the desktop's
 * held-queue CAS under MOCK_QUEUE_GHOST_APPEND_MS. A trio-bearing sendText whose
 * expectedHeldQueueItemIds no longer matches the live queue (host
 * applyHeldQueueDisposition: set-equality — no duplicates, same length, every id
 * present, only under inputRouting.mode=="choice") answers the desktop-faithful
 * failure ack {status:"failed", reasonCode:"guard.heldQueueConfirmationStale"} and
 * mutates NOTHING; the arm's one-shot SILENT ghost append (no patch, no revision
 * bump) is the race driver. Synthetic content only (INV-15/16).
 */
class MockZCodePass19RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 19 }), expectedSid = "d_p19")
        m.activeSessionForTest("sess-p19")
        block?.let { m.script.it() }
        return m
    }

    private fun trioEnvelope(
        disposition: String?,
        expectedIds: List<String>?,
        commandId: String = "cmd-1",
    ): JsonObject {
        val payload = buildJsonObject {
            put("text", JsonPrimitive("the raced send"))
            disposition?.let { put("heldQueueDisposition", JsonPrimitive(it)) }
            expectedIds?.let {
                put("expectedHeldQueueItemIds", kotlinx.serialization.json.JsonArray(it.map { id -> JsonPrimitive(id) }))
            }
        }
        return buildJsonObject {
            put("type", JsonPrimitive("sendText"))
            put("commandId", JsonPrimitive(commandId))
            put("payload", payload)
        }
    }

    private fun queueIds(m: MockZCode): List<String> =
        m.script.queueItems.orEmpty().map { it.queueItemId }

    // ---- the CAS mismatch: desktop-faithful failure ack, zero mutation ----

    @Test
    fun `mismatched expected ids answer guard-heldQueueConfirmationStale and mutate nothing`() {
        val m = mock {
            commandEffects = true
            heldQueueCasArm = true
            inputRoutingMode = "choice"
            queueItems = listOf(
                MockZCode.QueueItem("q-rig-1", "one"),
                MockZCode.QueueItem("q-rig-2", "two"),
                MockZCode.QueueItem("q-rig-ghost-1", "ghost"),
            )
            autoDrain = false
        }
        val before = m.revisionForTest()
        val ack = assertNotNull(m.heldQueueCasRejection(trioEnvelope("keepQueueAndSend", listOf("q-rig-1", "q-rig-2"))))
        assertEquals("failed", (ack["status"] as JsonPrimitive).content, "the host's dispatcher catch maps the guard error to status failed")
        assertEquals("guard.heldQueueConfirmationStale", (ack["reasonCode"] as JsonPrimitive).content)
        assertEquals("paused queue changed after send confirmation opened", (ack["message"] as JsonPrimitive).content)
        assertEquals(listOf("q-rig-1", "q-rig-2", "q-rig-ghost-1"), queueIds(m), "the mismatch path mutates the queue in no way")
        assertTrue(m.appendedRowsForTest().isEmpty(), "the mismatch path appends NO rows")
        assertEquals(before, m.revisionForTest(), "the mismatch path bumps no revision")
    }

    @Test
    fun `matching expected ids pass the CAS (the effect may proceed)`() {
        val m = mock {
            commandEffects = true
            heldQueueCasArm = true
            inputRoutingMode = "choice"
            queueItems = listOf(MockZCode.QueueItem("q-rig-1", "one"), MockZCode.QueueItem("q-rig-2", "two"))
            autoDrain = false
        }
        assertNull(m.heldQueueCasRejection(trioEnvelope("clearQueueAndSend", listOf("q-rig-2", "q-rig-1"))), "set-equality is order-insensitive")
        // And the effect itself runs normally after the pass:
        m.applyCommandEffectForTest("sendText", trioEnvelope("clearQueueAndSend", listOf("q-rig-2", "q-rig-1")))
        assertTrue(m.appendedRowsForTest().isNotEmpty(), "a CAS-passing clear send takes the immediate path")
        assertTrue(queueIds(m).isEmpty(), "the clear disposition empties the queue")
    }

    @Test
    fun `duplicate ids fail the set-equality even when the members match`() {
        val m = mock {
            heldQueueCasArm = true
            inputRoutingMode = "choice"
            queueItems = listOf(MockZCode.QueueItem("q-rig-1", "one"), MockZCode.QueueItem("q-rig-2", "two"))
        }
        assertNotNull(m.heldQueueCasRejection(trioEnvelope("keepQueueAndSend", listOf("q-rig-1", "q-rig-1"))))
    }

    @Test
    fun `empty expected ids pass (the host validates only a non-null set)`() {
        val m = mock {
            heldQueueCasArm = true
            inputRoutingMode = "choice"
            queueItems = listOf(MockZCode.QueueItem("q-rig-1", "one"))
        }
        assertNull(m.heldQueueCasRejection(trioEnvelope("keepQueueAndSend", emptyList())))
        assertNull(m.heldQueueCasRejection(trioEnvelope("keepQueueAndSend", null)))
    }

    @Test
    fun `the check is host-faithful (only under choice mode with a disposition)`() {
        val m = mock {
            heldQueueCasArm = true
            inputRoutingMode = "startNow"
            queueItems = listOf(MockZCode.QueueItem("q-rig-1", "one"))
        }
        assertNull(m.heldQueueCasRejection(trioEnvelope("keepQueueAndSend", listOf("stale-id"))), "no choice mode -> no CAS")
        val m2 = mock {
            heldQueueCasArm = true
            inputRoutingMode = "choice"
        }
        assertNull(m2.heldQueueCasRejection(trioEnvelope(null, listOf("stale-id"))), "no disposition -> no CAS")
    }

    @Test
    fun `arm off behaves exactly as before (regression guard)`() {
        val m = mock {
            commandEffects = true
            inputRoutingMode = "choice"
            queueItems = listOf(MockZCode.QueueItem("q-rig-1", "one"))
        }
        assertNull(m.heldQueueCasRejection(trioEnvelope("keepQueueAndSend", listOf("stale-id"))))
    }

    // ---- the ghost append: silent (no patch, no revision bump) ----

    @Test
    fun `the ghost append mutates the queue silently`() {
        val m = mock {
            heldQueueCasArm = true
            inputRoutingMode = "choice"
            queueItems = listOf(MockZCode.QueueItem("q-rig-1", "one"), MockZCode.QueueItem("q-rig-2", "two"))
        }
        val before = m.revisionForTest()
        m.appendHeldQueueGhostForTest()
        assertEquals(listOf("q-rig-1", "q-rig-2", "q-rig-ghost-1"), queueIds(m))
        assertEquals(before, m.revisionForTest(), "the ghost append pushes NO patch (silent by design)")
    }
}
