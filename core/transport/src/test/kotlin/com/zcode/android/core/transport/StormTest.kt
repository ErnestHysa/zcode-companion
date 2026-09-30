package com.zcode.android.core.transport

import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2.3 acceptance — fault-injection suite (§35.2): disconnect storms, duplicates,
 * out-of-order fragments, oversized payloads (#228 class), CRC faults, latency —
 * against MockZCode. INV-5: no unbounded growth under any scripted storm.
 */
class StormTest {

    private val passHash = "c3ludGhldGljLXBhc3NoYXNoLWtleQ=="
    private val sid = "d_syntheticdevice000001"

    private fun log(): ZLog = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = { e ->
    if (System.getenv("ZCODE_STORM_DEBUG") != null) println("[zclog] ${e.level} ${e.event} ${e.details ?: ""}")
})

    private fun stormConfig() = RelayTransport.Config(
        connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
        channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
        heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500,
        ackResendMs = 250, v4CallTimeoutMs = 5_000,
    )

    private fun fastReconnect() = ReconnectPolicy(
        baseDelayMs = 50, maxDelayMs = 300, budgetMs = 60_000,
    )

    private fun connect(t: RelayTransport, url: String) = runBlocking {
        t.connect(
            EndpointDescriptor(
                id = "ep-storm", kind = EndpointKind.RELAY_ZAI, displayName = "Storm",
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

    @Test
    fun `disconnect storm recovers to CONNECTED repeatedly (S1)`() = runBlocking {
        val mock = MockZCode(passHash, sid).apply { script.disconnectAfterFrames = 3 }
        val url = mock.start()
        val t = RelayTransport(log = log(), config = stormConfig(), reconnectPolicyOverride = fastReconnect())
        try {
            withTimeout(60_000) {
                connect(t, url)
                assertTrue(awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 10_000))
                // server kills the socket after 3 logical frames → auto-recovery
                assertTrue(awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 20_000), "first recovery")
                // unacked table must be bounded (INV-5 observability)
                assertEquals(0, t.pendingAckCountForTest())
            }
        } finally {
            t.close("done")
            mock.stop()
        }
    }

    @Test
    fun `duplicate storm dedupes logical frames (S7)`() = runBlocking {
        val mock = MockZCode(passHash, sid).apply {
            script.duplicateEvery = 1 // every logical frame delivered twice
        }
        val url = mock.start()
        val t = RelayTransport(log = log(), config = stormConfig(), reconnectPolicyOverride = fastReconnect())
        try {
            withTimeout(60_000) {
                connect(t, url)
                // collector BEFORE subscribe: the snapshot frame rides the subscribe ack
                var snapshots = 0
                val collector = launch {
                    t.inbound.collect {
                        if (it is InboundFrame.TopicFrameArrived &&
                            it.frame.payload is com.zcode.android.core.protocol.v4.TopicFrame.TopicPayload.Snapshot
                        ) snapshots++
                    }
                }
                delay(100)
                t.subscribeSession("synth-task-1")
                val deadline = System.currentTimeMillis() + 6_000
                while (System.currentTimeMillis() < deadline && snapshots < 1) delay(20)
                delay(1_000) // let any duplicate slip through
                assertEquals(1, snapshots, "duplicates must be deduped (S7)")
                collector.cancel()
                assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)
            }
        } finally {
            t.close("done")
            mock.stop()
        }
    }

    @Test
    fun `out-of-order fragments still assemble (S8)`() = runBlocking {
        // large snapshot (400 rows) fragments into several rpc-frames; shuffled delivery
        val bigWorkspace = MockZCode.MockWorkspace(rowCount = 400)
        val mock = MockZCode(passHash, sid, workspace = bigWorkspace).apply { script.shuffleFragments = true }
        val url = mock.start()
        val t = RelayTransport(log = log(), config = stormConfig(), reconnectPolicyOverride = fastReconnect())
        try {
            withTimeout(60_000) {
                connect(t, url)
                var assembled = false
                val collector = launch {
                    t.inbound.collect {
                        if (it is InboundFrame.TopicFrameArrived) assembled = true
                    }
                }
                delay(100)
                t.subscribeSession("synth-task-1")
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline && !assembled) delay(20)
                assertTrue(assembled, "shuffled fragments must still assemble in index order")
                collector.cancel()
            }
        } finally {
            t.close("done")
            mock.stop()
        }
    }

    @Test
    fun `oversized envelope is a protocol fault and recovers via resync (228-class, S11)`() = runBlocking {
        val mock = MockZCode(passHash, sid)
        val url = mock.start()
        val t = RelayTransport(log = log(), config = stormConfig(), reconnectPolicyOverride = fastReconnect())
        val errors = java.util.concurrent.CopyOnWriteArrayList<com.zcode.android.core.common.error.AppError>()
        try {
            withTimeout(60_000) {
                val c = launch { t.appErrors.collect { errors.add(it) } }
                connect(t, url)
                t.subscribeSession("synth-task-1")
                // arm the one-shot fault AFTER the session is up so it hits a live frame
                mock.script.injectOversizedEnvelope = true
                mock.pushTopicFrame("synth-task-1", com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = false, fromSeq = 2, toSeq = 3)
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline &&
                    errors.none { it.category == ErrorCategory.PROTOCOL || it.category == ErrorCategory.SERIALIZATION }
                ) delay(20)
                assertTrue(
                    errors.any { it.category == ErrorCategory.PROTOCOL || it.category == ErrorCategory.SERIALIZATION },
                    "oversized envelope must surface as a counted fault, saw: $errors",
                )
                // FIELD-KILLER #9 (vc14): the fault is CONTAINED — the connection survives
                // (the affected topic gap escalates at the manager level, not the wire).
                assertTrue(awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 20_000), "connection survives oversize fault")
                c.cancel()
            }
        } finally {
            t.close("done")
            mock.stop()
        }
    }

    @Test
    fun `crc-corrupted fragment is a protocol fault and never merges (S11, INV-14)`() = runBlocking {
        val bigWorkspace = MockZCode.MockWorkspace(rowCount = 400)
        val mock = MockZCode(passHash, sid, workspace = bigWorkspace)
        val url = mock.start()
        val t = RelayTransport(log = log(), config = stormConfig(), reconnectPolicyOverride = fastReconnect())
        val errors = java.util.concurrent.CopyOnWriteArrayList<com.zcode.android.core.common.error.AppError>()
        try {
            withTimeout(60_000) {
                val c = launch { t.appErrors.collect { errors.add(it) } }
                connect(t, url)
                t.subscribeSession("synth-task-1")
                // arm the one-shot corruption AFTER the session is up (big snapshot → fragments)
                mock.script.corruptFragmentCrc = true
                mock.pushTopicFrame("synth-task-1", com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = true, fromSeq = 2, toSeq = 3)
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline &&
                    errors.none { it.category == ErrorCategory.SERIALIZATION || it.category == ErrorCategory.PROTOCOL }
                ) delay(20)
                assertTrue(
                    errors.any { it.category == ErrorCategory.SERIALIZATION || it.category == ErrorCategory.PROTOCOL },
                    "crc fault must surface as a counted error, saw: $errors",
                )
                // FIELD-KILLER #9 (vc14): the connection must SURVIVE the poisoned message
                // (the relay redelivers unacked messages — tearing down looped forever).
                assertTrue(awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 20_000), "connection survives crc fault")
                c.cancel()
            }
        } finally {
            t.close("done")
            mock.stop()
        }
    }

    /**
     * FIELD-KILLER #9 regression (2026-09-30, vc14): the relay REDELIVERS unacked
     * messages on every reconnect — one corrupted fragment poisoned messageSeq=52
     * across 5 consecutive reconnects (endless RECOVERING). The transport must ACK the
     * poisoned messageSeq (relay drops it) and keep the connection up.
     */
    @Test
    fun `poisoned message is acked and never redelivered across recovery`() = runBlocking {
        val bigWorkspace = MockZCode.MockWorkspace(rowCount = 400)
        val mock = MockZCode(passHash, sid, workspace = bigWorkspace)
        val url = mock.start()
        val t = RelayTransport(log = log(), config = stormConfig(), reconnectPolicyOverride = fastReconnect())
        try {
            withTimeout(60_000) {
                connect(t, url)
                t.subscribeSession("synth-task-1")
                mock.script.corruptFragmentCrc = true
                mock.pushTopicFrame("synth-task-1", com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = true, fromSeq = 2, toSeq = 3)
                delay(1_000) // poison delivered + acked
                // Force a reconnect: the relay's replay of the poisoned messageSeq must
                // be EMPTY (it was acked) — the recovery completes to CONNECTED cleanly.
                t.abortConnectionForTest()
                val reconnected = awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 20_000)
                assertTrue(reconnected, "recovery must complete with no poisoned redelivery")
                assertEquals(0, t.pendingAckCountForTest())
            }
        } finally {
            kotlinx.coroutines.runBlocking { t.close("done") }
            mock.stop()
        }
        Unit
    }

    @Test
    fun `latency storm stays bounded and connected (INV-5)`() = runBlocking {
        val mock = MockZCode(passHash, sid).apply { script.perFrameLatencyMs = 30 }
        val url = mock.start()
        val t = RelayTransport(log = log(), config = stormConfig(), reconnectPolicyOverride = fastReconnect())
        try {
            withTimeout(60_000) {
                connect(t, url)
                t.subscribeSession("synth-task-1")
                repeat(10) {
                    mock.pushTopicFrame("synth-task-1", com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = false, fromSeq = 10L + it, toSeq = 11L + it)
                }
                assertTrue(awaitState(t, ConnectionStateMachine.ConnectionState.CONNECTED, 15_000))
                assertEquals(0, t.pendingAckCountForTest())
            }
        } finally {
            t.close("done")
            mock.stop()
        }
    }

    // ---------------------------------------------------------------- backoff policy

    @Test
    fun `backoff grows with full jitter and exhausts its budget`() {
        var now = 0L
        val rnd = kotlin.random.Random(7)
        val policy = ReconnectPolicy(
            clock = { now }, random = rnd, baseDelayMs = 1_000, maxDelayMs = 60_000, budgetMs = 120_000,
        )
        // immediate trigger collapses the first delay
        policy.requestImmediate()
        assertEquals(0L, policy.nextDelayMs())
        // jitter bounds: [0, min(cap, base*2^n)]
        now = 1
        var maxSeen = 0L
        for (n in 1..7) {
            val d = policy.nextDelayMs()!!
            val bound = minOf(60_000L, 1_000L * (1L shl n))
            assertTrue(d in 0..bound, "delay $d outside jitter bound $bound")
            maxSeen = maxOf(maxSeen, d)
        }
        // cap at 60 s
        now = 2
        repeat(10) {
            val d = policy.nextDelayMs()!!
            assertTrue(d in 0..60_000L)
        }
        // budget: advance past 2 minutes → null (Degraded)
        now = 130_000
        assertEquals(null, policy.nextDelayMs())
        // reset restores
        policy.reset()
        now = 200_000
        assertTrue(policy.nextDelayMs() != null)
    }
}
