package com.zcode.android.domain.state

/**
 * Task projection state machine — architectural.md §14.4.2, implemented EXACTLY.
 *
 * Two entry classes are distinguished on purpose:
 *  - [on] applies EVENT-driven transitions and enforces the §14.4.2 table including
 *    the two named-illegal transitions (`Completed → Running` never resurrects;
 *    `WaitingForPermission → Completed` without resolution is refused).
 *  - [applySnapshot] is the AUTHORITATIVE path (desktop wins, INV-1 / §14.3 "runtime
 *    wins; Android overlay discarded"): a snapshot/list arrival seeds state per the
 *    `Unknown → any per status` row from ANY state — it never fabricates, it replaces.
 *
 * The Disconnected overlay is the `stale` FLAG (§14.4.2 last row), never a machine
 * state — the previous state survives under the flag.
 */
class TaskStateMachine {

    enum class State {
        UNKNOWN, LOADING, IDLE, RUNNING, WAITING_FOR_PERMISSION, WAITING_FOR_USER,
        COMPLETED, FAILED, CANCELLED,
    }

    sealed class Event {
        /** `permission.requested`. */
        data object PermissionRequested : Event()
        /** `userInput.requested`. */
        data object UserInputRequested : Event()
        /** `permission.resolved`. */
        data object PermissionResolved : Event()
        /** turnHeader state → terminal success (completedSuccess/completedInterrupted). */
        data object TurnCompleted : Event()
        /** turnHeader state → failed. */
        data object TurnFailed : Event()
        /** stop command ACKed by the runtime (runtime-confirmed cancellation). */
        data object StopAcked : Event()

        /** Authoritative snapshot/list arrival (applySnapshot record marker; desktop wins, INV-1). */
        data object AuthoritativeSnapshot : Event()
    }

    class IllegalTaskTransition(val from: State, val event: Event) :
        Exception("ILLEGAL §14.4.2 transition: $from --$event-->")

    data class Transition(val from: State, val event: Event, val to: State, val metadataOnlyUpdate: Boolean = false)

    var state: State = State.UNKNOWN
        private set

    /** §14.4.2 overlay flag: connection Degraded / socket lost labels the projection stale. */
    var stale: Boolean = false
        private set

    private val table: Map<State, Map<Event, State>> = buildMap {
        put(
            State.RUNNING,
            mapOf(
                Event.PermissionRequested to State.WAITING_FOR_PERMISSION,
                Event.UserInputRequested to State.WAITING_FOR_USER,
                Event.TurnCompleted to State.COMPLETED,
                Event.TurnFailed to State.FAILED,
                Event.StopAcked to State.CANCELLED,
            ),
        )
        put(State.WAITING_FOR_PERMISSION, mapOf(Event.PermissionResolved to State.RUNNING))
        put(State.WAITING_FOR_USER, mapOf(Event.PermissionResolved to State.RUNNING))
    }

    /**
     * Apply an event-driven transition. Terminal states accept only the §14.4.2-legal
     * follow-ups; `Completed → Running`-class protocol emissions arrive as metadata
     * updates (title/revision) and are reported as [Transition.metadataOnlyUpdate]
     * WITHOUT resurrecting the machine (the normative "never resurrect locally").
     */
    fun on(event: Event): Transition {
        val from = state
        val target = table[from]?.get(event)
        if (target != null) {
            state = target
            return Transition(from, event, target)
        }
        // Terminal-state tolerance row: a terminal event on an already-terminal state
        // is a metadata/revision update, never a resurrection.
        if ((from == State.COMPLETED && event == Event.TurnCompleted) ||
            (from == State.FAILED && event == Event.TurnFailed) ||
            (from == State.CANCELLED && event == Event.StopAcked)
        ) {
            return Transition(from, event, from, metadataOnlyUpdate = true)
        }
        throw IllegalTaskTransition(from, event)
    }

    /**
     * Authoritative snapshot/list arrival (§14.4.2 row 1 generalized to desktop-wins):
     * seeds state per status from ANY current state (INV-1). UNKNOWN wire statuses map
     * to the tolerant model BEFORE this call (INV-8 lives in the model layer).
     */
    fun applySnapshot(status: State): Transition {
        val from = state
        state = status
        return Transition(from, Event.AuthoritativeSnapshot, status, metadataOnlyUpdate = true)
    }

    /** §14.4.2 "Any → connection Degraded → previous state + stale flag". */
    fun markStale() {
        stale = true
    }

    /** Recovery applied (gap-free or snapshot replace) → the stale overlay clears. */
    fun markFresh() {
        stale = false
    }

    companion object {
        /** Wire status → machine state (model TaskStatus is the tolerant decode, INV-8). */
        fun fromTaskStatus(status: com.zcode.android.domain.model.TaskStatus): State = when (status) {
            com.zcode.android.domain.model.TaskStatus.IDLE -> State.IDLE
            com.zcode.android.domain.model.TaskStatus.RUNNING -> State.RUNNING
            com.zcode.android.domain.model.TaskStatus.WAITING -> State.WAITING_FOR_PERMISSION
            // §14.4.2 has no paused state; the verified `paused` wire status is an
            // idle resumable task at this machine's granularity (documented mapping).
            com.zcode.android.domain.model.TaskStatus.PAUSED -> State.IDLE
            com.zcode.android.domain.model.TaskStatus.COMPLETED -> State.COMPLETED
            com.zcode.android.domain.model.TaskStatus.ERROR -> State.FAILED
            com.zcode.android.domain.model.TaskStatus.UNKNOWN -> State.UNKNOWN
        }
    }
}
