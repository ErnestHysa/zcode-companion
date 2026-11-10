package com.zcode.android.core.transport

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * G67 (vc52 Phase 11): the disconnected-REMOTE-workspace flow — a bridge-open for a
 * disconnected remote (SSH/WSL/Docker) fails with the desktop's typed disconnected
 * error ("The remote workspace … is not connected. Send /reconnect first"), the phone
 * maps it to the TYPED error (never the retrying-connection face), the Reconnect wire
 * verb fires `workspace-reconnect-request`, and the bridge succeeds after the
 * reconnect (the mock's remote-down fixture flips off).
 */
class RelayTransportRemoteWorkspaceReconnectTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 33 })
    private val sid = "d_RemoteReconnect1"

    private fun connect(): Pair<MockZCode, RelayTransport> {
        val mock = MockZCode(passHash = passHash, expectedSid = sid)
        val url = mock.start()
        val t = RelayTransport(
            log = ZLog(sink = {}),
            config = RelayTransport.Config(
                connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
                channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
                heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
                v4CallTimeoutMs = 5_000,
            ),
        )
        return mock to t
    }

    @Test
    fun `a remote-down bridge-open throws the TYPED disconnected error`() = runBlocking {
        val (mock, t) = connect()
        try {
            mock.armRemoteDown()
            val url = mock.start()
            withTimeout(30_000) {
                // The arm makes the FIRST bridge-open fail with the typed error — the
                // connect() throws and the FAILURE ITSELF carries the typed face (the
                // app's lastError lands via the connect-fault classification).
                val thrown = runCatching {
                    t.connect(
                        EndpointDescriptor(
                            id = "ep-remotedown", kind = EndpointKind.RELAY_ZAI, displayName = "RemoteDown",
                            wsUrl = url, workspaceKey = mock.workspace.sessionId,
                            workspacePath = "/synthetic/remote-one", deviceSid = sid, deviceMid = "mock-desktop-mid",
                        ),
                    ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                }.exceptionOrNull()
                assertTrue(thrown is RelayTransport.Failure, "the connect failed: $thrown")
                val failure = thrown as RelayTransport.Failure
                assertEquals(
                    com.zcode.android.core.common.error.UserMessageKey.REMOTE_WORKSPACE_DISCONNECTED,
                    failure.error.messageKey,
                    "the TYPED disconnected face (never the retrying-connection one)",
                )
                assertTrue(
                    failure.error.userMessage.contains("disconnected"),
                    "the card copy names the disconnection: ${failure.error.userMessage}",
                )
            }
        } finally {
            kotlinx.coroutines.runBlocking { t.close("test done") }
            mock.stop()
        }
        Unit
    }

    @Test
    fun `the reconnect request fires and the bridge succeeds after reconnect`() = runBlocking {
        val (mock, t) = connect()
        try {
            withTimeout(30_000) {
                // The card's sequence on a live connection: connect (the bridge worked
                // before) → the remote goes down → Reconnect fires the request → the
                // retry's bridge-open succeeds against the reconnected workspace.
                t.connect(
                    EndpointDescriptor(
                        id = "ep-remotereconnect", kind = EndpointKind.RELAY_ZAI, displayName = "RemoteReconnect",
                        wsUrl = mock.start(), workspaceKey = mock.workspace.sessionId,
                        workspacePath = "/synthetic/remote-one", deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
                mock.armRemoteDown()
                // The Reconnect wire verb fires on the LIVE socket and FLIPS the arm off.
                val result = t.requestWorkspaceReconnect(mock.workspace.sessionId)
                assertTrue(result.success, "the mock's reconnect response is success")
                assertEquals(1, synchronized(mock.reconnectRequestsLedger) { mock.reconnectRequestsLedger.size }, "one request on the wire")
                assertTrue(!mock.remoteDownArmed, "the reconnect fixed the remote (the arm is off)")
                // The retry's bridge-open now succeeds (the recovery ladder completes).
                t.abortConnectionForTest()
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
            }
        } finally {
            kotlinx.coroutines.runBlocking { t.close("test done") }
            mock.stop()
        }
        Unit
    }
}
