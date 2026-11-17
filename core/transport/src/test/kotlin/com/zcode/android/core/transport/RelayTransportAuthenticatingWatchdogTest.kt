package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import java.util.Base64
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * F-P9-A (pass-0 Phase 1): the AUTHENTICATING wedge. The handshake state has NO
 * ladder of its own — both live wedge classes left the machine parked in
 * AUTHENTICATING forever (relay killed mid-auth: 8 min 6 s of zero sockets, zero
 * retries, zero logs, surviving foreground/unlock; relay absent at the cold-start
 * connect: a 10 s timeout → silent wedge), and the only escape was a cross-endpoint
 * switch. Pinned here in both classes:
 *
 *  1. the HANG class (no exception ever fires — the socket connect never resolves):
 *     the watchdog must flip the machine out of AUTHENTICATING within its window and
 *     ARM the recovery ladder (a second connect attempt — "zero reconnect attempts"
 *     was the defect), with the transition LOGGED (never a silent state);
 *  2. the TIMEOUT class (the ws connect's withTimeout fires → the old establish()
 *     catch converted + rethrew WITHOUT the machine handling): the machine must
 *     reach RECONNECTING, never rest in AUTHENTICATING.
 */
class RelayTransportAuthenticatingWatchdogTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 21 })
    private val endpoint = EndpointDescriptor(
        id = "ep-auth-watchdog",
        kind = EndpointKind.RELAY_ZAI,
        displayName = "WatchdogBook",
        wsUrl = "ws://127.0.0.1:1/ws", // never dialed by the fakes below
        deviceSid = "d_AuthWatchdogSynthetic1",
        deviceMid = "mock-desktop-mid",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun signer(): CredentialSigner = CredentialSigner { _, _, _ ->
        // Never invoked in these scenarios (no auth round ever completes).
        "unused-proof"
    }

    private fun logLines() = Collections.synchronizedList(ArrayList<String>())

    private fun logOf(lines: MutableList<String>) = ZLog(sink = { e -> lines.add("${e.event} ${e.details ?: ""}") })

    /** A ws whose connect() NEVER resolves (the hang class: no exception, no timeout). */
    private class HangingWs : Ws {
        private val gate = CompletableDeferred<Unit>()
        var cancelled = false
            private set
        override suspend fun connect(url: String, timeoutMs: Long) {
            gate.await()
        }
        override fun cancel() {
            cancelled = true
            gate.complete(Unit)
        }
        override fun sendText(text: String): Boolean = true
        override fun close(code: Int, reason: String) = Unit
        override val incoming: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow()
        override val closed: kotlinx.coroutines.flow.Flow<WsClosed> = kotlinx.coroutines.flow.emptyFlow()
    }

    @Test
    fun `F-P9-A hang class - the AUTHENTICATING watchdog flips the wedge and arms the ladder`() = runBlocking {
        val lines = logLines()
        var sockets = 0
        val factory = WsFactory {
            sockets++
            HangingWs()
        }
        val transport = RelayTransport(
            wsFactory = factory,
            log = logOf(lines),
            config = RelayTransport.Config(authWatchdogTimeoutMs = 700),
        )
        try {
            // connect() parks inside establish() (the ws connect never resolves) — the
            // wedge shape. Run it detached; the watchdog must own the rescue.
            val connectJob = launch { runCatching { transport.connect(endpoint, signer()) } }
            // The machine ENTERS AUTHENTICATING (the seed) and would rest there forever
            // pre-fix; the watchdog flips it within the configured window.
            withTimeout(10_000) {
                transport.states.first { it == ConnectionStateMachine.ConnectionState.AUTHENTICATING }
            }
            val startedAt = System.currentTimeMillis()
            withTimeout(10_000) {
                transport.states.first { it == ConnectionStateMachine.ConnectionState.RECONNECTING }
            }
            val flipMs = System.currentTimeMillis() - startedAt
            assertTrue(flipMs >= 500, "the watchdog respected the configured window (${flipMs}ms)")
            assertTrue(flipMs < 8_000, "the wedge resolved via the watchdog (${flipMs}ms), not a silent stall")
            // "No silent state": the transition is logged (the relay.lost line).
            assertTrue(
                lines.any { it.startsWith("relay.lost") && it.contains("auth watchdog") },
                "the watchdog rescue must be logged (no silent state): ${lines.takeLast(12)}",
            )
            // The ladder ARMED: the recovery loop made a SECOND connect attempt
            // ("zero reconnect attempts" was the wedge's signature).
            withTimeout(10_000) {
                while (sockets < 2) delay(50)
            }
            connectJob.cancel()
        } finally {
            runCatching { transport.close("test end") }
        }
        Unit
    }

    @Test
    fun `F-P9-A timeout class - a connect timeout routes through the socket-loss path, never rests in AUTHENTICATING`() = runBlocking {
        val lines = logLines()
        val factory = WsFactory {
            object : Ws {
                override suspend fun connect(url: String, timeoutMs: Long) {
                    // Simulate the ws connect's own withTimeout firing (the NAT
                    // blackhole class): the caller sees TimeoutCancellationException.
                    kotlinx.coroutines.withTimeout(50) { CompletableDeferred<Unit>().await() }
                }
                override fun cancel() = Unit
                override fun sendText(text: String): Boolean = true
                override fun close(code: Int, reason: String) = Unit
                override val incoming: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow()
                override val closed: kotlinx.coroutines.flow.Flow<WsClosed> = kotlinx.coroutines.flow.emptyFlow()
            }
        }
        val transport = RelayTransport(
            wsFactory = factory,
            log = logOf(lines),
            config = RelayTransport.Config(
                connectTimeoutMs = 5_000,
                authTimeoutMs = 5_000,
                heartbeatIntervalMs = 200,
                heartbeatWatchdogMs = 1_500,
                // The recovery ladder's backoff floor: bounded for the test.
            ),
        )
        try {
            val connectResult = kotlinx.coroutines.CompletableDeferred<Throwable?>()
            scope.async { connectResult.complete(runCatching { transport.connect(endpoint, signer()) }.exceptionOrNull()) }
            // The failure surfaces to the caller…
            val thrown = withTimeout(10_000) { connectResult.await() }
            assertTrue(thrown != null, "the connect failure surfaces to the caller")
            // …AND the machine must NOT rest in AUTHENTICATING: the socket-loss path
            // (RECONNECTING + the armed recovery loop) owns the aftermath.
            withTimeout(10_000) {
                transport.states.first { it == ConnectionStateMachine.ConnectionState.RECONNECTING }
            }
            assertTrue(
                lines.any { it.startsWith("relay.connectFailed") },
                "the failure is logged (the hub's connectFailed counterpart)",
            )
        } finally {
            runCatching { transport.close("test end") }
        }
        Unit
    }
}
