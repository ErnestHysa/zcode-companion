package com.zcode.android.core.notifications

import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.model.OffPeakTaskRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * G63 (vc52 Phase 13): idle-time (off-peak) run outcomes never notify — the
 * desktop notifies both outcomes ("Idle-time task finished / failed"), the
 * phone's task-terminal diff only sees the MAIN task projection and never the
 * `off-peak-task.list` channel. The diff (pure, StoreDiffs):
 *
 *  - first emission seeds the baseline (a cold process must not spam terminal
 *    states that were terminal yesterday — the taskTerminalTransitions rule);
 *  - a transition INTO completed/failed (vs a non-terminal baseline) → exactly
 *    one TASK_FINISHED / TASK_FAILED (the desktop's two idle-time outcomes);
 *  - the same terminal state again → no duplicate (reconnect re-lists);
 *  - `cancelled` is NOT an outcome the desktop notifies — no event.
 */
class StoreDiffsOffPeakTest {

    private fun row(
        id: String,
        status: String,
        title: String = "Idle $id",
    ) = OffPeakTaskRow(
        offPeakTaskId = id, title = title, status = status, queuePosition = null,
        sessionId = null, createdAt = 1, workspaceKey = "/ws", failureReason = null,
    )

    @Test
    fun `first emission seeds - a seeded completed run never notifies`() {
        val events = StoreDiffs.offPeakTerminalTransitions(emptyMap(), listOf(row("o1", "completed")))
        assertEquals(0, events.size)
    }

    @Test
    fun `queued to completed on reconnect posts TASK_FINISHED exactly once`() {
        val previous = mapOf("o1" to "queued")
        val events = StoreDiffs.offPeakTerminalTransitions(previous, listOf(row("o1", "completed")))
        assertEquals(1, events.size)
        assertEquals("o1", events[0].first.offPeakTaskId)
        assertEquals(NotificationRecord.Kind.TASK_FINISHED, events[0].second)
    }

    @Test
    fun `running to failed posts TASK_FAILED`() {
        val previous = mapOf("o1" to "running")
        val events = StoreDiffs.offPeakTerminalTransitions(previous, listOf(row("o1", "failed")))
        assertEquals(1, events.size)
        assertEquals(NotificationRecord.Kind.TASK_FAILED, events[0].second)
    }

    @Test
    fun `the same completed state again never duplicates`() {
        val previous = mapOf("o1" to "completed")
        val events = StoreDiffs.offPeakTerminalTransitions(previous, listOf(row("o1", "completed")))
        assertEquals(0, events.size)
    }

    @Test
    fun `completed to completed across two reconnects posts once total`() {
        var previous = mapOf("o1" to "queued")
        val first = StoreDiffs.offPeakTerminalTransitions(previous, listOf(row("o1", "completed")))
        assertEquals(1, first.size)
        previous = mapOf("o1" to "completed")
        val second = StoreDiffs.offPeakTerminalTransitions(previous, listOf(row("o1", "completed")))
        assertEquals(0, second.size)
    }

    @Test
    fun `cancelled is not a notified outcome`() {
        val events = StoreDiffs.offPeakTerminalTransitions(mapOf("o1" to "running"), listOf(row("o1", "cancelled")))
        assertEquals(0, events.size)
    }

    @Test
    fun `paused stays quiet and later completion still fires`() {
        val paused = StoreDiffs.offPeakTerminalTransitions(mapOf("o1" to "queued"), listOf(row("o1", "paused")))
        assertEquals(0, paused.size)
        val done = StoreDiffs.offPeakTerminalTransitions(mapOf("o1" to "paused"), listOf(row("o1", "completed")))
        assertEquals(1, done.size)
        assertEquals(NotificationRecord.Kind.TASK_FINISHED, done[0].second)
    }

    @Test
    fun `mixed batch - each row resolves independently`() {
        val previous = mapOf("a" to "queued", "b" to "running", "c" to "completed")
        val current = listOf(row("a", "completed"), row("b", "failed"), row("c", "completed"), row("d", "queued"))
        val events = StoreDiffs.offPeakTerminalTransitions(previous, current)
        assertEquals(2, events.size)
        assertEquals("a" to NotificationRecord.Kind.TASK_FINISHED, events[0].first.offPeakTaskId to events[0].second)
        assertEquals("b" to NotificationRecord.Kind.TASK_FAILED, events[1].first.offPeakTaskId to events[1].second)
    }

    @Test
    fun `unknown raw status stays quiet (INV-8 tolerant)`() {
        val events = StoreDiffs.offPeakTerminalTransitions(mapOf("o1" to "queued"), listOf(row("o1", "weird-status")))
        assertEquals(0, events.size)
    }

    @Test
    fun `the notification key collapses per task+kind (no stacking)`() {
        // Same key for a re-listed completed run → the broker's dedupe replaces, never stacks.
        val k1 = NotificationPlan.notificationKey(NotificationRecord.Kind.TASK_FINISHED, "o1", null)
        val k2 = NotificationPlan.notificationKey(NotificationRecord.Kind.TASK_FINISHED, "o1", null)
        assertEquals(k1, k2)
        assertNull(NotificationPlan.notificationKey(NotificationRecord.Kind.TASK_FINISHED, "", null))
    }
}
