package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * G48+G51 (vc52 Phase 11) mock self-test: the capability-enforcing workflowRun
 * delta gate. The shipped desktop streams the workflowRun delta family ONLY to
 * clients whose clientHello declared `capabilities.workflowRunDeltas === true`
 * (`clientSupportsWorkflowRunDeltas`, byte-verified asar `12a2fc93…`) — this arm
 * makes the rig catch what it structurally could not before:
 *  - an undeclaring client → 0 deltas streamed, suppression counted;
 *  - a declaring client → the scripted `workflowRun.updated` (run patch + actor/node
 *    step graph) and `workflowRun.removed` stream with the byte-verified shapes;
 *  - the arm un-armed → nothing pushes (never a fixture-only behavior).
 */
class MockZCodeRunDeltasGateSelfTest {

    private fun mock(): MockZCode =
        MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 }), expectedSid = "d_runparam")

    @Test
    fun `an undeclaring client is suppressed at the gate`() {
        val m = mock()
        m.script.enforceWorkflowRunDeltas = true
        m.activeSessionForTest("sess-delta")
        m.pushWorkflowRunUpdatedForTest()
        m.pushWorkflowRunRemovedForTest()
        assertEquals(0L, m.workflowRunDeltasSent, "no deltas to an undeclaring client")
        assertEquals(2L, m.workflowRunDeltasSuppressed, "both pushes counted suppressed")
    }

    @Test
    fun `a declaring client receives the scripted updated and removed shapes`() {
        val m = mock()
        m.script.enforceWorkflowRunDeltas = true
        m.activeSessionForTest("sess-delta")
        m.lastClientHelloCapabilitiesForTest(
            JsonObject(mapOf("workflowRunDeltas" to JsonPrimitive(true), "workspaceHookReviewUi" to JsonPrimitive(true))),
        )
        m.pushWorkflowRunUpdatedForTest()
        m.pushWorkflowRunRemovedForTest()
        assertEquals(2L, m.workflowRunDeltasSent)
        assertEquals(0L, m.workflowRunDeltasSuppressed)
        val frames = m.opLogForTest("sess-delta")
        assertEquals(2, frames.size, "one frame per op: ${frames.size}")
        val updated = frames[0].ops.first() as JsonObject
        assertEquals("workflowRun.updated", (updated["op"] as JsonPrimitive).content)
        assertEquals("wf-run-0", (updated["runId"] as JsonPrimitive).content)
        val run = updated["run"] as JsonObject
        assertEquals("completed", (run["status"] as JsonPrimitive).content)
        assertTrue("lastEventSequence" in run.keys, "the run patch carries lastEventSequence")
        val actors = updated["actors"] as kotlinx.serialization.json.JsonArray
        assertEquals("actor-a1", ((actors.first() as JsonObject)["siteId"] as JsonPrimitive).content)
        val nodes = updated["nodes"] as kotlinx.serialization.json.JsonArray
        assertEquals("settled", ((nodes.first() as JsonObject)["phase"] as JsonPrimitive).content)
        val removed = frames[1].ops.first() as JsonObject
        assertEquals("workflowRun.removed", (removed["op"] as JsonPrimitive).content)
        assertEquals("wf-run-s1", (removed["runId"] as JsonPrimitive).content)
    }

    @Test
    fun `the gate reads the recorded clientHello capabilities`() {
        val m = mock()
        assertNull(m.lastClientHelloCapabilitiesForTest(null), "never declared before a hello")
        m.lastClientHelloCapabilitiesForTest(
            JsonObject(mapOf("workflowRunDeltas" to JsonPrimitive(true))),
        )
        m.script.enforceWorkflowRunDeltas = true
        m.activeSessionForTest("sess-delta")
        m.pushWorkflowRunUpdatedForTest()
        assertEquals(1L, m.workflowRunDeltasSent, "the recorded hello's bit opens the gate")
    }

    @Test
    fun `the arm un-armed pushes nothing`() {
        val m = mock()
        m.lastClientHelloCapabilitiesForTest(JsonObject(mapOf("workflowRunDeltas" to JsonPrimitive(true))))
        m.pushWorkflowRunUpdatedForTest()
        assertEquals(0L, m.workflowRunDeltasSent, "un-armed fixture = never a behavior")
        assertEquals(0L, m.workflowRunDeltasSuppressed)
    }

    @Test
    fun `the schedule delays are armable`() {
        val m = mock()
        m.script.enforceWorkflowRunDeltas = true
        m.script.runDeltaUpdateMs = 500L
        m.script.runDeltaRemoveMs = 2_000L
        assertEquals(500L, m.script.runDeltaUpdateMs)
        assertEquals(2_000L, m.script.runDeltaRemoveMs)
    }
}
