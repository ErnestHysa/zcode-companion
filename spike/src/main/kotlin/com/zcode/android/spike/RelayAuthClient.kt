package com.zcode.android.spike

import com.zcode.android.core.protocol.channel.ChannelClient
import com.zcode.android.core.protocol.channel.ChannelWire
import com.zcode.android.core.protocol.relay.RelayFrameCodec
import com.zcode.android.core.protocol.bytes.ProtocolException
import com.zcode.android.core.protocol.relay.RelayLimits
import com.zcode.android.core.protocol.relay.AuthInit
import com.zcode.android.core.protocol.relay.AuthMeta
import com.zcode.android.core.protocol.relay.AuthResponse
import com.zcode.android.core.protocol.relay.BootstrapRequest
import com.zcode.android.core.protocol.relay.DeviceRegisterInit
import com.zcode.android.core.protocol.relay.PairStatusQuery
import com.zcode.android.core.protocol.relay.WorkspaceBridgeOpen
import com.zcode.android.core.protocol.relay.WorkspaceListRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 1 spike client: relay WS connect → terminal-role HMAC auth → heartbeat →
 * bootstrap/workspace-list → workspace bridge (rpc-frame) → v4 conversation frames →
 * forced reconnect + resync. Spike quality by design (§43.2-1.3).
 *
 * INV-3: the passHash and proofs never appear in logs or the capture transcript's
 * human-readable log — only in the raw capture file, which stays OUTSIDE the repo
 * and is sanitized by tools/capture/redact.mjs before anything is committed (INV-15).
 */
class RelayAuthClient(
    private val relayWsUrl: String,
    private val params: PairingParams,
    private val signer: CredentialSigner,
    private val capture: CaptureSink?,
    private val log: (String) -> Unit,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val jsonTolerant = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val seqCounter = AtomicLong(0)
    private var messageSeqCounter = AtomicLong(0)
    private var bridgeSessionId: String = RelayFrameCodec.newBridgeSessionId()
    private var currentBridgeGeneration: Int? = null
    @Volatile private var recoveryIdOverride: String? = null
    private val rpcMessageCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val assembler = RelayFrameCodec.Assembler()
    val channel = ChannelClient()
    val events = ArrayDeque<String>() // human-readable event trail (redacted)

    private var webSocket: WebSocket? = null
    private var clientName = "zcode-android-spike"
    private var heartbeat: Thread? = null
    @Volatile private var closedByUs = false
    @Volatile private var lastPairStatusAckAt = 0L
    @Volatile var pairStatus: String? = null
        private set
    @Volatile var authFailedCode: String? = null
        private set
    @Volatile var lastCloseCode: Int? = null
        private set
    private val authLatch = CountDownLatch(1)
    private val pairLatch = CountDownLatch(1)

    fun connectAndAuthenticate(deviceName: String = clientName, role: String = "terminal"): String {
        clientName = deviceName
        val client = HttpClient.newBuilder().build()
        val wsUri = URI.create(relayWsUrl)
        val listener = Listener()
        log("connecting to $wsUri")
        val ws = client.newWebSocketBuilder().buildAsync(wsUri, listener).get(15, TimeUnit.SECONDS)
        webSocket = ws
        events.add("ws-open")
        sendJson(
            json.encodeToString(
                AuthInit(
                    role = role,
                    deviceSid = params.deviceSid,
                    meta = AuthMeta(platform = "android-spike", version = "1.0.0-phase1", name = deviceName),
                    clientTs = System.currentTimeMillis(),
                )
            )
        )
        events.add("auth_init sent (role=$role)")
        // auth_challenge arrives → Listener replies; auth_ack/pair_status_ack resolves.
        val ok = authLatch.await(15, TimeUnit.SECONDS)
        if (!ok) throw SpikeException("auth handshake timed out after 15s")
        authFailedCode?.let { code -> throw SpikeAuthException(code) }
        val pairOk = pairLatch.await(20, TimeUnit.SECONDS)
        log("pair status: $pairStatus (waited=$pairOk)")
        startHeartbeat()
        return pairStatus ?: "unknown"
    }

    private fun startHeartbeat() {
        heartbeat = Thread {
            while (!closedByUs && webSocket != null) {
                try {
                    Thread.sleep(RelayLimits.HEARTBEAT_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (closedByUs) return@Thread
                if (pairStatus == "matched" || pairStatus == "waiting") {
                    sendJson(json.encodeToString(PairStatusQuery(deviceSid = params.deviceSid, clientTs = System.currentTimeMillis())))
                    if (System.currentTimeMillis() - lastPairStatusAckAt > RelayLimits.HEARTBEAT_ACK_WATCHDOG_MS) {
                        log("heartbeat ack watchdog fired — connection considered dead")
                        return@Thread
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------ public probe ops

    // Control-plane app payloads travel INSIDE {type:"data",payload:{...}} envelopes —
    // the desktop's relay dispatch only routes data/auth_*/error at top level
    // (verified against app.asar parseRelayMessage/handleMessage, 2026-09-28).
    fun sendBootstrapRequest(requestId: String): Unit =
        sendJson(RelayFrameCodec.encodeEnvelope(json.encodeToString(BootstrapRequest(requestId = requestId))))

    fun sendWorkspaceListRequest(requestId: String): Unit =
        sendJson(RelayFrameCodec.encodeEnvelope(json.encodeToString(WorkspaceListRequest(requestId = requestId))))

    fun overrideRecovery(recoveryId: String) {
        recoveryIdOverride = recoveryId
    }

    fun rpcMessageCount(): Long = rpcMessageCounter.get()

    /** Blocks until the decoded-message count reaches [since]+[n]; returns the count. */
    fun awaitRpcMessageSince(since: Long, timeoutMs: Long, n: Long = 1): Long? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (rpcMessageCounter.get() >= since + n) return rpcMessageCounter.get()
            Thread.sleep(50)
        }
        return null
    }

    fun sendWorkspaceBridgeOpen(requestId: String, workspaceKey: String, taskId: String? = null) {
        sendJson(
            RelayFrameCodec.encodeEnvelope(
            json.encodeToString(
                WorkspaceBridgeOpen(
                    requestId = requestId,
                    bridgeSessionId = bridgeSessionId,
                    bridgeGeneration = currentBridgeGeneration,
                    recoveryId = recoveryIdOverride,
                    workspaceKey = workspaceKey,
                    taskId = taskId,
                )
            )
            )
        )
    }

    /** Send one logical Channel frame through the relay rpc-frame transport. */
    fun sendChannelFrame(bytes: ByteArray) {
        val seq = seqCounter.addAndGet(1)
        val msgSeq = messageSeqCounter.addAndGet(1)
        val fragments = RelayFrameCodec.fragment(bridgeSessionId, currentBridgeGeneration, null, bytes, seq, msgSeq)
        for (f in fragments) {
            sendJson(f.envelopeJson)
        }
        events.add("rpc-frame sent (messageSeq=$msgSeq, fragments=${fragments.size})")
    }

    fun close() {
        closedByUs = true
        heartbeat?.interrupt()
        try {
            webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "spike done")?.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // ignore — spike
        }
        webSocket = null
        events.add("closed")
    }

    fun forceKill() {
        // Simulate transport loss without a clean close (S1/§1.6).
        closedByUs = true
        heartbeat?.interrupt()
        try {
            webSocket?.abort()
        } catch (e: Exception) {
        }
        webSocket = null
        events.add("socket aborted")
    }

    private fun sendJson(text: String) {
        capture?.write("send", text)
        webSocket?.sendText(text, true)?.get(5, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------ WS listener

    private inner class Listener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            val text = data.toString()
            capture?.write("recv", text)
            try {
                handleMessage(text)
            } catch (e: Exception) {
                log("handler error: ${e.message}")
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            lastCloseCode = statusCode
            events.add("ws-close code=$statusCode reason=$reason")
            log("ws close code=$statusCode reason=$reason")
            return null
        } // CompletionStage null is legal for java.net.http listeners

        override fun onError(webSocket: WebSocket, error: Throwable) {
            events.add("ws-error ${error.javaClass.simpleName}: ${error.message?.take(120)}")
            log("ws error: ${error.javaClass.simpleName} ${error.message?.take(120)}")
        }
    }

    private fun handleMessage(text: String) {
        val obj = try {
            jsonTolerant.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            log("non-JSON relay message dropped")
            return
        }
        when (val type = (obj["type"] as? JsonPrimitive)?.content) {
            "auth_challenge" -> {
                val nonce = (obj["nonce"] as? JsonPrimitive)?.content ?: ""
                events.add("auth_challenge received (nonce len=${nonce.length})")
                val proof = signer.proof(nonce, "terminal", params.deviceSid)
                sendJson(json.encodeToString(AuthResponse(deviceSid = params.deviceSid, proof = proof, clientTs = System.currentTimeMillis())))
                events.add("auth_response sent")
            }
            "auth_ack", "pair_status_ack" -> {
                val status = (obj["pair_status"] as? JsonPrimitive)?.content
                lastPairStatusAckAt = System.currentTimeMillis()
                if (status != null) {
                    pairStatus = status
                    events.add("$type pair_status=$status")
                    authLatch.countDown()
                    if (status == "matched") pairLatch.countDown()
                }
            }
            "device_register_ack" -> {
                events.add("device_register_ack (unexpected for terminal role)")
                authLatch.countDown()
            }
            "error" -> {
                val code = (obj["code"] as? JsonPrimitive)?.content ?: "UNKNOWN"
                val message = (obj["message"] as? JsonPrimitive)?.content ?: ""
                events.add("relay error code=$code message=${message.take(80)}")
                if (code == "AUTH_FAILED" || code == "KICKED" || code == "WRONG_PARAM" || code == "INTERNAL") {
                    authFailedCode = code
                    authLatch.countDown()
                    pairLatch.countDown()
                }
            }
            "data" -> handleDataPayload(obj)
            else -> {
                events.add("unknown control type=$type (counted, INV-8)")
            }
        }
    }

    private fun handleDataPayload(envelope: JsonObject) {
        val results = try {
            assembler.handleEnvelope(envelope.toString())
        } catch (e: ProtocolException) {
            events.add("protocol fault: ${e.message?.take(100)} (S11 → resync would trigger)")
            return
        }
        for (r in results) {
            when (r) {
                is RelayFrameCodec.Reassembled.Message -> {
                    messageSeqCounter.getAndUpdate { current -> maxOf(current, r.messageSeq) }
                    rpcMessageCounter.incrementAndGet()
                    events.add("rpc logical frame decoded (messageSeq=${r.messageSeq}, bytes=${r.payload.size})")
                    channel.onFrame(r.payload)
                }
                is RelayFrameCodec.Reassembled.Ack -> {
                    events.add("rpc-frame-ack received ackMessageSeq=${r.ackMessageSeq}")
                }
                is RelayFrameCodec.Reassembled.Control -> {
                    events.add("control payload zcode_type=${r.zcodeType}")
                    controlPayloads.add(r.payload)
                }
            }
        }
    }

    val controlPayloads = ArrayDeque<JsonObject>()

    fun awaitControl(zcodeType: String, timeoutMs: Long): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            controlPayloads.firstOrNull { (it["zcode_type"] as? JsonPrimitive)?.content == zcodeType }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    fun awaitChannelEvent(timeoutMs: Long): ChannelWire.Incoming? {
        // Driven from the WS thread; poll for the spike's needs.
        var waited = 0L
        while (waited < timeoutMs) {
            Thread.sleep(50)
            waited += 50
        }
        return null
    }
}

class SpikeException(message: String) : Exception(message)
class SpikeAuthException(val code: String) : Exception("relay auth failed: $code")

/** Raw transcript sink — writes OUTSIDE the repo; sanitized before any commit. */
class CaptureSink(private val file: File) {
    init {
        file.parentFile?.mkdirs()
        file.createNewFile()
    }

    fun write(dir: String, text: String) {
        synchronized(this) {
            file.appendText(
                Json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("dir", JsonPrimitive(dir))
                        put("ts", JsonPrimitive(System.currentTimeMillis()))
                        put("msg", Json.parseToJsonElement(text))
                    }
                ) + "\n"
            )
        }
    }
}
