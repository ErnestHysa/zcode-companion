package com.zcode.android.app.connect

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.data.session.CommandCoordinator
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.test.assertEquals

/**
 * N-BZ-1 (pass-0 Phase 2; the pass-44 receipts, gen1 + gen2): the hooks viewer's
 * Refresh rendered "The desktop refused the review request." on a DEAD transport
 * — the old mapping folded BlockedOffline/Failed(no-ack)/Stale/… into `false`
 * (the refused note) while the honest offline note was structurally
 * near-unreachable. Receipted BOTH ways on the DB pulls: relay-down → refused
 * (s05, FAILED_TERMINAL "channel closed: abnormal close 1006"); the refuse arm
 * → the SAME refused note, honestly (g2, FAILED_TERMINAL
 * "fault.command.rigRefused:requestWorkspaceHookReview"). The classes are
 * indistinguishable to the user — this mapping is the fix:
 *
 *  - transport-class (BlockedOffline; Failed with NO ack = the outcome is lost)
 *    → NULL → the offline note;
 *  - typed server verdicts (Failed WITH an ack; Stale; MissingBaseRevision)
 *    → FALSE → the refused note;
 *  - Accepted / UnknownKept (transmitted, no ack yet) → TRUE → "requested".
 */
class Pass0Phase2HookRefreshMappingTest {

    private fun ack(status: String, reason: String? = null) =
        CommandAck("cmd-1", status, 0, reason, null, null)

    @Test
    fun `accepted is requested`() {
        assertEquals(
            true,
            mapHookReviewRefresh(CommandCoordinator.Outcome.Accepted(ack("accepted"))),
        )
    }

    @Test
    fun `N-BZ-1 - BlockedOffline is OFFLINE, never refused`() {
        assertNull(
            "the s05 receipt: relay-down rendered the refused note — BlockedOffline must map to the offline note",
            mapHookReviewRefresh(CommandCoordinator.Outcome.BlockedOffline),
        )
    }

    @Test
    fun `N-BZ-1 - a lost outcome (Failed with no ack) is OFFLINE, never refused`() {
        assertNull(
            "the throw path (outcome genuinely unknown) must reach the offline note",
            mapHookReviewRefresh(CommandCoordinator.Outcome.Failed(ack = null)),
        )
    }

    @Test
    fun `the g2 refuse arm stays REFUSED (typed server verdict)`() {
        assertEquals(
            false,
            mapHookReviewRefresh(
                CommandCoordinator.Outcome.Failed(
                    ack("rejected", "fault.command.rigRefused:requestWorkspaceHookReview"),
                ),
            ),
        )
    }

    @Test
    fun `CAS-class verdicts stay refused`() {
        assertEquals(false, mapHookReviewRefresh(CommandCoordinator.Outcome.Stale(ack("stale"))))
        assertEquals(false, mapHookReviewRefresh(CommandCoordinator.Outcome.MissingBaseRevision))
    }

    @Test
    fun `an unknown-kept outcome is requested (the outbox holds the request)`() {
        assertEquals(
            true,
            mapHookReviewRefresh(CommandCoordinator.Outcome.UnknownKept(com.zcode.android.domain.ids.CommandId("cmd-1"))),
        )
    }

    @Test
    fun `no outcome at all stays offline`() {
        assertNull(mapHookReviewRefresh(null))
    }
}
