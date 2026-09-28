package com.zcode.android.core.protocol.relay

import com.zcode.android.core.protocol.bytes.Crc32Wire
import com.zcode.android.core.protocol.bytes.ProtocolException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Relay rpc-frame codec: fragments outbound logical messages into bounded rpc-frames,
 * reassembles inbound fragments, and produces/consumes the physical JSON envelopes.
 *
 * Bounds are the bundle-extracted adapter limits: physical envelope ≤ 1 MiB (the desktop
 * drops oversize messages), fragments ≤ 64, logical message ≤ 16 MiB, assembly ≤ 30 s.
 * The fragment chunk size is chosen so base64 (×4/3) plus envelope overhead stays under
 * the physical limit — checked before allocation (INV-5).
 */
object RelayFrameCodec {

    private val encodeJson = Json { encodeDefaults = true; explicitNulls = false }
    private val decodeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val BASE64_ENCODER = Base64.getEncoder()
    private val BASE64_DECODER = Base64.getDecoder()

    /**
     * Max decoded bytes per fragment: the WHOLE physical envelope (JSON overhead +
     * base64-inflated payload) must stay under 1 MiB. Overhead headroom 1 KiB keeps the
     * check conservative; allocation still re-verifies before send (INV-5).
     */
    private val MAX_FRAGMENT_DECODED_BYTES =
        ((RelayLimits.RELAY_MAX_PHYSICAL_FRAME_BYTES - 1024) * 3 / 4) - 3

    data class OutboundFragment(
        val envelopeJson: String,
        val fragmentIndex: Int,
        val fragmentCount: Int,
    )

    fun newBridgeSessionId(random: (Int) -> ByteArray = { ByteArray(it).also(java.security.SecureRandom()::nextBytes) }): String {
        val raw = random(16)
        return buildString {
            for (b in raw) append("0123456789abcdefghijklmnopqrstuvwxyz-._~"[(b.toInt() and 0xFF) % 40])
        }
    }

    /**
     * Fragment one logical Channel message into rpc-frame envelopes.
     * [seq] is the physical envelope sequence counter (monotonic per bridge),
     * [messageSeq] the logical message counter (what the peer acks).
     */
    fun fragment(
        bridgeSessionId: String,
        bridgeGeneration: Int?,
        recoveryId: String?,
        payload: ByteArray,
        seq: Long,
        messageSeq: Long,
    ): List<OutboundFragment> {
        require(bridgeSessionId.matches(Regex(RelayLimits.TRANSPORT_ID_REGEX))) { "bridgeSessionId violates transport id class" }
        if (payload.isEmpty() || payload.size > RelayLimits.RELAY_MAX_MESSAGE_BYTES) {
            throw ProtocolException("logical message size ${payload.size} outside 1..16MiB")
        }
        val fragmentCount = (payload.size + MAX_FRAGMENT_DECODED_BYTES - 1) / MAX_FRAGMENT_DECODED_BYTES
        if (fragmentCount > RelayLimits.RELAY_MAX_FRAGMENTS) {
            throw ProtocolException("message needs $fragmentCount fragments > ${RelayLimits.RELAY_MAX_FRAGMENTS}")
        }
        val out = ArrayList<OutboundFragment>(fragmentCount)
        var offset = 0
        for (index in 0 until fragmentCount) {
            val chunkEnd = minOf(offset + MAX_FRAGMENT_DECODED_BYTES, payload.size)
            val chunk = payload.copyOfRange(offset, chunkEnd)
            offset = chunkEnd
            val frame = RpcFrameOut(
                bridgeSessionId = bridgeSessionId,
                bridgeGeneration = bridgeGeneration,
                recoveryId = recoveryId,
                seq = seq + index,
                messageSeq = messageSeq,
                fragmentIndex = index,
                fragmentCount = fragmentCount,
                messageBytes = payload.size,
                checksum = Checksum(value = Crc32Wire.checksum(chunk)),
                dataBase64 = BASE64_ENCODER.encodeToString(chunk),
            )
            val json = encodeEnvelope(encodeJson.encodeToString(frame))
            if (json.encodeToByteArray().size > RelayLimits.RELAY_MAX_PHYSICAL_FRAME_BYTES) {
                throw ProtocolException("physical envelope exceeds 1 MiB before send")
            }
            out.add(OutboundFragment(json, index, fragmentCount))
        }
        return out
    }

    fun encodeEnvelope(payloadJson: String, clientTs: Long? = null): String {
        val ts = clientTs?.let { "\"client_ts\":$it," } ?: ""
        return "{\"type\":\"data\",${ts}\"payload\":$payloadJson}"
    }

    fun encodeAck(
        bridgeSessionId: String,
        bridgeGeneration: Int?,
        recoveryId: String?,
        ackMessageSeq: Long,
    ): String = encodeEnvelope(
        encodeJson.encodeToString(
            RpcFrameAckOut(
                bridgeSessionId = bridgeSessionId,
                bridgeGeneration = bridgeGeneration,
                recoveryId = recoveryId,
                ackMessageSeq = ackMessageSeq,
            )
        )
    )

    fun encodeControl(json: String): String = json

    sealed class Inbound {
        data class RpcFrame(
            val bridgeSessionId: String,
            val bridgeGeneration: Long?,
            val recoveryId: String?,
            val seq: Long,
            val messageSeq: Long,
            val fragmentIndex: Int,
            val fragmentCount: Int,
            val messageBytes: Int,
        ) {
            internal val parts = HashMap<Int, ByteArray>()
        }

        data class RpcFrameAck(val bridgeSessionId: String, val ackMessageSeq: Long)
        data class Control(val payload: kotlinx.serialization.json.JsonObject) : Inbound()
    }

    @Serializable
    private data class FrameIn(
        @SerialName("zcode_type") val zcodeType: String,
        val bridgeSessionId: String,
        val bridgeGeneration: Long? = null,
        val recoveryId: String? = null,
        val seq: Long = 0,
        val messageSeq: Long = 0,
        val fragmentIndex: Int = 0,
        val fragmentCount: Int = 1,
        val messageBytes: Int = 0,
        val checksum: Checksum? = null,
        val dataBase64: String? = null,
    )

    /**
     * Feed one `data` envelope; returns delivered logical messages and acks.
     * CRC mismatch / overflow / wrong-fragment errors are ProtocolException — the
     * caller escalates to resync (S11), never merges (INV-14).
     */
    class Assembler {
        private val staged = LinkedHashMap<Pair<String, Long>, Inbound.RpcFrame>()

        fun handleEnvelope(envelopeJson: String): List<Reassembled> {
            val envelope = decodeJson.decodeFromString(DataEnvelope.serializer(), envelopeJson)
            if (envelope.type != "data") throw ProtocolException("expected data envelope, got ${envelope.type}")
            val obj = envelope.payload as? kotlinx.serialization.json.JsonObject
                ?: throw ProtocolException("payload is not an object")
            val ztype = (obj["zcode_type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            return when (ztype) {
                "rpc-frame" -> listOfNotNull(handleRpcFrame(obj))
                "rpc-frame-ack" -> listOf(
                    Reassembled.Ack(
                        bridgeSessionId = obj.str("bridgeSessionId"),
                        ackMessageSeq = obj.long("ackMessageSeq"),
                    )
                )
                else -> listOf(Reassembled.Control(ztype, obj))
            }
        }

        private fun handleRpcFrame(obj: kotlinx.serialization.json.JsonObject): Reassembled.Message? {
            val frame = decodeJson.decodeFromJsonElement(FrameIn.serializer(), obj)
            val data = frame.dataBase64
                ?: throw ProtocolException("rpc-frame missing dataBase64")
            val chunk = try {
                BASE64_DECODER.decode(data)
            } catch (e: IllegalArgumentException) {
                throw ProtocolException("rpc-frame dataBase64 is not canonical base64")
            }
            val expected = frame.checksum?.value ?: throw ProtocolException("rpc-frame missing checksum")
            if (Crc32Wire.checksum(chunk) != expected) {
                throw ProtocolException("rpc-frame crc32 mismatch (messageSeq=${frame.messageSeq})")
            }
            if (frame.fragmentIndex < 0 || frame.fragmentIndex >= frame.fragmentCount ||
                frame.fragmentCount > RelayLimits.RELAY_MAX_FRAGMENTS ||
                frame.messageBytes <= 0 || frame.messageBytes > RelayLimits.RELAY_MAX_MESSAGE_BYTES
            ) {
                throw ProtocolException("rpc-frame fragment geometry invalid")
            }
            if (chunk.size > frame.messageBytes) {
                throw ProtocolException("rpc-frame fragment larger than declared messageBytes")
            }
            // The peer's chunk size is not carried on the wire (Q1 long tail): stage parts
            // independently and assemble assuming equal-size chunks with a short tail —
            // fragment 0's length is the chunk size whenever fragmentCount > 1.
            val key = frame.bridgeSessionId to frame.messageSeq
            var entry = staged[key]
            if (entry == null) {
                if (staged.size >= RelayLimits.LOGICAL_FRAME_ASSEMBLY_MAX_CONCURRENT) {
                    throw ProtocolException("too many concurrent assemblies (INV-5)")
                }
                entry = Inbound.RpcFrame(
                    bridgeSessionId = frame.bridgeSessionId,
                    bridgeGeneration = frame.bridgeGeneration,
                    recoveryId = frame.recoveryId,
                    seq = frame.seq,
                    messageSeq = frame.messageSeq,
                    fragmentIndex = frame.fragmentIndex,
                    fragmentCount = frame.fragmentCount,
                    messageBytes = frame.messageBytes,
                )
                staged[key] = entry
            }
            if (entry.parts.putIfAbsent(frame.fragmentIndex, chunk) != null) return null // duplicate fragment
            if (entry.parts.size < entry.fragmentCount) return null

            // Assemble: equal chunk size from fragment 0 (never the tail when count > 1).
            val payload = ByteArray(entry.messageBytes)
            if (entry.fragmentCount == 1) {
                if (entry.parts[0]!!.size != entry.messageBytes) {
                    throw ProtocolException("single-fragment size != messageBytes")
                }
                entry.parts[0]!!.copyInto(payload)
            } else {
                val chunkSize = entry.parts[0]!!.size
                for (i in 0 until entry.fragmentCount) {
                    val part = entry.parts[i] ?: throw ProtocolException("assembly gap")
                    val offset = i * chunkSize
                    if (offset + part.size > entry.messageBytes) {
                        throw ProtocolException("fragment assembly exceeds messageBytes")
                    }
                    part.copyInto(payload, offset)
                }
            }
            staged.remove(key)
            return Reassembled.Message(
                bridgeSessionId = entry.bridgeSessionId,
                messageSeq = entry.messageSeq,
                payload = payload,
            )
        }

        fun stagedBridgeCount(): Int = staged.size
    }

    sealed class Reassembled {
        data class Message(val bridgeSessionId: String, val messageSeq: Long, val payload: ByteArray) : Reassembled()
        data class Ack(val bridgeSessionId: String, val ackMessageSeq: Long) : Reassembled()
        data class Control(val zcodeType: String?, val payload: kotlinx.serialization.json.JsonObject) : Reassembled()
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: throw ProtocolException("missing string field $key")

    private fun kotlinx.serialization.json.JsonObject.long(key: String): Long =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong()
            ?: throw ProtocolException("missing numeric field $key")
}
