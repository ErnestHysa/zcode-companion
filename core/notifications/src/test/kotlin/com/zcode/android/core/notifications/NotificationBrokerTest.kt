package com.zcode.android.core.notifications

import com.zcode.android.domain.model.NotificationRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §24.3 dedupe/collapse, §9.9 suppression + background re-post (§15-S13), S12
 * reconciliation, and the §16 7-day retention — the broker's state machine over a
 * recording fake sink (pure JVM; the tray-level surface is AndroidNotificationSinkTest).
 */
class NotificationBrokerTest {

    private class RecordingSink : NotificationBroker.Sink {
        val shown = LinkedHashMap<String, NotificationBroker.Spec>()
        val cancelled = LinkedHashSet<String>()
        override fun show(spec: NotificationBroker.Spec) {
            shown[spec.key] = spec
            cancelled.remove(spec.key)
        }

        override fun cancel(key: String) {
            shown.remove(key)
            cancelled.add(key)
        }
    }

    private class MemoryStore : NotificationBroker.RecordStore {
        var saved: List<NotificationRecord> = emptyList()
        override suspend fun load(): List<NotificationRecord> = saved
        override suspend fun save(all: List<NotificationRecord>) {
            saved = all
        }
    }

    private fun broker(
        sink: RecordingSink = RecordingSink(),
        store: MemoryStore = MemoryStore(),
        foreground: Boolean = false,
    ): NotificationBroker = NotificationBroker(sink = sink, records = store).also {
        // The broker starts FOREGROUND (app-process reality: wiring sets it from
        // lifecycle); tests opt into background explicitly.
        kotlinx.coroutines.runBlocking { if (!foreground) it.onBackgroundEntry() }
    }

    @Test
    fun `24-3 - one notification per interaction - updates replace, never stack`() = runTest {
        val sink = RecordingSink()
        val b = broker(sink = sink)
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = "i-1", taskTitle = null)
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = "i-1", taskTitle = null)
        assertEquals(1, sink.shown.size)
        assertEquals(listOf("interaction:i-1"), sink.shown.keys.toList())
    }

    @Test
    fun `24-3 - terminal outcomes collapse per task`() = runTest {
        val sink = RecordingSink()
        val b = broker(sink = sink)
        b.onEvent(NotificationRecord.Kind.TASK_FINISHED, taskId = "t1", interactionId = null, taskTitle = "Write docs")
        b.onEvent(NotificationRecord.Kind.TASK_FINISHED, taskId = "t1", interactionId = null, taskTitle = "Write docs")
        assertEquals(1, sink.shown.size)
    }

    @Test
    fun `9-9 - foreground suppresses delivery but keeps the record`() = runTest {
        val sink = RecordingSink()
        val store = MemoryStore()
        val b = NotificationBroker(sink = sink, records = store)
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, "t1", "i-1", null)
        assertTrue(sink.shown.isEmpty()) // suppressed (foreground)
        assertTrue(b.visible().any { it.notificationKey == "interaction:i-1" })
        // Background entry re-posts the still-open interaction (§15-S13 complement).
        b.onBackgroundEntry()
        assertEquals(1, sink.shown.size)
    }

    @Test
    fun `foreground entry cancels attention notifications (in-app owns them)`() = runTest {
        val sink = RecordingSink()
        val b = broker(sink = sink)
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, "t1", "i-1", null)
        assertEquals(1, sink.shown.size)
        b.onForegroundEntry()
        assertTrue(sink.shown.isEmpty())
        assertTrue("i-1" in sink.cancelled.joinToString().let { sink.cancelled.toString() })
    }

    @Test
    fun `A12 - the background re-post of an approval carries the two shade actions`() = runTest {
        // The recorded defect (pass-9 Y2): the fresh-event path gates Approve-once/Deny on
        // `permission` (NotificationBroker.kt:138-141) but the §15-S13 background re-post
        // built its Spec WITHOUT actions — and the re-post is the ONLY post an approval
        // gets when it arrives while the app is foregrounded (numWithActions never left 0).
        // F-33-H (vc52): shade actions now exist only beside a visible subject, i.e. under
        // the "Task titles" detail level — A12's invariant (re-post == fresh path) is pinned
        // there; the Minimal shape is pinned below (no blind actions on either path).
        val sink = RecordingSink()
        val b = NotificationBroker(sink = sink) // starts FOREGROUND: the fresh post is suppressed
        b.contentLevelProvider = { com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.CONTENT }
        b.subjectProvider = { id -> if (id == "i-1") "Bash: ./gradlew build" else null }
        b.onEvent(
            NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = "i-1", taskTitle = null,
            permission = true, subject = "Bash: ./gradlew build",
        )
        assertTrue(sink.shown.isEmpty())
        b.onBackgroundEntry()
        val repost = sink.shown.getValue("interaction:i-1")
        assertEquals("Bash: ./gradlew build", repost.detailText)
        assertEquals(2, repost.actions.size)
        assertEquals(NotificationActions.ID_APPROVE, repost.actions[0].id)
        assertEquals("allowOnce", repost.actions[0].optionId)
        assertEquals("i-1", repost.actions[0].interactionId)
        assertEquals("t1", repost.actions[0].taskId)
        assertEquals(NotificationActions.ID_DENY, repost.actions[1].id)
        assertEquals("deny", repost.actions[1].optionId)
    }

    @Test
    fun `A12 - a re-posted question carries no actions (the permission gate holds)`() = runTest {
        val sink = RecordingSink()
        val b = NotificationBroker(sink = sink)
        b.onEvent(NotificationRecord.Kind.USER_INPUT_NEEDED, taskId = "t1", interactionId = "i-2", taskTitle = null, permission = false)
        b.onBackgroundEntry()
        val repost = sink.shown.getValue("interaction:i-2")
        assertEquals(emptyList<NotificationBroker.SpecAction>(), repost.actions)
    }

    /**
     * F-33-H (vc52 Phase 6, P3, safety) — threat: a prompt-injected or simply surprising
     * command is approved from the shade by a user who only ever saw "ZCode needs your
     * approval". Under the default "Minimal" detail level the subject may not be shown
     * (that setting promises a category-only lock screen, and Android shows a private
     * version on the lock screen unless the user hides sensitive content), so the approval
     * is answered IN-APP: no shade actions on the fresh post…
     */
    @Test
    fun `F-33-H - under Minimal an approval carries no blind shade actions`() = runTest {
        val sink = RecordingSink()
        val b = broker(sink = sink)
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = "i-1", taskTitle = null, permission = true)
        val spec = sink.shown.getValue("interaction:i-1")
        assertEquals(null, spec.detailText)
        assertEquals("a subject-less approval must not be answerable from the shade", emptyList<NotificationBroker.SpecAction>(), spec.actions)
        assertEquals("i-1", spec.deepLinkInteractionId) // the tap still lands on the in-app card
    }

    /** …nor on the A12 background re-post (one shared rule for both paths). */
    @Test
    fun `F-33-H - under Minimal the background re-post carries no blind shade actions either`() = runTest {
        val sink = RecordingSink()
        val b = NotificationBroker(sink = sink) // foreground: the fresh post is suppressed
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = "i-1", taskTitle = null, permission = true)
        b.onBackgroundEntry()
        assertEquals(emptyList<NotificationBroker.SpecAction>(), sink.shown.getValue("interaction:i-1").actions)
    }

    @Test
    fun `F-33-H - under Task titles the fresh post names the subject, and only then offers Approve and Deny`() = runTest {
        val sink = RecordingSink()
        val b = broker(sink = sink)
        b.contentLevelProvider = { com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.CONTENT }
        b.onEvent(
            NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = "i-1", taskTitle = null,
            permission = true, subject = "Bash: rm -rf build",
        )
        val spec = sink.shown.getValue("interaction:i-1")
        assertEquals("ZCode needs your approval", spec.publicText) // the lock-screen line never changes
        assertEquals("Bash: rm -rf build", spec.detailText)
        assertEquals(listOf(NotificationActions.ID_APPROVE, NotificationActions.ID_DENY), spec.actions.map { it.id })
        // …and a subject-less approval under the same level stays action-less.
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = "i-2", taskTitle = null, permission = true)
        val blind = sink.shown.getValue("interaction:i-2")
        assertEquals(null, blind.detailText)
        assertEquals(emptyList<NotificationBroker.SpecAction>(), blind.actions)
    }

    @Test
    fun `F-33-H - a re-post whose subject is no longer known stays action-less under Task titles`() = runTest {
        val sink = RecordingSink()
        val b = NotificationBroker(sink = sink) // foreground: the fresh post is suppressed
        b.contentLevelProvider = { com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.CONTENT }
        b.subjectProvider = { null } // e.g. a cold process before the interaction store emitted
        b.onEvent(
            NotificationRecord.Kind.APPROVAL_NEEDED, taskId = "t1", interactionId = "i-9", taskTitle = null,
            permission = true, subject = "Bash: echo hi",
        )
        b.onBackgroundEntry()
        val repost = sink.shown.getValue("interaction:i-9")
        assertEquals(null, repost.detailText)
        assertEquals(emptyList<NotificationBroker.SpecAction>(), repost.actions)
    }

    @Test
    fun `S12 reconciliation dismisses consumed records and their notifications`() = runTest {
        val sink = RecordingSink()
        val store = MemoryStore()
        val b = broker(sink = sink, store = store)
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, "t1", "i-1", null)
        b.onEvent(NotificationRecord.Kind.TASK_FINISHED, "t2", null, "Other task")
        val dismissed = b.reconcile(consumedTaskIds = emptySet(), resolvedInteractionIds = setOf("i-1"))
        assertEquals(listOf("interaction:i-1"), dismissed)
        assertTrue(sink.shown.keys.contains("task:t2:TASK_FINISHED"))
        assertTrue(store.saved.none { it.notificationKey == "interaction:i-1" })
    }

    @Test
    fun `records persist through the store and reload after process death`() = runTest {
        val sink = RecordingSink()
        val store = MemoryStore()
        val first = NotificationBroker(sink = sink, records = store)
        first.onBackgroundEntry()
        first.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, "t1", "i-1", null)
        assertTrue(store.saved.isNotEmpty())

        // Fresh broker (process restart), same store: dedupe identity survives.
        val second = NotificationBroker(sink = RecordingSink(), records = store)
        second.load()
        assertTrue(second.visible().any { it.notificationKey == "interaction:i-1" })
    }

    @Test
    fun `16 retention - records older than 7 days are swept`() = runTest {
        val sink = RecordingSink()
        var now = 1_000_000L
        val b = NotificationBroker(sink = sink, records = MemoryStore(), clock = { now })
        kotlinx.coroutines.runBlocking { b.onBackgroundEntry() }
        b.onEvent(NotificationRecord.Kind.TASK_FINISHED, "t1", null, "old")
        now += NotificationBroker.RETENTION_MS + 1
        b.sweepRetention()
        assertTrue(b.visible().isEmpty())
    }

    @Test
    fun `connection lost clears on restore - 24-2 update-in-place`() = runTest {
        val sink = RecordingSink()
        val b = broker(sink = sink)
        b.onEvent(NotificationRecord.Kind.CONNECTION_LOST, null, null, null)
        assertEquals(1, sink.shown.size)
        b.onConnectionRestored()
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun `INV-5 - the record set is bounded (oldest dropped)`() = runTest {
        val b = NotificationBroker(sink = RecordingSink(), records = null)
        kotlinx.coroutines.runBlocking { b.onBackgroundEntry() }
        for (i in 0 until NotificationBroker.MAX_RECORDS + 20) {
            b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, "t$i", "i-$i", null)
        }
        kotlinx.coroutines.runBlocking {
            assertTrue(b.visible().size <= NotificationBroker.MAX_RECORDS)
        }
    }

    @Test
    fun `F-36-B - the background re-post skips an interaction the open set reports closed`() = runTest {
        val sink = RecordingSink()
        val b = broker(sink = sink)
        // The wiring's open-set projection: "closed-1" is terminal, "maybe-open" unknown.
        b.repostGate = { id -> if (id == "closed-1") false else null }
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, "task-1", "closed-1", null, permission = true, subject = "Bash: echo synthetic")
        sink.shown.clear()
        b.onBackgroundEntry()
        assertTrue(
            "a known-closed interaction never re-posts (the pass-36 two-day-old STALE approval)",
            sink.shown.isEmpty(),
        )
        // An UNKNOWN id (gate null) keeps the S15-S13 contract: it re-posts.
        b.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, "task-1", "maybe-open", null, permission = true, subject = "Bash: echo synthetic")
        b.onBackgroundEntry()
        assertTrue("unknown openness never skips", sink.shown.containsKey("interaction:maybe-open"))
    }
}
