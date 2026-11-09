package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-34-B (vc52 Phase 8, P2) — returning from the background could land in
 * "Disconnected" for minutes. Pass-34 sequence: HOME → `relay.lost` → reconnect ladder
 * (10 s connect timeouts — the network was blocked) → the process froze MID-ATTEMPT →
 * the user returned 4.5 min later → `hub.foregroundTrigger` → `reconnectNow()`: the
 * machine was RECONNECTING, so it only latched requestImmediate() + a single-flight
 * recover() that no-op'd (the frozen loop still owned `recovering`) → the thawed attempt
 * failed 34 ms later → `nextDelayMs()` checked the long-expired budget BEFORE the latch
 * → DEGRADED in the foreground; the loop quiesced by design → 13.5 min "Disconnected".
 *
 * These pins drive the REAL RelayTransport against MockZCode with the policy's clock
 * injected (the frozen interval is a clock jump; every other timer stays real).
 */
class ForegroundReconnectTest {

    private val passHash = "c3ludGhldGljLXBhc3NoYXNoLWtleQ=="
    private val sid = "d_syntheticdevice000001"

    private fun log() = ZLog(sink = {})

    private fun connect(t: RelayTransport, url: String) = runBlocking {
        t.connect(
            EndpointDescriptor(
                id = "ep-f34b", kind = EndpointKind.RELAY_ZAI, displayName = "F34B",
                wsUrl = url, workspaceKey = "synthetic-ws-one", workspacePath = "/synthetic/ws-one",
                deviceSid = sid, deviceMid = "mock-desktop-mid",
            ),
            CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
        )
    }

    private suspend fun awaitState(t: RelayTransport, target: ConnectionStateMachine.ConnectionState, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (t.states.value == target) return true
            delay(25)
        }
        return false
    }

    /** Records every state the machine visits (DEGRADED must never appear). */
    private fun recordStates(t: RelayTransport, scope: CoroutineScope): MutableList<ConnectionStateMachine.ConnectionState> {
        val seen = java.util.Collections.synchronizedList(mutableListOf<ConnectionStateMachine.ConnectionState>())
        scope.launch { t.states.collect { seen += it } }
        return seen
    }

    @Test
    fun `F-34-B - frozen mid-attempt then foregrounded - the thawed loop attempts with a fresh budget, never DEGRADED`() = runBlocking {
        var policyNow = 0L
        val policy = ReconnectPolicy(clock = { policyNow }, baseDelayMs = 50, maxDelayMs = 300, budgetMs = 120_000)
        val mock = MockZCode(passHash, sid)
        val url = mock.start()
        val t = RelayTransport(
            log = log(),
            // Every handshake stage times out BELOW the stall (the field's 10 s connect
            // timeout against a blocked network) — the frozen attempt must FAIL on thaw
            // and hand control back to the policy, exactly like the receipt's relay-13.
            config = RelayTransport.Config(
                connectTimeoutMs = 1_000, authTimeoutMs = 1_000, bridgeTimeoutMs = 1_000,
                channelInitTimeoutMs = 1_000, controlRequestTimeoutMs = 1_000,
                heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500,
                ackResendMs = 250, v4CallTimeoutMs = 5_000,
            ),
            reconnectPolicyOverride = policy,
        )
        val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(60_000) {
                connect(t, url)
                assertTrue(awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 10_000))
                val seen = recordStates(t, watch)
                // The network goes bad: every reconnect attempt now stalls past the auth timeout.
                mock.script.perFrameLatencyMs = 2_500
                t.abortConnectionForTest() // relay.lost
                delay(600) // the loop is MID-ATTEMPT (auth stalled) — this is where the freezer hit
                policyNow += 5 * 60_000L // five minutes frozen: the wall-clock budget burned
                mock.script.perFrameLatencyMs = 0 // back in the foreground: the network is fine
                t.reconnectNow() // hub.foregroundTrigger
                assertTrue(
                    awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 15_000),
                    "the foreground return did not reconnect; states=$seen",
                )
                assertFalse(
                    ConnectionStateMachine.ConnectionState.DEGRADED in seen,
                    "the thawed attempt hit the frozen-clock budget and DEGRADED in the foreground; states=$seen",
                )
            }
        } finally {
            watch.cancel()
            t.close("done")
            mock.stop()
        }
    }

    /** A jitter source that always picks the bound: the loop parks for the full delay. */
    private object MaxJitter : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextLong(until: Long): Long = until - 1
    }

    @Test
    fun `F-34-B - a loop parked in a long backoff delay is restarted by the foreground signal`() = runBlocking {
        val policy = ReconnectPolicy(random = MaxJitter, baseDelayMs = 20_000, maxDelayMs = 20_000, budgetMs = 120_000)
        val mock = MockZCode(passHash, sid)
        val url = mock.start()
        val t = RelayTransport(
            log = log(),
            config = RelayTransport.Config(
                connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
                channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
                heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500,
                ackResendMs = 250, v4CallTimeoutMs = 5_000,
            ),
            reconnectPolicyOverride = policy,
        )
        try {
            withTimeout(60_000) {
                connect(t, url)
                assertTrue(awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 10_000))
                t.abortConnectionForTest() // the loop's first backoff parks it for 20 s
                assertTrue(awaitState(t, ConnectionStateMachine.ConnectionState.RECONNECTING, 5_000))
                delay(500)
                val returnedAt = System.currentTimeMillis()
                t.reconnectNow() // the user is back
                assertTrue(
                    awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 8_000),
                    "the foreground signal waited out the parked 20 s backoff (single-flight no-op poke)",
                )
                assertTrue(System.currentTimeMillis() - returnedAt < 8_000)
            }
        } finally {
            t.close("done")
            mock.stop()
        }
    }
}
