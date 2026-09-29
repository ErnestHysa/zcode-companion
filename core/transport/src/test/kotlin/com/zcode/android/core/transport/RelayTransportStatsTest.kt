package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/** Round-7e: cumulative wire counters feed the field export — they must move. */
class RelayTransportStatsTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 61 })
    private val sid = "d_StatsSynthetic001"
    private var mock: MockZCode? = null

    @After
    fun tearDown() {
        mock?.stop()
    }

    @Test
    fun `wire counters advance over a connect-subscribe-command cycle`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        mock = m
        val url = m.start()
        val t = RelayTransport(
            log = ZLog(sink = {}),
            config = RelayTransport.Config(
                connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
                channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
                heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
                v4CallTimeoutMs = 5_000,
            ),
        )
        runBlocking {
            try {
                val endpoint = EndpointDescriptor(
                    id = "ep-stats", kind = EndpointKind.RELAY_ZAI, displayName = "Stats",
                    wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
                )
                t.connect(endpoint) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                t.subscribeSession(m.workspace.sessionId)
                // Let a heartbeat beat (200 ms interval) and a couple of acks land.
                withTimeout(3_000) { kotlinx.coroutines.delay(700) }

                val stats = t.statsSnapshot()
                assertTrue(stats["bridgeOpens"]!! >= 1, "bridgeOpens: $stats")
                assertTrue(stats["framesSent"]!! >= 1, "framesSent: $stats")
                assertTrue(stats["framesRecv"]!! >= 1, "framesRecv: $stats")
                // v4Calls counts only v4Call()-routed calls (subscribe etc.);
                // hello/initialize use channel.call directly.
                assertTrue(stats["controlRequests"]!! >= 2, "controlRequests (bootstrap + bridge-open): $stats")
                assertTrue(stats["heartbeatsSent"]!! >= 1, "heartbeatsSent: $stats")
                assertTrue(stats["pairStatusAcks"]!! >= 1, "pairStatusAcks: $stats")
                assertTrue(stats["controlRequests"]!! >= 2, "controlRequests: $stats")
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }
}
