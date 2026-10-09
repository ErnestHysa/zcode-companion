package com.zcode.android.domain.journal

import kotlinx.serialization.json.JsonObject

/**
 * Recovery journal model — architectural.md §15.4 (conceptual schema, normative):
 * persisted transactionally at every state transition, consulted on every
 * (re)connect; nothing about recovery depends on UI liveness. Persistence is Room
 * (ADR-008); this is the pure model + the cold-start replay contract.
 */
data class RecoveryJournal(
    val endpointId: String,
    /** Last hello serverTime (clock calibration hint, §7.4.1). */
    val helloServerTimeMs: Double?,
    /** Capability set observed at hello (info-level, non-sensitive, §34). */
    val capabilitiesSeen: JsonObject?,
    /** Live subscription set with per-topic watermarks. */
    val subscriptions: List<Subscription>,
    /** Outbox entries still awaiting authoritative confirmation (refs, §15.4). */
    val pendingOutbox: List<PendingCommandRef>,
    /** Last applied op seq per topic — stale-guard currency (§9.13). */
    val lastAppliedPerTopic: Map<String, Long>,
    /** Schema version (§32.3 additive-first). */
    val version: Int = JOURNAL_VERSION,
) {
    data class Subscription(
        val topic: String,
        val sessionId: String,
        val afterSeq: Long,
        val logEpoch: String,
    )

    data class PendingCommandRef(
        val commandId: String,
        val type: String,
        /** Opaque reference into the outbox store — payload never duplicated here. */
        val payloadRef: String,
        val issuedAtMs: Long,
    )

    companion object {
        const val JOURNAL_VERSION = 1
    }
}
