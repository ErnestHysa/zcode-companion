package com.zcode.android.features.conversation

import com.zcode.android.domain.model.WorkflowRunEvent
import com.zcode.android.domain.model.WorkflowRunEventsPage
import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.domain.model.WorkflowRunSummary
import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3.3 + L6.4 (Phase 5A) ViewModel legs: the run list loads on refresh and the open
 * sheet's run follows the list; events page forward by afterSequence (the max seen);
 * an unavailable events read renders the unavailable face (not empty); startSavedWorkflow
 * surfaces the typed reasonCode on refusal and refreshes the runs on success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowRunsVmTest {

    private fun run(id: String, status: WorkflowRunStatus, label: String? = id) = WorkflowRunSummary(
        runId = id, label = label, status = status, statusRaw = status.wire, stopReason = null, toolCallId = null,
        updatedAtMs = 1, resumedFrom = null, supersededBy = null, failureCode = null, failureMessage = null, resumable = false,
    )

    private class WorkflowPort : Phase7FakePort() {
        val calls = mutableListOf<String>()
        var runs: List<WorkflowRunSummary>? = emptyList()
        var pages: MutableMap<Long?, WorkflowRunEventsPage?> = mutableMapOf()
        var startOutcome = ConversationPort.StartWorkflowOutcome(ConversationPort.SendOutcome.ACCEPTED, null)

        override suspend fun fetchWorkflowRuns(taskId: String): List<WorkflowRunSummary>? { calls += "runs"; return runs }
        override suspend fun fetchWorkflowRunEvents(taskId: String, runId: String, afterSequence: Long?): WorkflowRunEventsPage? {
            calls += "events:$runId:$afterSequence"; return pages[afterSequence]
        }
        override suspend fun startSavedWorkflow(taskId: String, name: String, scope: String?, args: Map<String, String>): ConversationPort.StartWorkflowOutcome {
            calls += "start:$name:$scope:$args"; return startOutcome
        }
    }

    private fun ev(seq: Long) = WorkflowRunEvent(seq, "node.step", JsonObject(emptyMap()), truncated = false)

    @Test
    fun `refresh loads the runs and the open sheet follows the list`() = runTest {
        val port = WorkflowPort().apply { runs = listOf(run("r1", WorkflowRunStatus.RUNNING)) }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.refreshWorkflowRuns()
        runCurrent()
        assertEquals(listOf("r1"), vm.workflowRuns.value?.map { it.runId })
        port.pages[null] = WorkflowRunEventsPage(listOf(ev(1), ev(2)), hasMore = true)
        vm.openWorkflowRun(run("r1", WorkflowRunStatus.RUNNING))
        runCurrent()
        assertEquals(2, vm.openWorkflowRun.value?.events?.size)
        assertTrue(vm.openWorkflowRun.value?.hasMore == true)
        // the list refresh flips the run to completed → the sheet's run updates too
        port.runs = listOf(run("r1", WorkflowRunStatus.COMPLETED))
        vm.refreshWorkflowRuns()
        runCurrent()
        assertEquals(WorkflowRunStatus.COMPLETED, vm.openWorkflowRun.value?.run?.status)
    }

    @Test
    fun `load more pages forward by the max sequence seen and appends`() = runTest {
        val port = WorkflowPort()
        port.pages[null] = WorkflowRunEventsPage(listOf(ev(1), ev(2)), hasMore = true)
        port.pages[2L] = WorkflowRunEventsPage(listOf(ev(3)), hasMore = false)
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.openWorkflowRun(run("r1", WorkflowRunStatus.RUNNING))
        runCurrent()
        vm.loadMoreRunEvents()
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), vm.openWorkflowRun.value?.events?.map { it.sequence })
        assertFalse(vm.openWorkflowRun.value?.hasMore ?: true)
        assertTrue("events:r1:2" in port.calls)
        vm.loadMoreRunEvents() // nothing more — no extra call
        runCurrent()
        assertEquals(1, port.calls.count { it == "events:r1:2" })
        vm.closeWorkflowRun()
        assertNull(vm.openWorkflowRun.value)
    }

    @Test
    fun `unavailable events read renders the unavailable face`() = runTest {
        val port = WorkflowPort() // pages[null] absent → null page
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.openWorkflowRun(run("r9", WorkflowRunStatus.ERRORED))
        runCurrent()
        assertTrue(vm.openWorkflowRun.value?.unavailable == true)
        assertFalse(vm.openWorkflowRun.value?.loading ?: true)
    }

    @Test
    fun `start success refreshes the runs and notices started`() = runTest {
        val port = WorkflowPort().apply { runs = listOf(run("new", WorkflowRunStatus.PENDING)) }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.startSavedWorkflow("release-notes", "project", mapOf("version" to "1.2.0"))
        runCurrent()
        assertTrue("start:release-notes:project:{version=1.2.0}" in port.calls)
        assertEquals(SendNotice.Key.WORKFLOW_STARTED, vm.state.value.sendNotice?.key)
        assertEquals(listOf("new"), vm.workflowRuns.value?.map { it.runId })
    }

    @Test
    fun `start refusal carries the typed reason code`() = runTest {
        val port = WorkflowPort().apply { startOutcome = ConversationPort.StartWorkflowOutcome(ConversationPort.SendOutcome.ACCEPTED, "session_busy") }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.startSavedWorkflow("lint-all", null, emptyMap())
        runCurrent()
        val notice = vm.state.value.sendNotice
        assertEquals(SendNotice.Key.WORKFLOW_REFUSED, notice?.key)
        assertEquals(listOf("session_busy"), notice?.args)
        assertEquals(SendNotice.Kind.ERROR, notice?.kind)
    }

    @Test
    fun `live runs keep refreshing on the ticker and stop once settled`() = runTest {
        val port = WorkflowPort().apply { runs = listOf(run("r1", WorkflowRunStatus.RUNNING)) }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.refreshWorkflowRuns()
        runCurrent()
        val before = port.calls.count { it == "runs" }
        port.runs = listOf(run("r1", WorkflowRunStatus.COMPLETED))
        advanceTimeBy(8_500)
        runCurrent()
        assertTrue(port.calls.count { it == "runs" } > before, "the ticker re-read the runs")
        val settled = port.calls.count { it == "runs" }
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(settled, port.calls.count { it == "runs" }, "no further ticks once nothing is live")
    }
}
