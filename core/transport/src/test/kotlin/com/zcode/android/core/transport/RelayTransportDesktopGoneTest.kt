package com.zcode.android.core.transport

import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * Round-4 field finding (real-phone log 2026-09-29): EVERY bridge attempt —
 * including the first on a fresh pairing — rejected in <1 s with
 * `workspace-bridge-error: desktop-disconnected` while relay auth and the control
 * plane work. That is the STALE-PAIRING shape (the desktop re-registered on QR
 * refresh; the old sid still authenticates but the relay holds no live desktop for
 * it). Retrying is pointless — the desktop does not return inside the budget — so
 * after [RelayTransport.DESKTOP_DISCONNECTED_DEGRADED_THRESHOLD] consecutive
 * rejections the transport must reach Degraded IMMEDIATELY (not after the full
 * 2-minute budget) with actionable copy.
 */
class RelayTransportDesktopGoneTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 31 })
    private val sid = "d_DesktopGoneSynth01"
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
        id = "ep-desktop-gone", kind = EndpointKind.RELAY_ZAI, displayName = "Gone",
        wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
    )

    @Test
    fun `repeated desktop-disconnected reaches Degraded fast with actionable copy`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        mock = m
        // More errors queued than the threshold — the loop must STOP at the threshold.
        synchronized(m.script.bridgeErrorReasons) {
            repeat(6) { m.script.bridgeErrorReasons.addLast("desktop-disconnected") }
        }
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                val degraded = CompletableDeferred<Unit>()
                val collector = launch {
                    t.states.first { it == ConnectionStateMachine.ConnectionState.DEGRADED }
                    degraded.complete(Unit)
                }
                val sawError = CompletableDeferred<com.zcode.android.core.common.error.AppError>()
                val errCollector = launch {
                    t.appErrors.collect { if (it.developerDiagnostic.contains("desktop-disconnected")) sawError.complete(it) }
                }
                val started = System.currentTimeMillis()
                runCatching {
                    t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                }
                withTimeout(30_000) { degraded.await() }
                val elapsed = System.currentTimeMillis() - started
                collector.cancel()
                // FAST: 3 consecutive rejections with backoff ≈ a few seconds — NOT
                // the 2-minute budget (the pre-fix UX ground "Reconnecting…" forever).
                assertTrue(elapsed < 30_000, "degraded after $elapsed ms")
                assertEquals(ConnectionStateMachine.ConnectionState.DEGRADED, t.states.value)
                // The surfaced error carries the actionable desktop-gone copy.
                val err = withTimeout(5_000) { sawError.await() }
                errCollector.cancel()
                assertEquals(ErrorCategory.TRANSPORT, err.category)
                assertTrue(err.userMessage.contains("Desktop"), "actionable copy: ${err.userMessage}")
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }
}
