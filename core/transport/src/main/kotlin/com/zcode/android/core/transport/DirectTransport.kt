package com.zcode.android.core.transport

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.protocol.v4.ConnectionFlowState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DirectTransport — SKELETON INTERFACE ONLY in Phase 2 (§43.2 Phase 2 non-scope:
 * "DirectTransport (skeleton interface only)"; delivery in Phase 10 per §43.2-10.1).
 *
 * Present in the API surface now because INV-6/ADR-011 demand the transport
 * abstraction from the first commit: features must never learn which implementation
 * sits behind [Transport]. The stub honors that by existing, not by pretending.
 *
 * Auth-model separation is NORMATIVE (§11.4): direct mode uses shared-secret token +
 * zcode_lite_token cookie over binary WS frames (no base64, no relay envelopes, close
 * codes 4000/4004) — NO relay-auth assumption may be carried into direct mode or vice
 * versa. Server-info preflight: GET /api/server-info → {protocolVersion, workspaces,
 * capabilities} (§7.10, verified).
 */
class DirectTransport(
    private val tokenProvider: TokenProvider,
    private val log: com.zcode.android.core.common.log.ZLog,
) : Transport {

    private val notImplemented = UnsupportedOperationException(
        "DirectTransport is a Phase 10 deliverable (§43.2-10.1); Phase 2 ships the interface only"
    )

    private val _states = MutableStateFlow(ConnectionStateMachine.ConnectionState.UNPAIRED)
    override val states: StateFlow<ConnectionStateMachine.ConnectionState> = _states.asStateFlow()

    private val _capabilities = MutableStateFlow<CapabilitySnapshot?>(null)
    override val capabilities: StateFlow<CapabilitySnapshot?> = _capabilities.asStateFlow()

    private val _flowPressure = MutableStateFlow(ConnectionFlowState.DRAINED)
    override val flowPressure: StateFlow<ConnectionFlowState> = _flowPressure.asStateFlow()

    private val _inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
    override val inbound: SharedFlow<InboundFrame> = _inbound.asSharedFlow()

    private val _appErrors = MutableSharedFlow<AppError>(extraBufferCapacity = 16)
    override val appErrors: SharedFlow<AppError> = _appErrors.asSharedFlow()

    override suspend fun connect(endpoint: EndpointDescriptor, signer: CredentialSigner) {
        throw notImplemented
    }

    override suspend fun send(frame: OutboundFrame) = throw notImplemented

    override suspend fun recover() = throw notImplemented

    override suspend fun close(reason: String) {
        // Idempotent by contract; nothing to release yet.
    }

    override fun reconnectNow() = throw notImplemented

    override fun notifyNetworkAvailable() = throw notImplemented
}
