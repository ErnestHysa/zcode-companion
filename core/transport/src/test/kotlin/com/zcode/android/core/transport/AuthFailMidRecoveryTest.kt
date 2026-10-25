package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * P1-GAP (rig discovery 2026-10-16, appended finding): an AUTH failure DURING
 * AUTOMATIC recovery hit `safeMachine(AUTH_FAILED)` from RECONNECTING — an ILLEGAL
 * §14.4.1 transition that safeMachine swallowed (`state.anomaly ::
 * RECONNECTING --AUTH_FAILED--> RECONNECTING`, observed live twice on the rig) —
 * stranding the machine in RECONNECTING with no loop running (the catch returns)
 * instead of landing FAILED (user action required). The fix adds the table edge
 * RECONNECTING --AUTH_FAILED--> FAILED (and the idempotent FAILED self-transition,
 * which also kills the cold-start double-fire anomaly FAILED --AUTH_FAILED--> FAILED).
 * The live-wire pin reproduces the RELAY path (where it was rig-observed); the
 * direct surface shares the same table + catch shape and is covered by the
 * machine pins.
 */
class AuthFailMidRecoveryTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 23 })
    private val sid = "d_AuthMidRecovery01"
    private var mock: MockZCode? = null

    @After
    fun tearDown() {
        mock?.stop()
    }

    // ---------------------------------------------------------------- state machine

    @Test
    fun `state machine - RECONNECTING exits via AUTH_FAILED to FAILED`() {
        val m = ConnectionStateMachine()
        m.seed(ConnectionStateMachine.ConnectionState.RECONNECTING)
        val t = m.on(ConnectionStateMachine.ConnectionEvent.AUTH_FAILED)
        assertEquals(ConnectionStateMachine.ConnectionState.FAILED, t.to)
    }

    @Test
    fun `state machine - AUTH_FAILED while already FAILED is an idempotent no-op`() {
        val m = ConnectionStateMachine()
        m.seed(ConnectionStateMachine.ConnectionState.FAILED)
        val t = m.on(ConnectionStateMachine.ConnectionEvent.AUTH_FAILED)
        assertEquals(ConnectionStateMachine.ConnectionState.FAILED, t.to)
    }

    // ---------------------------------------------------------------- relay (live wire)

    private fun relayTransport() = RelayTransport(
        log = ZLog(sink = {}),
        config = RelayTransport.Config(
            connectTimeoutMs = 4_000, authTimeoutMs = 4_000, bridgeTimeoutMs = 4_000,
            channelInitTimeoutMs = 4_000, controlRequestTimeoutMs = 4_000,
            heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
            v4CallTimeoutMs = 4_000,
        ),
    )

    private fun relayEndpoint(url: String) = EndpointDescriptor(
        id = "ep-p1gap", kind = EndpointKind.RELAY_ZAI, displayName = "P1Gap",
        wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
    )

    @Test
    fun `relay - a credential revoked MID-RECOVERY lands FAILED (not stranded RECONNECTING) - user Retry recovers`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        mock = m
        val url = m.start()
        val t = relayTransport()
        runBlocking {
            try {
                // 1. Healthy connect.
                t.connect(relayEndpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }

                // 2. The credential "becomes invalid" while the socket is live, then the
                //    socket drops: the automatic recovery loop's next handshake now fails
                //    with AUTH — exactly the rig observation.
                m.script.authScenario = MockZCode.AuthScenario.WRONG_PROOF
                t.abortConnectionForTest()

                withTimeout(30_000) {
                    t.states.first { it == ConnectionStateMachine.ConnectionState.FAILED }
                }

                // 3. The user story continues through the F2 arm: transient fault clears,
                //    Retry → CONNECTED.
                m.script.authScenario = MockZCode.AuthScenario.HAPPY
                t.reconnectNow()
                withTimeout(30_000) {
                    t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
                }
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }

}
