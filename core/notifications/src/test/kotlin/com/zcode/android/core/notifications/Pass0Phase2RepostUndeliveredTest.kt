package com.zcode.android.core.notifications

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.domain.model.NotificationRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-P7-B (pass-0 Phase 2; the pass-13 P.A5 receipt): a shade answer that did NOT
 * reach the desktop left NO user face — "the card simply clears; the inbox
 * renders the empty face; no 'answer not delivered' notice, no retry". The
 * receiver's tap had cancelled the system notification while the answer stayed
 * undelivered (offline / outcome lost).
 *
 * [NotificationBroker.repostInteractionNow] re-shows the interaction's unseen
 * attention record — the visible not-delivered/retry face, replicating the
 * background re-post's shape (subject via the provider, the approval's shade
 * actions, the fuse) and gated by the same F-36-B open-set rule. No unseen
 * record → no-op. A closed interaction (repostGate=false) → no-op (the pass-36
 * rule: a terminal row must never resurface).
 */
class Pass0Phase2RepostUndeliveredTest {

    private class RecordingSink : NotificationBroker.Sink {
        val shown = mutableListOf<NotificationBroker.Spec>()
        override fun show(spec: NotificationBroker.Spec) {
            shown.add(spec)
        }
        override fun cancel(key: String) = Unit
    }

    private class MemoryStore : NotificationBroker.RecordStore {
        val saved = mutableListOf<NotificationRecord>()
        override suspend fun load(): List<NotificationRecord> = saved
        override suspend fun save(all: List<NotificationRecord>) {
            saved.clear()
            saved.addAll(all)
        }
    }

    private fun broker(
        sink: RecordingSink,
        gate: (String) -> Boolean? = { true },
        subject: suspend (String) -> String? = { "Bash: echo synthetic" },
    ): NotificationBroker = NotificationBroker(
        sink = sink,
        records = MemoryStore(),
        log = ZLog(sink = {}),
    ).also {
        it.repostGate = { id -> gate(id) }
        it.subjectProvider = subject
        // The Part-J recorded prerequisite: shade actions exist only under
        // Notification detail: Task titles (CONTENT) — MINIMAL drops the subject
        // and the F-33-H gate keeps actions beside the subject they answer.
        it.contentLevelProvider = {
            com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.CONTENT
        }
    }

    private suspend fun NotificationBroker.seedApproval(interactionId: String) {
        // The fresh-path event (foreground default → the record lands unseen,
        // the §9.9 suppression keeps the shade quiet until the re-post).
        onEvent(
            kind = NotificationRecord.Kind.APPROVAL_NEEDED,
            taskId = "task-1",
            interactionId = interactionId,
            taskTitle = "Synthetic task",
            permission = true,
            subject = "Bash: echo synthetic",
        )
    }

    @Test
    fun `F-P7-B - an undelivered shade answer re-posts the shade face with the retry actions`() = runTest {
        val sink = RecordingSink()
        val broker = broker(sink)
        broker.seedApproval("i-undelivered")

        val reposted = broker.repostInteractionNow("i-undelivered")

        assertTrue("the undelivered answer must re-post the shade face", reposted)
        assertEquals(1, sink.shown.size)
        val spec = sink.shown.single()
        assertEquals("the re-post rides the SAME key (it replaces the cancelled one)", "interaction:i-undelivered", spec.key)
        assertTrue(
            "the re-post carries the Approve/Deny retry actions",
            spec.actions.size == 2,
        )
        assertEquals("allowOnce", spec.actions[0].optionId)
        assertEquals("deny", spec.actions[1].optionId)
        assertTrue(
            "the not-delivered face names the failure",
            spec.publicText.contains("not delivered", ignoreCase = true),
        )
    }

    @Test
    fun `F-P7-B - a closed interaction never resurfaces (the F-36-B rule)`() = runTest {
        val sink = RecordingSink()
        val broker = broker(sink, gate = { false })
        broker.seedApproval("i-closed")

        val reposted = broker.repostInteractionNow("i-closed")

        assertFalse("a terminal row must never resurface", reposted)
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun `F-P7-B - no unseen record → no-op`() = runTest {
        val sink = RecordingSink()
        val broker = broker(sink)

        val reposted = broker.repostInteractionNow("i-unknown")

        assertFalse(reposted)
        assertTrue(sink.shown.isEmpty())
    }
}
