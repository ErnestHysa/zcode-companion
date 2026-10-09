package com.zcode.android.domain

import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §14.4.2 transition-table tests (Phase 5.1) — legal rows, the named-illegal rows,
 * the authoritative-snapshot seeding row, and the stale overlay.
 */
class TaskStateMachineTest {

    @Test
    fun `§14-4-2 table rows - running transitions`() {
        val m = TaskStateMachine()
        m.applySnapshot(TaskStateMachine.State.RUNNING)
        assertEquals(TaskStateMachine.State.WAITING_FOR_PERMISSION, m.on(TaskStateMachine.Event.PermissionRequested).to)
        assertEquals(TaskStateMachine.State.RUNNING, m.on(TaskStateMachine.Event.PermissionResolved).to)
        assertEquals(TaskStateMachine.State.WAITING_FOR_USER, m.on(TaskStateMachine.Event.UserInputRequested).to)
        assertEquals(TaskStateMachine.State.RUNNING, m.on(TaskStateMachine.Event.PermissionResolved).to)
        assertEquals(TaskStateMachine.State.COMPLETED, m.on(TaskStateMachine.Event.TurnCompleted).to)
    }

    @Test
    fun `running + turn failed - stop acked`() {
        val m = TaskStateMachine()
        m.applySnapshot(TaskStateMachine.State.RUNNING)
        assertEquals(TaskStateMachine.State.FAILED, m.on(TaskStateMachine.Event.TurnFailed).to)
        val m2 = TaskStateMachine()
        m2.applySnapshot(TaskStateMachine.State.RUNNING)
        assertEquals(TaskStateMachine.State.CANCELLED, m2.on(TaskStateMachine.Event.StopAcked).to)
    }

    @Test
    fun `snapshot seeds from UNKNOWN per status (table row 1)`() {
        for (status in TaskStatus.entries) {
            val m = TaskStateMachine()
            m.applySnapshot(TaskStateMachine.fromTaskStatus(status))
            assertEquals(TaskStateMachine.fromTaskStatus(status), m.state)
        }
    }

    @Test
    fun `ILLEGAL - completed never resurrects to running, terminal event on terminal state is a metadata update`() {
        val m = TaskStateMachine()
        m.applySnapshot(TaskStateMachine.State.COMPLETED)
        assertFailsWith<TaskStateMachine.IllegalTaskTransition> {
            m.on(TaskStateMachine.Event.PermissionRequested)
        }
        // The protocol emitting turn-completed again on a completed task is a
        // title/revision update — reported, state NOT resurrected (§14.4.2).
        val t = m.on(TaskStateMachine.Event.TurnCompleted)
        assertTrue(t.metadataOnlyUpdate)
        assertEquals(TaskStateMachine.State.COMPLETED, m.state)
    }

    @Test
    fun `ILLEGAL - waiting-for-permission cannot complete without permission-resolved`() {
        val m = TaskStateMachine()
        m.applySnapshot(TaskStateMachine.State.RUNNING)
        m.on(TaskStateMachine.Event.PermissionRequested)
        assertFailsWith<TaskStateMachine.IllegalTaskTransition> {
            m.on(TaskStateMachine.Event.TurnCompleted)
        }
        // But the desktop-forced path is the AUTHORITATIVE snapshot, which is legal:
        m.applySnapshot(TaskStateMachine.State.COMPLETED)
        assertEquals(TaskStateMachine.State.COMPLETED, m.state)
    }

    @Test
    fun `unreachable events from idle-loading throw (no fabrication)`() {
        val m = TaskStateMachine()
        m.applySnapshot(TaskStateMachine.State.IDLE)
        assertFailsWith<TaskStateMachine.IllegalTaskTransition> { m.on(TaskStateMachine.Event.TurnCompleted) }
        assertFailsWith<TaskStateMachine.IllegalTaskTransition> { m.on(TaskStateMachine.Event.StopAcked) }
    }

    @Test
    fun `stale overlay survives under the previous state and clears on recovery (14-4-2 last row)`() {
        val m = TaskStateMachine()
        m.applySnapshot(TaskStateMachine.State.RUNNING)
        m.on(TaskStateMachine.Event.PermissionRequested)
        m.markStale()
        assertTrue(m.stale)
        assertEquals(TaskStateMachine.State.WAITING_FOR_PERMISSION, m.state, "previous state survives under the flag")
        m.markFresh()
        assertFalse(m.stale)
        assertEquals(TaskStateMachine.State.WAITING_FOR_PERMISSION, m.state)
    }
}
