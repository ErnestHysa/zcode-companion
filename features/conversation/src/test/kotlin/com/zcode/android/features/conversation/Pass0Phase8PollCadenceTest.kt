package com.zcode.android.features.conversation

import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.domain.model.WorkflowRunSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-P1-D (pass-0 Phase 8, observation; receipts Part D.A2 + K.A3): the open
 * conversation polled `conversationWorkflowRunsV4` on an ~8 s cadence — 48 calls in
 * gen1's ~6.5 minutes, continuous while the screen was open, even fully
 * idle/backgrounded. The fix floors the live-run poll ticker at ≥30 s (the roadmap's
 * named cadence); the delta plane already replaces the poll entirely when it flows
 * (G51, pinned in WorkflowRunsVmTest).
 *
 * The pin drives the ticker with virtual time in WINDOWED segments: a live run must
 * produce zero ticks before the 30 s floor and exactly one tick per 30 s window —
 * not the pre-fix 8 s drum (a tick every 8 s).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Pass0Phase8PollCadenceTest {

    private fun liveRun(): WorkflowRunSummary = WorkflowRunSummary(
        runId = "r1", label = "r1", status = WorkflowRunStatus.RUNNING, statusRaw = WorkflowRunStatus.RUNNING.wire,
        stopReason = null, toolCallId = null, updatedAtMs = 1, resumedFrom = null, supersededBy = null,
        failureCode = null, failureMessage = null, resumable = false,
    )

    private class CadencePort(private val liveRun: WorkflowRunSummary) : Phase7FakePort() {
        var runReads = 0
        override suspend fun fetchWorkflowRuns(taskId: String): List<WorkflowRunSummary> {
            runReads++
            return listOf(liveRun)
        }
    }

    @Test
    fun `F-P1-D - the live-run poll drum runs at the 30s floor, not the 8s drum`() = runTest {
        val port = CadencePort(liveRun())
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.refreshWorkflowRuns()
        runCurrent()
        assertEquals(1, port.runReads, "the initial refresh is the only read so far")

        // The debounced revision-follower's initial pass (~400 ms) is a separate,
        // legitimate reader — let it land before pinning the ticker's windows.
        advanceTimeBy(1_000)
        runCurrent()
        val baseline = port.runReads

        // No TICK inside the first ~29 s (the pre-fix drum ticked at 8 s and 16 s and 24 s).
        advanceTimeBy(28_000)
        runCurrent()
        assertEquals(
            baseline, port.runReads,
            "F-P1-D: a poll tick fired before the 30 s floor — the 8 s drum face (reads=${port.runReads})",
        )

        // The first tick lands at the 30 s floor; the run is still live so the drum continues.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(baseline + 1, port.runReads, "exactly one tick at the 30 s floor")

        // The next window: exactly one more tick — no re-acceleration.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(baseline + 2, port.runReads, "exactly one tick per 30 s window")
    }
}
