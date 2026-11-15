package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pass-20 rig self-tests: (1) the GOAL half of the host's held-queue admission
 * (F-P20-A leg) — under inputRouting.mode=="choice" a disposition-less
 * sendGoalCommand answers the host-verbatim
 * {status:"failed", reasonCode:"heldQueueDispositionRequired"} ack (zcode.cjs
 * SMc -> n9e -> applyHeldQueueDisposition; the desktop UI pre-gates, the phone's
 * goal surfaces carry no trio); (2) the MOCK_CAS_STALE_TYPES arm — listed types
 * answer the host's revision-mismatch ack {status:"stale",
 * reasonCode:"proto.staleRevision"} (the phone's typed SESSION_CHANGED faces).
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass20RigSelfTest {

    private fun mock(): MockZCode =
        MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 12 }), expectedSid = "d_p20")

    private fun goalEnv(): JsonObject = Json.parseToJsonElement(
        """{"commandId":"c-p20-goal-1","type":"sendGoalCommand","payload":{"text":"finish the ledger","displayText":"/goal finish the ledger"}}""",
    ) as JsonObject

    @Test
    fun `un-armed goal admission proceeds (old shape)`() {
        val m = mock()
        m.script.inputRoutingMode = "choice"
        assertNull(m.heldQueueGoalAdmissionRejection(goalEnv()), "un-armed: every goal proceeds")
    }

    @Test
    fun `armed but non-choice mode proceeds`() {
        val m = mock()
        m.script.heldQueueCasArm = true
        m.script.inputRoutingMode = "startNow"
        assertNull(m.heldQueueGoalAdmissionRejection(goalEnv()))
    }

    @Test
    fun `armed + choice + disposition-less goal answers heldQueueDispositionRequired`() {
        val m = mock()
        m.script.heldQueueCasArm = true
        m.script.inputRoutingMode = "choice"
        val ack = m.heldQueueGoalAdmissionRejection(goalEnv())
        assertEquals("failed", ack?.get("status")?.jsonPrimitive?.content)
        assertEquals("heldQueueDispositionRequired", ack?.get("reasonCode")?.jsonPrimitive?.content)
        assertEquals(
            "held queue requires heldQueueDisposition (clearQueueAndSend | keepQueueAndSend)",
            ack?.get("message")?.jsonPrimitive?.content,
        )
        assertEquals("c-p20-goal-1", ack?.get("commandId")?.jsonPrimitive?.content)
    }

    @Test
    fun `armed + choice + goal CARRYING a disposition proceeds (CAS is the sendText helper's job)`() {
        val m = mock()
        m.script.heldQueueCasArm = true
        m.script.inputRoutingMode = "choice"
        val env = Json.parseToJsonElement(
            """{"commandId":"c-p20-goal-2","type":"sendGoalCommand","payload":{"text":"x","heldQueueDisposition":"keepQueueAndSend"}}""",
        ) as JsonObject
        assertNull(m.heldQueueGoalAdmissionRejection(env))
    }

    @Test
    fun `sendText trio-less shape unchanged (pass-19 contract - tolerates missing disposition)`() {
        val m = mock()
        m.script.heldQueueCasArm = true
        m.script.inputRoutingMode = "choice"
        val env = Json.parseToJsonElement(
            """{"commandId":"c-p20-text-1","type":"sendText","payload":{"text":"hello"}}""",
        ) as JsonObject
        assertNull(m.heldQueueCasRejection(env))
    }

    @Test
    fun `stale arm un-armed proceeds for any type`() {
        val m = mock()
        assertNull(m.casStaleAck("editQueueItem", goalEnv()))
    }

    @Test
    fun `stale arm unlisted type proceeds`() {
        val m = mock()
        m.script.casStaleTypes = setOf("editQueueItem")
        assertNull(m.casStaleAck("reorderQueueItem", goalEnv()))
    }

    @Test
    fun `stale arm listed type answers proto_staleRevision`() {
        val m = mock()
        m.script.casStaleTypes = setOf("editQueueItem", "sendQueuedNow")
        val ack = m.casStaleAck("editQueueItem", goalEnv())
        assertEquals("stale", ack?.get("status")?.jsonPrimitive?.content)
        assertEquals("proto.staleRevision", ack?.get("reasonCode")?.jsonPrimitive?.content)
        assertEquals("c-p20-goal-1", ack?.get("commandId")?.jsonPrimitive?.content)
    }
}
