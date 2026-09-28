package com.zcode.android.domain

import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.recovery.DeepLinkRoute
import com.zcode.android.domain.recovery.NotificationReconciler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * §24.7 deep-link routing safety (the §15-S14 decision core) + §15-S12/§24.3
 * notification reconciliation decisions — pure, Phase 5 level.
 */
class DeepLinkRouteTest {

    private fun paired(
        connected: Boolean = true,
        taskExists: Boolean = true,
        interactionKnown: Boolean? = null,
        otherEndpoint: Boolean = false,
    ): DeepLinkRoute.Environment.Paired =
        DeepLinkRoute.Environment.Paired("ep1", connected, taskExists, interactionKnown, otherEndpoint)

    @Test
    fun `malformed links reject - never crash (24-7)`() {
        assertNull(DeepLinkRoute.parse(null, null))
        assertNull(DeepLinkRoute.parse("", null))
        assertNull(DeepLinkRoute.parse("a/b", null))
        assertNull(DeepLinkRoute.parse("a?b", null))
        assertNull(DeepLinkRoute.parse("a b", null))
        assertNull(DeepLinkRoute.parse("ok", "../evil"))
        assertNull(DeepLinkRoute.parse("x".repeat(513), null))
        assertEquals(DeepLinkRoute.Outcome.MALFORMED, DeepLinkRoute.resolve(null, paired()), "unparseable link never routes")
    }

    @Test
    fun `unpaired device routes to pairing - link revalidated after pairing`() {
        val link = DeepLinkRoute.parse("task-1", null)!!
        assertEquals(DeepLinkRoute.Outcome.UNPAIRED, DeepLinkRoute.resolve(link, DeepLinkRoute.Environment.Unpaired))
    }

    @Test
    fun `task gone routes to the graceful no-longer-available surface (S14, S17)`() {
        val link = DeepLinkRoute.parse("task-1", null)!!
        assertEquals(DeepLinkRoute.Outcome.TASK_GONE, DeepLinkRoute.resolve(link, paired(taskExists = false)))
        // a deep link is a routing hint, NOT an authorization: offline + uncached → gone,
        // never a stale-content render presented as confirmable (§24.7)
        assertEquals(DeepLinkRoute.Outcome.TASK_GONE, DeepLinkRoute.resolve(link, paired(connected = false, taskExists = false)))
    }

    @Test
    fun `wrong endpoint disambiguates (24-7)`() {
        val link = DeepLinkRoute.parse("task-1", null)!!
        assertEquals(
            DeepLinkRoute.Outcome.WRONG_ENDPOINT,
            DeepLinkRoute.resolve(link, paired(taskExists = false, otherEndpoint = true)),
        )
    }

    @Test
    fun `stale interaction opens the task with the resolved-card state (S14, S15 interplay)`() {
        val link = DeepLinkRoute.parse("task-1", "i9")!!
        assertEquals(
            DeepLinkRoute.Outcome.OPEN_TASK_INTERACTION_STALE,
            DeepLinkRoute.resolve(link, paired(interactionKnown = false)),
        )
        assertEquals(DeepLinkRoute.Outcome.OPEN_TASK, DeepLinkRoute.resolve(link, paired(interactionKnown = true)))
        // unknown-yet interaction (not in projection): normal open; card state resolves after load
        assertEquals(DeepLinkRoute.Outcome.OPEN_TASK, DeepLinkRoute.resolve(link, paired(interactionKnown = null)))
    }

    @Test
    fun `happy path opens the task`() {
        assertEquals(DeepLinkRoute.Outcome.OPEN_TASK, DeepLinkRoute.resolve(DeepLinkRoute.parse("t1", null)!!, paired()))
    }
}

class NotificationReconcilerTest {

    private fun record(key: String, kind: NotificationRecord.Kind, taskId: String? = null, interactionId: String? = null) =
        NotificationRecord(notificationKey = key, taskId = taskId, kind = kind, createdAtMs = 0, interactionId = interactionId)

    @Test
    fun `terminal task outcomes collapse their records (S12, 24-3)`() {
        val records = listOf(
            record("k1", NotificationRecord.Kind.TASK_FINISHED, taskId = "t1"),
            record("k2", NotificationRecord.Kind.TASK_FAILED, taskId = "t2"),
            record("k3", NotificationRecord.Kind.TASK_FINISHED, taskId = "t3"),
        )
        assertEquals(
            listOf("k1", "k2"),
            NotificationReconciler.dismissals(records, consumedTaskIds = setOf("t1", "t2"), resolvedInteractionIds = emptySet()),
        )
    }

    @Test
    fun `resolved-elsewhere interactions dismiss approval records (S13, S15)`() {
        val records = listOf(
            record("a1", NotificationRecord.Kind.APPROVAL_NEEDED, interactionId = "i1"),
            record("u1", NotificationRecord.Kind.USER_INPUT_NEEDED, interactionId = "i2"),
            record("a2", NotificationRecord.Kind.APPROVAL_NEEDED, interactionId = "i3"),
        )
        assertEquals(
            listOf("a1", "u1"),
            NotificationReconciler.dismissals(records, consumedTaskIds = emptySet(), resolvedInteractionIds = setOf("i1", "i2")),
        )
    }

    @Test
    fun `connection-lost records are the connection machine's business - never dismissed here`() {
        val records = listOf(record("c1", NotificationRecord.Kind.CONNECTION_LOST))
        assertEquals(emptyList(), NotificationReconciler.dismissals(records, setOf("t"), setOf("i")))
    }
}
