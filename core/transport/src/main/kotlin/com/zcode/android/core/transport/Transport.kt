package com.zcode.android.core.transport

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.protocol.v4.ConnectionFlowState
import com.zcode.android.core.protocol.v4.DeliveryKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport contract — architectural.md §11.2 (conceptual contract made concrete for
 * Phase 2; the API freezes here for Phase 3–5 consumers per §43.2-2.1).
 *
 * Boundary rules (hard, §11.2 "Does NOT belong in transport"): no domain models, no
 * conversation reducers, no permission logic, no persistence, no notification logic,
 * no UI state, no credential custody (the transport holds ONLY a [CredentialSigner]
 * capability that answers challenges), no command retry policy, no payload logging.
 *
 * INV-6: features branch on [capabilities], never on transport kind. INV-2: nothing
 * above core:transport touches sockets.
 */
interface Transport {

    /** Connection state machine (§14.4.1) — in-memory truth for the current endpoint. */
    val states: StateFlow<ConnectionStateMachine.ConnectionState>

    /**
     * Negotiated capability snapshot (hello HostCapabilities + endpoint metadata) —
     * the ONLY variability source features may read (INV-6). Null before hello.
     */
    val capabilities: StateFlow<CapabilitySnapshot?>

    /** v4/connection/flow backpressure signal (saturated/drained/closed, §28.4). */
    val flowPressure: StateFlow<ConnectionFlowState>

    /** Ordered per-connection inbound frames after parse (§11.2 receive). */
    val inbound: SharedFlow<InboundFrame>

    /** Classified errors surfaced to higher layers (§33 model; never raw exceptions). */
    val appErrors: SharedFlow<AppError>

    /**
     * Connect + authenticate (§11.2 connect/authenticate): TLS WebSocket, transport-
     * specific handshake via the injected signer capability, bridge/channel open, v4
     * hello. Emits [CapabilitySnapshot] and moves the machine to Connected. The
     * transport NEVER holds secret material — only the signer capability.
     */
    suspend fun connect(endpoint: EndpointDescriptor, signer: CredentialSigner)

    /** Send one outbound unit through the bounded send queue (§11.2 send, INV-5). */
    suspend fun send(frame: OutboundFrame)

    /**
     * Recover after a connection loss (§11.2 recover / §15.1): re-auth + resync; the
     * ONLY path that may issue snapshot replacement.
     */
    suspend fun recover()

    /** Graceful close; releases the socket; idempotent (§11.2 close). */
    suspend fun close(reason: String)

    /** Immediate reconnect attempt (app foreground / manual retry, §11.5). */
    fun reconnectNow()

    /** Network-change trigger (ConnectivityManager callback wired by the app layer). */
    fun notifyNetworkAvailable()
}

/** §11.3 endpoint model — descriptors only; credential material stays behind refs. */
data class EndpointDescriptor(
    val id: String,
    val kind: EndpointKind,
    val displayName: String,
    val wsUrl: String,
    /** Relay terminal-role target workspace for the bridge (relay-specific wiring). */
    val workspaceKey: String? = null,
    /** Workspace path/identity targets for the v4 conversation plane (live-proven shape). */
    val workspacePath: String? = null,
    val workspaceIdentity: String? = null,
    val taskId: String? = null,
    /** QR deviceSid (relay); token endpoints carry no deviceSid. */
    val deviceSid: String? = null,
    /** QR deviceMid — REQUIRED in the relay URL (Q4 live finding, Phase 1). */
    val deviceMid: String? = null,
    val desktopAppVersion: String? = null,
    val candidates: List<String> = emptyList(),
    /** Client identity sent in v4 clientHello (Q6 decision: mobileRemote default). */
    val clientKind: String = CLIENT_KIND_DEFAULT,
    val appVersion: String = "0.1.0-phase2",
) {
    companion object {
        /** Q6 fallback (§46): declare the observed phone-client class. */
        const val CLIENT_KIND_DEFAULT = "mobileRemote"
        const val CLIENT_MODE_PHONE = "web-remote-replayable"
    }
}

enum class EndpointKind { RELAY_ZAI, DIRECT_SERVER }

/**
 * Proof capability (§12.4/§26.3): answers a challenge WITHOUT exposing material.
 * Implementations live in core:security (Phase 3); tests use fakes. The transport
 * consumes proofs only — it can never read, cache, or log the secret (INV-3/INV-10).
 */
fun interface CredentialSigner {
    /** proof = base64url(HMAC-SHA256(key=passHash, "${nonce}|${role}|${sid}")) — §7.6 exact. */
    fun proof(nonce: String, role: String, sid: String): String
}

/** Direct-endpoint token capability (§11.4; used by DirectTransport in Phase 10). */
fun interface TokenProvider {
    fun token(): String
}

/** Capability snapshot emitted at hello (INV-6 variability source). */
data class CapabilitySnapshot(
    val connectionId: String,
    val serverTimeMs: Double,
    val host: com.zcode.android.core.protocol.v4.HostCapabilities,
    val desktopAppVersion: String?,
    val endpointKind: EndpointKind,
)

/** Ordered inbound frames (§11.2 receive). */
sealed class InboundFrame {
    /** Assembled v4 topic frame (snapshot or deltas) — deliveryKind preserved. */
    data class TopicFrameArrived(
        val topic: String,
        val subscriptionId: String,
        val deliveryKind: DeliveryKind,
        val fromSeq: Long,
        val toSeq: Long,
        val frame: com.zcode.android.core.protocol.v4.TopicFrame,
    ) : InboundFrame()

    /** Relay control-plane payload (bootstrap-response, workspace-list-response, …). */
    data class ControlPayload(val zcodeType: String, val payload: kotlinx.serialization.json.JsonObject) : InboundFrame()

    /** v4/connection/flow signal (§28.4). */
    data class FlowControl(val state: ConnectionFlowState, val connectionId: String) : InboundFrame()

    /** Non-v4 channel event (future surfaces; decoded, never silently dropped — INV-8). */
    data class ChannelEvent(val event: String, val args: com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue) : InboundFrame()
}

/** Outbound units (§11.2 send). */
sealed class OutboundFrame {
    /** Raw binary Channel frame — wrapped per transport (relay: rpc-frame envelope). */
    class Channel(val bytes: ByteArray) : OutboundFrame()

    /** Relay control-plane request (bootstrap/workspace-list/bridge-open …). */
    class RelayControl(val json: String) : OutboundFrame()
}
