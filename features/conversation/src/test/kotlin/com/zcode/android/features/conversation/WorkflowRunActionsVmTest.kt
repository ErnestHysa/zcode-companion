package com.zcode.android.features.conversation

import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.domain.model.WorkflowRunSummary
import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G3 (vc51) ViewModel legs: the run sheet's Resume and amend actions dispatch through the
 * port (their FIRST send sites — the two words were modeled-never-sent), an acceptance
 * refreshes the runs list (the open sheet's run follows it — the sheet reflects the new
 * state), and a refusal raises the WORKFLOW_REFUSED notice instead of silence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowRunActionsVmTest {

    private fun run(id: String, status: WorkflowRunStatus, resumable: Boolean = false) = WorkflowRunSummary(
        runId = id, label = id, status = status, statusRaw = status.wire, stopReason = null, toolCallId = null,
        updatedAtMs = 1, resumedFrom = null, supersededBy = null, failureCode = null, failureMessage = null, resumable = resumable,
    )

    private class WorkflowPort : Phase7FakePort() {
        val calls = mutableListOf<String>()
        var runs: List<WorkflowRunSummary>? = emptyList()
        var resumeOutcome = ConversationPort.SendOutcome.ACCEPTED
        var amendOutcome = ConversationPort.SendOutcome.ACCEPTED

        override suspend fun fetchWorkflowRuns(taskId: String): List<WorkflowRunSummary>? { calls += "runs"; return runs }
        override suspend fun resumeWorkflowRun(taskId: String, runId: String): ConversationPort.SendOutcome {
            calls += "resume:$runId"; return resumeOutcome
        }
        override suspend fun amendWorkflowRunSettings(taskId: String, runId: String, subagentModel: String?, maxConcurrency: Int?): ConversationPort.SendOutcome {
            calls += "amend:$runId:$subagentModel:$maxConcurrency"; return amendOutcome
        }
    }

    @Test
    fun `resume dispatches the run and refreshes the sheet state`() = runTest {
        val port = WorkflowPort().apply { runs = listOf(run("r1", WorkflowRunStatus.RUNNING)) }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.resumeWorkflowRun(run("r1", WorkflowRunStatus.STOPPED, resumable = true))
        runCurrent()
        assertTrue("resume:r1" in port.calls)
        assertEquals(SendNotice.Key.WORKFLOW_STARTED, vm.state.value.sendNotice?.key)
        assertEquals(listOf("r1"), vm.workflowRuns.value?.map { it.runId })
    }

    @Test
    fun `resume refusal notices refused instead of silence`() = runTest {
        val port = WorkflowPort().apply { resumeOutcome = ConversationPort.SendOutcome.FAILED }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.resumeWorkflowRun(run("r1", WorkflowRunStatus.STOPPED, resumable = true))
        runCurrent()
        val notice = vm.state.value.sendNotice
        assertEquals(SendNotice.Key.WORKFLOW_REFUSED, notice?.key)
        assertEquals(SendNotice.Kind.ERROR, notice?.kind)
    }

    @Test
    fun `amend dispatches the typed fields and refreshes`() = runTest {
        val port = WorkflowPort().apply { runs = listOf(run("r1", WorkflowRunStatus.RUNNING)) }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.amendWorkflowRunSettings(run("r1", WorkflowRunStatus.RUNNING), subagentModel = "zai/glm-5.1", maxConcurrency = 3)
        runCurrent()
        assertTrue("amend:r1:zai/glm-5.1:3" in port.calls)
        assertEquals(listOf("r1"), vm.workflowRuns.value?.map { it.runId })
    }

    @Test
    fun `amend offline is the typed offline notice`() = runTest {
        val port = WorkflowPort().apply { amendOutcome = ConversationPort.SendOutcome.BLOCKED_OFFLINE }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.amendWorkflowRunSettings(run("r1", WorkflowRunStatus.RUNNING), null, null)
        runCurrent()
        assertEquals(SendNotice.Key.ROW_ACTION_OFFLINE, vm.state.value.sendNotice?.key)
    }
}
