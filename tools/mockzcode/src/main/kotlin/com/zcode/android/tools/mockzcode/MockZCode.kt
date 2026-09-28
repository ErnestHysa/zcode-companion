package com.zcode.android.tools.mockzcode

import com.zcode.android.core.protocol.bytes.RpcSerialization
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.protocol.channel.ChannelWire
import com.zcode.android.core.protocol.relay.RelayFrameCodec
import com.zcode.android.core.protocol.relay.RelayLimits
import com.zcode.android.core.protocol.v4.DeliveryKind
import com.zcode.android.core.protocol.v4.TopicWireFrame
import com.zcode.android.core.protocol.v4.TopicWireFrameCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MockZCode core (§35.4) — a JVM protocol-faithful mock of the relay + desktop side,
 * built ON the shipped core:protocol codecs (inbound frames are decoded by the real
 * [RelayFrameCodec]; outbound topic frames are encoded by the real
 * [TopicWireFrameCodec]) so the self-test proves round-trips against the shipped wire
 * layer. Test tooling only: NEVER shipped, NEVER pointed at production systems (INV-15).
 *
 * Grounding — nothing invented: handshake/envelope/ack semantics from
 * docs/protocol/relay-control-plane.md (Phase 1 bundle extraction + live captures);
 * v4 shapes from packages/shared/src/zcode-protocol-v4 @ 29628c9; fault classes from
 * Phase-1-observed desktop reason codes (KICKED / AUTH_FAILED / replayGraceExceeded
 * class) and the §15 scenario matrix.
 */
class MockZCode(
    val passHash: String,
    val expectedSid: String,
    val desktopMid: String = "mock-desktop-mid",
    val workspace: MockWorkspace = MockWorkspace(),
) {
    /** Scriptable behavior per test. */
    class Script {
        var authScenario: AuthScenario = AuthScenario.HAPPY
        var kickFirstTerminalOnSecond: Boolean = true
        var sendInitializeAfterBridge: Boolean = true
        /** Ack every delivered logical message twice (duplicate-ack tolerance). */
        var duplicateInboundAcks: Boolean = false
        /** Deliver every Nth outbound logical frame twice (duplicate storm). */
        var duplicateEvery: Int = 0
        /** Shuffle fragment envelopes inside each logical message (out-of-order storm). */
        var shuffleFragments: Boolean = false
        /** #228 class: inject one physical envelope > 1 MiB (once). */
        var injectOversizedEnvelope: Boolean = false
        /** S11 class: corrupt one fragment's crc32 value (once). */
        var corruptFragmentCrc: Boolean = false
        /** Close the socket after N outbound logical frames (disconnect storm driver). */
        var disconnectAfterFrames: Int? = null
        /** Server-side delay before each outbound frame (latency storm). */
        var perFrameLatencyMs: Long = 0
        /** Emit v4/connection/flow with this state right after subscribe. */
        var flowStateAfterSubscribe: String? = null
        /** Corpus playback: raw recv-direction messages replayed verbatim after auth. */
        var playbackMessages: List<JsonObject> = emptyList()
        /** When false the mock never acks pair_status_query (watchdog fault class). */
        var ackHeartbeats: Boolean = true
    }

    enum class AuthScenario { HAPPY, WRONG_PROOF, UNKNOWN_SID }

    data class MockWorkspace(
        val workspaceKey: String = "synthetic-ws-one",
        val workspacePath: String = "/synthetic/ws-one",
        val workspaceIdentity: String? = null,
        val sessionId: String = "synth-task-1",
        val desktopAppVersion: String = "3.14.3",
        val rowCount: Int = 8,
    )

    sealed class ServerEvent {
        data class AuthCompleted(val role: String) : ServerEvent()
        data class AuthRejected(val reason: String) : ServerEvent()
        data class TerminalKicked(val reason: String) : ServerEvent()
        data class BridgeOpened(val workspaceKey: String, val recoveryId: String?) : ServerEvent()
        data class LogicalFrameFromClient(val messageSeq: Long, val bytes: Int) : ServerEvent()
        data class CommandReceived(val type: String, val commandId: String) : ServerEvent()
    }

    val script = Script()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 256)
    val eventsFlow: SharedFlow<ServerEvent> = events

    private val server = mockwebserver3.MockWebServer()

    /** The socket that currently owns the terminal slot (server-initiated sends). */
    @Volatile private var terminalSocket: okhttp3.WebSocket? = null

    private val outSeq = AtomicLong(0)
    private val outMessageSeq = AtomicLong(0)
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** Recent outbound logical envelopes — replayed when a bridge re-opens with recoveryId. */
    private val recentOutbound = ArrayDeque<String>()

    private val dispatcher = object : mockwebserver3.Dispatcher() {
        override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse =
            mockwebserver3.MockResponse.Builder().webSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    val obj = try {
                        json.parseToJsonElement(text).jsonObject
                    } catch (e: Exception) {
                        return
                    }
                    route(webSocket, obj)
                }
            }).build()
    }

    fun start(): String {
        server.dispatcher = dispatcher
        server.start()
        return server.url("/ws").toString()
    }

    fun stop() {
        terminalSocket?.let { runCatching { it.close(1000, "mock stop") } }
        try {
            server.close()
        } catch (e: AssertionError) {
            // Test-tooling tolerance: MockWebServer gives up draining lingering sockets
            // after the client already closed its side; no assertion rides on this.
        }
        terminalSocket = null
    }

    val url: String get() = server.url("/ws").toString()

    // ------------------------------------------------------------------ connection

    @Volatile private var pairStatus: String = "matched"
    @Volatile private var nonceIssued: String? = null
    @Volatile private var expectedProof: String? = null

    private fun route(ws: okhttp3.WebSocket, obj: JsonObject) {
        when ((obj["type"] as? JsonPrimitive)?.content) {
            "auth_init" -> handleAuthInit(ws, obj)
            "auth_response" -> handleAuthResponse(ws, obj)
            "pair_status_query" -> if (script.ackHeartbeats) sendTo(
                ws, mapOf("type" to JsonPrimitive("pair_status_ack"), "pair_status" to JsonPrimitive(pairStatus))
            ) else Unit
            "device_register_init" -> sendTo(
                ws, mapOf("type" to JsonPrimitive("device_register_ack"), "device_sid" to JsonPrimitive(expectedSid))
            )
            "data" -> if (ws === terminalSocket) handleData(ws, obj) else Unit
            else -> Unit // desktop dispatch parity: unknown top-level types ignored
        }
    }

    private fun handleAuthInit(ws: okhttp3.WebSocket, obj: JsonObject) {
        val role = obj.str("role")
        val sid = obj.str("device_sid")
        if (script.authScenario == AuthScenario.UNKNOWN_SID || sid != expectedSid) {
            sendTo(ws, mapOf("type" to JsonPrimitive("error"), "code" to JsonPrimitive("AUTH_FAILED"), "message" to JsonPrimitive("mock: unknown device_sid")))
            scope.launch { events.emit(ServerEvent.AuthRejected("unknown_sid")) }
            return
        }
        nonceIssued = UUID.randomUUID().toString().replace("-", "").take(24)
        expectedProof = hmacProof(passHash, nonceIssued!!, role, sid)
        sendTo(ws, mapOf("type" to JsonPrimitive("auth_challenge"), "nonce" to JsonPrimitive(nonceIssued)))
    }

    private fun handleAuthResponse(ws: okhttp3.WebSocket, obj: JsonObject) {
        val proof = obj.str("proof")
        if (script.authScenario == AuthScenario.WRONG_PROOF || proof != expectedProof) {
            sendTo(ws, mapOf("type" to JsonPrimitive("error"), "code" to JsonPrimitive("AUTH_FAILED"), "message" to JsonPrimitive("mock: proof mismatch")))
            scope.launch { events.emit(ServerEvent.AuthRejected("proof_mismatch")) }
            return
        }
        // Single-terminal rule: a second authenticated terminal kicks the first (Q10).
        val prior = terminalSocket
        if (prior != null && prior !== ws && script.kickFirstTerminalOnSecond) {
            prior.send("""{"type":"error","code":"KICKED","message":""}""")
            prior.close(1000, "kicked by mock")
            scope.launch { events.emit(ServerEvent.TerminalKicked("second terminal authenticated")) }
        }
        terminalSocket = ws
        pairStatus = "matched"
        sendTo(ws, mapOf("type" to JsonPrimitive("auth_ack"), "pair_status" to JsonPrimitive("matched")))
        sendTo(ws, mapOf("type" to JsonPrimitive("pair_status_ack"), "pair_status" to JsonPrimitive("matched")))
        scope.launch { events.emit(ServerEvent.AuthCompleted("terminal")) }
    }

    /** Queue recv-direction messages (corpus replay) — delivered by [startPlayback]. */
    fun queuePlayback(messages: List<JsonObject>) {
        playbackQueue.clear()
        playbackQueue.addAll(messages)
    }

    private val playbackQueue = ArrayDeque<JsonObject>()

    /** Deterministic corpus playback trigger (tests drive it after connect completes). */
    fun startPlayback() {
        val ws = terminalSocket ?: return
        val messages = playbackQueue.toList()
        playbackQueue.clear()
        for (m in messages) sendTo(ws, raw = m.toString())
    }

    private fun handleData(ws: okhttp3.WebSocket, obj: JsonObject) {
        val payload = obj["payload"] as? JsonObject ?: return
        when ((payload["zcode_type"] as? JsonPrimitive)?.content) {
            "rpc-frame" -> handleRpcFrame(payload)
            "rpc-frame-ack" -> Unit // client acking our logical frames
            "bootstrap-request" -> sendBootstrapResponse(ws, payload.str("requestId"))
            "workspace-list-request" -> sendWorkspaceListResponse(ws, payload.str("requestId"))
            "workspace-bridge-open" -> handleBridgeOpen(ws, payload)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ rpc-frame plane

    /** Inbound client frames are decoded WITH THE REAL CODEC (self-test grounding). */
    private val inboundAssembler = RelayFrameCodec.Assembler()

    private fun handleRpcFrame(payload: JsonObject) {
        val envelopeJson = RelayFrameCodec.encodeEnvelope(payload.toString())
        val results = try {
            inboundAssembler.handleEnvelope(envelopeJson)
        } catch (e: Exception) {
            return
        }
        for (r in results) {
            when (r) {
                is RelayFrameCodec.Reassembled.Message -> {
                    scope.launch { events.emit(ServerEvent.LogicalFrameFromClient(r.messageSeq, r.payload.size)) }
                    sendAck(payload.str("bridgeSessionId"), r.messageSeq)
                    if (script.duplicateInboundAcks) sendAck(payload.str("bridgeSessionId"), r.messageSeq)
                    dispatchChannelFrame(r.payload)
                }
                else -> Unit
            }
        }
    }

    private fun sendAck(bridgeSessionId: String, ackMessageSeq: Long) {
        val ackJson = json.encodeToString(
            com.zcode.android.core.protocol.relay.RpcFrameAckOut.serializer(),
            com.zcode.android.core.protocol.relay.RpcFrameAckOut(
                bridgeSessionId = bridgeSessionId,
                ackMessageSeq = ackMessageSeq,
            ),
        )
        sendRaw(RelayFrameCodec.encodeEnvelope(ackJson))
    }

    /** Server→client: fragment one logical message and send (per-direction counters from 1). */
    private fun sendLogicalFrame(bytes: ByteArray) {
        val ws = terminalSocket ?: return
        val seq = outSeq.addAndGet(1)
        val messageSeq = outMessageSeq.addAndGet(1)
        if (script.perFrameLatencyMs > 0) Thread.sleep(script.perFrameLatencyMs)
        var envelopes = RelayFrameCodec.fragment(currentBridge(), 0, null, bytes, seq, messageSeq)
            .map { it.envelopeJson }
        if (script.shuffleFragments && envelopes.size > 1) envelopes = envelopes.shuffled()
        if (script.corruptFragmentCrc && envelopes.isNotEmpty()) {
            script.corruptFragmentCrc = false
            envelopes = envelopes.toMutableList().also {
                it[0] = it[0].replace(Regex("\"value\":\"[0-9a-f]{8}\""), "\"value\":\"00000000\"")
            }
        }
        if (script.injectOversizedEnvelope) {
            script.injectOversizedEnvelope = false
            sendRaw(oversizedEnvelope(seq + 1, messageSeq + 1))
            outSeq.addAndGet(1)
            outMessageSeq.addAndGet(1)
        }
        var sends = 0
        for (e in envelopes) {
            sendRaw(e)
            recentOutbound.addLast(e)
            if (recentOutbound.size > 32) recentOutbound.removeFirst()
            sends++
            if (script.duplicateEvery > 0 && sends % script.duplicateEvery == 0) sendRaw(e)
        }
        val limit = script.disconnectAfterFrames
        if (limit != null && messageSeq >= limit) {
            script.disconnectAfterFrames = null
            ws.close(1011, "mock disconnect storm")
        }
    }

    /** #228-class fixture: a physical envelope exceeding the 1 MiB budget. */
    private fun oversizedEnvelope(seq: Long, messageSeq: Long): String {
        val big = "A".repeat(RelayLimits.RELAY_MAX_PHYSICAL_FRAME_BYTES + 4096)
        return """{"type":"data","payload":{"zcode_type":"rpc-frame","bridgeSessionId":"${currentBridge()}","seq":$seq,""" +
            """"messageSeq":$messageSeq,"fragmentIndex":0,"fragmentCount":1,"messageBytes":${big.length},""" +
            """"checksum":{"algorithm":"crc32","value":"00000000"},"dataBase64":"$big"},"client_ts":0}"""
    }

    // ------------------------------------------------------------------ channel (v4)

    @Volatile private var conversationListenerId: Int? = null
    @Volatile private var flowListenerId: Int? = null
    @Volatile private var currentBridgeId: String? = null
    private var subscriptionId = "sub-mock-1"
    private var logEpoch = "epoch-mock-1"

    private fun currentBridge(): String = currentBridgeId ?: "bridge-unopened"

    private fun dispatchChannelFrame(bytes: ByteArray) {
        val values = try {
            RpcSerialization.decodeAll(bytes)
        } catch (e: Exception) {
            return // e.g. sanitized corpus synthetic bytes — geometry only
        }
        val header = values.getOrNull(0) as? RpcValue.Arr ?: return
        val typeV = header.items.getOrNull(0) as? RpcValue.Int ?: return
        val id = (header.items.getOrNull(1) as? RpcValue.Int)?.value ?: return
        val channelName = (header.items.getOrNull(2) as? RpcValue.Str)?.value
        val name = (header.items.getOrNull(3) as? RpcValue.Str)?.value
        val arg = values.getOrNull(1)
        when (typeV.value) {
            ChannelWire.RequestType.EventListen.id -> {
                if (name == "onDynamicConversationFrame") conversationListenerId = id
                if (name == "v4/connection/flow") flowListenerId = id
            }
            ChannelWire.RequestType.Promise.id -> handlePromise(id, channelName, name, arg)
            else -> Unit
        }
    }

    /** Live-proven convention: Promise-call args arrive wrapped in an ARRAY. */
    private fun argAsObject(arg: RpcValue?): JsonObject? = when (arg) {
        is RpcValue.Arr -> (arg.items.firstOrNull() as? RpcValue.Obj)?.json as? JsonObject
        is RpcValue.Obj -> arg.json as? JsonObject
        else -> null
    }

    private fun handlePromise(id: Int, channelName: String?, name: String?, arg: RpcValue?) {
        if (channelName != "zcode-agent") return
        when (name) {
            "helloConversationV4" -> {
                val hello = buildJsonObject {
                    put("kind", JsonPrimitive("hello"))
                    put("protocolVersion", JsonPrimitive(3))
                    put("connectionId", JsonPrimitive("mock-conn-1"))
                    put("clientMode", JsonPrimitive("web-remote-replayable"))
                    put("deliveryProfile", JsonPrimitive("replayable"))
                    put("serverTime", JsonPrimitive(System.currentTimeMillis().toDouble()))
                    put("capabilities", buildJsonObject {
                        put("nativeDialogs", JsonPrimitive(true))
                        put("localTerminal", JsonPrimitive(true))
                        put("binaryFrames", JsonPrimitive(false))
                        put("compression", JsonPrimitive("none"))
                    })
                    put("auth", buildJsonObject { })
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(hello))
            }
            "initializeConversationV4" -> {
                val hello = argAsObject(arg)
                if (hello == null || hello.strOrNull("kind") != "clientHello") {
                    respondError(id, "fault.connection.handshakeRequired")
                } else {
                    respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Undefined)
                }
            }
            "subscribeConversationV4" -> {
                val params = argAsObject(arg) ?: return respondError(id, "fault.subscribe.params")
                val sessionId = params.str("sessionId")
                subscriptionId = "sub-mock-$sessionId"
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("ack", buildJsonObject {
                        put("subscriptionId", JsonPrimitive(subscriptionId))
                        put("mode", JsonPrimitive("snapshot"))
                        put("logEpoch", JsonPrimitive(logEpoch))
                    })
                }))
                // Initial snapshot rides the post-response notification (schema-true timing)
                pushTopicFrame(sessionId, DeliveryKind.INITIAL, snapshot = true, fromSeq = 0, toSeq = 1)
                script.flowStateAfterSubscribe?.let { st ->
                    script.flowStateAfterSubscribe = null
                    (flowListenerId ?: conversationListenerId)?.let { lit ->
                        val bytes = RpcSerialization.encodeAll(
                            listOf(
                                RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(lit))),
                                RpcValue.Obj(buildJsonObject {
                                    put("connectionId", JsonPrimitive("mock-conn-1"))
                                    put("state", JsonPrimitive(st))
                                }),
                            )
                        )
                        sendLogicalFrame(bytes)
                    }
                }
            }
            "resyncConversationV4" -> respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                put("ack", buildJsonObject {
                    put("subscriptionId", JsonPrimitive(subscriptionId))
                    put("mode", JsonPrimitive("snapshot"))
                    put("logEpoch", JsonPrimitive(logEpoch))
                })
            }))
            "conversationRowsRangeV4" -> {
                val params = argAsObject(arg) ?: return respondError(id, "fault.rowsRange.params")
                val limit = params.strOrNull("limit")?.toIntOrNull() ?: 10
                val rows = (0 until limit.coerceAtMost(workspace.rowCount)).map { i ->
                    buildJsonObject {
                        put("rowId", JsonPrimitive(i + 1))
                        put("kind", JsonPrimitive(if (i % 2 == 0) "assistantText" else "toolCall"))
                        put("text", JsonPrimitive("synthetic row ${i + 1}"))
                    }
                }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("rows", JsonArray(rows))
                    put("atSeq", JsonPrimitive(1))
                    put("atRevision", JsonPrimitive(1))
                    put("atLogEpoch", JsonPrimitive(logEpoch))
                    put("hasMore", JsonPrimitive(false))
                }))
            }
            "sendConversationCommandV4" -> {
                val env = argAsObject(arg)
                val commandId = env?.strOrNull("commandId") ?: "unknown"
                scope.launch { events.emit(ServerEvent.CommandReceived(env?.strOrNull("type") ?: "unknown", commandId)) }
                respond(id, ChannelWire.ResponseType.PromiseSuccess, RpcValue.Obj(buildJsonObject {
                    put("commandId", JsonPrimitive(commandId))
                    put("status", JsonPrimitive("accepted"))
                    put("revisionAtDecision", JsonPrimitive(7))
                }))
            }
            else -> respondError(id, "fault.mock.unknownMethod")
        }
    }

    private fun handleBridgeOpen(ws: okhttp3.WebSocket, payload: JsonObject) {
        val requestId = payload.str("requestId")
        val recovery = payload.strOrNull("recoveryId")
        currentBridgeId = payload.str("bridgeSessionId")
        scope.launch { events.emit(ServerEvent.BridgeOpened(payload.str("workspaceKey"), recovery)) }
        if (recovery != null) {
            // Bridge replay semantics (Phase 1 live-proven): re-opening with the same
            // recoveryId replays the bridge's recent logical frames.
            val replay = recentOutbound.toList()
            recentOutbound.clear()
            for (e in replay) sendRaw(e)
        }
        sendRaw(
            RelayFrameCodec.encodeEnvelope(
                json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("zcode_type", JsonPrimitive("workspace-bridge-ready"))
                    put("requestId", JsonPrimitive(requestId))
                    put("bridgeSessionId", JsonPrimitive(currentBridgeId))
                    put("bridgeGeneration", JsonPrimitive(0))
                    put("bridge", buildJsonObject {
                        put("kind", JsonPrimitive("local"))
                        put("workspaceKey", JsonPrimitive(workspace.workspaceKey))
                        put("workspacePath", JsonPrimitive(workspace.workspacePath))
                        workspace.workspaceIdentity?.let { put("workspaceIdentity", JsonPrimitive(it)) }
                        put("initialTaskId", JsonPrimitive(workspace.sessionId))
                    })
                })
            )
        )
        if (script.sendInitializeAfterBridge) {
            sendLogicalFrame(initializeBytes())
        }
    }

    private fun initializeBytes(): ByteArray =
        RpcSerialization.encodeAll(
            listOf(RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.Initialize.id))), RpcValue.Undefined)
        )

    private fun respond(id: Int, type: ChannelWire.ResponseType, data: RpcValue) {
        sendLogicalFrame(
            RpcSerialization.encodeAll(
                listOf(RpcValue.Arr(listOf(RpcValue.Int(type.id), RpcValue.Int(id))), data)
            )
        )
    }

    private fun respondError(id: Int, fault: String) {
        respond(
            id, ChannelWire.ResponseType.PromiseError,
            RpcValue.Obj(buildJsonObject { put("message", JsonPrimitive(fault)); put("name", JsonPrimitive("Error")) })
        )
    }

    /** Build + send a v4 topic frame through the REAL TopicWireFrameCodec (schema-true). */
    fun pushTopicFrame(sessionId: String, deliveryKind: DeliveryKind, snapshot: Boolean, fromSeq: Long, toSeq: Long) {
        val listener = conversationListenerId ?: return
        val topic = "conversation/$sessionId"
        val frame = buildJsonObject {
            put("topic", JsonPrimitive(topic))
            put("subscriptionId", JsonPrimitive(subscriptionId))
            put("fromSeq", JsonPrimitive(fromSeq))
            put("toSeq", JsonPrimitive(toSeq))
            put("sentAt", JsonPrimitive(System.currentTimeMillis().toDouble()))
            put("payload", buildJsonObject {
                if (snapshot) {
                    put("kind", JsonPrimitive("snapshot"))
                    put("snapshot", buildJsonObject {
                        put("sessionId", JsonPrimitive(sessionId))
                        put("rows", JsonArray((0 until workspace.rowCount).map { i ->
                            buildJsonObject {
                                put("rowId", JsonPrimitive(i + 1))
                                put("kind", JsonPrimitive(if (i % 2 == 0) "assistantText" else "reasoning"))
                                put("text", JsonPrimitive("synthetic $i"))
                            }
                        }))
                    })
                } else {
                    put("kind", JsonPrimitive("deltas"))
                    put("deltas", JsonArray(listOf(buildJsonObject {
                        put("op", JsonPrimitive("row.delta"))
                        put("rowId", JsonPrimitive(1))
                        put("path", JsonPrimitive("text"))
                        put("append", JsonPrimitive("…"))
                    })))
                }
            })
        }
        val wireFrames = TopicWireFrameCodec.encode(
            frame = frame,
            deliveryKind = deliveryKind,
            topic = topic,
            subscriptionId = subscriptionId,
            logicalFrameId = "lf-${System.nanoTime()}",
            logicalFrameOrdinal = 1,
        )
        for (wire in wireFrames) {
            val bytes = RpcSerialization.encodeAll(
                listOf(
                    RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.EventFire.id), RpcValue.Int(listener))),
                    RpcValue.Obj(buildJsonObject {
                        put("topic", JsonPrimitive(topic))
                        put("frame", wireJson(wire))
                    }),
                )
            )
            sendLogicalFrame(bytes)
        }
    }

    private fun wireJson(wire: TopicWireFrame): JsonObject = buildJsonObject {
        put("wireVersion", JsonPrimitive(wire.wireVersion))
        put("kind", JsonPrimitive(if (wire is TopicWireFrame.Complete) "complete" else "fragment"))
        put("deliveryKind", JsonPrimitive(wire.deliveryKind.name.lowercase()))
        put("logicalFrameId", JsonPrimitive(wire.logicalFrameId))
        put("logicalFrameOrdinal", JsonPrimitive(wire.logicalFrameOrdinal))
        put("topic", JsonPrimitive(wire.topic))
        put("subscriptionId", JsonPrimitive(wire.subscriptionId))
        when (wire) {
            is TopicWireFrame.Complete -> put("frame", wire.frame)
            is TopicWireFrame.Fragment -> {
                put("fragmentIndex", JsonPrimitive(wire.fragmentIndex))
                put("fragmentCount", JsonPrimitive(wire.fragmentCount))
                put("logicalBytes", JsonPrimitive(wire.logicalBytes))
                put("checksum", buildJsonObject {
                    put("algorithm", JsonPrimitive("crc32"))
                    put("value", JsonPrimitive(wire.checksum))
                })
                put("dataBase64", JsonPrimitive(Base64.getEncoder().encodeToString(wire.data)))
            }
        }
    }

    // ------------------------------------------------------------------ control plane

    private fun sendBootstrapResponse(ws: okhttp3.WebSocket, requestId: String) {
        sendTo(ws, raw = RelayFrameCodec.encodeEnvelope(
            json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("zcode_type", JsonPrimitive("bootstrap-response"))
                put("requestId", JsonPrimitive(requestId))
                put("success", JsonPrimitive(true))
                put("result", buildJsonObject {
                    put("windowControlSessionId", JsonPrimitive("synth-window-1"))
                    put("desktopAppVersion", JsonPrimitive(workspace.desktopAppVersion))
                    put("workspaces", JsonArray(listOf(buildJsonObject {
                        put("workspacePath", JsonPrimitive(workspace.workspacePath))
                        put("label", JsonPrimitive("Synthetic One"))
                        put("kind", JsonPrimitive("local"))
                        put("workspaceKey", JsonPrimitive(workspace.workspaceKey))
                    })))
                    put("tasks", JsonArray(listOf(buildJsonObject {
                        put("id", JsonPrimitive(workspace.sessionId))
                        put("title", JsonPrimitive("Synthetic task"))
                        put("status", JsonPrimitive("idle"))
                    })))
                })
            })
        ))
    }

    private fun sendWorkspaceListResponse(ws: okhttp3.WebSocket, requestId: String) {
        sendTo(ws, raw = RelayFrameCodec.encodeEnvelope(
            json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("zcode_type", JsonPrimitive("workspace-list-response"))
                put("requestId", JsonPrimitive(requestId))
                put("success", JsonPrimitive(true))
                put("result", buildJsonObject {
                    put("workspaces", JsonArray(listOf(buildJsonObject {
                        put("workspacePath", JsonPrimitive(workspace.workspacePath))
                        put("label", JsonPrimitive("Synthetic One"))
                        put("kind", JsonPrimitive("local"))
                        put("workspaceKey", JsonPrimitive(workspace.workspaceKey))
                    })))
                    put("activeWorkspaceKey", JsonPrimitive(workspace.workspaceKey))
                })
            })
        ))
    }

    // ------------------------------------------------------------------ plumbing

    private fun sendTo(ws: okhttp3.WebSocket, fields: Map<String, JsonPrimitive>? = null, raw: String? = null) {
        val text = raw ?: json.encodeToString(JsonObject.serializer(), JsonObject(fields ?: emptyMap()))
        try {
            ws.send(text)
        } catch (e: Exception) {
            // closed socket — mock-side noise
        }
    }

    private fun sendRaw(text: String) {
        terminalSocket?.let { sendTo(it, raw = text) }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""
    private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        /** Server-side proof verification — EXACT desktop construction (INV-10 parity). */
        fun hmacProof(passHash: String, nonce: String, role: String, sid: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passHash.encodeToByteArray(), "HmacSHA256"))
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal("${nonce}|${role}|${sid}".encodeToByteArray()))
        }
    }
}
