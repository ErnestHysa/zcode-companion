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

    private open class WorkflowPort : Phase7FakePort() {
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
        // F-P1-D: the ticker's floor is now 30 s (was 8 s) — the window crosses it.
        advanceTimeBy(30_500)
        runCurrent()
        assertTrue(port.calls.count { it == "runs" } > before, "the ticker re-read the runs")
        val settled = port.calls.count { it == "runs" }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(settled, port.calls.count { it == "runs" }, "no further ticks once nothing is live")
    }

    // ---------------------------------------------------------------- G51 (vc52 Phase 11): the delta plane

    private class DeltaPort(runs0: List<WorkflowRunSummary>) : WorkflowPort() {
        val plane = kotlinx.coroutines.flow.MutableStateFlow(com.zcode.android.domain.model.WorkflowRunPlane.EMPTY)
        override fun observeWorkflowRunPlane(taskId: String) = plane
        init { runs = runs0 }
        fun updatedDelta(runId: String, status: String, error: String? = null): com.zcode.android.domain.model.WorkflowRunDelta {
            val patch = JsonObject(mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                "status" to kotlinx.serialization.json.JsonPrimitive(status),
            ).apply { error?.let { put("error", kotlinx.serialization.json.JsonPrimitive(it)) } })
            return com.zcode.android.domain.model.WorkflowRunDelta.Updated(
                runId, 1, patch, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), JsonObject(emptyMap()),
            )
        }
    }

    @Test
    fun `a run delta folds into the summaries and marks deltas flowing`() = runTest {
        val port = DeltaPort(listOf(run("r1", WorkflowRunStatus.RUNNING)))
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.refreshWorkflowRuns()
        runCurrent()
        assertFalse(vm.runDeltasFlowing.value, "no deltas yet — the poll plane is the source")
        port.plane.value = com.zcode.android.domain.model.WorkflowRunPlane.apply(
            port.plane.value, port.updatedDelta("r1", "errored", "boom"),
        )
        runCurrent()
        assertTrue(vm.runDeltasFlowing.value, "the deltas-flowing face is up")
        val r1 = vm.workflowRuns.value?.first { it.runId == "r1" }
        assertEquals(WorkflowRunStatus.ERRORED, r1?.status, "the delta patch merged into the run list")
        assertEquals("boom", r1?.failureMessage)
    }

    @Test
    fun `deltas flowing stop the poll ticker and a new live run never starts one`() = runTest {
        val port = DeltaPort(listOf(run("r1", WorkflowRunStatus.RUNNING)))
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.refreshWorkflowRuns()
        runCurrent()
        // Deltas arrive while the ticker could be live: the ticker is cancelled at once.
        port.plane.value = com.zcode.android.domain.model.WorkflowRunPlane.apply(
            port.plane.value, port.updatedDelta("r1", "running"),
        )
        runCurrent()
        kotlin.test.assertTrue(vm.runDeltasFlowing.value, "the plane collector processed the delta before the window")
        // Let the debounced revision-follower's initial pass fire (a distinct source
        // of runs reads — the WINDOW being pinned is the poll ticker's 30 s floor (F-P1-D)).
        advanceTimeBy(1_000)
        runCurrent()
        val before = port.calls.count { it == "runs" }
        advanceTimeBy(25_000)
        runCurrent()
        assertEquals(before, port.calls.count { it == "runs" }, "no poll ticks once deltas flow (the G48 defect's symptom); calls=${port.calls}")
        // A second refresh with a live run STILL does not start a ticker.
        port.runs = listOf(run("r1", WorkflowRunStatus.RUNNING))
        vm.refreshWorkflowRuns()
        runCurrent()
        val afterExplicit = port.calls.count { it == "runs" }
        advanceTimeBy(25_000)
        runCurrent()
        assertEquals(afterExplicit, port.calls.count { it == "runs" }, "the ticker never starts while deltas flow")
    }

    @Test
    fun `a removed delta drops the run from the list`() = runTest {
        val port = DeltaPort(listOf(run("r1", WorkflowRunStatus.RUNNING), run("r2", WorkflowRunStatus.PENDING)))
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.refreshWorkflowRuns()
        runCurrent()
        port.plane.value = com.zcode.android.domain.model.WorkflowRunPlane.apply(
            port.plane.value,
            com.zcode.android.domain.model.WorkflowRunDelta.Removed("r1", 1, JsonObject(emptyMap())),
        )
        runCurrent()
        assertEquals(listOf("r2"), vm.workflowRuns.value?.map { it.runId })
    }

    @Test
    fun `the open run's step graph rides the plane and follows the sheet`() = runTest {
        val port = DeltaPort(listOf(run("r1", WorkflowRunStatus.RUNNING)))
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.openWorkflowRun(run("r1", WorkflowRunStatus.RUNNING))
        runCurrent()
        assertNull(vm.openRunGraph.value, "no graph before any delta")
        val step = com.zcode.android.domain.model.WorkflowRunStep.decode(
            JsonObject(mapOf(
                "siteId" to kotlinx.serialization.json.JsonPrimitive("n1"),
                "ordinal" to kotlinx.serialization.json.JsonPrimitive(0),
                "phase" to kotlinx.serialization.json.JsonPrimitive("settled"),
                "outcome" to kotlinx.serialization.json.JsonPrimitive("ok"),
            )),
        )!!
        port.plane.value = com.zcode.android.domain.model.WorkflowRunPlane.apply(
            port.plane.value,
            com.zcode.android.domain.model.WorkflowRunDelta.Updated(
                "r1", 1, null, emptyList(), emptyList(), emptyList(), emptyList(), listOf(step), JsonObject(emptyMap()),
            ),
        )
        runCurrent()
        val graph = vm.openRunGraph.value
        assertEquals("r1", graph?.runId)
        assertEquals(1, graph?.nodes?.size)
        assertEquals("n1", graph?.nodes?.values?.first()?.siteId)
        vm.closeWorkflowRun()
        assertNull(vm.openRunGraph.value, "closing the sheet clears the graph")
    }

    @Test
    fun `a tail overrun resyncs from the poll plane once instead of silently skipping`() = runTest {
        val port = DeltaPort(listOf(run("r1", WorkflowRunStatus.RUNNING)))
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.refreshWorkflowRuns()
        runCurrent()
        val before = port.calls.count { it == "runs" }
        // Drive the plane past the fold window: the consumer resyncs ONCE.
        var plane = port.plane.value
        for (i in 0 until (com.zcode.android.domain.model.WorkflowRunPlane.RECENT_TAIL + 5)) {
            plane = com.zcode.android.domain.model.WorkflowRunPlane.apply(plane, port.updatedDelta("r$i", "running"))
        }
        port.plane.value = plane
        runCurrent()
        assertTrue(port.calls.count { it == "runs" } > before, "the overrun resync re-read the runs")
        assertTrue(vm.runDeltasFlowing.value)
    }
}
