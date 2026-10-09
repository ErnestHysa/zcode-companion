package com.zcode.android.core.transport

import com.zcode.android.core.transport.TransportFailure

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
 * Phase 6 FIELD-FINDING regressions (real-phone failure against the production
 * relay): the post-auth workspace-bridge handshake failed with
 * `workspace-bridge-error {reason: session-not-found}` — because the bridge-open
 * went out WITHOUT a resolvable taskId (production requires bootstrap-first
 * discovery, per the Phase 1 live corpus ordering) — and the failure was (a)
 * reported WITHOUT its reason and (b) fatal to the recovery loop, stranding the
 * machine in RECONNECTING forever.
 *
 * Grounded in the verified bridge-failure enum (relay-control-plane.md) and the
 * live-corpus message ordering (bootstrap-request → bootstrap-response →
 * workspace-bridge-open {workspaceKey, taskId}).
 */
class RelayTransportBridgeErrorTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 11 })
    private val sid = "d_BridgeErrSynthetic01"
    private var mock: MockZCode? = null

    @After
    fun tearDown() {
        mock?.stop()
    }

    private fun strictMock(): MockZCode = MockZCode(passHash = passHash, expectedSid = sid).apply {
        // Production-faithful strictness: a taskless bridge-open is rejected.
        script.bridgeRequireTaskId = true
    }

    private fun transport(): RelayTransport = RelayTransport(
        log = ZLog(sink = {}),
        config = RelayTransport.Config(
            connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
            channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
            heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
        ),
    )

    private fun endpoint(url: String) = EndpointDescriptor(
        id = "ep-bridge-err", kind = EndpointKind.RELAY_ZAI, displayName = "BridgeErr",
        wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
    )

    /**
     * THE field failure: strict production bridge + taskless first open → the
     * transport must (a) bootstrap FIRST and resolve the active task (fix), or
     * failing that (b) surface the VERBATIM reason. The fix makes (a) the path —
     * connect SUCCEEDS because bootstrap supplies activeTaskId.
     */
    @Test
    fun `connect resolves the active task through bootstrap-first discovery (field fix)`() {
        val m = strictMock()
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }

    /**
     * When the bridge still errors, the reason must reach the AppError VERBATIM
     * (it was previously discarded — the field diagnostic only said "SESSION").
     */
    @Test
    fun `bridge error surfaces its verbatim reason with the faithful category`() {
        val m = strictMock()
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                synchronized(m.script.bridgeErrorReasons) { m.script.bridgeErrorReasons.addLast("session-not-found") }
                val failure = runCatching {
                    t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                }.exceptionOrNull()
                org.junit.Assert.assertNotNull("connect must fail while the error is queued", failure)
                val err = (failure as TransportFailure).error
                assertEquals(ErrorCategory.SESSION, err.category)
                assertTrue(
                    err.developerDiagnostic.contains("session-not-found"),
                    "reason must be preserved verbatim, got: ${err.developerDiagnostic}",
                )
                // NOT the takeover marker — session-not-found is transient (§15.3 fix).
                assertTrue(
                    err.autoRecovery != com.zcode.android.core.common.error.RecoveryAction.RECONNECT,
                    "session-not-found must not carry the takeover marker",
                )
                // The machine must rest in an HONEST state (RECONNECTING), not CONNECTING.
                assertEquals(ConnectionStateMachine.ConnectionState.RECONNECTING, t.states.value)
            } finally {
                runCatching { runBlocking { t.close("done") } }
            }
        }
    }

    /**
     * THE stuck-forever regression: a TRANSIENT session error must not strand the
     * machine — after the error clears, the recovery loop reaches CONNECTED.
     */
    @Test
    fun `recovery continues after a transient session error and reaches CONNECTED`() {
        val m = strictMock()
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            // NOTE: no finally-close — closing would kill the just-launched self-heal
            // loop it is supposed to prove (production keeps the transport alive).
            synchronized(m.script.bridgeErrorReasons) { m.script.bridgeErrorReasons.addLast("session-not-found") }
            val connected = CompletableDeferred<Unit>()
            val collector = launch { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }; connected.complete(Unit) }
            runCatching {
                t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
            } // fails (queued error) → machine RECONNECTING → the self-heal loop must RETRY
            withTimeout(25_000) { connected.await() } // pre-fix: stranded forever
            collector.cancel()
            runCatching { runBlocking { t.close("done") } }
        }
    }

    /** session-conflict IS the takeover class: the loop stops for a user decision (§15.3). */
    @Test
    fun `session-conflict keeps the takeover semantics`() {
        val m = strictMock()
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                // Two queued conflicts: attempt #1 (direct connect) + attempt #2 (the
                // self-heal recovery loop) — the loop STOPS on the takeover class.
                synchronized(m.script.bridgeErrorReasons) {
                    m.script.bridgeErrorReasons.addLast("session-conflict")
                    m.script.bridgeErrorReasons.addLast("session-conflict")
                }
                val errSignal = CompletableDeferred<com.zcode.android.core.common.error.AppError>()
                val collector = launch {
                    t.appErrors.collect { if (it.category == ErrorCategory.SESSION) errSignal.complete(it) }
                }
                runCatching {
                    t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                }
                val err = withTimeout(15_000) { errSignal.await() } // takeover: loop STOPS (user decides)
                assertTrue(err.developerDiagnostic.contains("session-conflict"))
                assertEquals(
                    com.zcode.android.core.common.error.RecoveryAction.RECONNECT,
                    err.autoRecovery,
                    "session-conflict carries the takeover marker (§15.3)",
                )
                collector.cancel()
                runCatching { runBlocking { t.close("done") } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                org.junit.Assert.fail("unexpected: $e")
            }
        }
    }
}
