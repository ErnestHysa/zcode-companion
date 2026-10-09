package com.zcode.android.core.protocol.relay

/**
 * Verified protocol budgets.
 *
 * [PROTOCOL_V4_LIMITS] from packages/shared/src/zcode-protocol-v4/core.ts (v3.14.3, commit 29628c9);
 * relay adapter limits from the shipping desktop bundle v3.14.3 (zcode.cjs `QI` object,
 * extracted 2026-09-28). These are hard budgets — the layer refuses payloads beyond them
 * before allocation (INV-5 / §28.7).
 */
object RelayLimits {
    // PROTOCOL_V4_LIMITS (open source, core.ts)
    const val MAX_FRAME_BYTES = 1024 * 1024
    const val LOGICAL_FRAME_ASSEMBLY_MAX_BYTES = 16 * 1024 * 1024
    const val LOGICAL_FRAME_ASSEMBLY_MAX_FRAGMENTS = 1024
    const val LOGICAL_FRAME_ASSEMBLY_MAX_CONCURRENT = 32
    const val LOGICAL_FRAME_ASSEMBLY_MAX_STAGED_BYTES = 32 * 1024 * 1024
    const val LOGICAL_FRAME_ASSEMBLY_TIMEOUT_MS = 30_000
    const val TRANSPORT_ENVELOPE_ID_MAX_CHARS = 256
    const val SUBSCRIBER_BUFFER_MAX_OPS = 500
    const val SUBSCRIBER_BUFFER_MAX_BYTES = 1024 * 1024
    const val EVENT_RETENTION_PER_SESSION = 2000
    const val SNAPSHOT_TAIL_WINDOW_ROWS = 60
    const val ROWS_RANGE_MAX_LIMIT = 200
    const val TOOL_OUTPUT_FINAL_HEAD_BYTES = 32 * 1024
    const val TOOL_OUTPUT_FINAL_TAIL_BYTES = 32 * 1024
    const val PENDING_COMMANDS_DISPLAY_MAX = 32
    const val COMMAND_PENDING_TTL_MS = 24 * 60 * 60 * 1000L
    const val IDEMPOTENCY_TABLE_PER_SESSION = 512
    const val CONVERSATION_QUERY_TIMEOUT_MS = 10_000
    const val ATTACHMENT_MAX_BYTES = 20 * 1024 * 1024
    const val ATTACHMENT_CHUNK_MAX_BYTES = 512 * 1024

    // Relay rpc-frame adapter (closed source, shipping bundle schema)
    const val RELAY_MAX_PHYSICAL_FRAME_BYTES = MAX_FRAME_BYTES
    const val RELAY_MAX_MESSAGE_BYTES = 16 * 1024 * 1024
    const val RELAY_MAX_FRAGMENTS = 64
    const val RELAY_ASSEMBLY_TIMEOUT_MS = 30_000

    // Desktop-observed relay transport cadence (app.asar WebRemoteControlDeviceTransport)
    const val HEARTBEAT_INTERVAL_MS = 10_000L
    const val HEARTBEAT_ACK_WATCHDOG_MS = 30_000L
    const val RECONNECT_DELAY_MS = 1_000L

    const val TRANSPORT_ID_REGEX = "^[A-Za-z0-9._~-]+$"
}
