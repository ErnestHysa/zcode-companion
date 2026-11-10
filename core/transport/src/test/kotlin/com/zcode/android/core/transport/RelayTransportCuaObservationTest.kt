package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * G64 (vc52 Phase 11): the CUA permission observation REACHES the app — the phone
 * attaches the scoped `onDynamicCuaPermissionObservation` listen (the 4th notify
 * method, constant-declared since Phase 3 and handled nowhere) and the transport
 * exposes the decoded payload. The mock pushes the byte-verified blocked payload;
 * the granted face posts no card (the pin asserts the `blocked` face decode).
 */
class RelayTransportCuaObservationTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 29 })
    private val sid = "d_CuaObsSynthetic1"

    @Test
    fun `a pushed cua permission observation arrives decoded on the transport`() = runBlocking {
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
        val seen = ArrayList<com.zcode.android.core.protocol.v4.CuaPermissionObservation>()
        val collector = launch {
            t.cuaPermissionObservations.collect { seen.add(it) }
        }
        try {
            withTimeout(30_000) {
                t.connect(
                    EndpointDescriptor(
                        id = "ep-cuaobs", kind = EndpointKind.RELAY_ZAI, displayName = "CuaObs",
                        wsUrl = url, workspaceKey = mock.workspace.sessionId, workspacePath = "/synthetic/ws-one",
                        deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
                // The conversation subscribe attaches the workspace-scoped listen
                // (the CUA listen rides the same scope registration).
                t.subscribeSession(mock.workspace.sessionId, visibility = null)
                // The mock pushes the blocked payload (Accessibility denied).
                mock.pushCuaPermissionObservation(mock.blockedCuaPayload(mock.workspace.sessionId))
                withTimeout(5_000) {
                    while (seen.isEmpty()) delay(25)
                }
                val o = seen.first()
                assertEquals("cua-rig-1", o.eventId)
                assertEquals(mock.workspace.sessionId, o.sessionId)
                assertEquals("denied", o.accessibility)
                assertTrue(o.blocked, "the denied face IS the blocked card face")
            }
        } finally {
            collector.cancel()
            kotlinx.coroutines.runBlocking { t.close("test done") }
            mock.stop()
        }
        Unit
    }
}
