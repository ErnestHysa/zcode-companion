package com.zcode.android.app.notifications

import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.repo.InteractionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-33-H (vc52 Phase 6): the private notification's subject — the desktop's own
 * "Command: {command}" / "File: {paths}" shape — derived from the open interaction the
 * broker posts. A permission needs its preview line (a bare tool name would still be a
 * blind approve, so it yields no subject and therefore no shade actions).
 */
class InteractionSubjectTest {

    private fun record(
        kind: InteractionRepository.InteractionRecord.Kind,
        toolName: String?,
        preview: String?,
    ) = InteractionRepository.InteractionRecord(
        interactionId = InteractionId("i-1"),
        sessionId = SessionId("t-1"),
        kind = kind,
        lifecycle = InteractionLifecycle.REQUESTED,
        toolName = toolName,
        createdAtMs = 1L,
        resolvedAtMs = null,
        preview = preview,
    )

    private val permission = InteractionRepository.InteractionRecord.Kind.PERMISSION
    private val question = InteractionRepository.InteractionRecord.Kind.USER_INPUT

    @Test
    fun `a permission names its tool and the command or path it acts on`() {
        assertEquals("Bash: echo synthetic", interactionSubject(record(permission, "Bash", "echo synthetic")))
        assertEquals("Edit: src/Main.kt", interactionSubject(record(permission, "Edit", "src/Main.kt")))
        assertEquals("echo synthetic", interactionSubject(record(permission, null, "echo synthetic")))
    }

    @Test
    fun `a permission without its preview line has no subject - a tool name alone is still blind`() {
        assertNull(interactionSubject(record(permission, "Bash", null)))
        assertNull(interactionSubject(record(permission, "Bash", "   ")))
    }

    @Test
    fun `a question is its first question`() {
        assertEquals("Which environment?", interactionSubject(record(question, "AskUserQuestion", "Which environment?")))
        assertNull(interactionSubject(record(question, "AskUserQuestion", null)))
    }
}
