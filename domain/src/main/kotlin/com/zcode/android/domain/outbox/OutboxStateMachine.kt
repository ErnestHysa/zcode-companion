package com.zcode.android.domain.outbox

import com.zcode.android.domain.ids.CommandId

/**
 * Command outbox lifecycle — architectural.md §17.5 (normative lifecycle) + §15.5
 * ("no timeout = assume success": a command without its ACK is UNKNOWN, not
 * confirmed). The state machine is pure domain; persistence is Room (ADR-008/E4:
 * the outbox is a structured durable store — the §17.5/§14.2 "DataStore journal"
 * wording predates the E4 correction and is superseded by ADR-008).
 *
 * INV-4 (hard): permission actions are idempotent and replay-safe — the commandId is
 * minted ONCE at ACCEPT and is STABLE across every retry; and there is NO path that
 * duplicates a server-contacted command: once [State.TRANSMITTED]/[State.UNKNOWN],
 * only the SAFE_AGGRESSIVE class may retransmit automatically; every other class
 * requires an explicit user-gated retry event, and CANCEL is legal only
 * pre-transmit.
 *
 * States (§17.5 + §15.5):
 *   DRAFT → ACCEPTED_LOCALLY → TRANSMITTED → ACKED
 *        ACCEPTED_LOCALLY → FAILED_RETRYABLE      (pre-contact transport failure; auto-retry ok)
 *        TRANSMITTED  → UNKNOWN                   (outcome lost — never "assume success")
 *        TRANSMITTED|UNKNOWN → ACKED | FAILED_TERMINAL | FAILED_STALE
 *        ACCEPTED_LOCALLY|TRANSMITTED|UNKNOWN → EXPIRED   (TTL 24 h, verified server TTL)
 *        DRAFT|ACCEPTED_LOCALLY → CANCELLED       (by user, before transmit only)
 */
class OutboxStateMachine private constructor(private var state: State) {

    enum class State {
        DRAFT,
        ACCEPTED_LOCALLY,
        TRANSMITTED,
        UNKNOWN,
        ACKED,
        FAILED_RETRYABLE,
        FAILED_TERMINAL,
        FAILED_STALE,
        EXPIRED,
        CANCELLED,
    }

    sealed class Event {
        /** Assign the commandId and accept locally (§17.5 "AcceptedLocally (commandId assigned)"). */
        data object Accept : Event()

        /** Wire transmit initiated (any bytes sent). */
        data object Transmit : Event()

        /** Transport-level failure BEFORE any server contact (§17.5 pre-transmit rule). */
        data object SendFailedPreContact : Event()

        /** Transmit outcome lost (socket died mid-flight / ack never matched) — §15.5. */
        data object SendLost : Event()

        /** Idempotent server ACK (§7.4.5 commandResult); fields verbatim for the store. */
        data class Ack(
            val ackStatus: String,
            val reasonCode: String? = null,
            val message: String? = null,
            val revisionAtDecision: Long? = null,
        ) : Event()

        /** User-gated retry after server contact — SAME commandId (INV-4 idempotency). */
        data object UserGatedRetry : Event()

        /**
         * Automatic retransmission of the §28.6 SAFE_AGGRESSIVE family (stop/cancel —
         * "idempotent semantics, verified command family") from UNKNOWN/FAILED_RETRYABLE.
         * Phase 7: the caller MUST consult [CommandRetryClassifier.canAutoRetry] first —
         * the classifier is the policy, this event is the machine-legal transition that
         * keeps the retransmission INSIDE the state machine (INV-4: same commandId,
         * attempts counted, no duplicate mints).
         */
        data object AutoRetransmit : Event()

        /** TTL sweep (§17.5: TTL 24 h verified). */
        data object Expire : Event()

        /** User cancel — pre-transmit only (§17.5). */
        data object Cancel : Event()
    }

    class IllegalOutboxTransition(val from: State, val event: Event, message: String) :
        Exception("ILLEGAL outbox transition: $from --$event--> ($message)")

    val currentState: State get() = state

    /** Server-contacted = a transmit was initiated; retry policy consults this (INV-4). */
    val serverContacted: Boolean
        get() = state == State.TRANSMITTED || state == State.UNKNOWN ||
            state == State.ACKED || state == State.FAILED_TERMINAL ||
            state == State.FAILED_STALE

    fun on(event: Event): State {
        val from = state
        val to = when (event) {
            is Event.Accept -> if (from == State.DRAFT) State.ACCEPTED_LOCALLY
            else throw illegal(from, event, "Accept only from DRAFT")
            is Event.Transmit -> if (from == State.ACCEPTED_LOCALLY || from == State.FAILED_RETRYABLE) State.TRANSMITTED
            else throw illegal(from, event, "Transmit only pre-contact (ACCEPTED_LOCALLY/FAILED_RETRYABLE)")
            is Event.SendFailedPreContact -> if (from == State.ACCEPTED_LOCALLY) State.FAILED_RETRYABLE
            else throw illegal(from, event, "pre-contact failure only from ACCEPTED_LOCALLY")
            is Event.SendLost -> if (from == State.TRANSMITTED) State.UNKNOWN
            else throw illegal(from, event, "SendLost only from TRANSMITTED")
            is Event.Ack -> when (from) {
                State.TRANSMITTED, State.UNKNOWN -> when (event.ackStatus) {
                    "accepted", "duplicate", "noop" -> State.ACKED
                    "stale" -> State.FAILED_STALE
                    else -> State.FAILED_TERMINAL // rejected / failed
                }
                else -> throw illegal(from, event, "Ack only from TRANSMITTED/UNKNOWN")
            }
            is Event.UserGatedRetry -> if (from == State.UNKNOWN || from == State.FAILED_RETRYABLE) State.TRANSMITTED
            else throw illegal(from, event, "user retry only from UNKNOWN/FAILED_RETRYABLE (explicit user action)")
            is Event.AutoRetransmit -> if (from == State.UNKNOWN || from == State.FAILED_RETRYABLE) State.TRANSMITTED
            else throw illegal(from, event, "auto-retransmit only from UNKNOWN/FAILED_RETRYABLE (SAFE_AGGRESSIVE class, §28.6)")
            is Event.Expire -> when (from) {
                State.ACCEPTED_LOCALLY, State.TRANSMITTED, State.UNKNOWN -> State.EXPIRED
                else -> throw illegal(from, event, "Expire only from live states")
            }
            is Event.Cancel -> when (from) {
                State.DRAFT, State.ACCEPTED_LOCALLY -> State.CANCELLED
                else -> throw illegal(from, event, "Cancel only PRE-transmit (§17.5)")
            }
        }
        state = to
        return to
    }

    private fun illegal(from: State, event: Event, why: String) =
        IllegalOutboxTransition(from, event, why)

    companion object {
        fun fresh() = OutboxStateMachine(State.DRAFT)

        /** Restore from a persisted row; unknown persisted states fail typed (§33 storage). */
        fun restore(persisted: State) = OutboxStateMachine(persisted)

        /** §7.4.5 commandAck statuses — dedupe table reference for repositories. */
        val ACKED_STATUSES = setOf("accepted", "duplicate", "noop")
        const val SERVER_TTL_MS: Long = 24 * 60 * 60 * 1000L // verified commandPendingTtlMs
    }
}

/** Domain view of a persisted outbox row (persistence shape is Room's, ADR-008). */
data class OutboxEntry(
    val commandId: CommandId,
    val endpointId: String,
    val sessionId: String?,
    val type: String,
    val payloadJson: String,
    val baseRevision: Long?,
    val baseLogEpoch: String?,
    val issuedAtMs: Long,
    val state: OutboxStateMachine.State,
    val attempts: Int,
    val lastTransitionAtMs: Long,
    /** Last ack decision verbatim (§7.4.5), when one arrived. */
    val ackStatus: String? = null,
    val ackReasonCode: String? = null,
    val ackMessage: String? = null,
    val ackRevisionAtDecision: Long? = null,
)
