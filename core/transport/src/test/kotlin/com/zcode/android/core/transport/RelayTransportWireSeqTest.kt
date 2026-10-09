package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * FIELD-KILLER regression (2026-09-30, vc7 real device: task open → desktop pushes
 * `bridge-degraded rpc-transport-fault` → endless RECOVERING↔RECONNECTING).
 *
 * The desktop's AcknowledgedRelayProtocol keeps a FRESH assembler per bridge-open and
 * its own log named the real faults:
 *  - `remote.rpcFrame.physicalGap`  — recovery reused the previous connection's
 *    messageSeqs (counters only reset at CONNECTED, which never arrives when hello
 *    itself faults) → degraded ~400 ms into every recovery attempt.
 *  - `remote.rpcFrame.conflictingDuplicate` — the old mid-connection reset at CONNECTED
 *    re-zeroed the counters, so the first post-CONNECTED channel frame (subscribe)
 *    reused messageSeq=1 with different bytes → degraded ~15 s into the first
 *    connection (the exact field timing).
 *
 * Contract pinned here: messageSeq is strictly +1 monotonic WITHIN a connection and
 * RESTARTS at 1 on every NEW connection.
 */
class RelayTransportWireSeqTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 17 })
    private val sid = "d_WireSeqSynthetic01"
    private var mock: MockZCode? = null

    @org.junit.After
    fun tearDown() {
        mock?.stop()
    }

    @Test
    fun `message seq is monotonic within a connection and restarts at 1 on each new connection`() = runBlocking {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        mock = m
        val url = m.start()
        val t = RelayTransport(
            log = ZLog(sink = {}),
            config = RelayTransport.Config(
                connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
                channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
                heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
                v4CallTimeoutMs = 5_000,
            ),
        )
        val seqs = ArrayList<Long>()
        val collector = launch {
            m.events.collect { e ->
                if (e is MockZCode.ServerEvent.LogicalFrameFromClient) seqs.add(e.messageSeq)
            }
        }
        try {
            withTimeout(30_000) {
                t.connect(
                    EndpointDescriptor(
                        id = "ep-wireseq", kind = EndpointKind.RELAY_ZAI, displayName = "WireSeq",
                        wsUrl = url, workspaceKey = m.workspace.sessionId, workspacePath = "/synthetic/ws-one",
                        deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
                // A post-CONNECTED channel frame (the old reset made THIS one seq=1 again).
                t.subscribeSession(m.workspace.sessionId)
                delay(300)

                // Connection 1: strictly increasing, starts at 1, ≥ 3 frames (hello,
                // channel init, v4 initialize, subscribe…).
                assertTrue(seqs.firstOrNull() == 1L, "first frame must carry messageSeq=1, saw $seqs")
                val firstSegment = seqs.toList()
                assertStrictlyIncreasing(firstSegment)

                // Kill the connection → single-flight recovery opens a FRESH bridge.
                t.abortConnectionForTest()
                // Wait for the recovery cycle to COMPLETE (leave CONNECTED, then come
                // back) — do not race the state flow's stale values.
                withTimeout(30_000) {
                    while (t.states.value == ConnectionStateMachine.ConnectionState.CONNECTED) delay(25)
                    while (t.states.value != ConnectionStateMachine.ConnectionState.CONNECTED) delay(25)
                }
                withTimeout(10_000) {
                    while (seqs.size <= firstSegment.size) delay(25)
                }

                // Connection 2 must RESTART at 1 (desktop's fresh assembler), then be
                // strictly increasing again (recovery resubscribes → ≥ 1 frame).
                val restartIdx = firstSegment.size
                assertTrue(seqs.size > restartIdx, "no frames after recovery: $seqs")
                assertTrue(seqs[restartIdx] == 1L, "first frame of the new connection must restart at messageSeq=1, saw $seqs")
                // Assert +1 monotonicity PER CONNECTION SEGMENT (segments start at seq 1).
                var segStart = 0
                for (i in 1 until seqs.size) {
                    if (seqs[i] == 1L) {
                        assertStrictlyIncreasing(seqs.subList(segStart, i))
                        segStart = i
                    }
                }
                assertStrictlyIncreasing(seqs.subList(segStart, seqs.size))
            }
        } finally {
            collector.cancel()
            kotlinx.coroutines.runBlocking { t.close("done") }
        }
        Unit
    }

    private fun assertStrictlyIncreasing(seqs: List<Long>) {
        for (i in 1 until seqs.size) {
            assertTrue(
                seqs[i] == seqs[i - 1] + 1,
                "messageSeq must advance by exactly 1 within a connection (no mid-connection reset); saw ${seqs[i]} after ${seqs[i - 1]} in $seqs",
            )
        }
    }
}
