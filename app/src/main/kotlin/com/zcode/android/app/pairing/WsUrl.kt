package com.zcode.android.app.pairing

/**
 * QR/page origin → relay WS endpoint (§6.2 scheme mapping, §11.3 endpoint model).
 * Shared by the pairing port (first connect) and the Phase 6 connection hub
 * (cold-start reconnect) — one mapping, never two.
 */
fun wsUrlForOrigin(origin: String): String = when {
    origin.startsWith("https://") -> origin.replaceFirst("https://", "wss://") + "/ws"
    origin.startsWith("http://") -> origin.replaceFirst("http://", "ws://") + "/ws"
    else -> throw IllegalArgumentException("unsupported origin scheme")
}
