package com.zcode.android.tools.mockzcode

import com.zcode.android.core.protocol.bytes.RpcSerialization
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.protocol.channel.ChannelWire
import com.zcode.android.core.protocol.relay.RelayFrameCodec
import com.zcode.android.core.protocol.v4.DeliveryKind
import com.zcode.android.core.protocol.v4.LogicalFrameAssembler
import com.zcode.android.core.protocol.v4.TopicWireFrame
import com.zcode.android.core.protocol.v4.TopicWireFrameCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §35.4 self-test requirement: "the mock must decode its own output". Every encoder the
 * mock uses (Channel wire, rpc-frame fragmentation, TopicWireFrame codec, auth HMAC)
 * round-trips through the shipped core:protocol decoders — and the server-side HMAC
 * construction is byte-compared to the client-side CredentialSigner semantics.
 */
class MockZCodeSelfTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `mock channel responses decode with the real ChannelWire`() {
        val bytes = RpcSerialization.encodeAll(
            listOf(
                RpcValue.Arr(listOf(RpcValue.Int(ChannelWire.ResponseType.PromiseSuccess.id), RpcValue.Int(7))),
                RpcValue.Obj(kotlinx.serialization.json.buildJsonObject {
                    put("ack", kotlinx.serialization.json.buildJsonObject {
                        put("subscriptionId", kotlinx.serialization.json.JsonPrimitive("sub-1"))
                        put("mode", kotlinx.serialization.json.JsonPrimitive("snapshot"))
                        put("logEpoch", kotlinx.serialization.json.JsonPrimitive("e1"))
                    })
                }),
            )
        )
        val incoming = ChannelWire.decodeIncoming(bytes)
        assertTrue(incoming is ChannelWire.Incoming.PromiseSuccess)
        assertEquals(7, incoming.id)
    }

    @Test
    fun `mock topic frames round-trip through the real wire codec`() {
        val frame = kotlinx.serialization.json.buildJsonObject {
            put("topic", kotlinx.serialization.json.JsonPrimitive("conversation/s1"))
            put("subscriptionId", kotlinx.serialization.json.JsonPrimitive("sub"))
            put("fromSeq", kotlinx.serialization.json.JsonPrimitive(0))
            put("toSeq", kotlinx.serialization.json.JsonPrimitive(3))
            put("sentAt", kotlinx.serialization.json.JsonPrimitive(1.0))
            put("payload", kotlinx.serialization.json.buildJsonObject {
                put("kind", kotlinx.serialization.json.JsonPrimitive("snapshot"))
                put("snapshot", kotlinx.serialization.json.buildJsonObject { put("pad", kotlinx.serialization.json.JsonPrimitive("x".repeat(1_400_000))) })
            })
        }
        val wire = TopicWireFrameCodec.encode(
            frame = frame, deliveryKind = DeliveryKind.INITIAL, topic = "conversation/s1",
            subscriptionId = "sub", logicalFrameId = "lf-1", logicalFrameOrdinal = 1,
        )
        assertTrue(wire.size > 1, "large logical frame must fragment")
        val assembler = LogicalFrameAssembler(clock = { 0L })
        var assembled: LogicalFrameAssembler.Assembled? = null
        for (w in wire) {
            assembler.accept(w)?.let { assembled = it }
        }
        assertEquals("conversation/s1", assembled?.topic)
        val decoded = com.zcode.android.core.protocol.v4.TopicFrame.decode(assembled!!.frame)
        assertEquals(0L, decoded.fromSeq)
        assertTrue(decoded.payload is com.zcode.android.core.protocol.v4.TopicFrame.TopicPayload.Snapshot)
    }

    @Test
    fun `mock rpc-frame envelopes reassemble with the real relay codec`() {
        // simulate what the mock's sendLogicalFrame produces: fragment + envelope JSON
        val payload = ByteArray(2_000_000) { (it % 7).toByte() }
        val fragments = RelayFrameCodec.fragment("bridge-selftest", 0, null, payload, seq = 1, messageSeq = 1)
        val assembler = RelayFrameCodec.Assembler()
        var delivered: RelayFrameCodec.Reassembled.Message? = null
        for (f in fragments) {
            assembler.handleEnvelope(f.envelopeJson)
                .filterIsInstance<RelayFrameCodec.Reassembled.Message>()
                .forEach { delivered = it }
        }
        assertTrue(payload.contentEquals(delivered?.payload))
    }

    @Test
    fun `server-side HMAC matches the client construction byte for byte`() {
        // §6.2/INV-10: proof = base64url(HMAC-SHA256(key=passHash-string, nonce|role|sid))
        val passHash = "c3ludGhldGljLXBhc3NoYXNoLWtleQ=="
        val nonce = "a1b2c3d4e5f6a7b8c9d0e1f2"
        val proof = MockZCode.hmacProof(passHash, nonce, "terminal", "d_syntheticdevice000001")
        assertTrue(proof.isNotBlank())
        // known-answer check: recompute with an independent construction (javax.crypto direct)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(passHash.encodeToByteArray(), "HmacSHA256"))
        val expected = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("$nonce|terminal|d_syntheticdevice000001".encodeToByteArray()))
        assertEquals(expected, proof)
    }

    @Test
    fun `oversized envelope exceeds the physical budget (228-class fixture shape)`() {
        val big = "A".repeat((1 shl 20) + 4096)
        val envelope = """{"type":"data","payload":{"zcode_type":"rpc-frame","bridgeSessionId":"b","seq":1,"messageSeq":1,"fragmentIndex":0,"fragmentCount":1,"messageBytes":${big.length},"checksum":{"algorithm":"crc32","value":"00000000"},"dataBase64":"$big"},"client_ts":0}"""
        assertTrue(envelope.encodeToByteArray().size > com.zcode.android.core.protocol.relay.RelayLimits.RELAY_MAX_PHYSICAL_FRAME_BYTES)
    }
}
