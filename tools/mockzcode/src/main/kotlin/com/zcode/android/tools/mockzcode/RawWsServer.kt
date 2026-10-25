package com.zcode.android.tools.mockzcode

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Minimal RFC 6455 WebSocket server with UNMASKED server→client frames.
 *
 * Why this exists (report PART E9): mockwebserver3's `webSocketUpgrade` masks
 * server-sent frames — an RFC 6455 §5.1 violation byte-confirmed by probe
 * (pong MASK-BIT=1, garbled payload) — and the app's OkHttp client correctly
 * refuses with close 1006, killing every live channel call on device E2E.
 * This hand-rolled server speaks just enough of the protocol for MockZCode:
 * handshake, text/binary frames, ping→pong, close, and client-frame unmasking.
 * Test tooling only (§35.4, INV-15).
 */
class RawWsServer(private val port: Int) {

    sealed interface Route {
        data class Http(
            val code: Int,
            val contentType: String? = null,
            val body: String = "",
            val setCookie: String? = null,
        ) : Route

        data class Upgrade(
            val onOpen: (WebSocket) -> Unit = {},
            val onText: (WebSocket, String) -> Unit = { _, _ -> },
            val onBytes: (WebSocket, ByteArray) -> Unit = { _, _ -> },
        ) : Route
    }

    @Volatile private var serverSocket: ServerSocket? = null
    private val acceptThread = Thread({ acceptLoop() }, "raw-ws-accept").apply { isDaemon = true }

    /** Ephemeral-port support: the locally bound port after [start]. */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    fun start(router: (path: String, query: Map<String, String>, headers: Map<String, String>) -> Route) {
        this.router = router
        serverSocket = ServerSocket(port)
        acceptThread.start()
    }

    fun stop() {
        runCatching { serverSocket?.close() }
    }

    @Volatile private var router: ((String, Map<String, String>, Map<String, String>) -> Route)? = null

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                break
            }
            Thread({ runCatching { serve(client) } }, "raw-ws-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return socket.close()
        val parts = requestLine.split(" ")
        if (parts.size < 2) return socket.close()
        val rawTarget = parts[1]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return socket.close()
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val pathSplit = rawTarget.split("?", limit = 2)
        val path = pathSplit[0]
        val query = pathSplit.getOrNull(1)?.split("&")?.mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) java.net.URLDecoder.decode(kv[0], "UTF-8") to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
        }?.toMap() ?: emptyMap()

        val route = router?.invoke(path, query, headers) ?: return socket.close()
        when (route) {
            is Route.Http -> {
                val out = socket.getOutputStream()
                val bodyBytes = route.body.toByteArray(Charsets.UTF_8)
                val sb = StringBuilder("HTTP/1.1 ${route.code} ${reasonFor(route.code)}\r\n")
                route.contentType?.let { sb.append("Content-Type: $it\r\n") }
                route.setCookie?.let { sb.append("Set-Cookie: $it\r\n") }
                sb.append("Content-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(bodyBytes)
                out.flush()
                socket.close()
            }
            is Route.Upgrade -> {
                val key = headers["sec-websocket-key"] ?: return socket.close()
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
                )
                val out = socket.getOutputStream()
                out.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                )
                out.flush()
                socket.soTimeout = 0
                val conn = RawSocket(socket, out)
                route.onOpen(conn)
                readLoop(input, conn, route)
            }
        }
    }

    private fun readLoop(input: InputStream, conn: RawSocket, route: Route.Upgrade) {
        var opcode = -1
        var buffer = ByteArray(0)
        while (true) {
            val head = input.readNBytes(2)
            if (head.size < 2) break
            val b1 = head[0].toInt() and 0xFF
            val b2 = head[1].toInt() and 0xFF
            val fin = b1 and 0x80 != 0
            val frameOpcode = b1 and 0x0F
            val masked = b2 and 0x80 != 0
            var len = (b2 and 0x7F).toLong()
            if (len == 126L) {
                val ext = input.readNBytes(2); if (ext.size < 2) break
                len = ((ext[0].toLong() and 0xFF) shl 8) or (ext[1].toLong() and 0xFF)
            } else if (len == 127L) {
                val ext = input.readNBytes(8); if (ext.size < 8) break
                len = 0
                for (b in ext) len = (len shl 8) or (b.toLong() and 0xFF)
            }
            val maskKey: ByteArray? = if (masked) input.readNBytes(4) else null
            if (maskKey != null && maskKey.size < 4) break
            var payload = input.readNBytes(len.toInt())
            if (payload.size < len) break
            if (maskKey != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
            }
            when (frameOpcode) {
                0x8 -> { conn.sendCloseFrame(); break } // close
                0x9 -> conn.writeFrame(0xA, payload) // ping → unmasked pong
                0xA -> Unit // pong — ignore
                else -> {
                    if (frameOpcode != 0) opcode = frameOpcode
                    buffer += payload
                    if (fin) {
                        val delivered = buffer
                        buffer = ByteArray(0)
                        when (opcode) {
                            0x1 -> route.onText(conn, String(delivered, Charsets.UTF_8))
                            0x2 -> route.onBytes(conn, delivered)
                        }
                    }
                }
            }
        }
        runCatching { socket2(conn).close() }
    }

    private fun socket2(conn: RawSocket): Socket = conn.socket

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.removeSuffix("\r").toString()
            sb.append(c.toChar())
            if (sb.length > 16_384) return null
        }
    }

    private fun reasonFor(code: Int): String = when (code) {
        200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 503 -> "Service Unavailable"; else -> "Status"
    }

    /** okhttp3.WebSocket adapter writing UNMASKED frames (server role, RFC 6455 §5.1). */
    class RawSocket(val socket: Socket, private val out: OutputStream) : WebSocket {
        private val writeLock = Any()
        @Volatile private var closed = false

        fun writeFrame(opcode: Int, payload: ByteArray) {
            synchronized(writeLock) {
                if (closed) return
                val header = ArrayList<Byte>(10)
                header.add(((0x80 or opcode).toByte()))
                when {
                    payload.size <= 125 -> header.add(payload.size.toByte())
                    payload.size <= 65535 -> {
                        header.add(126.toByte())
                        header.add(((payload.size shr 8) and 0xFF).toByte())
                        header.add((payload.size and 0xFF).toByte())
                    }
                    else -> {
                        header.add(127.toByte())
                        for (shift in 56 downTo 0 step 8) header.add(((payload.size.toLong() shr shift) and 0xFF).toByte())
                    }
                }
                out.write(header.toByteArray())
                out.write(payload)
                out.flush()
            }
        }

        fun sendCloseFrame() {
            writeFrame(0x8, ByteArray(0))
            closed = true
            runCatching { socket.close() }
        }

        override fun request(): okhttp3.Request = okhttp3.Request.Builder().url("http://raw.ws/").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            writeFrame(0x1, text.toByteArray(Charsets.UTF_8)); return true
        }
        override fun send(bytes: ByteString): Boolean {
            writeFrame(0x2, bytes.toByteArray()); return true
        }
        override fun close(code: Int, reason: String?): Boolean {
            val payload = ByteArray(2 + (reason?.length ?: 0))
            payload[0] = ((code shr 8) and 0xFF).toByte()
            payload[1] = (code and 0xFF).toByte()
            reason?.let { System.arraycopy(it.toByteArray(Charsets.UTF_8), 0, payload, 2, it.length) }
            writeFrame(0x8, payload)
            closed = true
            runCatching { socket.close() }
            return true
        }
        override fun cancel() {
            closed = true
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
