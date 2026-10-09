package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * Phase 6 regression (defect found by the Phase 6 hub integration evidence): socket
 * loss must classify IMMEDIATELY (§15.3 takeover UX, §33 close-code table). Before
 * the fix, `OkHttpWs.closed` had no collector — an abrupt socket death was silently
 * swallowed and detection fell to the 30 s heartbeat watchdog, so relay close codes
 * (4009 takeover, 4010 desktop-gone, 4011/4013 auth-expired) surfaced a full
 * watchdog period late.
 */
class RelayTransportLossClassificationTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid = "d_CleanCloseSynthetic1"
    private var mock: MockZCode? = null

    @After
    fun tearDown() {
        mock?.stop()
    }

    @Test
    fun `abrupt socket loss classifies within seconds, not via the watchdog`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        mock = m
        val url = m.start()
        val transport = RelayTransport(log = ZLog(sink = {}), config = RelayTransport.Config())
        runBlocking {
            try {
                val endpoint = EndpointDescriptor(
                    id = "ep-clean-close",
                    kind = EndpointKind.RELAY_ZAI,
                    displayName = "CleanCloseBook",
                    wsUrl = url,
                    deviceSid = sid,
                    deviceMid = "mock-desktop-mid",
                    desktopAppVersion = "3.14.3",
                )
                transport.connect(endpoint) { nonce, role, s ->
                    MockZCode.hmacProof(passHash, nonce, role, s)
                }
                withTimeout(20_000) { transport.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                transport.subscribeSession("synth-task-1")

                // Refuse reconnects BEFORE the abort so RECONNECTING RESTS (otherwise
                // the mock re-admits the transport in ~1 s and the transient value is
                // conflated away on the StateFlow).
                m.script.rejectConnections = true

                val started = System.currentTimeMillis()
                // The collector must be actively subscribed BEFORE the abort: launch a
                // child, yield the dispatcher so it attaches, then kill the socket.
                val lossDetected = kotlinx.coroutines.CompletableDeferred<Unit>()
                val collector = launch {
                    transport.states.first { it == ConnectionStateMachine.ConnectionState.RECONNECTING }
                    lossDetected.complete(Unit)
                }
                delay(200) // yield so the collector subscribes first
                transport.abortConnectionForTest()
                withTimeout(14_000) { lossDetected.await() }
                val detectionMs = System.currentTimeMillis() - started
                // Immediate classification — NOT the 30 s watchdog path.
                assertTrue(detectionMs < 10_000, "loss detection took $detectionMs ms")
            } finally {
                runCatching { transport.close("test end") }
            }
        }
    }
}
