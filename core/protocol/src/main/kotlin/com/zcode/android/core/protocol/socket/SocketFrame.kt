package com.zcode.android.core.protocol.socket

import com.zcode.android.core.protocol.bytes.ProtocolException

/**
 * Socket-level framing — faithful Kotlin port of packages/rpc/src/protocol.ts
 * (ChunkStream + 13-byte ProtocolMessage header, verified 2026-09-28).
 *
 * Frame: [type(1)][id(4 BE)][ack(4 BE)][length(4 BE)][payload(length)]
 */
object SocketFrame {

    const val HEADER_SIZE = 13

    enum class ProtocolMessageType(val id: Int) {
        None(0), Regular(1), Control(2), Ack(3), Disconnect(5), ReplayRequest(6), Pause(7), Resume(8), KeepAlive(9);

        companion object {
            fun from(id: Int): ProtocolMessageType? = entries.firstOrNull { it.id == id }
        }
    }

    data class ProtocolMessage(
        val type: ProtocolMessageType,
        val id: Int,
        val ack: Int,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ProtocolMessage && other.type == type && other.id == id && other.ack == ack &&
                other.data.contentEquals(data)

        override fun hashCode(): Int = type.id * 31 + id
    }

    fun writeMessage(msg: ProtocolMessage): ByteArray {
        val out = ByteArray(HEADER_SIZE + msg.data.size)
        out[0] = msg.type.id.toByte()
        writeUInt32BE(out, 1, msg.id)
        writeUInt32BE(out, 5, msg.ack)
        writeUInt32BE(out, 9, msg.data.size)
        msg.data.copyInto(out, HEADER_SIZE)
        return out
    }

    fun parseHeader(header: ByteArray): Triple<Int, Int, Int> {
        require(header.size >= HEADER_SIZE) { "header too short" }
        val type = header[0].toInt() and 0xFF
        val id = readUInt32BE(header, 1)
        val ack = readUInt32BE(header, 5)
        val length = readUInt32BE(header, 9)
        return Triple(type, id, length)
    }

    fun readUInt32BE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    fun writeUInt32BE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}

/**
 * Byte-accurate reassembly buffer for stream transports — port of ChunkStream.
 * Reads may span chunk boundaries; peek never consumes.
 */
class ChunkStream {
    private val chunks = ArrayDeque<ByteArray>()
    private var totalLength = 0

    val byteLength: Int get() = totalLength

    fun acceptChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        chunks.addLast(chunk)
        totalLength += chunk.size
    }

    fun peek(byteCount: Int): ByteArray? {
        if (totalLength < byteCount) return null
        val result = ByteArray(byteCount)
        var offset = 0
        for (chunk in chunks) {
            if (offset >= byteCount) break
            val remaining = byteCount - offset
            val copyLength = minOf(chunk.size, remaining)
            chunk.copyInto(result, offset, 0, copyLength)
            offset += copyLength
        }
        return result
    }

    fun skip(byteCount: Int) {
        read(byteCount) ?: throw ProtocolException("ChunkStream.skip($byteCount) out of range")
    }

    fun read(byteCount: Int): ByteArray? {
        if (totalLength < byteCount) return null
        val result = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val chunk = chunks.first()
            val needed = byteCount - offset
            if (chunk.size <= needed) {
                chunk.copyInto(result, offset)
                offset += chunk.size
                chunks.removeFirst()
            } else {
                chunk.copyInto(result, offset, 0, needed)
                chunks.removeFirst(); chunks.addFirst(chunk.copyOfRange(needed, chunk.size))
                offset += needed
            }
        }
        totalLength -= byteCount
        return result
    }
}
