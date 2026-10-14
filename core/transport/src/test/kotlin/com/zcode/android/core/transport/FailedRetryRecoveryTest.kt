package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After

/**
 * F2 (gaps-and-polish Phase 1A, P0): Retry-from-FAILED can never recover.
 *
 * The field path: an AUTH-class failure lands the machine in FAILED (user-action-
 * required). The user presses Retry — and every route was a dead end:
 *   - connect() REFUSES non-acquisition states ("connect() is the first-connection
 *     path; use recover()") → IllegalStateException;
 *   - recover()'s loop QUIESCES on FAILED (the #328-class storm guard — correct for
 *     the AUTOMATIC loop, wrong for an explicit user action);
 *   - reconnectNow() only opens a fresh budget from DEGRADED, so from FAILED it
 *     was a no-op.
 * The fix: FAILED gains the §14.4.1 "user action available" exit (RETRY_REQUESTED →
 * Reconnecting, fresh budget), both transports' reconnectNow() arm it, and the hub's
 * retry() routes FAILED there. The AUTOMATIC recover() loop must stay QUIET on
 * FAILED (the storm guard is not relaxed).
 */
class FailedRetryRecoveryTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 17 })
    private val sid = "d_FailedRetryRecovery1"
    private var mock: MockZCode? = null

    @After
    fun tearDown() {
        mock?.stop()
    }

    // ---------------------------------------------------------------- state machine

    @Test
    fun `state machine - FAILED exits via user RETRY_REQUESTED to RECONNECTING`() {
        val m = ConnectionStateMachine()
        m.seed(ConnectionStateMachine.ConnectionState.FAILED)
        val t = m.on(ConnectionStateMachine.ConnectionEvent.RETRY_REQUESTED)
        assertEquals(ConnectionStateMachine.ConnectionState.RECONNECTING, t.to)
    }

    // ---------------------------------------------------------------- relay

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
        id = "ep-f2-relay", kind = EndpointKind.RELAY_ZAI, displayName = "F2Relay",
        wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
    )

    @Test
    fun `relay - user retry from FAILED recovers to CONNECTED`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.authScenario = MockZCode.AuthScenario.WRONG_PROOF // the transient auth fault
        mock = m
        val url = m.start()
        val t = relayTransport()
        runBlocking {
            try {
                // Drive to FAILED exactly like the field: auth rejected → AUTH_FAILED → FAILED.
                runCatching {
                    t.connect(relayEndpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.FAILED } }

                // The transient fault clears (credential was always valid) → user Retry.
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

    @Test
    fun `relay - AUTOMATIC recover from FAILED stays quiet (storm guard not relaxed)`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.authScenario = MockZCode.AuthScenario.WRONG_PROOF
        mock = m
        val url = m.start()
        val t = relayTransport()
        runBlocking {
            try {
                runCatching {
                    t.connect(relayEndpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.FAILED } }
                // The loop entry point recovers nothing from FAILED — the #328 storm
                // guard: FAILED is USER-ACTION-REQUIRED for the automatic path.
                t.recover()
                val moved = withTimeoutOrNull(2_000) {
                    t.states.first { it != ConnectionStateMachine.ConnectionState.FAILED }
                }
                assertEquals(null, moved, "automatic recover() must not move the machine out of FAILED")
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }

    // ---------------------------------------------------------------- direct

    private fun directTransport() = DirectTransport(
        tokenProvider = { "direct-f2-token" },
        wsFactory = OkHttpWsFactory(),
        log = ZLog(sink = {}),
        config = DirectTransport.Config(
            connectTimeoutMs = 4_000, authTimeoutMs = 4_000, channelInitTimeoutMs = 4_000,
            v4CallTimeoutMs = 4_000, bridgeHandshakeTimeoutMs = 4_000, controlRequestTimeoutMs = 4_000,
        ),
    )

    private fun directEndpoint(url: String) = EndpointDescriptor(
        id = "ep-f2-direct", kind = EndpointKind.DIRECT_SERVER, displayName = "F2Direct",
        wsUrl = url, workspacePath = "/synthetic/ws-one",
    )

    @Test
    fun `direct - user retry from FAILED recovers to CONNECTED`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.directRejectToken = true // the 401 token gate (auth-class)
        mock = m
        val url = m.start()
        val t = directTransport()
        runBlocking {
            try {
                runCatching { t.connect(directEndpoint(url)) { _, _, _ -> "" } }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.FAILED } }

                m.script.directRejectToken = false // the token gate clears → user Retry
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
