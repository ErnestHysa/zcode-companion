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
}
