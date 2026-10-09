package com.zcode.android.core.protocol.bytes

/**
 * Wire-standard CRC32 — byte-exact Kotlin port of `crc32WireBytes` in
 * packages/shared/src/zcode-protocol-v4/wire-binary.ts (verified 2026-09-28 against
 * the reference implementation and golden fixtures).
 *
 * Reflected IEEE CRC-32: init 0xFFFFFFFF, polynomial 0xEDB88320, final XOR 0xFFFFFFFF,
 * rendered as 8 lowercase hex chars. (Same numeric variant as java.util.zip.CRC32 —
 * fixtures prove bit identity rather than assuming it, per architectural.md §13.2.)
 */
object Crc32Wire {

    fun checksum(bytes: ByteArray): String {
        var crc = -1 // 0xffffffff
        for (b in bytes) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = (crc ushr 1) xor (if (crc and 1 != 0) 0xEDB88320.toInt() else 0)
            }
        }
        return String.format("%08x", (crc xor -1).toLong() and 0xFFFFFFFFL)
    }
}
