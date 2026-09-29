package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * Phase 6 FIELD-FINDING regressions, round 3 (real-phone log 2026-09-29):
 *
 *  `auth.matched → (bridge-open OK) → helloConversationV4 timed out after 10000ms`
 *  → every retry `workspace-bridge-error: desktop-disconnected`
 *
 * Root cause: the EventListen for `onDynamicConversationFrame` went out WITHOUT the
 * WORKSPACE-SCOPE argument ({workspacePath, workspaceIdentity?}) — the spike-proven
 * shape that routes the zcode-agent channel to the workspace's agent connection on
 * production. The desktop host attached the channel to no workspace and answered
 * nothing; the lenient mock ignored the arg, so every suite was green while the real
 * phone hung. Follow-on defects covered here: the bridge-discovered workspace was
 * never written back onto the endpoint (later v4 calls would send an empty
 * workspace), and the bridge identity was never rotated after the desktop refused
 * it (re-opening the same dead bridge → desktop-disconnected forever).
 */
class RelayTransportScopedChannelTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 21 })
    private val sid = "d_ScopedChannelSynth1"
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

    /** Production-faithful endpoint: NO workspace fields (first-pair descriptor). */
    private fun endpoint(url: String) = EndpointDescriptor(
        id = "ep-scoped", kind = EndpointKind.RELAY_ZAI, displayName = "Scoped",
        wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
    )

    /**
     * THE field regression: against the SCOPE-REQUIRING mock (default — mirroring
     * production's silence for unscoped conversation calls), a workspace-less
     * first-pair connect must still reach CONNECTED: the transport registers the
     * scoped listener BEFORE the v4 handshake. Pre-fix this reproduces the phone's
     * `helloConversationV4 timed out after 10000ms`.
     */
    @Test
    fun `workspace-less first pair connects through the scoped channel listen`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid) // requireScopedListen = true (default)
        m.script.bridgeRequireTaskId = true // round-1 production strictness, combined
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                // The conversation plane answers end-to-end (also proves the
                // discovered-workspace write-back: the mock faults a blank scope).
                val ack = t.subscribeSession(m.workspace.sessionId)
                assertTrue(ack.logEpoch.isNotBlank(), "subscribe must be answered on the scoped channel")
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }

    /**
     * The bridge-discovered workspace must be written back onto the endpoint so
     * LATER v4 calls carry it. The strict mock REJECTS a blank workspacePath on
     * subscribe (fault.subscribe.workspaceScope) — a passing subscribe proves the
     * write-back; the returned ack's epoch proves the call was routed and answered.
     */
    @Test
    fun `bridge-discovered workspace threads through to later v4 calls`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                val ack = t.subscribeSession(m.workspace.sessionId)
                assertTrue(ack.subscriptionId.isNotBlank(), "subscribe ack must carry a subscription")
                // rowsRange through the same routed scope (≤ 200, INV-13).
                val page = t.rowsRange(m.workspace.sessionId, beforeRowId = null, limit = 10)
                assertTrue(page.rows.isNotEmpty(), "rowsRange must answer through the scoped channel")
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }

    /**
     * Bridge identity rotation: after a bridge-ERROR failure the next attempt must
     * open a FRESH bridgeSessionId (the old one is wedged desktop-side — the field's
     * eternal desktop-disconnected loop), and the self-heal must reach CONNECTED.
     */
    @Test
    fun `bridge error rotates the bridge identity for the next attempt`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                synchronized(m.script.bridgeErrorReasons) { m.script.bridgeErrorReasons.addLast("desktop-disconnected") }
                runCatching {
                    t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                } // fails with workspace-bridge-error → identity rotated
                val first = m.lastBridgeSessionId
                org.junit.Assert.assertNotNull("first open must carry a bridge id", first)
                val connected = CompletableDeferred<Unit>()
                val collector = launch { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }; connected.complete(Unit) }
                withTimeout(25_000) { connected.await() }
                collector.cancel()
                val second = m.lastBridgeSessionId
                org.junit.Assert.assertNotNull(second)
                assertNotEquals(first, second, "bridge id must rotate after a bridge-error failure")
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }
}
