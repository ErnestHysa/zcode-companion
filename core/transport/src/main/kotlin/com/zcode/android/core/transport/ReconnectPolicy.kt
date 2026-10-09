package com.zcode.android.core.transport

import kotlin.math.min
import kotlin.random.Random

/**
 * Reconnect/backoff policy — §11.5 (normative numbers): exponential backoff base 1 s,
 * factor 2, cap 60 s, FULL jitter (uniform 0..bound); immediate on app foreground and
 * network-available; attempt budget (2 min of retrying, §15-S1 timeout) → Degraded.
 *
 * Pure and clock-injected so the storm suite runs deterministically.
 */
class ReconnectPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val baseDelayMs: Long = BASE_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val budgetMs: Long = BUDGET_MS,
) {
    private var attempt: Int = 0
    private var budgetStart: Long = -1
    @Volatile private var immediateNext: Boolean = false

    /** Called when a connection is up: reset attempt/budget accounting. */
    fun reset() {
        attempt = 0
        budgetStart = -1
        immediateNext = false
    }

    /** Foreground/manual trigger: the NEXT delay collapses to 0 (§11.5 "immediate"). */
    fun requestImmediate() {
        immediateNext = true
    }

    /**
     * Next delay before attempt [attempt]+1. Returns null when the attempt budget is
     * exhausted (→ Degraded per §14.4.1).
     */
    fun nextDelayMs(): Long? {
        val now = clock()
        if (budgetStart < 0) budgetStart = now
        if (now - budgetStart >= budgetMs) return null
        if (immediateNext) {
            immediateNext = false
            return 0L
        }
        val bound = min(maxDelayMs, baseDelayMs * (1L shl min(attempt, 16)))
        attempt += 1
        return if (bound <= 0) 0L else random.nextLong(bound + 1)
    }

    companion object {
        const val BASE_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 60_000L
        const val BUDGET_MS = 120_000L
    }
}
