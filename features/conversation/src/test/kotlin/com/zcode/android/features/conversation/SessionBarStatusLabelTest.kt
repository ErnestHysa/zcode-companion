package com.zcode.android.features.conversation

import com.zcode.android.domain.state.TaskStateMachine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * N-BF-2 (vc52 Phase 7): the session bar's running label derives from
 * `control.phase` when the LIVE control plane disagrees with the task
 * projection. The pass-36 receipt: the header read "Connected · Completed"
 * while the composer showed the running morph (a control {phase: running,
 * canStop: true} patch without a task-status change) — the two planes
 * disagreed and the header showed the stale one.
 */
class SessionBarStatusLabelTest {

    @Test
    fun `the pass-36 face - a COMPLETED task with a running control phase reads Running`() {
        assertEquals(
            "Running",
            sessionBarStatusLabel(TaskStateMachine.State.COMPLETED, controlPhase = "running"),
        )
    }

    @Test
    fun `a FAILED or STOPPED task with a live running turn reads Running`() {
        assertEquals("Running", sessionBarStatusLabel(TaskStateMachine.State.FAILED, "running"))
        assertEquals("Running", sessionBarStatusLabel(TaskStateMachine.State.CANCELLED, "running"))
        assertEquals("Running", sessionBarStatusLabel(TaskStateMachine.State.IDLE, "running"))
        assertEquals("Running", sessionBarStatusLabel(TaskStateMachine.State.UNKNOWN, "running"))
    }

    @Test
    fun `an already-running or waiting task keeps its finer label`() {
        assertEquals("Running", sessionBarStatusLabel(TaskStateMachine.State.RUNNING, "running"))
        assertEquals("Running", sessionBarStatusLabel(TaskStateMachine.State.LOADING, "running"))
        assertEquals("Waiting", sessionBarStatusLabel(TaskStateMachine.State.WAITING_FOR_PERMISSION, "running"))
        assertEquals("Waiting", sessionBarStatusLabel(TaskStateMachine.State.WAITING_FOR_USER, "running"))
    }

    @Test
    fun `no live running turn - the task label stands unchanged (no completed-header regression)`() {
        assertEquals("Completed", sessionBarStatusLabel(TaskStateMachine.State.COMPLETED, controlPhase = null))
        assertEquals("Completed", sessionBarStatusLabel(TaskStateMachine.State.COMPLETED, controlPhase = "idle"))
        assertEquals("Failed", sessionBarStatusLabel(TaskStateMachine.State.FAILED, controlPhase = null))
        assertEquals("Stopped", sessionBarStatusLabel(TaskStateMachine.State.CANCELLED, controlPhase = "idle"))
        assertEquals("Running", sessionBarStatusLabel(TaskStateMachine.State.RUNNING, controlPhase = "idle"))
        assertEquals(null, sessionBarStatusLabel(TaskStateMachine.State.UNKNOWN, controlPhase = null))
    }
}
