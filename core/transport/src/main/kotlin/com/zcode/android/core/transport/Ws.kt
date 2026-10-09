package com.zcode.android.core.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * WS seam between the transport and the socket implementation — the only OkHttp-touched
 * surface (§11: no transport-specific types leak above core:transport; tests inject
 * fakes). `incoming` yields COMPLETE text messages (OkHttp aggregates continuation
 * frames; the java.net.http fragmentation pitfall from the Phase 1 findings does not
 * apply here). Inbound text buffering is a bounded Channel (INV-5): overflow fails the
 * connection — correctness over completeness (§28.4).
 *
 * Phase 10: binary + custom-header support for the direct surface (§11.4 — binary
 * SocketProtocol frames; the token cookie rides a request header, never a logged URL).
 */
interface Ws {
    suspend fun connect(url: String, timeoutMs: Long)

    /** Same as [connect] with extra request headers (e.g. the zcode_lite_token cookie). */
    suspend fun connect(url: String, timeoutMs: Long, headers: Map<String, String>) =
        connect(url, timeoutMs)

    /** Abrupt client-side abort (socket death class) — test seam + forced teardown. */
    fun cancel()

    /** @return false when the socket is closing/closed. */
    fun sendText(text: String): Boolean

    /** Binary send (direct surface). @return false when the socket is closing/closed. */
    fun sendBinary(bytes: ByteArray): Boolean = false

    fun close(code: Int, reason: String)

    val incoming: Flow<String>

    /** Complete BINARY messages (direct surface; OkHttp aggregates continuations). */
    val incomingBinary: Flow<ByteArray> get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Emits the SHA-256 fingerprint of the TLS leaf certificate when a TLS connection
     * opens (TOFU option, §11.5); empty on plaintext sockets.
     */
    val tlsPeerFingerprint: Flow<String> get() = kotlinx.coroutines.flow.emptyFlow()

    val closed: Flow<WsClosed>
}

data class WsClosed(val code: Int, val reason: String, val expected: Boolean)

class OkHttpWsFactory : WsFactory {
    override fun create(): Ws = OkHttpWs()
}

fun interface WsFactory {
    fun create(): Ws
}

class OkHttpWs : Ws {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // NAT idle keepalive (§11.5)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS idle is event-driven
        .build()

    private var webSocket: WebSocket? = null
    private val inbound = Channel<String>(capacity = 1_024)
    private val binaryInbound = Channel<ByteArray>(capacity = 1_024)
    override val incoming: Flow<String> = inbound.receiveAsFlow()
    override val incomingBinary: Flow<ByteArray> = binaryInbound.receiveAsFlow()

    private val _closed = MutableSharedFlow<WsClosed>(extraBufferCapacity = 4)
    override val closed: Flow<WsClosed> = _closed

    private val _tlsFingerprint = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val tlsPeerFingerprint: Flow<String> = _tlsFingerprint

    @Volatile private var openSignal: CompletableDeferred<Unit>? = null

    override suspend fun connect(url: String, timeoutMs: Long) = connect(url, timeoutMs, emptyMap())

    override suspend fun connect(url: String, timeoutMs: Long, headers: Map<String, String>) {
        val builder = Request.Builder().url(url)
        for ((name, value) in headers) builder.header(name, value)
        val request = builder.build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                response.handshake?.peerCertificates?.firstOrNull()?.let { cert ->
                    runCatching { TlsFingerprint.of(cert) }.getOrNull()?.let { _tlsFingerprint.tryEmit(it) }
                }
                synchronized(this@OkHttpWs) {
                    openSignal?.complete(Unit)
                    openSignal = null
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!inbound.trySend(text).isSuccess) {
                    // INV-5: bounded inbound channel — fail the connection, never grow.
                    _closed.tryEmit(WsClosed(1006, "inbound bound exceeded", expected = false))
                    webSocket?.close(1009, "inbound bound exceeded")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!binaryInbound.trySend(bytes.toByteArray()).isSuccess) {
                    _closed.tryEmit(WsClosed(1006, "inbound bound exceeded", expected = false))
                    webSocket?.close(1009, "inbound bound exceeded")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                inbound.close()
                binaryInbound.close()
                _closed.tryEmit(WsClosed(code, reason, expected = true))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Abnormal closure — transport-classified upstream (§33).
                inbound.close()
                binaryInbound.close()
                _closed.tryEmit(WsClosed(1006, t.message?.take(120) ?: "failure", expected = false))
            }
        }
        val open = CompletableDeferred<Unit>()
        synchronized(this@OkHttpWs) { openSignal = open }
        webSocket = client.newWebSocket(request, listener)
        withTimeout(timeoutMs) { open.await() }
    }

    override fun sendText(text: String): Boolean = webSocket?.send(text) ?: false

    override fun sendBinary(bytes: ByteArray): Boolean =
        webSocket?.send(bytes.toByteString()) ?: false

    override fun cancel() {
        webSocket?.cancel()
    }

    override fun close(code: Int, reason: String) {
        webSocket?.close(code, reason)
        webSocket = null
    }
}
