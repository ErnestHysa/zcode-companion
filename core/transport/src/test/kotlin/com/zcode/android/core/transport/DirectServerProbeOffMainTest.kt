package com.zcode.android.core.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F-37-A (vc52 Phase 6, P1): the direct-endpoint probe performed a BLOCKING OkHttp
 * `execute()` on whatever dispatcher called it — the add-endpoint dialog called it from
 * the composition (Main) dispatcher, so Android threw NetworkOnMainThreadException on
 * every add (debugger-proven on ws://, LAN ws:// and wss://). These pins hold the probe
 * OFF its caller: a single-thread "fake-main" dispatcher must stay free to run a frame
 * while the probe waits on a server that has accepted the request but not answered.
 *
 * Threat: none (availability) — but the misclassification half is user-facing: OkHttp's
 * cleartext refusal ("CLEARTEXT communication … not permitted by network security
 * policy") matched the Local-Network-Protection markers and told the user to change an
 * OS setting that does not exist. The classifier fixtures pin the honest split.
 */
class DirectServerProbeOffMainTest {

    private val token = "probe-off-main-token"
    private val fakeMainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }
    private val fakeMain = fakeMainExecutor.asCoroutineDispatcher()
    private var server: GatedServerInfo? = null

    @AfterTest
    fun tearDown() {
        server?.close()
        fakeMain.close()
    }

    @Test
    fun `F-37-A - the probe never blocks its caller's dispatcher while the server is slow`() {
        val gated = GatedServerInfo(status = 200).also { server = it }
        runBlocking {
            val probe = async(fakeMain) { DirectServerProbe.probe(gated.origin, token, 10_000) }
            assertTrue(gated.requestArrived.await(5, TimeUnit.SECONDS), "the probe never reached the server")
            // The request is parked server-side. A caller-dispatcher frame must still run:
            // pre-fix the blocking execute() held "fake-main" until the response arrived.
            val frame = withTimeoutOrNull(2_000) { withContext(fakeMain) { Thread.currentThread().name } }
            gated.release()
            assertNotNull(frame, "the caller's dispatcher stayed blocked for 2 s by the probe (NetworkOnMainThread shape)")
            // (coroutine debug mode suffixes the thread name with " @coroutine#N")
            assertTrue(frame.startsWith("fake-main"), "frame ran on $frame")
            val result = withTimeout(10_000) { probe.await() }
            assertIs<ProbeResult.Ok>(result, "got $result")
        }
    }

    @Test
    fun `F-37-A - the blocking call is dispatched onto the injected dispatcher`() {
        val gated = GatedServerInfo(status = 200).also { server = it }
        gated.release() // answer at once: this pin is about WHERE the call runs
        val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "probe-io") }
        val dispatches = AtomicInteger()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches.incrementAndGet()
                ioExecutor.execute(block)
            }
        }
        try {
            val result = runBlocking {
                withContext(fakeMain) { DirectServerProbe.probe(gated.origin, token, 10_000, dispatcher = io) }
            }
            assertIs<ProbeResult.Ok>(result, "got $result")
            assertTrue(dispatches.get() >= 1, "the probe never hopped onto the injected dispatcher")
        } finally {
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun `F-37-A - a cleartext refusal maps to the honest copy key, never LNP`() {
        val error = ErrorMapping.cleartextRefused("ws://192.0.2.1:3030/ws?token=secret")
        assertEquals(com.zcode.android.core.common.error.UserMessageKey.CLEARTEXT_NOT_ALLOWED, error.messageKey)
        assertEquals(com.zcode.android.core.common.error.ErrorCategory.TRANSPORT, error.category)
        assertTrue(!error.developerDiagnostic.contains("secret"), "the query (token) never reaches diagnostics")
    }

    // ---------------------------------------------------------------- classification

    @Test
    fun `F-37-A - OkHttp's cleartext refusal is its own outcome, never Local-Network-Blocked`() {
        val refusal = java.net.UnknownServiceException(
            "CLEARTEXT communication to 192.0.2.1 not permitted by network security policy",
        )
        val classified = DirectServerProbe.classifyProbeFailure(refusal)
        assertTrue(classified !is ProbeResult.LocalNetworkBlocked, "cleartext refusal misdiagnosed as LNP: $classified")
        assertEquals("CleartextRefused", classified::class.simpleName, "got $classified")
    }

    @Test
    fun `F-37-A - a null-message NetworkOnMainThreadException is never classified as LNP or DNS`() {
        val classified = DirectServerProbe.classifyProbeFailure(android.os.NetworkOnMainThreadException())
        assertIs<ProbeResult.Unreachable>(classified)
        assertTrue(classified.reason.contains("NetworkOnMainThreadException"), "diagnostic must name the class: ${classified.reason}")
        assertTrue(!classified.reason.startsWith("DNS"), "a null message is not a name-resolution failure")
    }

    @Test
    fun `F-37-A - a real EPERM connect errno stays Local-Network-Blocked`() {
        val eperm = java.net.ConnectException(
            "failed to connect to /192.168.1.5 (port 3030) from /:: (port 0): connect failed: EPERM (Operation not permitted)",
        )
        assertIs<ProbeResult.LocalNetworkBlocked>(DirectServerProbe.classifyProbeFailure(eperm))
        val econnaborted = java.net.SocketException("Software caused connection abort (ECONNABORTED)")
        assertIs<ProbeResult.LocalNetworkBlocked>(DirectServerProbe.classifyProbeFailure(econnaborted))
    }

    /**
     * A one-shot HTTP server that accepts the probe's request and HOLDS the response until
     * [release] — the deterministic shape of "a LAN host slow to answer".
     */
    private class GatedServerInfo(private val status: Int) : AutoCloseable {
        private val socket = ServerSocket(0)
        val origin = "http://127.0.0.1:${socket.localPort}"
        val requestArrived = CountDownLatch(1)
        private val gate = CountDownLatch(1)
        private val worker = thread(name = "gated-server-info", isDaemon = true) {
            runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    requestArrived.countDown()
                    gate.await(15, TimeUnit.SECONDS)
                    val body = if (status == 200) {
                        """{"serverId":"gated","name":"Gated","version":"3.14.5","protocolVersion":1,"authRequired":true,"workspaces":[]}"""
                    } else {
                        """{"error":"Unauthorized"}"""
                    }
                    val reason = if (status == 200) "OK" else "Unauthorized"
                    val bytes = body.toByteArray()
                    client.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        write(bytes)
                        flush()
                    }
                }
            }
        }

        fun release() = gate.countDown()

        override fun close() {
            gate.countDown()
            runCatching { socket.close() }
            worker.join(2_000)
        }
    }
}
