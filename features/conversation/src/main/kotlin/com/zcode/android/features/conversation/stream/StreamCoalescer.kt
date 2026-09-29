package com.zcode.android.features.conversation.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

/**
 * §19.2 coalescer — the LAST hop of the normative streaming pipeline
 * ("network chunks → protocol events → domain events → coalescer → state store →
 * UI projection; the UI never observes raw network cadence").
 *
 * Emits the FIRST value immediately (tail paint latency) then at most one emission
 * per [periodMs] (default ≈ 30 Hz — the §19.2 frame-budget-aligned rate). Drops
 * intermediate render states, never state ops (the source is a conflate-able
 * snapshot flow; correctness lives in the store, not here).
 *
 * Unit-testable with virtual time (StreamCoalescerTest).
 */
fun <T> Flow<T>.coalesceForUi(periodMs: Long = 33L): Flow<T> = flow {
    conflate().collect { value ->
        emit(value)
        delay(periodMs)
    }
}
