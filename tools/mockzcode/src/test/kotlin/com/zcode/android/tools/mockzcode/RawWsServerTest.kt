package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * RawWsServer regression pin (report PART E9): mockwebserver3's webSocketUpgrade
 * MASKS server frames (RFC 6455 §5.1 violation, probe-confirmed) and OkHttp clients
 * refuse them with close 1006 — which is exactly how live device E2E broke. This
 * test drives the raw server with a REAL OkHttp client; if server frames ever come
 * back masked, the handshake/echo here fails the same way the app did on-device.
 * Also exercises the 8-byte extended-length form via a large echo payload.
 */
class RawWsServerTest {

    @Test
    fun echoRoundTripWithRealOkHttpClient() {
        val server = RawWsServer(0) // ephemeral
        server.start { _, _, _ ->
            RawWsServer.Route.Upgrade(
                onText = { ws, text -> ws.send("echo:$text") },
            )
        }
        val port = server.boundPort
        assertTrue(port > 0, "raw server did not bind")

        val received = ArrayDeque<String>()
        var opened = false
        var failure: Throwable? = null

        val client = OkHttpClient()
        val ws = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$port/ws").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    webSocket.send("hello")
                    webSocket.send("x".repeat(70_000)) // >65535 → extended length form
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.addLast(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = t
                }
            },
        )

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && received.size < 2 && failure == null) {
            Thread.sleep(50)
        }
        ws.close(1000, "done")
        client.dispatcher.executorService.shutdown()
        server.stop()

        failure?.let { throw AssertionError("OkHttp client failed (masked server frames?): $it") }
        assertTrue(opened, "connection never opened")
        assertEquals("echo:hello", received.firstOrNull())
        val big = received.elementAtOrNull(1)
        assertTrue(big != null && big.length == 70_000 + "echo:".length, "large echo frame lost/truncated: ${big?.length}")
    }

    @Test
    fun directModeServerInfoAuthGate() {
        val mock = MockZCode(
            passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
            expectedSid = "d_SyntheticSid000001",
            mode = MockZCode.Mode.DIRECT,
        )
        val server = RawWsServer(0)
        server.start(mock::rawRoute)
        val port = server.boundPort
        assertTrue(port > 0, "raw server did not bind")

        // Unauthenticated server-info must 401 …
        val unauth = java.net.URL("http://127.0.0.1:$port/api/server-info")
            .openConnection() as java.net.HttpURLConnection
        assertEquals(401, unauth.responseCode)

        // … and the tokened request must return the workspaces payload.
        val authed = java.net.URL("http://127.0.0.1:$port/api/server-info?token=${mock.serverToken}")
            .openConnection() as java.net.HttpURLConnection
        assertEquals(200, authed.responseCode)
        val body = authed.inputStream.bufferedReader().readText()
        assertTrue(body.contains("\"workspaces\""), "server-info missing workspaces: $body")

        server.stop()
        mock.stop()
    }
}
