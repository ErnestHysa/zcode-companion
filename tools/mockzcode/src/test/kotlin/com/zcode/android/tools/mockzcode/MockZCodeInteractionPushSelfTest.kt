package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RIG-3 (vc51 Phase 5) mock self-tests: the MOCK_INTERACTION_PUSH arm schedules a
 * FRESH approval push on the FIRST conversation subscribe — the rig app backgrounds
 * within the window and receives the state patch off-foreground, exercising the FRESH
 * notification path (shade actions included; the A12 re-post path is pinned on the
 * app side by NotificationBrokerTest). Synthetic content only (INV-15/16).
 */
class MockZCodeInteractionPushSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_push")
        // The relay arms emitStateKeys unconditionally — the push leg needs the block.
        m.script.emitStateKeys = true
        return m
    }

    private fun awaitPush(m: MockZCode, expectedId: String): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (m.pendingPermissionForTest()?.interactionId == expectedId) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `the armed push lands as a journaled fresh approval after the delay`() {
        val m = mock()
        m.armInteractionPushForTest(50)
        assertTrue(awaitPush(m, "mock-approval-push-1"), "the scheduled push must flip pendingPermission")
        // The state block carries the interaction (what the phone's coordinator consumes).
        val pending = m.stateBlockForTest()!!["pendingInteractions"] as JsonArray
        assertTrue(pending.any { (it.jsonObject["interactionId"] as? JsonPrimitive)?.content == "mock-approval-push-1" })
        // The patch is journaled on the default session (routing seam covered in the RIG-5 suite).
        assertTrue(m.opLogForTest("synth-task-1").isNotEmpty())
        // Permission-shaped payload: the fresh path's actions gate (toolName present).
        assertEquals("Bash", m.pendingPermissionForTest()?.toolName)
    }

    @Test
    fun `the push is once-per-connection — a second subscribe cannot re-arm`() {
        val m = mock()
        m.armInteractionPushForTest(30)
        assertTrue(awaitPush(m, "mock-approval-push-1"))
        assertTrue(m.interactionPushArmedForTest(), "the once-guard must hold after firing")
        // The production subscribe-side arming runs again (a resubscribe) — the guard refuses.
        m.subscribeArmsInteractionPushForTest()
        assertFalse(m.pushRescheduledForTest(), "no second push may be scheduled")
    }

    @Test
    fun `an unarmed mock schedules nothing (legacy suites keep the plain-ack contract)`() {
        val m = mock()
        m.subscribeArmsInteractionPushForTest()
        assertFalse(m.pushRescheduledForTest())
        assertEquals(null, m.pendingPermissionForTest())
    }

    @Test
    fun `the pushed PATCH carries the pushed interaction BEFORE the delivered flag flips (F-P4-A)`() {
        val m = mock()
        m.armInteractionPushForTest(50)
        assertTrue(awaitPush(m, "mock-approval-push-1"), "the scheduled push must flip pendingPermission")
        // The PATCH content pin — what the wire actually received at fire time, not the
        // post-delivery state block. Before the fix the patch was built from
        // snapshotPendingInteractions() (which excludes the undelivered push id), so
        // delivery degraded to the NEXT snapshot and landed STALE in the app.
        val carried = m.lastPushedPendingInteractionsForTest
        assertTrue(carried != null, "the push must have produced a patch")
        assertTrue(
            carried!!.any { (it.jsonObject["interactionId"] as? JsonPrimitive)?.content == "mock-approval-push-1" },
            "the pushed patch itself must carry the pushed interaction (F-P4-A)",
        )
    }

    @Test
    fun `the pushed patch keeps sibling pending interactions (no slot drop)`() {
        val m = mock()
        m.script.pendingUserInput = MockZCode.PendingUserInput(
            interactionId = "q-sibling", prompt = "Pick one", freeText = false,
            options = listOf("a", "b"), createdAtMs = System.currentTimeMillis(),
        )
        m.armInteractionPushForTest(40)
        assertTrue(awaitPush(m, "mock-approval-push-1"))
        val carried = m.lastPushedPendingInteractionsForTest
        assertTrue(carried != null && carried.any {
            (it.jsonObject["interactionId"] as? JsonPrimitive)?.content == "q-sibling"
        }, "the push patch must carry the sibling userInput beside the fresh permission")
    }
}
