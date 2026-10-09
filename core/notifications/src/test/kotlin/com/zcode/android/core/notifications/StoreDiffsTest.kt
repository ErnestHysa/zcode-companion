package com.zcode.android.core.notifications

import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.repo.InteractionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Store-emission diffs → broker events (the wiring's pure decision layer). */
class StoreDiffsTest {

    private fun interaction(id: String, kind: InteractionRepository.InteractionRecord.Kind) =
        InteractionRepository.InteractionRecord(
            interactionId = InteractionId(id),
            sessionId = SessionId("s-1"),
            kind = kind,
            lifecycle = com.zcode.android.domain.model.InteractionLifecycle.REQUESTED,
            toolName = "Bash",
            createdAtMs = 1L,
            resolvedAtMs = null,
        )

    private fun task(id: String, status: TaskStatus) = Task(
        taskId = id,
        title = "Task $id",
        status = status,
        workspace = WorkspaceIdentityKey("/ws"),
        createdAtMs = 1L,
        updatedAtMs = 1L,
    )

    @Test
    fun `new REQUESTED interactions become attention events - PERMISSION to approval`() {
        val fresh = StoreDiffs.newOpenInteractions(
            previousIds = emptySet(),
            current = listOf(interaction("i-1", InteractionRepository.InteractionRecord.Kind.PERMISSION)),
        )
        assertEquals(1, fresh.size)
        assertEquals(NotificationRecord.Kind.APPROVAL_NEEDED, fresh.single().second)
    }

    @Test
    fun `user-input kind maps to USER_INPUT_NEEDED`() {
        val fresh = StoreDiffs.newOpenInteractions(
            previousIds = emptySet(),
            current = listOf(interaction("i-2", InteractionRepository.InteractionRecord.Kind.USER_INPUT)),
        )
        assertEquals(NotificationRecord.Kind.USER_INPUT_NEEDED, fresh.single().second)
    }

    @Test
    fun `already-seen interactions never re-fire (dedupe is the wiring's job)`() {
        val current = listOf(
            interaction("i-1", InteractionRepository.InteractionRecord.Kind.PERMISSION),
            interaction("i-2", InteractionRepository.InteractionRecord.Kind.USER_INPUT),
        )
        val fresh = StoreDiffs.newOpenInteractions(previousIds = setOf("i-1", "i-2"), current = current)
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `task transitions into COMPLETED fire exactly once`() {
        val previous = mapOf("t1" to TaskStatus.RUNNING)
        val fresh = StoreDiffs.taskTerminalTransitions(previous, listOf(task("t1", TaskStatus.COMPLETED)))
        assertEquals(NotificationRecord.Kind.TASK_FINISHED, fresh.single().second)
    }

    @Test
    fun `already-terminal tasks never re-fire on list re-emission`() {
        val previous = mapOf("t1" to TaskStatus.COMPLETED)
        val fresh = StoreDiffs.taskTerminalTransitions(previous, listOf(task("t1", TaskStatus.COMPLETED)))
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `first emission seeds the baseline without notifying (cold start)`() {
        val fresh = StoreDiffs.taskTerminalTransitions(emptyMap(), listOf(task("t1", TaskStatus.COMPLETED)))
        assertTrue(fresh.isEmpty())
    }

    @Test
    fun `ERROR transitions fire TASK_FAILED`() {
        val previous = mapOf("t1" to TaskStatus.RUNNING)
        val fresh = StoreDiffs.taskTerminalTransitions(previous, listOf(task("t1", TaskStatus.ERROR)))
        assertEquals(NotificationRecord.Kind.TASK_FAILED, fresh.single().second)
    }
}
