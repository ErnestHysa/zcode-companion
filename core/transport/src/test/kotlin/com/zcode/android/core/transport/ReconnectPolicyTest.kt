package com.zcode.android.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * §11.5 backoff semantics — including the Phase 6 FIELD-FINDING regression: a
 * latched `requestImmediate()` must collapse EXACTLY ONE delay. The hub's failure
 * path used to poke reconnectNow() on EVERY connect failure, re-latching the flag
 * each time → a 0 ms retry storm against the relay (70–250 ms gaps observed on a
 * real phone) → the relay/desktop answered workspace-bridge-error
 * desktop-disconnected in a loop (feedback #328 class).
 */
class ReconnectPolicyTest {

    @Test
    fun `immediate request collapses exactly one delay - the next is exponential`() {
        var now = 0L
        val policy = ReconnectPolicy(clock = { now }, random = kotlin.random.Random(42))
        policy.requestImmediate()
        assertEquals(0L, policy.nextDelayMs()) // the one-shot immediate
        val next = policy.nextDelayMs()
        assertTrue(next in 1L..1_000L, "attempt #1 must be jittered within base (1 s), got $next")
    }

    @Test
    fun `consecutive failures back off exponentially with full jitter`() {
        var now = 0L
        val policy = ReconnectPolicy(clock = { now }, random = kotlin.random.Random(7))
        val delays = (1..5).map { policy.nextDelayMs()!! }
        assertTrue(delays[0] <= 1_000, "attempt 1 bound 1 s: $delays")
        assertTrue(delays[1] <= 2_000, "attempt 2 bound 2 s: $delays")
        assertTrue(delays[2] <= 4_000, "attempt 3 bound 4 s: $delays")
        assertTrue(delays[3] <= 8_000, "attempt 4 bound 8 s: $delays")
        assertTrue(delays[4] <= 16_000, "attempt 5 bound 16 s: $delays")
    }

    @Test
    fun `budget exhaustion returns null - degraded (14-4-1)`() = runTest {
        var now = 0L
        val policy = ReconnectPolicy(clock = { now }, random = kotlin.random.Random(1), budgetMs = 10_000)
        while (true) {
            val d = policy.nextDelayMs()
            if (d == null) break
            now += d.coerceAtLeast(1) // advance virtual clock past each delay
        }
        // Budget exhausted → null (Degraded), reached within the budget window.
        assertTrue(now >= 10_000)
    }

    @Test
    fun `reset clears attempts budget and the immediate latch`() {
        val policy = ReconnectPolicy(clock = { 0L }, random = kotlin.random.Random(3))
        policy.requestImmediate()
        policy.nextDelayMs() // consume the immediate + advance attempt
        policy.reset()
        val d = policy.nextDelayMs()
        assertTrue(d in 0L..1_000L, "post-reset attempt 1 bound is base, got $d")
    }

    // ------------------------------------------------------------- F-34-B (vc52 Phase 8)
    //
    // The budget is WALL-CLOCK from the first loss and burns while the process is frozen
    // in the background (Android 15+ freezes it mid-connect). Pass 34: HOME 14:36:32 →
    // frozen mid-attempt → back 14:41:12 → the foreground signal latched immediateNext,
    // but nextDelayMs() checked the long-expired budget FIRST → DEGRADED at 14:41:13 IN
    // THE FOREGROUND, the loop quiesced, "Disconnected" for 13.5 minutes.

    @Test
    fun `F-34-B - a foreground signal after a 5-minute frozen gap yields an attempt, not Degraded`() {
        var now = 0L
        val policy = ReconnectPolicy(clock = { now }, random = kotlin.random.Random(5))
        kotlin.test.assertNotNull(policy.nextDelayMs()) // attempt 1 — the budget opens at t=0
        now += 5 * 60_000L // the process froze mid-attempt for five minutes
        policy.requestImmediate() // hub.foregroundTrigger on return
        assertEquals(
            0L,
            policy.nextDelayMs(),
            "the thawed foreground attempt was refused by a budget that burned while frozen (→ DEGRADED)",
        )
    }

    @Test
    fun `F-34-B - freshBudget after the freeze grants the FULL budget again`() {
        var now = 0L
        val policy = ReconnectPolicy(clock = { now }, random = kotlin.random.Random(9), budgetMs = 120_000)
        policy.nextDelayMs()
        now += 5 * 60_000L
        policy.freshBudget() // RelayTransport.reconnectNow() from RECONNECTING
        kotlin.test.assertNotNull(policy.nextDelayMs(), "the fresh budget opens at the foreground instant")
        now += 119_000L
        kotlin.test.assertNotNull(policy.nextDelayMs(), "still inside the fresh 120 s window")
        now += 2_000L
        kotlin.test.assertNull(policy.nextDelayMs(), "the fresh budget still bounds automatic retrying (§14.4.1)")
    }

    @Test
    fun `F-34-B guard - with NO foreground signal the frozen budget still degrades (the storm guard holds)`() {
        var now = 0L
        val policy = ReconnectPolicy(clock = { now }, random = kotlin.random.Random(2))
        policy.nextDelayMs()
        now += 5 * 60_000L
        kotlin.test.assertNull(policy.nextDelayMs(), "automatic retrying must not outlive its budget")
    }

    @Test
    fun `F-34-B guard - the immediate latch alone buys exactly ONE attempt past an expired budget`() {
        var now = 0L
        val policy = ReconnectPolicy(clock = { now }, random = kotlin.random.Random(4))
        policy.nextDelayMs()
        now += 5 * 60_000L
        policy.requestImmediate()
        assertEquals(0L, policy.nextDelayMs())
        kotlin.test.assertNull(policy.nextDelayMs(), "no fresh budget was opened — the next automatic try degrades")
    }
}
