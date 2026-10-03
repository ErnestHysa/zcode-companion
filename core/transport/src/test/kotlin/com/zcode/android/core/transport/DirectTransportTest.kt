package com.zcode.android.core.transport

import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Phase 10.1 acceptance — DirectTransport against MockZCode's DIRECT surface
 * (docs/protocol/phase10-direct.md): server-info preflight + token/cookie auth,
 * BINARY SocketProtocol frames, Initialize, v4 hello/subscribe/rowsRange/command,
 * the direct task plane (`zcode-task.listTasks`), auth-model separation, candidate
 * failover (§11.3), LNP classification (§25.3.7) and the TOFU pin option (fail-closed).
 * CI runs exclusively against the mock (INV-15).
 */
class DirectTransportTest {

    private val token = "direct-smoke-token-abcdef"
    private val wsPath = "/synthetic/ws-one"
    private val sessionId = "synth-task-1"

    private fun log(): ZLog = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = { e ->
        if (System.getenv("ZCODE_DIRECT_DEBUG") != null) println("[zclog] ${e.level} ${e.event} ${e.details ?: ""}")
    })

    private fun shortConfig() = DirectTransport.Config(
        connectTimeoutMs = 4_000,
        authTimeoutMs = 4_000,
        channelInitTimeoutMs = 4_000,
        v4CallTimeoutMs = 4_000,
        bridgeHandshakeTimeoutMs = 4_000,
        controlRequestTimeoutMs = 4_000,
    )

    private fun transport(mock: MockZCode, config: DirectTransport.Config = shortConfig(), tofu: TofuPinPolicy? = null) =
        DirectTransport(
            tokenProvider = { token },
            wsFactory = OkHttpWsFactory(),
            log = log(),
            config = config,
            tofuPolicy = tofu,
        )

    private fun endpoint(url: String, candidates: List<String> = emptyList()) = EndpointDescriptor(
        id = "ep-direct-1",
        kind = EndpointKind.DIRECT_SERVER,
        displayName = "Test Server",
        wsUrl = url,
        workspacePath = wsPath,
        candidates = candidates,
    )

    private fun taskJson(taskId: String, title: String, status: String = "running") = buildJsonObject {
        put("taskId", JsonPrimitive(taskId))
        put("title", JsonPrimitive(title))
        put("status", JsonPrimitive(status))
        put("workspacePath", JsonPrimitive(wsPath))
        put("createdAt", JsonPrimitive(1_700_000_000_000.0))
        put("updatedAt", JsonPrimitive(1_700_000_300_000.0))
        put("mode", JsonPrimitive("build"))
        put("futureUnknownField", JsonPrimitive("ignored (INV-8)"))
    }

    // ---------------------------------------------------------------- happy path (10.1 E2E)

    @Test
    fun `direct connect - server-info cookie binary frames hello subscribe rowsRange listTasks`() {
        val mock = MockZCode(passHash = "unused-direct", expectedSid = "unused", mode = MockZCode.Mode.DIRECT, serverToken = token)
        mock.script.directTasks = listOf(taskJson("t-1", "Fix auth token refresh"))
        val url = mock.start()
        val t = transport(mock)
        try {
            runBlocking {
                withTimeout(30_000) {
                    t.connect(endpoint(url)) { _, _, _ -> "" }
                    assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)
                    // Capability discovery: hello capabilities + server-info version (§43.2-10.1)
                    val caps = t.capabilities.value
                    assertEquals(EndpointKind.DIRECT_SERVER, caps?.endpointKind)
                    assertEquals("3.14.3", caps?.desktopAppVersion)
                    // §11.6 workspace-list source: server-info workspaces decoded
                    assertEquals(1, t.serverInfo?.workspaces?.size)
                    assertEquals(wsPath, t.serverInfo?.workspaces?.first()?.path)

                    // Subscribe → INITIAL snapshot arrives over binary frames. The
                    // collector attaches FIRST (UNDISPATCHED runs it to its suspension
                    // point): inbound is a SharedFlow with no replay, so a snapshot that
                    // raced the collect would be dropped (production attaches collectors
                    // before subscribing — the test must mirror that discipline).
                    val frameDeferred = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        t.inbound.filterIsInstance<InboundFrame.TopicFrameArrived>().first()
                    }
                    val ack = t.subscribeSession(sessionId, workspacePath = wsPath)
                    assertEquals("epoch-mock-1", ack.logEpoch)
                    val frame = withTimeout(15_000) { frameDeferred.await() }
                    assertEquals("conversation/$sessionId", frame.topic)

                    // rowsRange (read-only, re-send-safe class)
                    val rows = t.rowsRange(sessionId, beforeRowId = null, limit = 50)
                    assertTrue(rows.rows.isNotEmpty(), "rowsRange must return the mock tail window")

                    // The direct task plane (phase10-direct.md §6): zcode-task.listTasks
                    val tasks = t.listTasks(wsPath)
                    assertEquals(1, tasks.size)
                    assertEquals("t-1", tasks[0].taskId)
                    assertEquals("Fix auth token refresh", tasks[0].title)
                    assertEquals("running", tasks[0].statusWire)
                }
            }
        } finally {
            runBlocking { t.close("test end") }
            mock.stop()
        }
    }

    // ---------------------------------------------------------------- auth-model separation (§11.4)

    @Test
    fun `wrong token is AUTH-class and never loops candidates`() {
        val mock = MockZCode(passHash = "unused-direct", expectedSid = "unused", mode = MockZCode.Mode.DIRECT, serverToken = token)
        val url = mock.start()
        val goodCandidate = mock.url // same server — the point: even a valid-looking candidate must not be tried
        val t = DirectTransport(
            tokenProvider = { "definitely-wrong" },
            wsFactory = OkHttpWsFactory(),
            log = log(),
            config = shortConfig(),
        )
        try {
            runBlocking {
                withTimeout(20_000) {
                    val err = runCatching { t.connect(endpoint(url, candidates = listOf(goodCandidate))) { _, _, _ -> "" } }
                        .exceptionOrNull()
                    assertTrue(err is TransportFailure, "expected TransportFailure, got $err")
                    assertEquals(ErrorCategory.AUTH, (err as TransportFailure).error.category)
                }
            }
        } finally {
            runBlocking { runCatching { t.close("test end") } }
            mock.stop()
        }
    }

    @Test
    fun `missing token on a protected server is AUTH-class`() {
        val mock = MockZCode(passHash = "unused-direct", expectedSid = "unused", mode = MockZCode.Mode.DIRECT, serverToken = token)
        val url = mock.start()
        val t = DirectTransport(tokenProvider = { "" }, wsFactory = OkHttpWsFactory(), log = log(), config = shortConfig())
        try {
            runBlocking {
                withTimeout(20_000) {
                    val err = runCatching { t.connect(endpoint(url)) { _, _, _ -> "" } }.exceptionOrNull()
                    assertTrue(err is TransportFailure && (err as TransportFailure).error.category == ErrorCategory.AUTH, "got $err")
                }
            }
        } finally {
            runBlocking { runCatching { t.close("test end") } }
            mock.stop()
        }
    }

    @Test
    fun `relay endpoint kind is refused (auth models never conflated)`() {
        val t = transport(MockZCode(passHash = "x", expectedSid = "y"))
        runBlocking {
            val err = runCatching {
                t.connect(
                    EndpointDescriptor(
                        id = "ep", kind = EndpointKind.RELAY_ZAI, displayName = "r", wsUrl = "wss://relay/ws", deviceSid = "d_x",
                    ),
                ) { _, _, _ -> "" }
            }.exceptionOrNull()
            assertTrue(err is IllegalStateException, "kind guard must refuse relay descriptors, got $err")
        }
    }

    // ---------------------------------------------------------------- failover (10.2)

    @Test
    fun `failover matrix - dead primary advances to the working candidate`() {
        val mock = MockZCode(passHash = "unused-direct", expectedSid = "unused", mode = MockZCode.Mode.DIRECT, serverToken = token)
        mock.script.directTasks = listOf(taskJson("t-2", "served via candidate"))
        val goodUrl = mock.start()
        val deadUrl = goodUrl.replace(Regex(":\\d+/"), ":1/ws") // port 1 — nothing listens (LAN-dead shape)
        val t = transport(mock)
        try {
            runBlocking {
                withTimeout(40_000) {
                    t.connect(endpoint(deadUrl, candidates = listOf(goodUrl))) { _, _, _ -> "" }
                    assertEquals(ConnectionStateMachine.ConnectionState.CONNECTED, t.states.value)
                    val tasks = t.listTasks(wsPath)
                    assertEquals("served via candidate", tasks.single().title)
                }
            }
        } finally {
            runBlocking { t.close("test end") }
            mock.stop()
        }
    }

    @Test
    fun `failover matrix - all candidates dead is a transport-class failure`() {
        val dead1 = "ws://127.0.0.1:1/ws"
        val dead2 = "ws://127.0.0.1:2/ws"
        val t = transport(MockZCode(passHash = "x", expectedSid = "y"), config = shortConfig())
        runBlocking {
            withTimeout(40_000) {
                val err = runCatching { t.connect(endpoint(dead1, candidates = listOf(dead2))) { _, _, _ -> "" } }.exceptionOrNull()
                assertTrue(err is TransportFailure, "got $err")
                val category = (err as TransportFailure).error.category
                assertTrue(
                    category == ErrorCategory.TRANSPORT || category == ErrorCategory.NETWORK,
                    "all-dead failover must land transport/network, got $category",
                )
            }
        }
    }

    @Test
    fun `failover matrix - LNP class advances to the next candidate`() {
        // §25.3.7: EPERM on the LAN candidate must NOT abort the loop — the tailnet
        // candidate (here: the live mock) must still be tried.
        val mock = MockZCode(passHash = "unused-direct", expectedSid = "unused", mode = MockZCode.Mode.DIRECT, serverToken = token)
        val goodUrl = mock.start()
        // A URL whose DNS resolves to a blocked shape is environment-dependent; instead
        // drive the CLASSIFIER directly for the LNP leg and the matrix above for the
        // ordering leg (deterministic, no network shaping needed).
        val classified = DirectServerProbe.classifyProbeFailure(java.net.SocketException("EPERM (operation not permitted)"))
        assertTrue(classified is ProbeResult.LocalNetworkBlocked, "EPERM must classify as local-network-blocked")
        val dns = DirectServerProbe.classifyProbeFailure(java.net.UnknownHostException("nas.local"))
        assertTrue(dns is ProbeResult.Unreachable && dns.reason.startsWith("DNS"), "got $dns")
        val okhttpDns = DirectServerProbe.classifyProbeFailure(java.io.IOException("Unable to resolve host \"nas.local\": No address associated with hostname"))
        assertTrue(okhttpDns is ProbeResult.Unreachable && okhttpDns.reason.startsWith("DNS"), "got $okhttpDns")
        mock.stop()
    }

    // ---------------------------------------------------------------- TOFU (§11.5, fail-closed)

    private class RecordingTofu(initial: Map<String, String> = emptyMap()) : TofuPinPolicy {
        val pins = initial.toMutableMap()
        override fun pinnedFingerprint(endpointId: String): String? = pins[endpointId]
        override fun recordFingerprint(endpointId: String, fingerprint: String) {
            pins[endpointId] = fingerprint
        }
    }

    /** Fake socket that opens instantly, emits a scripted TLS fingerprint, then stays silent. */
    private class FingerprintWs(private val fingerprint: String?) : Ws {
        override suspend fun connect(url: String, timeoutMs: Long) = Unit
        override suspend fun connect(url: String, timeoutMs: Long, headers: Map<String, String>) = Unit
        override fun cancel() {}
        override fun sendText(text: String): Boolean = true
        override fun sendBinary(bytes: ByteArray): Boolean = true
        override fun close(code: Int, reason: String) {}
        override val incoming = kotlinx.coroutines.flow.emptyFlow<String>()
        override val incomingBinary = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
        override val closed = MutableSharedFlow<WsClosed>()
        override val tlsPeerFingerprint = kotlinx.coroutines.flow.flowOf(fingerprint ?: "")
    }

    @Test
    fun `TOFU first use records the fingerprint`() {
        val mock = MockZCode(passHash = "unused-direct", expectedSid = "unused", mode = MockZCode.Mode.DIRECT, serverToken = token)
        val url = mock.start()
        val policy = RecordingTofu()
        val t = DirectTransport(
            tokenProvider = { token },
            wsFactory = { FingerprintWs("aa11bb22") },
            log = log(),
            config = shortConfig(),
            tofuPolicy = policy,
        )
        try {
            runBlocking {
                // The channel handshake can never complete against the silent fake — the
                // timeout is EXPECTED; the assertion is the first-use recording (§11.5).
                runCatching { withTimeout(15_000) { t.connect(endpoint(url)) { _, _, _ -> "" } } }
                assertEquals("aa11bb22", policy.pins["ep-direct-1"])
            }
        } finally {
            mock.stop()
        }
    }

    @Test
    fun `TOFU mismatch FAILS CLOSED before any plane traffic`() {
        val mock = MockZCode(passHash = "unused-direct", expectedSid = "unused", mode = MockZCode.Mode.DIRECT, serverToken = token)
        val url = mock.start()
        val policy = RecordingTofu(mapOf("ep-direct-1" to "expected0001"))
        val t = DirectTransport(
            tokenProvider = { token },
            wsFactory = { FingerprintWs("evil00000002") },
            log = log(),
            config = shortConfig(),
            tofuPolicy = policy,
        )
        try {
        runBlocking {
            withTimeout(15_000) {
                val err = runCatching { t.connect(endpoint(url)) { _, _, _ -> "" } }.exceptionOrNull()
                assertTrue(err is TransportFailure, "got $err")
                val error = (err as TransportFailure).error
                assertTrue(error.developerDiagnostic.contains("tofu-pin-mismatch"), error.developerDiagnostic)
                assertEquals(ErrorCategory.TRANSPORT, error.category)
                // Fail-closed: the pin was NOT overwritten by the attacker's cert.
                assertEquals("expected0001", policy.pins["ep-direct-1"])
            }
        }
        } finally {
            mock.stop()
        }
    }

    // ---------------------------------------------------------------- direct surface details

    @Test
    fun `RelayControl frames are unsupported-feature on the direct surface`() {
        val t = transport(MockZCode(passHash = "x", expectedSid = "y"))
        runBlocking {
            val err = runCatching { t.send(OutboundFrame.RelayControl("{}")) }.exceptionOrNull()
            assertTrue(err is TransportFailure && (err as TransportFailure).error.category == ErrorCategory.UNSUPPORTED_FEATURE, "got $err")
        }
    }

    @Test
    fun `server-info decode is tolerant and drops path-less workspaces (INV-8)`() {
        val raw = Json.parseToJsonElement(
            """{"serverId":"s1","version":"3.14.3","protocolVersion":1,"authRequired":true,
                "workspaces":[{"label":"no-path"},{"path":"/ws/a","label":"a"},{"path":"/ws/b"}],
                "capabilities":{"websocketRpc":true,"futureFlag":true},
                "brandNewTopLevelField":{"x":1}}""".replace("\n", ""),
        ).jsonObject
        val info = ServerInfo.decode(raw)
        assertEquals("s1", info.serverId)
        assertEquals(1L, info.protocolVersion)
        assertTrue(info.authRequired)
        assertEquals(listOf("/ws/a", "/ws/b"), info.workspaces.map { it.path })
        assertEquals("a", info.workspaces[0].label)
        assertNull(info.workspaces[1].label)
        assertTrue(info.capabilities.websocketRpc)
        assertTrue(!info.capabilities.desktopContinuous) // absent flag → false (host-capability rule)
    }

    @Test
    fun `close is idempotent and origin helpers are exact`() {
        val t = transport(MockZCode(passHash = "x", expectedSid = "y"))
        runBlocking {
            t.close("once")
            t.close("twice") // §11.2 close contract
        }
        assertEquals("http://h:3030", DirectTransport.httpOriginOf("ws://h:3030/ws"))
        assertEquals("https://h", DirectTransport.httpOriginOf("wss://h/ws?x=1"))
        assertEquals("h:3030", DirectTransport.hostOf("ws://h:3030/ws?token=SECRET")) // query never leaks
        assertEquals(DirectTransport.cookieHeader(token), "zcode_lite_token=$token")
    }
}
