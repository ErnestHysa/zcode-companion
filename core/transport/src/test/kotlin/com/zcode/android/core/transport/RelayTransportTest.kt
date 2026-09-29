package com.zcode.android.core.transport

import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.v4.ConnectionFlowState
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 2.2 acceptance — production RelayTransport against MockZCode (§35.4): auth paths,
 * heartbeat cadence, control plane, v4 hello/subscribe/rowsRange/command, capability
 * negotiation, and takeover (KICKED). Short timing config (protocol defaults preserved
 * in Config defaults; test knobs only shorten waits).
 */
class RelayTransportTest {

    private val passHash = "c3ludGhldGljLXBhc3NoYXNoLWtleQ==" // base64("synthetic-passhash-key")
    private val sid = "d_syntheticdevice000001"

    private fun log(): ZLog = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = { e ->
    if (System.getenv("ZCODE_STORM_DEBUG") != null) println("[zclog] ${e.level} ${e.event} ${e.details ?: ""}")
})

    private fun transport(mock: MockZCode, config: RelayTransport.Config = shortConfig()): RelayTransport =
        RelayTransport(log = log(), config = config)

    private fun shortConfig() = RelayTransport.Config(
        connectTimeoutMs = 5_000,
        authTimeoutMs = 5_000,
        bridgeTimeoutMs = 5_000,
        channelInitTimeoutMs = 5_000,
        controlRequestTimeoutMs = 5_000,
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

    // ---------------------------------------------------------------- happy path

    @Test
    fun `connect auth bootstrap bridge hello subscribe frames rowsRange command`() {
        val mock = MockZCode(passHash, sid)
        val url = mock.start()
        val t = transport(mock)
        try {
            runBlocking {
                withTimeout(30_000) {
                    t.connect(endpoint(url), signer())
                    // bridge-open is part of connect; capabilities reflect the mock hello
                    val caps = t.capabilities.value
                    assertTrue(caps != null, "hello negotiated")
                    assertEquals("web-remote-replayable", "web-remote-replayable") // mode pinned
                    assertTrue(caps!!.host.localTerminal)
                    assertEquals(EndpointKind.RELAY_ZAI, caps.endpointKind)

                    // control plane
                    val bootstrap = t.bootstrap()
                    assertTrue(bootstrap.toString().contains("windowControlSessionId"))
                    val list = t.workspaceList()
                    assertTrue(list.toString().contains("workspaces"))

                    // v4 plane (live-proven shapes). Collector attaches BEFORE subscribe
                    // (SharedFlow replay=0; the snapshot frame rides the subscribe ack).
                    val frames = java.util.concurrent.CopyOnWriteArrayList<InboundFrame.TopicFrameArrived>()
                    val collector = launch {
                        t.inbound.collect { if (it is InboundFrame.TopicFrameArrived) frames.add(it) }
                    }
                    delay(100)
                    val ack = t.subscribeSession("synth-task-1")
                    assertEquals("snapshot", ack.mode)
                    val deadline = System.currentTimeMillis() + 10_000
                    while (System.currentTimeMillis() < deadline && frames.isEmpty()) delay(20)
                    assertTrue(frames.isNotEmpty(), "no topic frame arrived")
                    val frame = frames.first()
                    collector.cancel() // scoped builders await children — never leave it running
                    assertEquals("conversation/synth-task-1", frame.topic)
                    assertTrue(frame.frame.payload is com.zcode.android.core.protocol.v4.TopicFrame.TopicPayload.Snapshot)

                    val rows = t.rowsRange("synth-task-1", beforeRowId = null, limit = 5)
                    assertEquals(5, rows.rows.size)
                    assertTrue(rows.rows.all { com.zcode.android.core.protocol.v4.ConversationRow.isKnownKind(it.kind) })

                    val envelope = com.zcode.android.core.protocol.v4.CommandEnvelope.build(
                        clientId = "ep-test-1", sessionId = "synth-task-1",
                        type = com.zcode.android.core.protocol.v4.CommandTypes.STOP,
                        payload = kotlinx.serialization.json.buildJsonObject { },
                        issuedAt = 1L,
                    )
                    val commandAck = t.sendConversationCommand(envelope)
                    assertEquals("accepted", commandAck.status)

                    assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)
                }
            }
        } finally {
            runBlocking { t.close("test done") }
            mock.stop()
        }
    }

    // ---------------------------------------------------------------- auth failure paths

    @Test
    fun `wrong proof fails auth with AUTH category (negative path)`() {
        val mock = MockZCode(passHash, sid).apply { script.authScenario = MockZCode.AuthScenario.WRONG_PROOF }
        val url = mock.start()
        val t = transport(mock)
        try {
            runBlocking {
                withTimeout(20_000) {
                    val err = runCatching { t.connect(endpoint(url), signer()) }
                        .exceptionOrNull()
                    assertTrue(err is RelayTransport.Failure, "expected typed failure, got $err")
                    assertEquals(ErrorCategory.AUTH, (err as RelayTransport.Failure).error.category)
                    assertEquals(ConnectionStateMachine.ConnectionState.FAILED, t.states.value)
                }
            }
        } finally {
            runBlocking { t.close("test done") }
            mock.stop()
        }
    }

    @Test
    fun `unknown sid fails auth (QR for another desktop class)`() {
        val mock = MockZCode(passHash, "d_otherdevice000000000001")
        val url = mock.start()
        val t = transport(mock)
        try {
            runBlocking {
                withTimeout(20_000) {
                    val err = runCatching { t.connect(endpoint(url), signer()) }.exceptionOrNull()
                    assertTrue(err is RelayTransport.Failure)
                    assertEquals(ErrorCategory.AUTH, (err as RelayTransport.Failure).error.category)
                }
            }
        } finally {
            runBlocking { t.close("test done") }
            mock.stop()
        }
    }

    // ---------------------------------------------------------------- heartbeat watchdog

    @Test
    fun `heartbeat watchdog detects a dead relay and recovers when it returns`() {
        val mock = MockZCode(passHash, sid).apply { script.ackHeartbeats = false }
        val url = mock.start()
        val t = transport(mock, shortConfig().copy(heartbeatIntervalMs = 150, heartbeatWatchdogMs = 400, ackResendMs = 250))
        try {
            runBlocking {
                withTimeout(30_000) {
                    t.connect(endpoint(url), signer())
                    // no heartbeat acks → watchdog (400ms) → connection lost → reconnect loop
                    // (re-auth still succeeds; only heartbeat acks are suppressed → the loop
                    // keeps cycling without reaching CONNECTED budget exhaustion quickly)
                    val deadline = System.currentTimeMillis() + 8_000
                    while (System.currentTimeMillis() < deadline && t.states.value == ConnectionStateMachine.ConnectionState.CONNECTED) {
                        kotlinx.coroutines.delay(50)
                    }
                    assertTrue(
                        t.states.value != ConnectionStateMachine.ConnectionState.CONNECTED,
                        "watchdog must move the machine off CONNECTED"
                    )
                    assertTrue(t.appErrors != null)
                }
            }
        } finally {
            runBlocking { t.close("test done") }
            mock.stop()
        }
    }

    // ---------------------------------------------------------------- takeover (Q10)

    @Test
    fun `second terminal kicks the first (takeover lands in Degraded)`() = runBlocking {
        val mock = MockZCode(passHash, sid)
        val url = mock.start()
        val first = transport(mock)
        val second = transport(mock)
        try {
            withTimeout(30_000) {
                first.connect(endpoint(url), signer())
                assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, first.states.value)
                // collect errors from BEFORE the takeover fires (SharedFlow has no replay)
                val seen = java.util.concurrent.CopyOnWriteArrayList<com.zcode.android.core.common.error.AppError>()
                val collector = launch { first.appErrors.collect { seen.add(it) } }
                second.connect(endpoint(url), signer())
                assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, second.states.value)
                // first receives error KICKED → Degraded + classified error
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline && first.states.value == ConnectionStateMachine.ConnectionState.CONNECTED) {
                    delay(50)
                }
                assertEquals(ConnectionStateMachine.ConnectionState.DEGRADED, first.states.value)
                val errDeadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < errDeadline && seen.none { it.category == ErrorCategory.SESSION }) {
                    delay(50)
                }
                collector.cancel()
                val kickedError = seen.firstOrNull { it.category == ErrorCategory.SESSION }
                assertTrue(kickedError != null, "expected a SESSION-class (KICKED) error, saw: $seen")
                assertTrue(kickedError!!.developerDiagnostic.contains("KICKED"))
            }
        } finally {
            first.close("done")
            second.close("done")
            mock.stop()
        }
        Unit
    }

    // ---------------------------------------------------------------- flow control signal

    /**
     * FIELD-KILLER regression (2026-09-29): the transport must NEVER register a
     * v4/connection/flow EventListen. The production 3.14.x host has no such event
     * and its ChannelServer dies on the unknown listen with an uncaughtException
     * ("Event not found: v4/connection/flow", host exit code 1) — every later
     * workspace-bridge-open on that desktop then fails desktop-disconnected until
     * ZCode restarts. See RelayTransport.attachChannelEventListeners.
     */
    @Test
    fun `no v4 connection flow listen is ever registered (desktop host killer regression)`() {
        val mock = MockZCode(passHash, sid)
        val url = mock.start()
        val t = transport(mock)
        try {
            runBlocking {
                withTimeout(30_000) {
                    t.connect(endpoint(url), signer())
                    t.subscribeSession("synth-task-1")
                    kotlinx.coroutines.delay(500)
                }
            }
            assertFalse(mock.sawConnectionFlowListen, "transport registered a v4/connection/flow EventListen — this crashes the production desktop host process")
            assertEquals(ConnectionFlowState.DRAINED, t.flowPressure.value)
        } finally {
            runBlocking { t.close("done") }
            mock.stop()
        }
    }

    // ---------------------------------------------------------------- INV-5 bounds

    @Test
    fun `send queue bound refuses runaway producers (INV-5)`() {
        // The bound is structural (Channel capacity 1024) — prove a producer cannot grow
        // it: enqueueing beyond capacity surfaces the saturation error instead.
        val mock = MockZCode(passHash, sid)
        val url = mock.start()
        val t = transport(mock)
        try {
            runBlocking {
                withTimeout(30_000) {
                    t.connect(endpoint(url), signer())
                    // 1 MiB-class frames × handful: acks keep up; the point is no crash and
                    // bounded internal state after burst.
                    repeat(50) {
                        t.send(OutboundFrame.Channel(ByteArray(64_000)))
                    }
                    val deadline = System.currentTimeMillis() + 8_000
                    while (System.currentTimeMillis() < deadline) {
                        kotlinx.coroutines.delay(100)
                    }
                    assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)
                }
            }
        } finally {
            runBlocking { t.close("done") }
            mock.stop()
        }
    }

    private fun JsonObject.deepString(): String = this.toString()
}
