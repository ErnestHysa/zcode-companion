package com.zcode.android.core.protocol.bytes

/**
 * VQL (Variable-Length Quantity) varint codec — faithful Kotlin port of the VQL
 * encoding in the ZCode `@zcode/rpc` serialization layer (packages/rpc/src/serialization.ts).
 *
 * 7 data bits per byte, little-endian group order; the high bit marks continuation.
 * Values are 32-bit and follow the JS reference semantics (signed 32-bit `<<`/`>>>`),
 * so a 5-byte group with bit 31 set decodes to a negative Int, byte-identically.
 */
object Vql {

    fun readInt(source: ByteArray, offset: Int): Pair<Int, Int> {
        var value = 0
        var n = 0
        var pos = offset
        while (true) {
            require(pos < source.size) { "VQL read past end of buffer" }
            val next = source[pos].toInt() and 0xFF
            pos++
            value = value or ((next and 0b01111111) shl n)
            if (next and 0b10000000 == 0) {
                return value to (pos - offset)
            }
            n += 7
            require(n <= 28) { "VQL varint exceeds 5 bytes" }
        }
    }

    fun writeInt(value: Int, sink: Sink) {
        if (value == 0) {
            sink.write(byteArrayOf(0))
            return
        }
        var v = value
        var len = 0
        var shift = v
        while (shift != 0) {
            len++
            shift = shift ushr 7
        }
        val scratch = ByteArray(len)
        var i = 0
        var rest = v
        while (rest != 0) {
            scratch[i] = (rest and 0b01111111).toByte()
            rest = rest ushr 7
            if (rest > 0) {
                scratch[i] = (scratch[i].toInt() or 0b10000000).toByte()
            }
            i++
        }
        sink.write(scratch)
    }

    fun encodedSize(value: Int): Int {
        if (value == 0) return 1
        var len = 0
        var shift = value
        while (shift != 0) {
            len++
            shift = shift ushr 7
        }
        return len
    }

    /** Simple growing sink used by the encoder. */
    class Sink {
        private val chunks = ArrayDeque<ByteArray>()
        var size: Int = 0
            private set

        fun write(bytes: ByteArray) {
            chunks.addLast(bytes)
            size += bytes.size
        }

        fun toByteArray(): ByteArray {
            val out = ByteArray(size)
            var offset = 0
            for (c in chunks) {
                c.copyInto(out, offset)
                offset += c.size
            }
            return out
        }
    }
}
