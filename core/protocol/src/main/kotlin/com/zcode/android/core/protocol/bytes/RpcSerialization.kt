package com.zcode.android.core.protocol.bytes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * RPC binary serialization — faithful Kotlin port of packages/rpc/src/serialization.ts.
 *
 * Each value: [1-byte DataType tag][VQL length when applicable][data].
 * The Object tag (5) is the JSON fallback: JS reaches it for objects, booleans, null and
 * non-integer numbers (`typeof x === "object"` / failed `(x|0)===x`), encoded as
 * `JSON.stringify(...)` UTF-8 bytes. Nested Uint8Array values inside objects are replaced
 * by the `__zcode_rpc_nested_uint8array_v1` marker object before stringify.
 */
object RpcSerialization {

    enum class DataType(val id: Byte) {
        Undefined(0), String(1), Buffer(2), VSBuffer(3), Array(4), Object(5), Int(6);

        companion object {
            fun from(id: Byte): DataType? = entries.firstOrNull { it.id == id }
        }
    }

    const val NESTED_UINT8_ARRAY_MARKER = "__zcode_rpc_nested_uint8array_v1"
    const val NESTED_UINT8_ARRAY_BASE64_KEY = "base64"

    sealed class RpcValue {
        data object Undefined : RpcValue()
        data class Str(val value: String) : RpcValue()
        data class Buf(val bytes: ByteArray) : RpcValue()
        data class VsBuf(val bytes: ByteArray) : RpcValue()
        data class Arr(val items: List<RpcValue>) : RpcValue()
        data class Int(val value: kotlin.Int) : RpcValue()
        data class Obj(val json: JsonElement) : RpcValue()
    }

    private class Writer {
        val sink = Vql.Sink()

        private fun tag(type: DataType) = sink.write(byteArrayOf(type.id))

        fun write(value: RpcValue) {
            when (value) {
                is RpcValue.Undefined -> tag(DataType.Undefined)
                is RpcValue.Str -> {
                    tag(DataType.String)
                    val bytes = value.value.encodeToByteArray()
                    Vql.writeInt(bytes.size, sink)
                    sink.write(bytes)
                }
                is RpcValue.VsBuf -> {
                    tag(DataType.VSBuffer)
                    Vql.writeInt(value.bytes.size, sink)
                    sink.write(value.bytes)
                }
                is RpcValue.Buf -> {
                    tag(DataType.Buffer)
                    Vql.writeInt(value.bytes.size, sink)
                    sink.write(value.bytes)
                }
                is RpcValue.Arr -> {
                    tag(DataType.Array)
                    Vql.writeInt(value.items.size, sink)
                    value.items.forEach { write(it) }
                }
                is RpcValue.Int -> {
                    tag(DataType.Int)
                    Vql.writeInt(value.value, sink)
                }
                is RpcValue.Obj -> {
                    tag(DataType.Object)
                    val bytes = RpcJson.stringify(value.json).encodeToByteArray()
                    Vql.writeInt(bytes.size, sink)
                    sink.write(bytes)
                }
            }
        }
    }

    fun encode(value: RpcValue): ByteArray {
        val w = Writer()
        w.write(value)
        return w.sink.toByteArray()
    }

    fun encodeAll(values: List<RpcValue>): ByteArray {
        val w = Writer()
        values.forEach { w.write(it) }
        return w.sink.toByteArray()
    }

    class Decoder(private val bytes: ByteArray, var pos: Int = 0) {
        val remaining: Int get() = bytes.size - pos

        fun read(n: Int): ByteArray {
            require(pos + n <= bytes.size) { "unexpected end of buffer" }
            val out = bytes.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun readVql(): Int {
            val (v, used) = Vql.readInt(bytes, pos)
            pos += used
            return v
        }
    }

    fun decode(bytes: ByteArray): RpcValue {
        val reader = Decoder(bytes)
        val value = try {
            decodeValue(reader)
        } catch (e: ProtocolException) {
            throw e
        } catch (e: Exception) {
            throw ProtocolException("malformed RPC value: ${e.message?.take(80)}")
        }
        if (reader.pos != bytes.size) throw ProtocolException("trailing bytes after RPC value")
        return value
    }

    fun decodeAll(bytes: ByteArray): List<RpcValue> {
        val reader = Decoder(bytes)
        val out = ArrayList<RpcValue>(2)
        while (reader.remaining > 0) {
            out.add(
                try {
                    decodeValue(reader)
                } catch (e: ProtocolException) {
                    throw e
                } catch (e: Exception) {
                    throw ProtocolException("malformed RPC value: ${e.message?.take(80)}")
                }
            )
        }
        return out
    }

    private fun decodeValue(reader: Decoder): RpcValue {
        val type = DataType.from(reader.read(1)[0])
            ?: throw ProtocolException("unknown DataType tag")
        return when (type) {
            DataType.Undefined -> RpcValue.Undefined
            DataType.String -> RpcValue.Str(reader.read(reader.readVql()).decodeToString())
            DataType.Buffer -> RpcValue.Buf(reader.read(reader.readVql()))
            DataType.VSBuffer -> RpcValue.VsBuf(reader.read(reader.readVql()))
            DataType.Array -> {
                val length = reader.readVql()
                require(length >= 0)
                val items = ArrayList<RpcValue>(length.coerceAtMost(1024))
                repeat(length) { items.add(decodeValue(reader)) }
                RpcValue.Arr(items)
            }
            DataType.Object -> {
                val text = reader.read(reader.readVql()).decodeToString()
                val json = try {
                    RpcJson.parse(text)
                } catch (e: Exception) {
                    throw ProtocolException("Object tag holds invalid JSON: ${e.message?.take(80)}")
                }
                RpcValue.Obj(json)
            }
            DataType.Int -> RpcValue.Int(reader.readVql())
        }
    }
}

/**
 * Minimal JSON writer/reader whose output is byte-compatible with JS `JSON.stringify`
 * (the Object tag's encoding in the reference implementation). Numbers/booleans/null are
 * emitted exactly as given in the source literal (JsonPrimitive content preservation).
 */
object RpcJson {

    private val strictJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(text: String): JsonElement = strictJson.parseToJsonElement(text)

    fun stringify(element: JsonElement): String {
        val sb = StringBuilder()
        write(element, sb)
        return sb.toString()
    }

    private fun write(element: JsonElement, sb: StringBuilder) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> {
                if (element.isString) writeString(element.content, sb) else sb.append(element.content)
            }
            is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    write(e, sb)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, v) in element) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k, sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Marker object JS's reviver produces/restores for nested Uint8Array values. */
    fun nestedBytesMarker(base64: String): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>(
            RpcSerialization.NESTED_UINT8_ARRAY_MARKER to JsonPrimitive(true),
            RpcSerialization.NESTED_UINT8_ARRAY_BASE64_KEY to JsonPrimitive(base64),
        )
    )
}

/** Typed failure for structurally invalid wire input — callers resync, never guess (INV-14). */
class ProtocolException(message: String) : Exception(message)
