package com.zcode.android.domain.state

/**
 * UI-facing mirror of the §14.4.1 connection state machine (Phase 6 §18.3
 * "Connection center: expose §14.4.1 states honestly").
 *
 * This type exists so feature modules can render connection truth WITHOUT a
 * dependency on `core:transport` (INV-2/INV-6: UI never touches transport types).
 * The composition root maps the authoritative machine state 1:1 into this enum —
 * no state may be invented, merged, or dropped here; the mapping is total and
 * unit-tested (ConnectionPhaseMapperTest).
 */
enum class ConnectionPhase {
    /** No pairing credential exists — the QR flow is the only path forward. */
    UNPAIRED,
    /** QR validated / credential sealing (§14.4.1 Pairing). */
    PAIRING,
    /** WS + HMAC handshake in flight (§14.4.1 Authenticating). */
    AUTHENTICATING,
    /** Auth matched, bridge/bootstrap in flight (§14.4.1 Connecting). */
    CONNECTING,
    /** hello received — live deltas flowing (§14.4.1 Connected). */
    CONNECTED,
    /** Socket loss, backoff in progress; projection stays stale-labeled (§15-S1). */
    RECONNECTING,
    /** Resync/snapshot replacement in progress after reconnect (§15.1). */
    RECOVERING,
    /** Reconnect budget exhausted or desktop gone — user action required (§15-S5). */
    DEGRADED,
    /** Auth-class failure — re-scan required (§14.4.1 Failed → Pairing). */
    FAILED,
    /** Identity destroyed by unpair/wipe (§14.4.1 Closed; terminal). */
    CLOSED,
}
