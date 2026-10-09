package com.zcode.android.core.protocol.v4

import com.zcode.android.core.protocol.bytes.Crc32Wire
import com.zcode.android.core.protocol.bytes.ProtocolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * v4 wire layer — Kotlin port of packages/shared/src/zcode-protocol-v4/ (v3.14.3,
 * commit 29628c9; schema texts verified 2026-09-28): hello/clientHello handshake,
 * HostCapabilities negotiation, TopicWireFrame physical envelope (complete/fragment)
 * and logical-frame assembly within the verified limits.
 *
 * Tolerance policy (INV-8/INV-14): unknown fields are preserved via raw JsonObject;
 * an unknown `kind` decodes as Unknown and is counted; a KNOWN shape that violates its
 * schema (bad wireVersion, bad deliveryKind, checksum mismatch, bounds breach) is a
 * typed failure — callers escalate to resync, never merge.
 */

const val V4_WIRE_PROTOCOL_VERSION = 3

/** transport.ts deliveryKind enum ("publisher reservation; consumer must not guess"). */
enum class DeliveryKind { INITIAL, ONLINE, RECOVERY }

/** hostCapabilitiesSchema (transport.ts:23) — required bits + optional bits via ===true. */
data class HostCapabilities(
    val nativeDialogs: Boolean,
    val localTerminal: Boolean,
    val binaryFrames: Boolean,
    val compression: String,
    val workspaceHookReview: Boolean = false,
    val independentPlanState: Boolean = false,
    val workflowRunDeltas: Boolean = false,
) {
    companion object {
        /**
         * Legacy hosts missing optional bits = false; consumers must test `=== true`
         * (verified schema comment). Unknown compression value → typed failure.
         */
        fun decode(obj: JsonObject): HostCapabilities {
            fun bool(key: String): Boolean =
                (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                    ?: throw ProtocolException("HostCapabilities.$key missing/not boolean")
            val compression = (obj["compression"] as? JsonPrimitive)?.content
                ?: throw ProtocolException("HostCapabilities.compression missing")
            if (compression != "none" && compression != "permessage-deflate") {
                throw ProtocolException("HostCapabilities.compression invalid: $compression")
            }
            fun optBool(key: String): Boolean = (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
            return HostCapabilities(
                nativeDialogs = bool("nativeDialogs"),
                localTerminal = bool("localTerminal"),
                binaryFrames = bool("binaryFrames"),
                compression = compression,
                workspaceHookReview = optBool("workspaceHookReview"),
                independentPlanState = optBool("independentPlanState"),
                workflowRunDeltas = optBool("workflowRunDeltas"),
            )
        }
    }
}

/** helloMessageSchema (transport.ts:31) — server → client, strictly versioned. */
data class Hello(
    val connectionId: String,
    val clientMode: String,
    val deliveryProfile: String,
    val serverTime: Double,
    val capabilities: HostCapabilities,
) {
    companion object {
        fun decode(obj: JsonObject): Hello {
            val kind = (obj["kind"] as? JsonPrimitive)?.content
            if (kind != "hello") throw ProtocolException("expected kind=hello, got $kind")
            val version = (obj["protocolVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (version != V4_WIRE_PROTOCOL_VERSION) {
                throw V4WireVersionException("hello protocolVersion=$version != $V4_WIRE_PROTOCOL_VERSION")
            }
            val clientMode = (obj["clientMode"] as? JsonPrimitive)?.content
                ?: throw ProtocolException("hello.clientMode missing")
            val profile = (obj["deliveryProfile"] as? JsonPrimitive)?.content
                ?: throw ProtocolException("hello.deliveryProfile missing")
            val expected = if (clientMode == "desktop-continuous") "continuous" else "replayable"
            if (profile != expected) {
                throw ProtocolException("clientMode and deliveryProfile must match (superRefine)")
            }
            val caps = obj["capabilities"] as? JsonObject ?: throw ProtocolException("hello.capabilities missing")
            val serverTime = (obj["serverTime"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                ?: throw ProtocolException("hello.serverTime missing")
            return Hello(
                connectionId = (obj["connectionId"] as? JsonPrimitive)?.content
                    ?: throw ProtocolException("hello.connectionId missing"),
                clientMode = clientMode,
                deliveryProfile = profile,
                serverTime = serverTime,
                capabilities = HostCapabilities.decode(caps),
            )
        }
    }
}

/** Hard wire-version gate (§39.3: wire mismatch → compat screen, fail fast). */
class V4WireVersionException(message: String) : ProtocolException(message)

/** clientHelloSchema (transport.ts:68) — client → server. */
data class ClientHello(
    val clientId: String,
    val clientKind: String?,
    val appVersion: String,
    /** workspaceHookReviewUi / workflowRunDeltas; declared only per the one-way rules. */
    val capabilities: JsonObject? = null,
) {
    fun encode(): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("clientHello"))
        put("protocolVersion", JsonPrimitive(V4_WIRE_PROTOCOL_VERSION))
        put("clientId", JsonPrimitive(clientId))
        clientKind?.let { put("clientKind", JsonPrimitive(it)) }
        put("appVersion", JsonPrimitive(appVersion))
        capabilities?.let { put("capabilities", it) }
    }
}

/** V4_METHODS (transport.ts:330) — verbatim method names. */
object V4Methods {
    const val CONNECTION_FLOW = "v4/connection/flow"
    const val CONTROLLER_SUBSCRIBE = "v4/controller/subscribe"
    const val CONTROLLER_RESYNC = "v4/controller/resync"
    const val CONTROLLER_UNSUBSCRIBE = "v4/controller/unsubscribe"
    const val CONVERSATION_SUBSCRIBE = "v4/conversation/subscribe"
    const val CONVERSATION_RESYNC = "v4/conversation/resync"
    const val CONVERSATION_UNSUBSCRIBE = "v4/conversation/unsubscribe"
    const val CONVERSATION_ROWS_RANGE = "v4/conversation/rowsRange"
    const val CONVERSATION_PLANS = "v4/conversation/plans"
    const val CONVERSATION_FILE_CHANGES = "v4/conversation/fileChanges"
    const val CONVERSATION_USAGE = "v4/conversation/usage"
    const val USAGE_STATS = "v4/usage/stats"
    const val COMMANDS_QUERY = "v4/commands/query"
    const val COMMAND = "v4/command"
    const val ATTACHMENT_BEGIN = "v4/attachment/begin"
    const val ATTACHMENT_CHUNK = "v4/attachment/chunk"
    const val ATTACHMENT_COMMIT = "v4/attachment/commit"
    const val ATTACHMENT_ABORT = "v4/attachment/abort"
    const val ATTACHMENT_READ = "v4/attachment/read"
}

/** V4_NOTIFICATIONS (transport.ts:397). */
object V4Notifications {
    const val CONVERSATION_FRAME = "v4/conversation/frame"
    const val CONVERSATION_TELEMETRY_FACT = "v4/telemetry/event"
    const val LOCAL_TTFT_FACTS = "v4/telemetry/local-ttft"
    const val CUA_PERMISSION_OBSERVATION = "v4/cua/permission-observation"
}

/** v4ConnectionFlowStateSchema — saturated/drained/closed (§28.4 backpressure input). */
enum class ConnectionFlowState { SATURATED, DRAINED, CLOSED }

// ---------------------------------------------------------------- TopicWireFrame

private val json = Json { ignoreUnknownKeys = true }

/** TopicWireFrame (wire.ts) — physical envelope of one logical topic frame. */
sealed class TopicWireFrame {
    abstract val wireVersion: Int
    abstract val deliveryKind: DeliveryKind
    abstract val logicalFrameId: String
    abstract val logicalFrameOrdinal: Long
    abstract val topic: String
    abstract val subscriptionId: String

    data class Complete(
        override val wireVersion: Int,
        override val deliveryKind: DeliveryKind,
        override val logicalFrameId: String,
        override val logicalFrameOrdinal: Long,
        override val topic: String,
        override val subscriptionId: String,
        val frame: JsonObject,
        val raw: JsonObject,
    ) : TopicWireFrame()

    data class Fragment(
        override val wireVersion: Int,
        override val deliveryKind: DeliveryKind,
        override val logicalFrameId: String,
        override val logicalFrameOrdinal: Long,
        override val topic: String,
        override val subscriptionId: String,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val logicalBytes: Int,
        val checksum: String,
        val data: ByteArray,
    ) : TopicWireFrame()

    companion object {
        fun decode(obj: JsonObject): TopicWireFrame {
            val version = (obj["wireVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (version != V4_WIRE_PROTOCOL_VERSION) {
                throw V4WireVersionException("TopicWireFrame wireVersion=$version != $V4_WIRE_PROTOCOL_VERSION")
            }
            val kind = (obj["kind"] as? JsonPrimitive)?.content
            if (kind != "complete" && kind != "fragment") {
                throw ProtocolException("TopicWireFrame kind invalid: $kind")
            }
            val delivery = when ((obj["deliveryKind"] as? JsonPrimitive)?.content) {
                "initial" -> DeliveryKind.INITIAL
                "online" -> DeliveryKind.ONLINE
                "recovery" -> DeliveryKind.RECOVERY
                // "坏 deliveryKind 必须进入 owned assembler 产生 typed fault" — no guessing.
                else -> throw ProtocolException("TopicWireFrame deliveryKind invalid")
            }
            val logicalFrameId = obj.string("logicalFrameId")
            val ordinal = obj["logicalFrameOrdinal"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw ProtocolException("TopicWireFrame logicalFrameOrdinal missing")
            val topic = obj.string("topic")
            val subscriptionId = obj.string("subscriptionId")
            return if (kind == "complete") {
                val frame = obj["frame"] as? JsonObject
                    ?: throw ProtocolException("complete wire frame payload missing")
                if (frame.strOrNull("topic")?.let { it != topic } == true) {
                    throw ProtocolException("complete wire topic must match logical frame topic")
                }
                if (frame.strOrNull("subscriptionId")?.let { it != subscriptionId } == true) {
                    throw ProtocolException("complete wire subscriptionId must match logical frame subscriptionId")
                }
                Complete(version, delivery, logicalFrameId, ordinal, topic, subscriptionId, frame, obj)
            } else {
                val index = obj.intOrNull("fragmentIndex") ?: throw ProtocolException("fragment.fragmentIndex missing")
                val count = obj.intOrNull("fragmentCount") ?: throw ProtocolException("fragment.fragmentCount missing")
                val logicalBytes = obj.intOrNull("logicalBytes") ?: throw ProtocolException("fragment.logicalBytes missing")
                if (count > V4Limits.LOGICAL_FRAME_ASSEMBLY_MAX_FRAGMENTS) {
                    throw ProtocolException("fragmentCount ${count} > ${V4Limits.LOGICAL_FRAME_ASSEMBLY_MAX_FRAGMENTS}")
                }
                if (index < 0 || index >= count) {
                    throw ProtocolException("fragmentIndex must be smaller than fragmentCount")
                }
                if (count > logicalBytes) {
                    throw ProtocolException("fragmentCount cannot exceed logicalBytes")
                }
                if (logicalBytes > V4Limits.LOGICAL_FRAME_ASSEMBLY_MAX_BYTES) {
                    throw ProtocolException("logical frame exceeds 16 MiB assembly bound (INV-5)")
                }
                val checksum = (obj["checksum"] as? JsonObject)?.get("value")?.jsonPrimitive?.content
                    ?: throw ProtocolException("fragment.checksum missing")
                if (!Regex("^[0-9a-f]{8}$").matches(checksum)) {
                    throw ProtocolException("fragment.checksum not 8-hex crc32")
                }
                val b64 = obj.string("dataBase64")
                val data = try {
                    Base64.getDecoder().decode(b64)
                } catch (e: IllegalArgumentException) {
                    throw ProtocolException("fragment.dataBase64 not canonical base64")
                }
                Fragment(version, delivery, logicalFrameId, ordinal, topic, subscriptionId, index, count, logicalBytes, checksum, data)
            }
        }

        private fun JsonObject.string(key: String): String =
            (this[key] as? JsonPrimitive)?.content ?: throw ProtocolException("TopicWireFrame.$key missing")

        private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content
        private fun JsonObject.intOrNull(key: String): Int? =
            (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
    }
}

/** Verified limits re-declared locally (mirror of RelayLimits PROTOCOL_V4_LIMITS block). */
object V4Limits {
    const val MAX_FRAME_BYTES = 1024 * 1024
    const val LOGICAL_FRAME_ASSEMBLY_MAX_BYTES = 16 * 1024 * 1024
    const val LOGICAL_FRAME_ASSEMBLY_MAX_FRAGMENTS = 1024
    const val LOGICAL_FRAME_ASSEMBLY_MAX_CONCURRENT = 32
    const val LOGICAL_FRAME_ASSEMBLY_MAX_STAGED_BYTES = 32 * 1024 * 1024
    const val LOGICAL_FRAME_ASSEMBLY_TIMEOUT_MS = 30_000
    const val ROWS_RANGE_MAX_LIMIT = 200
}

/**
 * Logical-frame reassembly (wire.ts fragments → JsonObject), bounded per the verified
 * PROTOCOL_V4_LIMITS. All bounds are checked BEFORE allocation (INV-5); CRC failure /
* timeout / overflow are typed failures — callers escalate to resync (S11), never merge.
 */
class LogicalFrameAssembler(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Staged(
        val topic: String,
        val subscriptionId: String,
        val deliveryKind: DeliveryKind,
        val ordinal: Long,
        val logicalBytes: Int,
        val checksum: String,
        val parts: HashMap<Int, ByteArray>,
        val firstSeenMs: Long,
    ) {
        var stagedBytes: Int = 0
    }

    private val staged = LinkedHashMap<String, Staged>()

    /** Bounded dedupe of already-delivered logical frames (re-sends never double-apply). */
    private val deliveredIds = object : LinkedHashMap<String, Unit>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > 256
    }

    fun accept(frame: TopicWireFrame): Assembled? = when (frame) {
        is TopicWireFrame.Complete -> {
            if (deliveredIds.containsKey(frame.logicalFrameId)) return null
            deliveredIds[frame.logicalFrameId] = Unit
            Assembled(
                topic = frame.topic,
                subscriptionId = frame.subscriptionId,
                deliveryKind = frame.deliveryKind,
                logicalFrameId = frame.logicalFrameId,
                logicalFrameOrdinal = frame.logicalFrameOrdinal,
                frame = frame.frame,
            )
        }
        is TopicWireFrame.Fragment -> acceptFragment(frame)
    }

    private fun acceptFragment(f: TopicWireFrame.Fragment): Assembled? {
        evictExpired()
        var entry = staged[f.logicalFrameId]
        if (entry == null) {
            if (staged.size >= V4Limits.LOGICAL_FRAME_ASSEMBLY_MAX_CONCURRENT) {
                throw ProtocolException("too many concurrent logical assemblies (INV-5)")
            }
            if (totalStagedBytes + f.data.size > V4Limits.LOGICAL_FRAME_ASSEMBLY_MAX_STAGED_BYTES) {
                throw ProtocolException("staged logical bytes over 32 MiB bound (INV-5)")
            }
            entry = Staged(
                topic = f.topic,
                subscriptionId = f.subscriptionId,
                deliveryKind = f.deliveryKind,
                ordinal = f.logicalFrameOrdinal,
                logicalBytes = f.logicalBytes,
                checksum = f.checksum,
                parts = HashMap(f.fragmentCount.coerceAtMost(1024)),
                firstSeenMs = clock(),
            )
            staged[f.logicalFrameId] = entry
        }
        if (entry.parts.putIfAbsent(f.fragmentIndex, f.data) != null) return null // duplicate fragment: idempotent drop
        entry.stagedBytes += f.data.size
        totalStagedBytes += f.data.size
        if (entry.parts.size < f.fragmentCount) return null

        // Assemble in index order; every byte accounted, total must equal logicalBytes.
        val payload = ByteArray(entry.logicalBytes)
        var offset = 0
        for (i in 0 until f.fragmentCount) {
            val part = entry.parts[i] ?: throw ProtocolException("assembly gap at fragment $i")
            if (offset + part.size > entry.logicalBytes) {
                throw ProtocolException("assembled bytes exceed logicalBytes")
            }
            part.copyInto(payload, offset)
            offset += part.size
        }
        if (offset != entry.logicalBytes) {
            throw ProtocolException("assembled size $offset != logicalBytes ${entry.logicalBytes}")
        }
        val crc = Crc32Wire.checksum(payload)
        if (crc != entry.checksum) {
            throw ProtocolException("logical frame crc32 mismatch (expected ${entry.checksum}, got $crc)")
        }
        staged.remove(f.logicalFrameId)
        totalStagedBytes -= entry.stagedBytes
        deliveredIds[f.logicalFrameId] = Unit
        return Assembled(
            topic = entry.topic,
            subscriptionId = entry.subscriptionId,
            deliveryKind = entry.deliveryKind,
            logicalFrameId = f.logicalFrameId,
            logicalFrameOrdinal = entry.ordinal,
            frame = try {
                json.parseToJsonElement(payload.decodeToString()).let { it as? JsonObject }
                    ?: throw ProtocolException("logical frame payload is not a JSON object")
            } catch (e: ProtocolException) {
                throw e
            } catch (e: Exception) {
                throw ProtocolException("logical frame payload is not valid JSON: ${e.message?.take(80)}")
            },
        )
    }

    private fun evictExpired() {
        val now = clock()
        val it = staged.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.firstSeenMs > V4Limits.LOGICAL_FRAME_ASSEMBLY_TIMEOUT_MS) {
                it.remove()
                totalStagedBytes -= e.value.stagedBytes
            }
        }
    }

    @Volatile
    private var totalStagedBytes: Int = 0

    fun stagedCount(): Int = staged.size

    /** For fault-injection tests: force-expire staging (simulates the 30 s timeout). */
    fun expireStagedForTest() {
        val it = staged.entries.iterator()
        while (it.hasNext()) {
            totalStagedBytes -= it.next().value.stagedBytes
            it.remove()
        }
    }

    data class Assembled(
        val topic: String,
        val subscriptionId: String,
        val deliveryKind: DeliveryKind,
        val logicalFrameId: String,
        val logicalFrameOrdinal: Long,
        val frame: JsonObject,
    )
}

/**
 * Encoder mirroring wire-codec.ts `encodeTopicWireFrames`: complete-if-fits, else
 * equal-size fragments of the JSON bytes with the LOGICAL crc32 on every fragment.
 * Used by MockZCode (protocol-faithful server side) and round-trip tests — the phone
 * client itself never publishes topic frames.
 */
object TopicWireFrameCodec {

    fun encode(
        frame: JsonObject,
        deliveryKind: DeliveryKind,
        topic: String,
        subscriptionId: String,
        logicalFrameId: String,
        logicalFrameOrdinal: Long,
        maxPhysicalFrameBytes: Int = V4Limits.MAX_FRAME_BYTES,
    ): List<TopicWireFrame> {
        val logical = RpcJsonBytes.stringify(frame)
        require(logical.size <= V4Limits.LOGICAL_FRAME_ASSEMBLY_MAX_BYTES) { "frameAssemblyTooLarge" }

        fun completeWire(): JsonObject = buildJsonObject {
            put("wireVersion", JsonPrimitive(V4_WIRE_PROTOCOL_VERSION))
            put("kind", JsonPrimitive("complete"))
            put("deliveryKind", JsonPrimitive(deliveryKind.wireName()))
            put("logicalFrameId", JsonPrimitive(logicalFrameId))
            put("logicalFrameOrdinal", JsonPrimitive(logicalFrameOrdinal))
            put("topic", JsonPrimitive(topic))
            put("subscriptionId", JsonPrimitive(subscriptionId))
            put("frame", frame)
        }

        fun fragmentWire(index: Int, count: Int, chunk: ByteArray, crc: String): JsonObject = buildJsonObject {
            put("wireVersion", JsonPrimitive(V4_WIRE_PROTOCOL_VERSION))
            put("kind", JsonPrimitive("fragment"))
            put("deliveryKind", JsonPrimitive(deliveryKind.wireName()))
            put("logicalFrameId", JsonPrimitive(logicalFrameId))
            put("logicalFrameOrdinal", JsonPrimitive(logicalFrameOrdinal))
            put("topic", JsonPrimitive(topic))
            put("subscriptionId", JsonPrimitive(subscriptionId))
            put("fragmentIndex", JsonPrimitive(index))
            put("fragmentCount", JsonPrimitive(count))
            put("logicalBytes", JsonPrimitive(logical.size))
            put("checksum", buildJsonObject {
                put("algorithm", JsonPrimitive("crc32"))
                put("value", JsonPrimitive(crc))
            })
            put("dataBase64", JsonPrimitive(Base64.getEncoder().encodeToString(chunk)))
        }

        val complete = completeWire()
        if (RpcJsonBytes.stringify(complete).size <= maxPhysicalFrameBytes) {
            return listOf(TopicWireFrame.decode(complete))
        }
        val crc = Crc32Wire.checksum(logical)
        var low = 1
        var high = minOf(logical.size, maxPhysicalFrameBytes)
        var chunkBytes = 0
        while (low <= high) {
            val candidate = (low + high) / 2
            val probe = fragmentWire(0, 2, ByteArray(candidate), crc)
            if (RpcJsonBytes.stringify(probe).size <= maxPhysicalFrameBytes) {
                chunkBytes = candidate
                low = candidate + 1
            } else {
                high = candidate - 1
            }
        }
        if (chunkBytes < 1) throw ProtocolException("frameEnvelopeTooLarge")
        val fragmentCount = (logical.size + chunkBytes - 1) / chunkBytes
        if (fragmentCount > V4Limits.LOGICAL_FRAME_ASSEMBLY_MAX_FRAGMENTS) {
            throw ProtocolException("frameFragmentCountExceeded")
        }
        val out = ArrayList<TopicWireFrame>(fragmentCount)
        for (index in 0 until fragmentCount) {
            val start = index * chunkBytes
            val end = minOf(start + chunkBytes, logical.size)
            val wire = fragmentWire(index, fragmentCount, logical.copyOfRange(start, end), crc)
            if (RpcJsonBytes.stringify(wire).size > maxPhysicalFrameBytes) {
                throw ProtocolException("frameEnvelopeTooLarge (fail closed)")
            }
            out.add(TopicWireFrame.decode(wire))
        }
        return out
    }

    private fun DeliveryKind.wireName(): String = when (this) {
        DeliveryKind.INITIAL -> "initial"
        DeliveryKind.ONLINE -> "online"
        DeliveryKind.RECOVERY -> "recovery"
    }
}

/** JSON writer byte-compatible with JSON.stringify for topic-frame encoding. */
private object RpcJsonBytes {
    fun stringify(element: kotlinx.serialization.json.JsonElement): ByteArray =
        element.toString().encodeToByteArray()
}
