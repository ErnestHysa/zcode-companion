package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * Round-5 FIELD-FINDING regression (real-phone log 2026-09-29, third re-test):
 *
 *  control plane HEALTHY (bootstrap answered `tasks=482 workspaces=7` over an
 *  authenticated socket) while EVERY `workspace-bridge-open` was rejected with
 *  `desktop-disconnected` — including retries minutes apart.
 *
 * Root cause (introduced by the round-3 endpoint write-back): after the first
 * successful discovery, every RE-connect SKIPPED bootstrap and bridged with the
 * PREVIOUS connection's cached (workspaceKey, taskId). The ACTIVE workspace/task
 * is connection-scoped desktop state — the verified SPA/spike client bootstraps →
 * bridges on EVERY connection. A stale cached discovery is exactly what a live
 * desktop refuses while still answering bootstrap.
 *
 * This mock models that: the bootstrap view CHANGES between connections, and a
 * bridge carrying stale discovery is rejected desktop-disconnected.
 */
class RelayTransportFreshDiscoveryTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 41 })
    private val sid = "d_FreshDiscoverySyn1"
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
        id = "ep-fresh-discovery", kind = EndpointKind.RELAY_ZAI, displayName = "Fresh",
        wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
    )

    /**
     * THE field scenario: connect (view A), drop, the desktop's ACTIVE task moves
     * (view B), reconnect — the client must bridge with B. Pre-fix it re-sent A and
     * looped desktop-disconnected forever (fast-degraded after 3).
     */
    @Test
    fun `reconnect re-discovers the active task instead of bridging stale state`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.bridgeRequireTaskId = true // strict: stale discovery is rejected
        m.script.bootstrapActiveTaskId = "task-view-A"
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                // Connection 1: fresh discovery (view A) → CONNECTED.
                t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                assertEquals("task-view-A", m.lastBridgeTaskId)

                // Desktop churn: socket loss (the recovery path) + the ACTIVE task
                // MOVES to view B while we are away.
                m.script.bootstrapActiveTaskId = "task-view-B"
                t.abortConnectionForTest() // §15-S1 socket death → recover() → establish()
                // The old CONNECTED must not satisfy the wait: observe the loss first.
                withTimeout(10_000) { t.states.first { it != ConnectionStateMachine.ConnectionState.CONNECTED } }
                withTimeout(25_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                // The reconnect bridged with the FRESH view, not the cached one.
                assertEquals("task-view-B", m.lastBridgeTaskId, "must re-discover per connection")
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }

    /** The workspace KEY moves too — same re-discovery duty. */
    @Test
    fun `reconnect re-discovers the active workspace key`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.bridgeRequireTaskId = true
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                val first = m.lastBridgeWorkspaceKey
                m.script.bootstrapActiveWorkspaceKey = "ws-moved-key"
                t.abortConnectionForTest()
                withTimeout(10_000) { t.states.first { it != ConnectionStateMachine.ConnectionState.CONNECTED } }
                withTimeout(25_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                assertEquals("ws-moved-key", m.lastBridgeWorkspaceKey)
                kotlin.test.assertNotEquals(first, "ws-moved-key")
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }

}
