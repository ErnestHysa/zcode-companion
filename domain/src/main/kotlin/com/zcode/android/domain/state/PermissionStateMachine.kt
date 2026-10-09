package com.zcode.android.domain.state

import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.PermissionOption

/**
 * Permission / user-input interaction card state machine — architectural.md §14.4.3,
 * implemented EXACTLY (the "Approval first" correctness core; §20.3 race matrix is the
 * Phase 5.4 acceptance suite).
 *
 * Illegal transitions (§14.4.3, hard): `Resolved → Submitting` (double-tap is guarded
 * by state + the idempotent commandId); `Stale → Submitting`. A second tap while
 * SUBMITTING is a NO-OP (not a transition). Duplicate/late responses after Resolved
 * are dropped and counted (correlation id kept for QA, §14.4.3).
 *
 * INV-14 (fail-closed, §20.4): an answer reaches the wire ONLY through
 * [AnswerPolicy.decide], which maps RECOGNIZED option ids to their verified semantics
 * and treats an unknown option id as inert — there is NO code path from an unknown
 * option to an allow decision, and a request with NO option set is not approvable.
 */
class PermissionStateMachine(
    val interactionId: InteractionId,
) {

    enum class State { REQUESTED, DISPLAYED, SUBMITTING, RESOLVED, EXPIRED, CANCELLED, STALE, FAILED }

    sealed class Event {
        /** Card rendered (§14.4.3 row 1). */
        data object CardRendered : Event()

        /** User tapped a RECOGNIZED option (§20.4: classification happened BEFORE this event). */
        data class UserChose(val optionId: String, val freeText: String? = null) : Event()

        /** commandResult ACK carrying the authoritative decision (accepted/duplicate/noop classes). */
        data object AckConfirmed : Event()

        /** Command acked `stale` (CAS/invalid-interaction class, §20.3 row 8). */
        data object AckStale : Event()

        /** Command acked rejected/failed — Failed(recoverable=resync), §20.3. */
        data object AckRejected : Event()

        /** Transport-level send failure BEFORE any server contact → retryable Displayed. */
        data object SendFailedPreContact : Event()

        /** `permission.resolved` arrived (possibly first — resolved-elsewhere, §20.3 row 3). */
        data object ResolvedElsewhere : Event()

        /** Recovery resnapshot lacks the interaction (§14.4.3 / §20.3 row 6). */
        data object ResnapshotLacks : Event()

        /** Desktop session closed / task deleted (§20.3 row 7). */
        data object SessionClosed : Event()

        /** TTL passed without resolution (reconnect reconciliation classifies; §14.4.3 row 8). */
        data object TtlExpired : Event()
    }

    class IllegalPermissionTransition(val from: State, val event: Event) :
        Exception("ILLEGAL §14.4.3 transition: $from --$event-->")

    data class Transition(val from: State, val event: Event, val to: State)

    /** True when the RESOLVED arrival was NOT our own submit (drives "Resolved on desktop"). */
    var resolvedByElsewhere: Boolean = false
        private set

    var state: State = State.REQUESTED
        private set

    private val table: Map<State, Map<Event, State>> = buildMap {
        put(State.REQUESTED, mapOf(Event.CardRendered to State.DISPLAYED))
        put(
            State.SUBMITTING,
            mapOf(
                Event.AckConfirmed to State.RESOLVED,
                Event.AckStale to State.STALE,
                Event.AckRejected to State.FAILED,
                Event.SendFailedPreContact to State.DISPLAYED,
            ),
        )
        // FAILED is recoverable-by-resync (§20.3): resync reconcile re-classifies to
        // STALE (submit disabled) — never back to an approvable state.
        put(State.FAILED, mapOf(Event.ResnapshotLacks to State.STALE))
    }

    fun on(event: Event): Transition {
        val from = state
        // Global-first events (§14.4.3 "Any" rows) — checked before the per-state table.
        when (event) {
            is Event.ResolvedElsewhere -> {
                if (from == State.RESOLVED) {
                    duplicateResolutions++
                    return Transition(from, event, from) // late duplicate: drop + count
                }
                resolvedByElsewhere = true
                state = State.RESOLVED
                return Transition(from, event, State.RESOLVED)
            }
            is Event.ResnapshotLacks -> {
                if (from == State.RESOLVED || from == State.CANCELLED || from == State.STALE) {
                    return Transition(from, event, from) // already terminal: idempotent
                }
                state = State.STALE
                return Transition(from, event, State.STALE)
            }
            is Event.SessionClosed -> {
                if (from == State.RESOLVED || from == State.CANCELLED) return Transition(from, event, from)
                state = State.CANCELLED
                return Transition(from, event, State.CANCELLED)
            }
            is Event.TtlExpired -> {
                if (from == State.REQUESTED) {
                    state = State.EXPIRED
                    return Transition(from, event, State.EXPIRED)
                }
                // TTL only expires un-answered REQUESTED cards (§14.4.3 row 8).
                return Transition(from, event, from)
            }
            else -> Unit
        }
        if (event is Event.UserChose) {
            when (from) {
                // Second tap while submitting: NO-OP (state + commandId dedupe, §14.4.3).
                State.SUBMITTING -> { duplicateTaps++; return Transition(from, event, from) }
                // Submit only from DISPLAYED; Resolved/Stale → Submitting are §14.4.3-illegal.
                State.DISPLAYED -> { state = State.SUBMITTING; return Transition(from, event, State.SUBMITTING) }
                else -> throw IllegalPermissionTransition(from, event)
            }
        }
        val target = table[from]?.get(event)
        if (target == null) throw IllegalPermissionTransition(from, event)
        state = target
        return Transition(from, event, target)
    }

    /** Diagnostics counters (duplicate-tap / late-response QA, §14.4.3). */
    var duplicateTaps: Long = 0
        private set
    var duplicateResolutions: Long = 0
        private set

    /** INV-4 observability: a submit may only be issued from DISPLAYED. */
    val canSubmit: Boolean get() = state == State.DISPLAYED

    // ------------------------------------------------------------------ answer policy

    /**
     * INV-14 enforcement point (§20.4): classify a user's option choice into the
     * verified v4 answer vocabulary — or refuse it. An unknown option id is INERT
     * (never actionable, never allow); a request with NO options is NOT approvable
     * ("missing required fields → fail closed"); workflowRefine requires free text.
     */
    object AnswerPolicy {

        sealed class Decision {
            /** Submit `resolveInteraction` with this answer (protocol-valid). */
            data class Submit(val optionId: String, val freeText: String?) : Decision()

            /** Unknown/inert option (§20.4 row 2): card may render it; it is never sent. */
            data class InertUnknown(val optionId: String) : Decision()

            /** Refinement option without the required free text. */
            data object RefineRequiresText : Decision()

            /** No option set / malformed request (§20.4 row 6): fail closed. */
            data object NotApprovable : Decision()
        }

        fun decide(options: List<PermissionOption>, optionId: String, freeText: String?): Decision {
            if (options.isEmpty()) return Decision.NotApprovable
            val option = options.firstOrNull { it.optionId == optionId } ?: return Decision.InertUnknown(optionId)
            return when (option.semantics) {
                PermissionOption.Semantics.ALLOW_ONCE,
                PermissionOption.Semantics.ALLOW_ALWAYS,
                PermissionOption.Semantics.ALLOW_PROJECT,
                PermissionOption.Semantics.DENY,
                -> Decision.Submit(optionId, null)
                PermissionOption.Semantics.REFINE ->
                    if (freeText.isNullOrBlank()) Decision.RefineRequiresText else Decision.Submit(optionId, freeText)
                PermissionOption.Semantics.OTHER -> Decision.InertUnknown(optionId)
            }
        }

        /**
         * U9 elicitation plane (Phase 7): answering a user-input question is NOT an
         * allow/deny decision — the verified `resolveInteraction` answer carries the
         * elicitation choice verbatim (`answer {optionId}`) with NO permission
         * semantics attached (INV-14 concerns ALLOW decisions; this path mints none).
         * Grounding: §7.4.4 U9 protocol basis + the verified userInputRequestPayload
         * options `{optionId, label}` (snapshot.ts). Fail-closed still applies:
         * an optionId outside the request's own option set is inert; with NO options
         * the payload's freeText flag must be honored (a blank answer is not sent).
         */
        fun decideUserInput(options: List<PermissionOption>, optionId: String, freeText: String?): Decision {
            return if (options.isEmpty()) {
                if (freeText.isNullOrBlank()) Decision.NotApprovable else Decision.Submit("", freeText)
            } else {
                if (options.any { it.optionId == optionId }) Decision.Submit(optionId, null)
                else Decision.InertUnknown(optionId)
            }
        }
    }
}
