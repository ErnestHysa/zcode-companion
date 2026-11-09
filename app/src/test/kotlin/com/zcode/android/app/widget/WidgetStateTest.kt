package com.zcode.android.app.widget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.notifications.NotificationBroker
import com.zcode.android.domain.model.NotificationRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * §43.2-11.4 widget gates: the pure record→count derivation, the broker's
 * observable record flow (the widget's ONLY data source), and the localized
 * headline. INV-2/6: no transport, no repository access anywhere in this path.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class WidgetStateTest {

    private fun record(
        key: String,
        kind: NotificationRecord.Kind,
        seen: Boolean,
    ) = NotificationRecord(
        notificationKey = key,
        taskId = "task-1",
        kind = kind,
        createdAtMs = 1_000L,
        seenAtMs = if (seen) 2_000L else null,
    )

    // -------------------------------------------------- pure derivation

    @Test
    fun countsOpenAttentionRecords_seenIrrelevant() {
        // F-33-E (vc52 Phase 7): the widget counts the broker's OPEN attention set —
        // seen-but-open records still need the user (the pass-33 receipt: badge 4,
        // widget "Nothing needs you" because the two live ones had been viewed).
        // Seen is irrelevant; informational outcomes and the connection slot are not.
        val state = WidgetState.from(
            listOf(
                record("i:1", NotificationRecord.Kind.APPROVAL_NEEDED, seen = false),
                record("i:2", NotificationRecord.Kind.USER_INPUT_NEEDED, seen = true),
                record("t:1", NotificationRecord.Kind.TASK_FINISHED, seen = true),
                record("connection-lost", NotificationRecord.Kind.CONNECTION_LOST, seen = false),
            ),
        )
        assertEquals(2, state.count)
    }

    @Test
    fun pass33Fixture_widgetAgreesWithBadgeTwo() {
        // The pass-33 DB truth: 2 DISPLAYED (1 viewed) + a persisted STALE ghost
        // (never dismissed pre-fix). Post-fix the ghost is dismissed by the wiring's
        // seeded closed-diff, so the broker mirror holds exactly the two live ones.
        val state = WidgetState.from(
            listOf(
                record("i:live-1", NotificationRecord.Kind.APPROVAL_NEEDED, seen = false),
                record("i:live-2", NotificationRecord.Kind.USER_INPUT_NEEDED, seen = true),
            ),
        )
        assertEquals("widget = 2, the same number the badge and inbox render", 2, state.count)
    }

    @Test
    fun zeroRecords_headlineIsTheHonestEmptyCopy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Nothing needs you", WidgetState(0).headline(context))
    }

    @Test
    fun countHeadline_usesThePlural() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val one = WidgetState(1).headline(context)
        val two = WidgetState(2).headline(context)
        assertTrue(one.contains("1 request"))
        assertTrue(two.contains("2 requests"))
    }

    // -------------------------------------------------- broker flow (widget source)

    @Test
    fun brokerRecordsFlow_emitsOnEvent_andOnDismiss() = runTest {
        val broker = NotificationBroker(sink = object : NotificationBroker.Sink {
            override fun show(spec: NotificationBroker.Spec) = Unit
            override fun cancel(key: String) = Unit
        })
        broker.load()
        assertEquals(0, broker.recordsFlow.first().size)
        broker.onEvent(NotificationRecord.Kind.APPROVAL_NEEDED, "t1", "i-1", null)
        assertEquals(1, broker.recordsFlow.value.size)
        broker.dismiss(listOf("interaction:i-1"))
        assertEquals(0, broker.recordsFlow.value.size)
    }
}
