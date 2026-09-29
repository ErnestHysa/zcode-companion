package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * Round-6 FIELD-FINDING regressions (real-phone log 2026-09-29 + user observation):
 *
 *  "every retry from the phone FREEZES the macOS ZCode app — it must be restarted"
 *  …and the log confirms the mechanism: after Degraded, the recovery loop KEPT
 *  running full auth+bootstrap+bridge handshakes (`machine in DEGRADED at auth ok`
 *  ×9, ~1/s), and each socket loss (the relay's DEVICE_OFFLINE pushes) relaunched
 *  the loop. Terminal bridge attempts wedging the desktop's relay session is the
 *  community #328 class — the app must go QUIET at Degraded and pace bridge
 *  rejections calmly.
 */
class RelayTransportDegradedQuiesceTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 51 })
    private val sid = "d_DegradedQuiesce01"
    private var mock: MockZCode? = null

    @After
    fun tearDown() {
        mock?.stop()
    }

    private fun transport(): RelayTransport = RelayTransport(
        log = ZLog(sink = {}),
        config = RelayTransport.Config(
            connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
            channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
            heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
            v4CallTimeoutMs = 5_000,
        ),
    )

    private fun endpoint(url: String) = EndpointDescriptor(
        id = "ep-quiesce", kind = EndpointKind.RELAY_ZAI, displayName = "Quiesce",
        wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
    )

    /**
     * THE field scenario: reach Degraded (3 desktop-disconnected) → the app must
     * make NO further auth attempts (quiet), and a later socket loss (the relay's
     * DEVICE_OFFLINE push class) must NOT relaunch the storm. Retry (user action)
     * still works when the desktop is healthy again.
     */
    @Test
    fun `degraded transport goes quiet - socket loss does not relaunch the storm - retry still works`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.bridgeRequireTaskId = false
        // NOTE: the FIRST attempt fails through connect()'s own catch (not counted
        // by the recovery-loop threshold) — 4 pops = connect + 3 counted rejections.
        synchronized(m.script.bridgeErrorReasons) {
            repeat(4) { m.script.bridgeErrorReasons.addLast("desktop-disconnected") }
        }
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                val auths = java.util.concurrent.ConcurrentLinkedQueue<Long>()
                val authJob = launch {
                    mock!!.events.collect { if (it is MockZCode.ServerEvent.AuthCompleted) auths.add(System.currentTimeMillis()) }
                }
                val degraded = CompletableDeferred<Unit>()
                val stateJob = launch { t.states.first { it == ConnectionStateMachine.ConnectionState.DEGRADED }; degraded.complete(Unit) }
                runCatching {
                    t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                }
                withTimeout(40_000) { degraded.await() }

                // Quiet window: no further auth attempts for 6 s (> the 5 s pacing floor).
                val afterDegraded = auths.size
                delay(6_000)
                assertEquals(afterDegraded, auths.size, "Degraded must be QUIET (no relay handshakes)")

                // Socket loss while DEGRADED (the relay's DEVICE_OFFLINE push class)
                // must NOT relaunch the recovery loop.
                t.abortConnectionForTest()
                delay(4_000)
                assertEquals(afterDegraded, auths.size, "loss while Degraded must not relaunch the storm")

                // User Retry with the desktop healthy again → recovers to CONNECTED.
                synchronized(m.script.bridgeErrorReasons) { m.script.bridgeErrorReasons.clear() }
                t.reconnectNow()
                withTimeout(25_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                stateJob.cancel()
                authJob.cancel()
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }

    /**
     * Pacing (feedback #328 class): bridge rejections must be followed by a calm
     * floor — the pre-fix 1 s cadence re-wedged the desktop before it could recover.
     * Three attempts (threshold) spread over ≥ 2×floor.
     */
    @Test
    fun `bridge rejections are paced with a calm floor`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        synchronized(m.script.bridgeErrorReasons) {
            repeat(4) { m.script.bridgeErrorReasons.addLast("desktop-disconnected") }
        }
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                val auths = java.util.concurrent.ConcurrentLinkedQueue<Long>()
                val authJob = launch {
                    mock!!.events.collect { if (it is MockZCode.ServerEvent.AuthCompleted) auths.add(System.currentTimeMillis()) }
                }
                val degraded = CompletableDeferred<Unit>()
                launch { t.states.first { it == ConnectionStateMachine.ConnectionState.DEGRADED }; degraded.complete(Unit) }
                runCatching {
                    t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                }
                withTimeout(40_000) { degraded.await() }
                authJob.cancel()
                val times = auths.toList().sorted()
                assertTrue(times.size >= 4, "expected 4 auth attempts, got ${times.size}")
                val gaps = times.zipWithNext().map { (a, b) -> b - a }
                // Gap 1 (initial connect → first recovery retry) carries only the
                // policy backoff; every POST-REJECTION retry (gaps 2+) carries the
                // #328 pacing floor.
                assertTrue(
                    gaps.drop(1).all { it >= RelayTransport.BRIDGE_REJECT_FLOOR_MS - 1_500 },
                    "bridge-rejection retry gaps too hot: $gaps",
                )
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }
}
