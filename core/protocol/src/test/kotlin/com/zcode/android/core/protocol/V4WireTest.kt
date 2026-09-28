package com.zcode.android.core.protocol

import com.zcode.android.core.protocol.bytes.ProtocolException
import com.zcode.android.core.protocol.v4.DeliveryKind
import com.zcode.android.core.protocol.v4.Hello
import com.zcode.android.core.protocol.v4.LogicalFrameAssembler
import com.zcode.android.core.protocol.v4.TopicWireFrame
import com.zcode.android.core.protocol.v4.TopicWireFrameCodec
import com.zcode.android.core.protocol.v4.V4WireVersionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * v4 wire layer tests — decode/assembly/limits per the schemas verified from
 * packages/shared/src/zcode-protocol-v4 (transport.ts/wire.ts/wire-codec.ts, 29628c9).
 */
class V4WireTest {

    private val helloJson = """
        {"kind":"hello","protocolVersion":3,"connectionId":"conn-1","clientMode":"web-remote-replayable",
         "deliveryProfile":"replayable","serverTime":1790600000000,
         "capabilities":{"nativeDialogs":true,"localTerminal":true,"binaryFrames":false,
           "compression":"permessage-deflate","workflowRunDeltas":true}}
    """.trimIndent()

    @Test
    fun `hello decodes with capability negotiation`() {
        val hello = Hello.decode(Json.parseToJsonElement(helloJson).jsonObject)
        assertEquals("conn-1", hello.connectionId)
        assertEquals("replayable", hello.deliveryProfile)
        assertTrue(hello.capabilities.nativeDialogs)
        assertTrue(hello.capabilities.workflowRunDeltas) // === true gate
        // optional bits absent → false, never null-crash
        val minimal = Hello.decode(
            Json.parseToJsonElement(
                """{"kind":"hello","protocolVersion":3,"connectionId":"c","clientMode":"desktop-continuous",
                   "deliveryProfile":"continuous","serverTime":1,
                   "capabilities":{"nativeDialogs":false,"localTerminal":false,"binaryFrames":false,"compression":"none"}}"""
            ).jsonObject
        )
        assertEquals(false, minimal.capabilities.workflowRunDeltas)
    }

    @Test
    fun `hello profile mismatch and unknown field shapes are typed failures`() {
        val bad = Json.parseToJsonElement(
            helloJson.replace("\"deliveryProfile\":\"replayable\"", "\"deliveryProfile\":\"continuous\"")
        ).jsonObject
        assertFailsWith<ProtocolException> { Hello.decode(bad) }

        val wrongVersion = Json.parseToJsonElement(helloJson.replace("\"protocolVersion\":3", "\"protocolVersion\":4")).jsonObject
        assertFailsWith<V4WireVersionException> { Hello.decode(wrongVersion) }

        val badDelivery = Json.parseToJsonElement(
            """{"wireVersion":3,"kind":"complete","deliveryKind":"sneaky","logicalFrameId":"l",
                "logicalFrameOrdinal":1,"topic":"conversation/s","subscriptionId":"sub","frame":{}}"""
        ).jsonObject
        assertFailsWith<ProtocolException> { TopicWireFrame.decode(badDelivery) }
    }

    // ---------------------------------------------------------------- encode→assemble round trip

    private fun frameJson(sizeHint: Int): JsonObject = buildJsonObject {
        put("topic", JsonPrimitive("conversation/sess-1"))
        put("subscriptionId", JsonPrimitive("sub-1"))
        put("fromSeq", JsonPrimitive(0))
        put("toSeq", JsonPrimitive(7))
        put("sentAt", JsonPrimitive(1.7912345E12))
        put("payload", buildJsonObject {
            put("kind", JsonPrimitive("snapshot"))
            put("snapshot", buildJsonObject { put("pad", JsonPrimitive("x".repeat(sizeHint))) })
        })
    }

    @Test
    fun `encode then decode-assemble round-trips at several sizes`() {
        for (size in listOf(10, 5_000, 200_000, 1_400_000)) {
            val frames = TopicWireFrameCodec.encode(
                frame = frameJson(size),
                deliveryKind = DeliveryKind.INITIAL,
                topic = "conversation/sess-1",
                subscriptionId = "sub-1",
                logicalFrameId = "lf-$size",
                logicalFrameOrdinal = 1,
                maxPhysicalFrameBytes = 1 shl 20,
            )
            assertTrue(frames.isNotEmpty())
            val assembler = LogicalFrameAssembler(clock = { 0L })
            var assembled: LogicalFrameAssembler.Assembled? = null
            for (f in frames) {
                assembler.accept(f)?.let { assembled = it }
            }
            val done = assembled ?: error("no assembly for size=$size (${frames.size} frames)")
            assertEquals("conversation/sess-1", done.topic)
            if (size <= 500_000) {
                assertTrue(frames.size == 1 && frames[0] is TopicWireFrame.Complete, "frames under the physical budget stay complete (size=$size)")
            } else {
                assertTrue(frames.size > 1, "large frames fragment (size=$size)")
            }
        }
    }

    @Test
    fun `fragment data corruption fails assembly on crc (S11)`() {
        // The crc32 is carried identically on every fragment and validated over the
        // assembled LOGICAL bytes — so the fault test corrupts DATA, not the field.
        val frames = TopicWireFrameCodec.encode(
            frameJson(1_500_000), DeliveryKind.ONLINE, "conversation/sess-1", "sub-1", "lf", 1,
        )
        assertTrue(frames.size > 1)
        val assembler = LogicalFrameAssembler(clock = { 0L })
        assembler.accept(frames[0])
        val brokenData = (frames[1] as TopicWireFrame.Fragment).data.copyOf()
        brokenData[0] = (brokenData[0].toInt() xor 0x01).toByte()
        val broken = (frames[1] as TopicWireFrame.Fragment).copy(data = brokenData)
        assertFailsWith<ProtocolException> { assembler.accept(broken) }
    }

    @Test
    fun `assembly bounds are enforced before allocation (INV-5)`() {
        // fragmentCount over limit
        val obj = buildJsonObject {
            put("wireVersion", JsonPrimitive(3))
            put("kind", JsonPrimitive("fragment"))
            put("deliveryKind", JsonPrimitive("online"))
            put("logicalFrameId", JsonPrimitive("lf"))
            put("logicalFrameOrdinal", JsonPrimitive(1))
            put("topic", JsonPrimitive("conversation/s"))
            put("subscriptionId", JsonPrimitive("sub"))
            put("fragmentIndex", JsonPrimitive(0))
            put("fragmentCount", JsonPrimitive(1025))
            put("logicalBytes", JsonPrimitive(2_000_000))
            put("checksum", buildJsonObject { put("algorithm", JsonPrimitive("crc32")); put("value", JsonPrimitive("0f0f0f0f")) })
            put("dataBase64", JsonPrimitive("QUJD"))
        }
        assertFailsWith<ProtocolException> { TopicWireFrame.decode(obj) }
    }

    @Test
    fun `duplicate fragment and duplicate logical frame are idempotent`() {
        val frames = TopicWireFrameCodec.encode(
            frameJson(1_500_000), DeliveryKind.RECOVERY, "conversation/sess-1", "sub-1", "lf-dup", 1,
        )
        val assembler = LogicalFrameAssembler(clock = { 0L })
        var deliveries = 0
        assembler.accept(frames[0])
        assembler.accept(frames[0]) // duplicate fragment: idempotent drop
        for (f in frames.drop(1)) {
            if (assembler.accept(f) != null) deliveries++
        }
        assertEquals(1, deliveries)
        // whole-frame re-send (duplicates fault class): no second delivery
        assertEquals(null, assembler.accept(frames[1]))
    }

    @Test
    fun `re-delivered complete frame is deduped by logicalFrameId`() {
        val frames = TopicWireFrameCodec.encode(
            frameJson(2_000), DeliveryKind.INITIAL, "conversation/sess-1", "sub-1", "lf-c", 1,
        )
        assertEquals(1, frames.size)
        val assembler = LogicalFrameAssembler(clock = { 0L })
        assertEquals(1, listOfNotNull(assembler.accept(frames[0])).size)
        assertEquals(null, assembler.accept(frames[0]))
    }

    @Test
    fun `rowsRange limit is hard-bounded at 200 (INV-13)`() {
        val ok = com.zcode.android.core.protocol.v4.RowsRangeParams(sessionId = "s1", limit = 200)
        assertEquals(200, ok.limit)
        assertFailsWith<ProtocolException> {
            com.zcode.android.core.protocol.v4.RowsRangeParams(sessionId = "s1", limit = 201)
        }
        assertFailsWith<ProtocolException> {
            com.zcode.android.core.protocol.v4.RowsRangeParams(sessionId = "s1", limit = 0)
        }
    }

    @Test
    fun `stale staged assembly expires (30 s bound)`() {
        var now = 0L
        val assembler = LogicalFrameAssembler(clock = { now })
        val frames = TopicWireFrameCodec.encode(
            frameJson(1_500_000), DeliveryKind.ONLINE, "conversation/sess-1", "sub-1", "lf-exp", 1,
        )
        assembler.accept(frames[0])
        assertEquals(1, assembler.stagedCount())
        now += 31_000
        assembler.accept(frames[1])
        // expiry evicted the stale staging; the late fragment re-stages fresh (no delivery)
        assertEquals(1, assembler.stagedCount())
    }
}
