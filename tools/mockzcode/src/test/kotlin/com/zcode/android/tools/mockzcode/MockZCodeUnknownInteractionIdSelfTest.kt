package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Mock-fidelity debt #1 (pass 13) mock self-test: the MOCK_UNKNOWN_INTERACTION_IDS
 * arm's typed unknown-id gate on resolveInteraction. Un-armed, the verb accepted ANY
 * interactionId with an unconditional ack, so the app's answer-with-error face was
 * structurally undrivable. Armed, an answer naming no pending slot is rejected with
 * the desktop's {status:"rejected", reasonCode:"fault.interaction.unknownId"} shape;
 * the pending permission/userInput ids stay known; a hook-review id stays UNKNOWN
 * (hook reviews resolve through respondWorkspaceHookReview, never this verb).
 * Synthetic content only (INV-15/16).
 */
class MockZCodeUnknownInteractionIdSelfTest {

    private fun mock(): MockZCode =
        MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 11 }), expectedSid = "d_unkid")

    @Test
    fun `un-armed gate returns null for any id (the pre-pass-13 shape)`() {
        val m = mock()
        assertNull(m.resolveInteractionRejection("never-pended"), "un-armed: every answer proceeds")
        assertNull(m.resolveInteractionRejection(null), "un-armed: even a missing id proceeds (old contract)")
    }

    @Test
    fun `armed + unknown id rejects with fault_interaction_unknownId`() {
        val m = mock()
        m.script.unknownInteractionIdsArm = true
        assertEquals("fault.interaction.unknownId", m.resolveInteractionRejection("ghost-id-1"))
    }

    @Test
    fun `armed + a missing interactionId rejects with fault_interaction_params`() {
        val m = mock()
        m.script.unknownInteractionIdsArm = true
        assertEquals("fault.interaction.params", m.resolveInteractionRejection(null))
    }

    @Test
    fun `armed + the pending permission id proceeds`() {
        val m = mock()
        m.script.unknownInteractionIdsArm = true
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "mock-approval-1")
        assertNull(m.resolveInteractionRejection("mock-approval-1"))
    }

    @Test
    fun `armed + the pending user input id proceeds`() {
        val m = mock()
        m.script.unknownInteractionIdsArm = true
        m.script.pendingUserInput = MockZCode.PendingUserInput(interactionId = "q-rig-1")
        assertNull(m.resolveInteractionRejection("q-rig-1"))
    }

    @Test
    fun `armed + a resolved slot id rejects (stale answer, not a live slot)`() {
        val m = mock()
        m.script.unknownInteractionIdsArm = true
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "mock-approval-1")
        m.script.pendingPermission = null // resolved elsewhere before the answer lands
        assertEquals("fault.interaction.unknownId", m.resolveInteractionRejection("mock-approval-1"))
    }
}
