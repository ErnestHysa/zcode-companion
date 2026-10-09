package com.zcode.android.core.protocol.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Relay wire models. Two families live on one WebSocket:
 *
 * 1. Plain JSON control frames (auth/pairing/workspace bootstrap) — {type:"…", …}.
 * 2. `data` envelopes carrying the rpc-frame transport payload (binary Channel frames
 *    base64'd inside, per the bundle zod schemas extracted 2026-09-28 from the shipping
 *    desktop bundle v3.14.3).
 *
 * Outbound shapes must satisfy the desktop's `.strict()` zod parsers (no extra fields);
 * inbound decoding is tolerant of unknown fields and unknown `type` values (INV-8).
 */

// ---------------------------------------------------------------- control frames

@Serializable
data class AuthInit(
    val type: String = "auth_init",
    val role: String,
    @SerialName("device_sid") val deviceSid: String,
    val meta: AuthMeta,
    @SerialName("client_ts") val clientTs: Long,
)

@Serializable
data class AuthMeta(
    val platform: String? = null,
    val version: String? = null,
    val name: String? = null,
)

@Serializable
data class AuthChallenge(
    val type: String,
    val nonce: String,
)

@Serializable
data class AuthResponse(
    val type: String = "auth_response",
    @SerialName("device_sid") val deviceSid: String,
    val proof: String,
    @SerialName("client_ts") val clientTs: Long,
)

@Serializable
data class PairStatusAck(
    val type: String,
    @SerialName("pair_status") val pairStatus: String,
)

/** Desktop heartbeat frame; terminal role mirrors it (bundle cadence: 10 s / 30 s watchdog). */
@Serializable
data class PairStatusQuery(
    val type: String = "pair_status_query",
    @SerialName("device_sid") val deviceSid: String,
    @SerialName("client_ts") val clientTs: Long,
)

@Serializable
data class DeviceRegisterInit(
    val type: String = "device_register_init",
    @SerialName("device_mid") val deviceMid: String,
    @SerialName("pass_hash") val passHash: String,
    val meta: AuthMeta,
    @SerialName("client_ts") val clientTs: Long,
)

@Serializable
data class DeviceRegisterAck(
    val type: String,
    @SerialName("device_sid") val deviceSid: String,
)

@Serializable
data class RelayErrorFrame(
    val type: String,
    val code: String,
    val message: String? = null,
)

// ---------------------------------------------------------------- workspace bootstrap (Q9)

@Serializable
data class BootstrapRequest(
    @SerialName("zcode_type") val zcodeType: String = "bootstrap-request",
    val requestId: String,
)

@Serializable
data class WorkspaceListRequest(
    @SerialName("zcode_type") val zcodeType: String = "workspace-list-request",
    val requestId: String,
)

@Serializable
data class WorkspaceBridgeOpen(
    @SerialName("zcode_type") val zcodeType: String = "workspace-bridge-open",
    val requestId: String,
    val bridgeSessionId: String,
    val bridgeGeneration: Int? = null,
    val recoveryId: String? = null,
    val workspaceKey: String,
    val taskId: String? = null,
)

@Serializable
data class WorkspaceInfo(
    val workspacePath: String,
    val workspaceIdentity: String? = null,
    val remoteSessionId: String? = null,
    val label: String,
    val workspacePurpose: String? = null,
    val kind: String,
    val connectionState: String? = null,
    val lastConnectionError: String? = null,
)

@Serializable
data class WorkspaceBridge(
    val kind: String,
    val bridgeSessionId: String,
    val bridgeGeneration: Int? = null,
    val recoveryId: String? = null,
    val workspaceKey: String,
    val workspacePath: String,
    val workspaceIdentity: String? = null,
    val remoteSessionId: String? = null,
    val initialTaskId: String? = null,
)

/** Inbound payloads are decoded generically (tolerant); these carry the known shapes. */
@Serializable
data class DataEnvelope(
    val type: String,
    val payload: JsonElement,
    @SerialName("client_ts") val clientTs: Long? = null,
    @SerialName("server_ts") val serverTs: Long? = null,
)

// ---------------------------------------------------------------- rpc-frame transport

/** Outbound rpc-frame: fields exactly per the desktop `.strict()` schema (ofe in the bundle). */
@Serializable
data class RpcFrameOut(
    @SerialName("zcode_type") val zcodeType: String = "rpc-frame",
    val bridgeSessionId: String,
    val bridgeGeneration: Int? = null,
    val recoveryId: String? = null,
    val seq: Long,
    val messageSeq: Long,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val messageBytes: Int,
    val checksum: Checksum,
    val dataBase64: String,
)

@Serializable
data class Checksum(
    val algorithm: String = "crc32",
    val value: String,
)

/** Outbound ack (LYe in the bundle — `.strict()`). */
@Serializable
data class RpcFrameAckOut(
    @SerialName("zcode_type") val zcodeType: String = "rpc-frame-ack",
    val bridgeSessionId: String,
    val bridgeGeneration: Int? = null,
    val recoveryId: String? = null,
    val ackMessageSeq: Long,
)
