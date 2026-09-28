package com.zcode.android.domain

import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.domain.state.PermissionStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §14.4.3 transition-table tests + §20.4 fail-closed answer policy (INV-14) —
 * Phase 5.4's machine-level half. The §20.3 race matrix + 10k-schedule property run
 * in :domain (property) and :data (coordinator-level).
 */
class PermissionStateMachineTest {

    private fun machine() = PermissionStateMachine(InteractionId("i1"))

    // ---------------------------------------------------------------- happy path

    @Test
    fun `requested - displayed - submitting - resolved (14-4-3 rows 1-3)`() {
        val m = machine()
        assertEquals(PermissionStateMachine.State.DISPLAYED, m.on(PermissionStateMachine.Event.CardRendered).to)
        assertEquals(
            PermissionStateMachine.State.SUBMITTING,
            m.on(PermissionStateMachine.Event.UserChose("allowOnce")).to,
        )
        assertEquals(PermissionStateMachine.State.RESOLVED, m.on(PermissionStateMachine.Event.AckConfirmed).to)
        assertFalse(m.resolvedByElsewhere)
    }

    @Test
    fun `send failure before server contact returns to retryable DISPLAYED (row 4)`() {
        val m = machine()
        m.on(PermissionStateMachine.Event.CardRendered)
        m.on(PermissionStateMachine.Event.UserChose("deny"))
        assertEquals(PermissionStateMachine.State.DISPLAYED, m.on(PermissionStateMachine.Event.SendFailedPreContact).to)
        // retry with the SAME choice is legal (commandId preserved by the outbox — INV-4)
        assertEquals(PermissionStateMachine.State.SUBMITTING, m.on(PermissionStateMachine.Event.UserChose("deny")).to)
        assertEquals(PermissionStateMachine.State.RESOLVED, m.on(PermissionStateMachine.Event.AckConfirmed).to)
    }

    // ---------------------------------------------------------------- races

    @Test
    fun `resolved-elsewhere wins regardless of current state (row 3)`() {
        val m = machine()
        m.on(PermissionStateMachine.Event.CardRendered)
        assertEquals(PermissionStateMachine.State.RESOLVED, m.on(PermissionStateMachine.Event.ResolvedElsewhere).to)
        assertTrue(m.resolvedByElsewhere)
        // late duplicate resolution: dropped + counted, no transition
        m.on(PermissionStateMachine.Event.ResolvedElsewhere)
        assertEquals(1, m.duplicateResolutions)
    }

    @Test
    fun `ILLEGAL - resolved cannot submit (double-tap guard row 1)`() {
        val m = machine()
        m.on(PermissionStateMachine.Event.CardRendered)
        m.on(PermissionStateMachine.Event.UserChose("allowOnce"))
        m.on(PermissionStateMachine.Event.AckConfirmed)
        assertFailsWith<PermissionStateMachine.IllegalPermissionTransition> {
            m.on(PermissionStateMachine.Event.UserChose("deny"))
        }
    }

    @Test
    fun `ILLEGAL - stale cannot submit (row 2)`() {
        val m = machine()
        m.on(PermissionStateMachine.Event.CardRendered)
        m.on(PermissionStateMachine.Event.ResnapshotLacks)
        assertEquals(PermissionStateMachine.State.STALE, m.state)
        assertFailsWith<PermissionStateMachine.IllegalPermissionTransition> {
            m.on(PermissionStateMachine.Event.UserChose("allowOnce"))
        }
    }

    @Test
    fun `second tap while submitting is a NO-OP (dedupe)`() {
        val m = machine()
        m.on(PermissionStateMachine.Event.CardRendered)
        m.on(PermissionStateMachine.Event.UserChose("allowOnce"))
        val t = m.on(PermissionStateMachine.Event.UserChose("deny"))
        assertEquals(PermissionStateMachine.State.SUBMITTING, t.to)
        assertEquals(t.from, t.to)
        assertEquals(1, m.duplicateTaps)
    }

    @Test
    fun `resnapshot-lacks marks STALE from any live state and is idempotent on resolved`() {
        val m = machine()
        m.on(PermissionStateMachine.Event.CardRendered)
        m.on(PermissionStateMachine.Event.UserChose("allowOnce"))
        assertEquals(PermissionStateMachine.State.STALE, m.on(PermissionStateMachine.Event.ResnapshotLacks).to)
        // already-terminal interactions are unaffected by a later resnapshot
        val resolved = machine()
        resolved.on(PermissionStateMachine.Event.ResolvedElsewhere)
        assertEquals(PermissionStateMachine.State.RESOLVED, resolved.on(PermissionStateMachine.Event.ResnapshotLacks).to)
    }

    @Test
    fun `session closed cancels, ttl only expires unanswered REQUESTED (rows 7-8)`() {
        val m = machine()
        m.on(PermissionStateMachine.Event.CardRendered)
        assertEquals(PermissionStateMachine.State.CANCELLED, m.on(PermissionStateMachine.Event.SessionClosed).to)

        val m2 = machine()
        m2.on(PermissionStateMachine.Event.TtlExpired)
        assertEquals(PermissionStateMachine.State.EXPIRED, m2.state)
        val m3 = machine()
        m3.on(PermissionStateMachine.Event.CardRendered)
        m3.on(PermissionStateMachine.Event.TtlExpired)
        assertEquals(PermissionStateMachine.State.DISPLAYED, m3.state, "TTL only expires pre-render REQUESTED")
    }

    @Test
    fun `ack classes route to stale and failed (row 8, recoverable=resync)`() {
        val m = machine()
        m.on(PermissionStateMachine.Event.CardRendered)
        m.on(PermissionStateMachine.Event.UserChose("allowOnce"))
        assertEquals(PermissionStateMachine.State.STALE, m.on(PermissionStateMachine.Event.AckStale).to)

        val m2 = machine()
        m2.on(PermissionStateMachine.Event.CardRendered)
        m2.on(PermissionStateMachine.Event.UserChose("allowOnce"))
        assertEquals(PermissionStateMachine.State.FAILED, m2.on(PermissionStateMachine.Event.AckRejected).to)
        // resync reconcile re-classifies FAILED → STALE (submit disabled), never approvable
        assertEquals(PermissionStateMachine.State.STALE, m2.on(PermissionStateMachine.Event.ResnapshotLacks).to)
    }

    // ---------------------------------------------------------------- INV-14 answer policy

    private val options = listOf(
        PermissionOption("allowOnce"), PermissionOption("allowAlways"),
        PermissionOption("allow_project"), PermissionOption("deny"),
        PermissionOption("workflowRefine"),
    )

    @Test
    fun `recognized options map to their exact semantics`() {
        for (id in listOf("allowOnce", "allowAlways", "allow_project", "deny")) {
            val d = PermissionStateMachine.AnswerPolicy.decide(options, id, null)
            assertTrue(d is PermissionStateMachine.AnswerPolicy.Decision.Submit, id)
            assertEquals(null, (d as PermissionStateMachine.AnswerPolicy.Decision.Submit).freeText)
        }
        val refine = PermissionStateMachine.AnswerPolicy.decide(options, "workflowRefine", "refine it this way")
        assertTrue(refine is PermissionStateMachine.AnswerPolicy.Decision.Submit)
        assertEquals("refine it this way", (refine as PermissionStateMachine.AnswerPolicy.Decision.Submit).freeText)
    }

    @Test
    fun `UNKNOWN option id is inert and NEVER allow (20-4 row 2, INV-14)`() {
        val d = PermissionStateMachine.AnswerPolicy.decide(options, "allowAllForever", null)
        assertTrue(d is PermissionStateMachine.AnswerPolicy.Decision.InertUnknown)
        // hostile near-miss spellings stay inert
        for (hostile in listOf("AllowOnce", "allow-once", "allowonce", "allowOnce ", "")) {
            assertTrue(
                PermissionStateMachine.AnswerPolicy.decide(options, hostile, null)
                    is PermissionStateMachine.AnswerPolicy.Decision.InertUnknown,
                "'$hostile' must be inert",
            )
        }
    }

    @Test
    fun `workflowRefine without free text is refused`() {
        val d = PermissionStateMachine.AnswerPolicy.decide(options, "workflowRefine", "  ")
        assertEquals(PermissionStateMachine.AnswerPolicy.Decision.RefineRequiresText, d)
    }

    @Test
    fun `empty option set is NOT approvable - fail closed (20-4 row 6)`() {
        assertEquals(
            PermissionStateMachine.AnswerPolicy.Decision.NotApprovable,
            PermissionStateMachine.AnswerPolicy.decide(emptyList(), "allowOnce", null),
        )
    }
}
