package com.zcode.android.app.notifications

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.notifications.NotificationBroker
import com.zcode.android.core.notifications.NotificationChannels
import com.zcode.android.core.notifications.NotificationPlan
import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.model.OffPeakTaskRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G63 (vc52 Phase 13): the reconnect diff over `off-peak-task.list` posts
 * idle-time outcomes through the ONE broker — a seeded completed run posts once
 * on reconnect; a re-listed terminal run never duplicates; the outcomes gate
 * (notifyTaskOutcomes) silences the whole plane; a provider fault degrades to a
 * logged no-op (a viewer-only channel must never crash the notification plane).
 */
class OffPeakOutcomeNotifierTest {

    private class RecordingSink : NotificationBroker.Sink {
        val shown = mutableListOf<NotificationBroker.Spec>()
        override fun show(spec: NotificationBroker.Spec) { shown.add(spec) }
        override fun cancel(key: String) {}
    }

    private fun row(id: String, status: String, title: String = "Idle $id") = OffPeakTaskRow(
        offPeakTaskId = id, title = title, status = status, queuePosition = null,
        sessionId = null, createdAt = 1, workspaceKey = "/ws", failureReason = null,
    )

    /** Programmable provider: one list per reconnect (the real wire's shape). */
    private fun provider(vararg lists: List<OffPeakTaskRow>): suspend () -> List<OffPeakTaskRow> {
        val queue = java.util.ArrayDeque(lists.toList())
        return {
            val next = queue.pollFirst() ?: error("provider exhausted")
            next
        }
    }

    private suspend fun backgroundedBroker(sink: RecordingSink): NotificationBroker =
        NotificationBroker(sink = sink, log = ZLog(sink = {})).apply {
            // §9.9: the broker starts foregrounded; the reconnect moment is the
            // background-relevant one (the phone's tray is the only surface there).
            onBackgroundEntry()
        }

    @Test
    fun `a seeded completed run posts once on reconnect`() = runTest {
        val sink = RecordingSink()
        val notifier = OffPeakOutcomeNotifier(
            broker = backgroundedBroker(sink),
            provider = provider(listOf(row("o1", "queued")), listOf(row("o1", "completed"))),
            outcomesEnabled = { true },
            log = ZLog(sink = {}),
        )
        // Reconnect 1: queued — seeds the baseline, posts nothing.
        notifier.onConnected()
        assertEquals(0, sink.shown.size)
        // Reconnect 2: completed — the transition the desktop would have notified
        // while the phone was away fires exactly once here.
        notifier.onConnected()
        assertEquals(1, sink.shown.size)
        assertEquals(NotificationChannels.TASK_STATUS, sink.shown[0].channelId)
        assertTrue(sink.shown[0].publicText.contains("completed", ignoreCase = true))
    }

    @Test
    fun `the same terminal state never duplicates across reconnects`() = runTest {
        val sink = RecordingSink()
        val state = listOf(row("o1", "completed"))
        val notifier = OffPeakOutcomeNotifier(
            broker = backgroundedBroker(sink),
            provider = { state },
            outcomesEnabled = { true },
            log = ZLog(sink = {}),
        )
        notifier.onConnected()
        notifier.onConnected()
        notifier.onConnected()
        assertEquals(0, sink.shown.size)
    }

    @Test
    fun `failed outcome posts the failed copy`() = runTest {
        val sink = RecordingSink()
        val notifier = OffPeakOutcomeNotifier(
            broker = backgroundedBroker(sink),
            provider = provider(listOf(row("o2", "running")), listOf(row("o2", "failed"))),
            outcomesEnabled = { true },
            log = ZLog(sink = {}),
        )
        notifier.onConnected()
        notifier.onConnected()
        assertEquals(1, sink.shown.size)
        assertTrue(sink.shown[0].publicText.contains("failed", ignoreCase = true))
    }

    @Test
    fun `the outcomes gate silences the plane without losing the baseline`() = runTest {
        val sink = RecordingSink()
        val state = listOf(row("o3", "completed"))
        val notifier = OffPeakOutcomeNotifier(
            broker = backgroundedBroker(sink),
            provider = { state },
            outcomesEnabled = { false },
            log = ZLog(sink = {}),
        )
        notifier.onConnected()
        notifier.onConnected()
        assertEquals(0, sink.shown.size)
    }

    @Test
    fun `a provider fault is a logged no-op (never crashes the plane)`() = runTest {
        val sink = RecordingSink()
        val notifier = OffPeakOutcomeNotifier(
            broker = backgroundedBroker(sink),
            provider = { error("relay refused: fault.mock.unscripted") },
            outcomesEnabled = { true },
            log = ZLog(sink = {}),
        )
        notifier.onConnected()
        assertEquals(0, sink.shown.size)
    }

    @Test
    fun `the dedupe key collapses per task+kind (re-lists replace, never stack)`() = runTest {
        val sink = RecordingSink()
        val notifier = OffPeakOutcomeNotifier(
            broker = backgroundedBroker(sink),
            provider = provider(listOf(row("o4", "running")), listOf(row("o4", "completed", title = "Nightly eval"))),
            outcomesEnabled = { true },
            log = ZLog(sink = {}),
        )
        notifier.onConnected()
        notifier.onConnected()
        assertEquals(1, sink.shown.size)
        assertEquals(
            NotificationPlan.notificationKey(NotificationRecord.Kind.TASK_FINISHED, "o4", null),
            sink.shown[0].key,
        )
    }
}
