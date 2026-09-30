package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * FIELD-KILLER #3 regression (2026-09-30, vc8 real device): production delivers
 * onDynamicConversationFrame events with the arg = the TopicWireFrame ITSELF
 * ({wireVersion, …, frame: <payload>}); the old listener pre-stripped `.frame` (the
 * PAYLOAD) and decoded THAT as a TopicWireFrame → wireVersion=null →
 * "wire version mismatch" → the healthy connection was torn down ~80 ms after every
 * real snapshot arrived (relay-5/10/12 in the vc8 report: subscribe ok → 178713 bytes
 * → wire version mismatch → RECONNECTING). This is why no conversation EVER loaded.
 *
 * Pinned here over the full pipeline with the production shape: the snapshot must land
 * as TopicFrameArrived and the connection must stay CONNECTED.
 */
class RelayTransportTopicShapeTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 21 })
    private val sid = "d_TopicShapeSynthetic1"

    @Test
    fun `production-shaped topic event decodes and keeps the connection alive`() = runBlocking {
        val mock = MockZCode(passHash = passHash, expectedSid = sid).apply {
            script.topicEventDirect = true // production shape
        }
        val url = mock.start()
        val t = RelayTransport(
            log = ZLog(sink = {}),
            config = RelayTransport.Config(
                connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
                channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
                heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
                v4CallTimeoutMs = 5_000,
            ),
        )
        var snapshots = 0
        val collector = launch {
            t.inbound.collect {
                if (it is InboundFrame.TopicFrameArrived &&
                    it.frame.payload is com.zcode.android.core.protocol.v4.TopicFrame.TopicPayload.Snapshot
                ) snapshots++
            }
        }
        try {
            withTimeout(30_000) {
                t.connect(
                    EndpointDescriptor(
                        id = "ep-topic-shape", kind = EndpointKind.RELAY_ZAI, displayName = "TopicShape",
                        wsUrl = url, workspaceKey = mock.workspace.sessionId, workspacePath = "/synthetic/ws-one",
                        deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
                t.subscribeSession(mock.workspace.sessionId)
                withTimeout(5_000) {
                    while (snapshots == 0) delay(25)
                }
                // The decode fault must NOT have torn the connection down.
                delay(500)
                assertEquals(
                    ConnectionStateMachine.ConnectionState.CONNECTED,
                    t.states.value,
                    "a decoded snapshot must not degrade the connection",
                )
                assertTrue(snapshots >= 1, "snapshot must arrive through the production event shape")
            }
        } finally {
            collector.cancel()
            kotlinx.coroutines.runBlocking { t.close("done") }
            mock.stop()
        }
        Unit
    }
}
