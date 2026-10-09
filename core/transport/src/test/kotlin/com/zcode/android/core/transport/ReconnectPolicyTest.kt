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
}
