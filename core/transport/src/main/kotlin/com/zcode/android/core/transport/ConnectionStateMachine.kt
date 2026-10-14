package com.zcode.android.core.transport

/**
 * Connection state machine — architectural.md §14.4.1, implemented EXACTLY.
 *
 * Legal transitions are the §14.4.1 table rows (plus the socket-loss-class extensions
 * required by the normative §15 scenarios S1/S4 — connection loss during the
 * Connecting/Authenticating acquisition states routes to Reconnecting, exactly as S1
 * mandates "Response: state → Reconnecting"; these extension rows are called out in
 * the phase gate). The four §14.4.1-illegal transitions throw; every OTHER unlisted
 * transition is logged as an anomaly and REJECTED (state unchanged — §14.4.1 "All
 * other transitions are logged as anomalies").
 *
 * Illegal (hard rejections):
 *   Pairing → Connected (must authenticate) · Degraded → Recovering (must pass
 *   through Reconnecting) · Failed → Connected (must re-pair) · Closed → any
 *   (identity destroyed).
 */
class ConnectionStateMachine(
    private val onAnomaly: ((from: ConnectionState, event: ConnectionEvent) -> Unit)? = null,
) {

    enum class ConnectionState {
        UNPAIRED, PAIRING, AUTHENTICATING, CONNECTING, CONNECTED,
        RECONNECTING, RECOVERING, DEGRADED, FAILED, CLOSED;
    }

    enum class ConnectionEvent {
        QR_VALIDATED,               // Unpaired → Pairing
        CREDENTIAL_SEALED,          // Pairing → Authenticating
        AUTH_MATCHED,               // Authenticating → Connecting
        AUTH_FAILED,                // Authenticating → Failed (4013/4011/AUTH_FAILED)
        HELLO_RECEIVED,             // Connecting → Connected
        SOCKET_LOST,                // Connected → Reconnecting (§14.4.1) — also acquisition states (S1)
        WS_AUTH_OK,                 // Reconnecting → Recovering
        RESYNC_GAP_FREE,            // Recovering → Connected
        RESYNC_SNAPSHOT_REPLACED,   // Recovering → Connected (epoch mismatch / gap unresolvable)
        UNPAIR_OR_WIPE,             // Any → Closed
        RECONNECT_BUDGET_EXHAUSTED, // Reconnecting → Degraded
        RETRY_REQUESTED,            // Degraded → Reconnecting (manual retry / network restored);
                                    // FAILED → Reconnecting (F2: the USER retry from the
                                    // auth-failed state — the automatic loop stays quiesced)
        DESKTOP_DISCONNECTED,       // Connected → Degraded (close 4010 class)
        RESCAN_REQUESTED,           // Failed → Pairing (the "(→ Pairing via re-scan)" path)
    }

    class IllegalTransitionException(val from: ConnectionState, val event: ConnectionEvent, val to: ConnectionState) :
        Exception("ILLEGAL §14.4.1 transition: $from --$event--> $to")

    data class Transition(val from: ConnectionState, val event: ConnectionEvent, val to: ConnectionState)

    @Volatile
    var state: ConnectionState = ConnectionState.UNPAIRED
        private set

    private val table: Map<ConnectionState, Map<ConnectionEvent, ConnectionState>> = buildMap {
        put(ConnectionState.UNPAIRED, mapOf(ConnectionEvent.QR_VALIDATED to ConnectionState.PAIRING))
        put(ConnectionState.PAIRING, mapOf(ConnectionEvent.CREDENTIAL_SEALED to ConnectionState.AUTHENTICATING))
        put(
            ConnectionState.AUTHENTICATING,
            mapOf(
                ConnectionEvent.AUTH_MATCHED to ConnectionState.CONNECTING,
                ConnectionEvent.AUTH_FAILED to ConnectionState.FAILED,
                // §15-S1 extension: socket loss during acquisition → retry machinery
                ConnectionEvent.SOCKET_LOST to ConnectionState.RECONNECTING,
            ),
        )
        put(
            ConnectionState.CONNECTING,
            mapOf(
                ConnectionEvent.HELLO_RECEIVED to ConnectionState.CONNECTED,
                // §15-S1/S4 extension: socket loss during bridge/channel setup
                ConnectionEvent.SOCKET_LOST to ConnectionState.RECONNECTING,
            ),
        )
        put(
            ConnectionState.CONNECTED,
            mapOf(
                ConnectionEvent.SOCKET_LOST to ConnectionState.RECONNECTING,
                ConnectionEvent.DESKTOP_DISCONNECTED to ConnectionState.DEGRADED,
            ),
        )
        put(
            ConnectionState.RECONNECTING,
            mapOf(
                ConnectionEvent.WS_AUTH_OK to ConnectionState.RECOVERING,
                ConnectionEvent.RECONNECT_BUDGET_EXHAUSTED to ConnectionState.DEGRADED,
                // DIRECT surface (F2 user-retry arm): its auth completes at the
                // server-info token gate, so the reconnect handshake's TERMINAL
                // event is hello (the relay exits via WS_AUTH_OK→RESYNC instead).
                ConnectionEvent.HELLO_RECEIVED to ConnectionState.CONNECTED,
            ),
        )
        put(
            ConnectionState.RECOVERING,
            mapOf(
                ConnectionEvent.RESYNC_GAP_FREE to ConnectionState.CONNECTED,
                ConnectionEvent.RESYNC_SNAPSHOT_REPLACED to ConnectionState.CONNECTED,
                // §15-S4 extension: connection loss DURING recovery → retry machinery
                ConnectionEvent.SOCKET_LOST to ConnectionState.RECONNECTING,
            ),
        )
        // Degraded
        put(
            ConnectionState.DEGRADED,
            mapOf(ConnectionEvent.RETRY_REQUESTED to ConnectionState.RECONNECTING),
        )
        // Failed: RESCAN = re-pair; RETRY (F2) = the user's explicit retry opens a
        // fresh budget and re-runs the handshake (a transient auth fault must not
        // brick the session — the AUTOMATIC loop still never retries FAILED).
        put(
            ConnectionState.FAILED,
            mapOf(
                ConnectionEvent.RESCAN_REQUESTED to ConnectionState.PAIRING,
                ConnectionEvent.RETRY_REQUESTED to ConnectionState.RECONNECTING,
            ),
        )
        // CLOSED: absorbing — no exits (identity destroyed)
    }

    /** Apply an event; returns the transition or throws for the named-illegal set. */
    @Synchronized
    fun on(event: ConnectionEvent): Transition {
        val from = state
        if (from == ConnectionState.CLOSED) {
            if (event == ConnectionEvent.UNPAIR_OR_WIPE) {
                return Transition(from, event, ConnectionState.CLOSED) // idempotent wipe
            }
            onAnomaly?.invoke(from, event)
            throw IllegalTransitionException(from, event, from)
        }
        if (event == ConnectionEvent.UNPAIR_OR_WIPE) {
            return apply(from, event, ConnectionState.CLOSED)
        }
        val target = table[from]?.get(event)
        if (target == null) {
            // Unlisted: anomaly-logged, REJECTED (state unchanged).
            onAnomaly?.invoke(from, event)
            throw IllegalTransitionException(from, event, from)
        }
        return apply(from, event, target)
    }

    private fun apply(from: ConnectionState, event: ConnectionEvent, to: ConnectionState): Transition {
        state = to
        return Transition(from, event, to)
    }

    /** Seed for restore-after-process-death / Phase-3 pairing entry (not a transition). */
    @Synchronized
    fun seed(initial: ConnectionState) {
        this.state = initial
    }
}
