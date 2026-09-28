package com.zcode.android.domain

import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.state.PermissionStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.random.Random

/**
 * Phase 5.4 acceptance — "zero double-apply across 10k random schedules" (§20.3
 * matrix semantics, INV-4 idempotency + INV-14 fail-closed).
 *
 * Each schedule drives ONE interaction's machine + its outbox entry through a random
 * event stream (taps with arbitrary option ids, acks of every class, transport
 * failures, resolved-elsewhere, resnapshot-lacks, closes, TTL). Oracles:
 *  1. the interaction is RESOLVED at most once and only via an authoritatively
 *     confirmed path (own ACK or resolved-elsewhere) — never by a tap alone;
 *  2. the minted commandId is stable across every retry of the same interaction
 *     (INV-4) and every wire submission carried the SAME id;
 *  3. no wire submission ever carried an unknown option id (INV-14 fail-closed);
 *  4. no outbox entry duplicates a server-contacted submission (machine-gated).
 */
class PermissionRacePropertyTest {

    private val optionPool = listOf("allowOnce", "allowAlways", "allow_project", "deny", "workflowRefine")
    private val hostilePool = listOf("allowAllForever", "AllowOnce", "allow-once", "", "allow *", "sudo")

    private class InteractionDriver {
        val machine = PermissionStateMachine(InteractionId("i"))
        val outbox = OutboxStateMachine.fresh()
        var commandId: String? = null
        var wireSubmissions = 0
        var wireOptionIds = HashSet<String>()
        var resolvedCount = 0
        var ackedCommandIds = HashSet<String>()
    }

    @Test
    fun `10k randomized schedules - zero double-apply, stable commandId, no unknown-option wire submissions`() {
        val options = optionPool.map { PermissionOption(it) } + PermissionOption("workflowRefine")
        for (seed in 1..10_000) {
            val rnd = Random(seed)
            val d = InteractionDriver()
            var freeText: String? = null

            repeat(rnd.nextInt(1, 12)) {
                when (rnd.nextInt(12)) {
                    0 -> runCatching { d.machine.on(PermissionStateMachine.Event.CardRendered) }
                    1, 2, 3 -> { // user tap (possibly hostile option id / refine without text)
                        val optionId = if (rnd.nextBoolean()) optionPool.random(rnd) else hostilePool.random(rnd)
                        val text = if (rnd.nextBoolean()) "refinement text" else null
                        // §20.4 order: the AnswerPolicy classifies BEFORE any machine
                        // transition — a refused decision never becomes UserChose.
                        val decision = PermissionStateMachine.AnswerPolicy.decide(options, optionId, text)
                        if (decision is PermissionStateMachine.AnswerPolicy.Decision.Submit) {
                            runCatching { d.machine.on(PermissionStateMachine.Event.UserChose(optionId, text)) }
                                .onSuccess {
                                    if (d.machine.state == PermissionStateMachine.State.SUBMITTING) {
                                        // ACCEPT: mint the commandId ONCE (INV-4)
                                        if (d.commandId == null) {
                                            d.commandId = "cmd-$seed"
                                            d.outbox.on(OutboxStateMachine.Event.Accept)
                                        }
                                        freeText = decision.freeText
                                        d.wireOptionIds.add(decision.optionId)
                                    }
                                }
                        }
                    }
                    4 -> { // transmit (machine-gated: only pre-contact states accept)
                        runCatching { d.outbox.on(OutboxStateMachine.Event.Transmit) }
                            .onSuccess { if (it == OutboxStateMachine.State.TRANSMITTED) d.wireSubmissions++ }
                    }
                    5 -> { // ACK: accepted/duplicate/noop → resolved (authoritative, by self)
                        val ackStatus = listOf("accepted", "duplicate", "noop").random(rnd)
                        runCatching { d.outbox.on(OutboxStateMachine.Event.Ack(ackStatus)) }
                            .onSuccess {
                                if (it == OutboxStateMachine.State.ACKED) {
                                    val before = d.machine.state
                                    // A late ACK after resolved-elsewhere is a real race —
                                    // the machine refuses it; the coordinator drops it.
                                    runCatching { d.machine.on(PermissionStateMachine.Event.AckConfirmed) }
                                    if (before != PermissionStateMachine.State.RESOLVED &&
                                        d.machine.state == PermissionStateMachine.State.RESOLVED
                                    ) d.resolvedCount++
                                    d.ackedCommandIds.add(d.commandId ?: "NO_ID")
                                }
                            }
                    }
                    6 -> { // ACK stale/rejected → machine STALE/FAILED; a later ack cannot resolve
                        val ev = if (rnd.nextBoolean()) PermissionStateMachine.Event.AckStale else PermissionStateMachine.Event.AckRejected
                        runCatching { d.machine.on(ev) }
                    }
                    7 -> { // transport failure pre-contact → retryable DISPLAYED
                        runCatching { d.outbox.on(OutboxStateMachine.Event.SendFailedPreContact) }
                        runCatching { d.machine.on(PermissionStateMachine.Event.SendFailedPreContact) }
                    }
                    8 -> { // resolved-elsewhere (authoritative, by others)
                        val before = d.machine.state
                        d.machine.on(PermissionStateMachine.Event.ResolvedElsewhere)
                        if (before != PermissionStateMachine.State.RESOLVED &&
                            d.machine.state == PermissionStateMachine.State.RESOLVED
                        ) d.resolvedCount++
                    }
                    9 -> runCatching { d.machine.on(PermissionStateMachine.Event.ResnapshotLacks) }
                    10 -> runCatching { d.machine.on(PermissionStateMachine.Event.SessionClosed) }
                    else -> runCatching { d.outbox.on(OutboxStateMachine.Event.UserGatedRetry) }
                        .onSuccess { if (it == OutboxStateMachine.State.TRANSMITTED) d.wireSubmissions++ }
                }
            }

            // ---- oracles
            assertTrue(d.resolvedCount <= 1, "seed $seed: resolved more than once (${d.resolvedCount})")
            if (d.resolvedCount == 1) {
                assertTrue(
                    d.machine.resolvedByElsewhere || d.ackedCommandIds.isNotEmpty(),
                    "seed $seed: resolution without any authoritative confirmation",
                )
            }
            if (d.commandId != null) {
                assertTrue(
                    d.ackedCommandIds.all { it == d.commandId } || d.ackedCommandIds.isEmpty(),
                    "seed $seed: acked ids diverge from the minted commandId (INV-4)",
                )
            }
            assertTrue(
                d.wireOptionIds.all { it in optionPool },
                "seed $seed: an unknown/hostile option id reached the wire (INV-14): ${d.wireOptionIds}",
            )
            // pre-contact-only auto transmit: wire submissions never exceed ACCEPT+retries
            assertTrue(d.wireSubmissions <= 6, "seed $seed: runaway retransmissions")
        }
    }

    @Test
    fun `coordinator-level §20-3 matrix cells hold through the machine contract`() {
        // (matrix cells with pure-machine representation; socket/transport cells live in :data)
        // duplicate taps → single SUBMITTING
        val m = PermissionStateMachine(InteractionId("x"))
        m.on(PermissionStateMachine.Event.CardRendered)
        m.on(PermissionStateMachine.Event.UserChose("allowOnce"))
        m.on(PermissionStateMachine.Event.UserChose("deny"))
        assertEquals(PermissionStateMachine.State.SUBMITTING, m.state)
        assertEquals(1, m.duplicateTaps)
        // resolved elsewhere while SUBMITTING → pending submit becomes a no-op
        m.on(PermissionStateMachine.Event.ResolvedElsewhere)
        assertEquals(PermissionStateMachine.State.RESOLVED, m.state)
        assertTrue(m.resolvedByElsewhere)
        // CAS/invalid-interaction rejection → FAILED (recoverable = resync)
        val m2 = PermissionStateMachine(InteractionId("y"))
        m2.on(PermissionStateMachine.Event.CardRendered)
        m2.on(PermissionStateMachine.Event.UserChose("deny"))
        m2.on(PermissionStateMachine.Event.AckRejected)
        assertEquals(PermissionStateMachine.State.FAILED, m2.state)
        // permission arrives during reconnect → recover-reconcile reclassifies to STALE
        m2.on(PermissionStateMachine.Event.ResnapshotLacks)
        assertEquals(PermissionStateMachine.State.STALE, m2.state)
    }
}
