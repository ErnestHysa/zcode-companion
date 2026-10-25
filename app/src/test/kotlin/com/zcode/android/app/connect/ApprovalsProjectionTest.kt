package com.zcode.android.app.connect

import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.repo.InteractionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * K4 (gaps-and-polish Phase 3B): the inbox projection resolves the task TITLE from the
 * task store by sessionId (the phone's task ids are the session-level ids — the same
 * value the card already navigates with). Unresolved → null (the card falls back to
 * the prefix, then `?`); the preview rides through from the record untouched.
 */
class ApprovalsProjectionTest {

    private fun task(id: String, title: String) = Task(
        taskId = id,
        title = title,
        status = TaskStatus.IDLE,
        workspace = WorkspaceIdentityKey("/ws"),
        createdAtMs = 1L,
        updatedAtMs = 2L,
    )

    private fun record(id: String, session: String?, preview: String? = null) = InteractionRepository.InteractionRecord(
        interactionId = InteractionId(id),
        sessionId = session?.let { SessionId(it) },
        kind = InteractionRepository.InteractionRecord.Kind.PERMISSION,
        lifecycle = InteractionLifecycle.DISPLAYED,
        toolName = "Bash",
        createdAtMs = 10L,
        resolvedAtMs = null,
        preview = preview,
    )

    @Test
    fun `title resolves from the task store by sessionId`() {
        val out = interactionSummaries(
            records = listOf(record("int-1", "sess-A", preview = "ls -la")),
            tasks = listOf(task("sess-A", "Fix the login redirect"), task("sess-B", "Other")),
        )
        val s = out.single()
        assertEquals("Fix the login redirect", s.taskTitle)
        assertEquals("ls -la", s.preview)
        assertEquals("sess-A", s.sessionId)
        assertEquals("Bash", s.toolName)
    }

    @Test
    fun `unknown session or no session leaves the title null - never a guessed name`() {
        val out = interactionSummaries(
            records = listOf(record("int-1", "sess-Z"), record("int-2", null)),
            tasks = listOf(task("sess-A", "A")),
        )
        assertNull(out[0].taskTitle)
        assertNull(out[1].taskTitle)
        assertNull(out[1].sessionId)
    }

    @Test
    fun `a blank stored title counts as unresolved`() {
        val out = interactionSummaries(listOf(record("int-1", "sess-A")), listOf(task("sess-A", "   ")))
        assertNull(out.single().taskTitle)
    }
}
