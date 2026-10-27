package com.zcode.android.core.transport

import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * FIELD (2026-10-26, vc46 S918B on the real relay — the diagnostics round): pins for
 * the four defects the field trace exposed beyond the F-PAIR2 release gap —
 *
 *  1. an error frame naming a requestId must complete the pending control request
 *     FAST (relay-4: DEVICE_OFFLINE arrived 7 s before the 10 s timeout and nobody
 *     consumed it),
 *  2. pair status is PER-CONNECTION state (a stale "matched" from the previous
 *     connection must not satisfy the next connection's approval wait),
 *  3. connect() takeover CANCELS the in-flight recovery loop (F-PAIR2's bounded wait
 *     alone left the loop running through the fresh handshake),
 *  4. the in-connect bootstrap payload is stashed for the hub's first refresh
 *     (F-BOOTSTRAP-REPLAY — the field captured both fetches at 183,755 bytes).
 */
class RelayTransportFieldRound26Test {

    private val passHash = "c3ludGhldGljLXBhc3NoYXNoLWtleQ=="
    private val sid = "d_syntheticdevice000001"

    private fun log(): ZLog = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = { })

    private fun shortConfig() = RelayTransport.Config(
        connectTimeoutMs = 5_000,
        authTimeoutMs = 5_000,
        bridgeTimeoutMs = 5_000,
        channelInitTimeoutMs = 5_000,
        controlRequestTimeoutMs = 5_000,
        bootstrapTimeoutMs = 5_000,
        heartbeatIntervalMs = 200,
        heartbeatWatchdogMs = 1_200,
        ackResendMs = 300,
        v4CallTimeoutMs = 5_000,
    )

    private fun endpoint(url: String, wsPath: String = "/synthetic/ws-one") = EndpointDescriptor(
        id = "ep-test-1",
        kind = EndpointKind.RELAY_ZAI,
        displayName = "Test Device",
        wsUrl = url,
        workspaceKey = "synthetic-ws-one",
        workspacePath = wsPath,
        deviceSid = sid,
        deviceMid = "mock-desktop-mid",
    )

    private fun signer(): CredentialSigner = CredentialSigner { nonce, role, sid ->
        MockZCode.hmacProof(passHash, nonce, role, sid)
    }

    // ------------------------------------------------------------------ 1. error → pending control

    @Test
    fun `error frame with requestId completes the pending control request fast`() {
        val t = RelayTransport(log = log(), config = shortConfig())
        t.seedForTest()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val started = System.currentTimeMillis()
                    val outcome = async {
                        runCatching { t.bootstrap() }
                    }
                    // let controlRequest register req-1 and (best-effort) send
                    delay(200)
                    t.ingestForTest("""{"type":"error","code":"DEVICE_OFFLINE","requestId":"req-1"}""")
                    val result = outcome.await()
                    assertTrue(result.isFailure, "bootstrap must fail on the requestId-matched error frame")
                    val err = result.exceptionOrNull()
                    assertTrue(err is TransportFailure, "expected TransportFailure, got $err")
                    assertEquals(ErrorCategory.TRANSPORT, (err as TransportFailure).error.category)
                    assertTrue(
                        System.currentTimeMillis() - started < 3_000,
                        "error frame must fail the request FAST (well under the 5 s budget)",
                    )
                }
            }
        } finally {
            runBlocking { runCatching { t.close("test done") } }
        }
    }

    @Test
    fun `request-less DEVICE_OFFLINE fails every pending control request`() {
        val t = RelayTransport(log = log(), config = shortConfig())
        t.seedForTest()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val first = async { runCatching { t.bootstrap() } }
                    delay(100)
                    val second = async { runCatching { t.workspaceList() } }
                    delay(200)
                    // No requestId: the relay condemned the desktop bridge — every
                    // pending control request of the family is dead.
                    t.ingestForTest("""{"type":"error","code":"DEVICE_OFFLINE"}""")
                    val r1 = first.await()
                    val r2 = second.await()
                    assertTrue(r1.isFailure, "first pending request must fail fast")
                    assertTrue(r2.isFailure, "second pending request must fail fast")
                    assertTrue(r1.exceptionOrNull() is TransportFailure)
                    assertTrue(r2.exceptionOrNull() is TransportFailure)
                }
            }
        } finally {
            runBlocking { runCatching { t.close("test done") } }
        }
    }

    @Test
    fun `non-device error frames leave pending control requests alone`() {
        val t = RelayTransport(log = log(), config = shortConfig())
        t.seedForTest()
        try {
            runBlocking {
                withTimeout(10_000) {
                    val pending = async { runCatching { t.bootstrap() } }
                    delay(200)
                    // An unrelated relay error (no DEVICE_OFFLINE semantics, no
                    // requestId) must NOT complete or kill the pending request.
                    t.ingestForTest("""{"type":"error","code":"INTERNAL"}""")
                    delay(300)
                    assertEquals(false, pending.isCompleted, "unrelated error must leave the request pending")
                    pending.cancel()
                }
            }
        } finally {
            runBlocking { runCatching { t.close("test done") } }
        }
    }

    // ------------------------------------------------------------------ 2. pair status per connection

    @Test
    fun `pair_status pending waits for desktop approval then matches`() {
        val mock = MockZCode(passHash, sid).apply {
            script.initialPairStatus = "pending"
            script.pairStatusMatchAfterMs = 1_200
        }
        val url = mock.start()
        val t = RelayTransport(log = log(), config = shortConfig())
        try {
            runBlocking {
                withTimeout(30_000) {
                    val started = System.currentTimeMillis()
                    t.connect(endpoint(url), signer())
                    val elapsed = System.currentTimeMillis() - started
                    assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)
                    assertTrue(elapsed >= 1_000, "approval wait must have observed the pending window (took ${elapsed}ms)")
                }
            }
        } finally {
            runBlocking { runCatching { t.close("test done") } }
            mock.stop()
        }
    }

    @Test
    fun `stale matched pair status never satisfies the next connection's approval wait`() {
        val mock = MockZCode(passHash, sid)
        val url = mock.start()
        val t = RelayTransport(log = log(), config = shortConfig().copy(pairStatusWaitMs = 2_000))
        try {
            runBlocking {
                withTimeout(30_000) {
                    // Connection 1: instant "matched" — lastPairStatus now holds it.
                    t.connect(endpoint(url), signer())
                    assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)
                    t.close("mid-test")
                    delay(200)

                    // Connection 2 (same transport, same in-memory state): the desktop
                    // approval NEVER comes. Without the per-connection reset the poll
                    // would see the STALE "matched" and pass instantly; with it, the
                    // connect must die with the honest approval-timeout AUTH failure.
                    mock.script.initialPairStatus = "pending"
                    mock.script.pairStatusMatchAfterMs = -1L
                    val err = runCatching { t.connect(endpoint(url), signer()) }.exceptionOrNull()
                    assertTrue(err is TransportFailure, "expected the approval wait to fail, got $err")
                    assertEquals(ErrorCategory.AUTH, (err as TransportFailure).error.category)
                    assertTrue(
                        (err as TransportFailure).error.developerDiagnostic.contains("pair status stayed pending"),
                        "diagnostic must name the pending wait: ${err.error.developerDiagnostic}",
                    )
                }
            }
        } finally {
            runBlocking { runCatching { t.close("test done") } }
            mock.stop()
        }
    }

    // ------------------------------------------------------------------ 3. takeover cancels the recovery loop

    @Test
    fun `connect takeover cancels an in-flight recovery loop and converges CONNECTED`() {
        val mock = MockZCode(passHash, sid).apply { script.kickFirstTerminalOnSecond = false }
        val url = mock.start()
        val t = RelayTransport(log = log(), config = shortConfig())
        try {
            runBlocking {
                withTimeout(60_000) {
                    t.connect(endpoint(url), signer())
                    assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)

                    // Kill the socket: the loss path owns recovery (loop retained).
                    mock.script.rejectConnections = true
                    t.abortConnectionForTest()
                    val deadline = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < deadline &&
                        t.states.value != ConnectionStateMachine.ConnectionState.RECONNECTING
                    ) delay(20)
                    assertEquals(
                        ConnectionStateMachine.ConnectionState.RECONNECTING,
                        t.states.value,
                        "the loss must hand the transport to the recovery loop",
                    )

                    // Fresh-pairing-class takeover WHILE the loop is in its retry cycle.
                    mock.script.rejectConnections = false
                    t.connect(endpoint(url), signer())
                    assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)

                    // RACE PIN (F-PAIR2 residual): the cancelled loop must stay dead —
                    // no further recovery attempts, no state churn after the takeover.
                    val attemptsAtTakeover = t.statsSnapshot()["recoveryAttempts"] ?: 0
                    delay(3_000)
                    val attemptsAfterSettle = t.statsSnapshot()["recoveryAttempts"] ?: 0
                    assertEquals(
                        attemptsAtTakeover,
                        attemptsAfterSettle,
                        "the cancelled recovery loop must not attempt again after the takeover",
                    )
                    assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)
                }
            }
        } finally {
            runBlocking { runCatching { t.close("test done") } }
            mock.stop()
        }
    }

    // ------------------------------------------------------------------ 4. bootstrap replay stash

    @Test
    fun `in-connect bootstrap payload is stashed and taken exactly once`() {
        val mock = MockZCode(passHash, sid)
        val url = mock.start()
        val t = RelayTransport(log = log(), config = shortConfig())
        try {
            runBlocking {
                withTimeout(30_000) {
                    t.connect(endpoint(url), signer())
                    val replay = t.takeInConnectBootstrap()
                    assertNotNull(replay, "establish()'s bootstrap must be stashed for the hub")
                    assertNotNull(replay["result"], "the stash must be the FULL bootstrap response")
                    assertNull(t.takeInConnectBootstrap(), "take-and-clear: the second take is empty")

                    // A fresh connection stashes again.
                    t.close("mid-test")
                    delay(200)
                    t.connect(endpoint(url), signer())
                    assertNotNull(t.takeInConnectBootstrap(), "each connection stashes its own payload")
                }
            }
        } finally {
            runBlocking { runCatching { t.close("test done") } }
            mock.stop()
        }
    }

    @Test
    fun `bootstrap request rides the widened budget (config pin)`() {
        val cfg = RelayTransport.Config()
        assertEquals(25_000L, cfg.bootstrapTimeoutMs, "bootstrap control budget (field 2026-10-26: 13 s observed)")
        assertEquals(25_000L, cfg.bridgeTimeoutMs, "bridge-open control budget (field 2026-10-26: >10 s observed)")
        assertEquals(20_000L, cfg.v4CallTimeoutMs, "viewer/v4 call budget (field 2026-10-26: 11.8 s observed)")
    }
}
