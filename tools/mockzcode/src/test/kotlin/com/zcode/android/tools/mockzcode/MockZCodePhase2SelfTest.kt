package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonNull
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
 * vc51 Phase-2 mock self-tests: the rig arms the fix legs ride are HONEST —
 * `resumeWorkflowRun` flips a resumable run to running (and refuses a non-resumable one
 * with the typed fault), `amendWorkflowRunSettings` stops/supersedes the old run and grows
 * a new live run (the desktop semantics), and `deleteQueueItem` removes the item and
 * pushes the queue patch (the fifth queue verb's real round-trip).
 */
class MockZCodePhase2SelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_phase2")
        script(m.script)
        return m
    }

    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun envelope(type: String, payload: JsonObject) = buildJsonObject {
        put("type", type)
        put("commandId", "cmd-1")
        put("payload", payload)
    }

    private fun run(id: String, status: String, resumable: Boolean) = buildJsonObject {
        put("runId", JsonPrimitive(id))
        put("label", JsonPrimitive("lint-all"))
        put("status", JsonPrimitive(status))
        put("updatedAt", JsonPrimitive(1L))
        put("resumable", JsonPrimitive(resumable))
    }

    @Test
    fun `resumeWorkflowRun command flips a resumable run to running`() {
        val m = mock { it.workflowRuns.add(run("r1", "stopped", resumable = true)) }
        val outcome = m.workflowRunCommandResult("resumeWorkflowRun", "r1")
        assertTrue(outcome is MockZCode.WorkflowRunCommandOutcome.Accepted)
        val stored = m.script.workflowRuns.single { it.strOrNull("runId") == "r1" }
        assertEquals("running", stored.strOrNull("status"))
        assertEquals("false", stored.strOrNull("resumable"))
        val events = m.script.workflowRunEvents.getValue("r1")
        assertEquals("run.resumed", events.last().strOrNull("type"))
    }

    @Test
    fun `resumeWorkflowRun refuses a non-resumable run with the typed fault`() {
        val m = mock { it.workflowRuns.add(run("r1", "running", resumable = false)) }
        val outcome = m.workflowRunCommandResult("resumeWorkflowRun", "r1")
        assertEquals("fault.command.workflowRunResumeRejected.not_resumable", (outcome as MockZCode.WorkflowRunCommandOutcome.Fault).code)
    }

    @Test
    fun `amendWorkflowRunSettings supersedes the run and grows a new live run`() {
        val m = mock { it.workflowRuns.add(run("r1", "running", resumable = false)) }
        val outcome = m.workflowRunCommandResult("amendWorkflowRunSettings", "r1")
        val accepted = outcome as MockZCode.WorkflowRunCommandOutcome.Accepted
        assertEquals("settings-rig", accepted.result.strOrNull("toolCallId"))
        val old = m.script.workflowRuns.first { it.strOrNull("runId") == "r1" }
        assertEquals("stopped", old.strOrNull("status"))
        assertEquals("r1-amended", old.strOrNull("supersededBy"))
        val fresh = m.script.workflowRuns.single { it.strOrNull("runId") == "r1-amended" }
        assertEquals("running", fresh.strOrNull("status"))
    }

    @Test
    fun `resume or amend on an unknown run answers the typed not-found fault`() {
        val m = mock { }
        assertEquals("fault.command.workflowRunRejected.not_found", (m.workflowRunCommandResult("resumeWorkflowRun", "zz") as MockZCode.WorkflowRunCommandOutcome.Fault).code)
        assertEquals("fault.command.workflowRunRejected.not_found", (m.workflowRunCommandResult("amendWorkflowRunSettings", "zz") as MockZCode.WorkflowRunCommandOutcome.Fault).code)
    }

    @Test
    fun `deleteQueueItem removes the item and the patch builder follows`() {
        val m = mock {
            it.queueItems = listOf(
                MockZCode.QueueItem("q-1", "one"),
                MockZCode.QueueItem("q-2", "two"),
            )
            it.autoDrain = false
        }
        val result = m.applyCommandEffectForTest("deleteQueueItem", envelope("deleteQueueItem", buildJsonObject { put("queueItemId", JsonPrimitive("q-1")) }))
        assertNull(result)
        assertEquals(listOf("q-2"), m.script.queueItems?.map { it.queueItemId })
    }
}
