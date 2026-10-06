package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After

/**
 * FIELD regression (2026-10-06, vc34 Samsung S24 — "conversation freezes, watchdog
 * loops"): sessions whose snapshot/delta logical frames exceed ~1 MiB arrive at the
 * wire (rpc.recv logs the 1,048,065 + 521,093-byte pairs) but NEVER become inbound
 * topic frames — the projection starves while the watchdog/fallback loop re-subscribes
 * every 30 s forever. This test drives a >1 MiB delta frame through the REAL
 * RelayTransport and asserts it reaches `inbound` as a decodable TopicFrameArrived.
 * RED = reproduced; the fix lands in whichever layer drops it.
 */
class RelayTransportLargeFrameTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 47 })
    private val sid = "d_LargeFrameSyn01"
    private var mock: MockZCode? = null

    @After
    fun tearDown() {
        mock?.stop()
    }

    private fun transport(): RelayTransport = RelayTransport(
        log = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = { e ->
            if (System.getenv("ZCODE_LARGE_DEBUG") != null) println("[zclog] ${e.level} ${e.event} ${e.details ?: ""}")
        }),
        config = RelayTransport.Config(
            connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
            channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
            heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
            v4CallTimeoutMs = 5_000,
        ),
    )

    private fun endpoint(url: String) = EndpointDescriptor(
        id = "ep-large-frame", kind = EndpointKind.RELAY_ZAI, displayName = "Large",
        wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
    )

    private fun bigDelta(sessionId: String, seq: Long, payloadChars: Int): MockZCode.DeltaFrame {
        val big = "x".repeat(payloadChars)
        val op = buildJsonObject {
            put("op", JsonPrimitive("row.delta"))
            put("rowId", JsonPrimitive(1))
            put("path", JsonPrimitive("text"))
            put("append", JsonPrimitive(big))
        }
        return MockZCode.DeltaFrame(seq, seq + 1L, JsonArray(listOf(op)))
    }

    @Test
    fun `a delta logical frame larger than 1 MiB reaches inbound as a topic frame`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }

                val received = java.util.Collections.synchronizedList(ArrayList<Long>())
                val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    t.inbound.collect { frame ->
                        if (frame is InboundFrame.TopicFrameArrived) {
                            received.add(frame.toSeq)
                        }
                    }
                }
                try {
                    // Push a delta whose logical frame is ~2.2 MB (well over 1 MiB).
                    val payloadChars = 2_200_000
                    m.pushDeltas("sess-large-1", listOf(bigDelta("sess-large-1", 1, payloadChars)))

                    withTimeout(20_000) {
                        while (received.isEmpty()) delay(50)
                    }
                    org.junit.Assert.assertTrue("expected the big delta to reach inbound", received.isNotEmpty())
                    org.junit.Assert.assertEquals(2L, received.first())
                } finally {
                    collector.cancel()
                }
            } finally {
                t.close("test done")
            }
        }
    }
}
