package com.zcode.android.core.protocol.v4

import com.zcode.android.core.protocol.bytes.ProtocolException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Phase 9 protocol types — §10 of docs/protocol/v4-wire.md (VERIFIED shapes). */
class V4Phase9Test {

    @Test
    fun `fileChanges result decodes with hunks`() {
        val obj = buildJsonObject {
            put("files", 2)
            put("additions", 12)
            put("deletions", 3)
            put("state", "active")
            putJsonArray("items") {
                add(buildJsonObject {
                    put("path", "src/A.kt")
                    put("additions", 10)
                    put("deletions", 3)
                    put("writeCount", 2)
                    putJsonArray("toolNames") { add(JsonPrimitive("Write")); add(JsonPrimitive("Edit")) }
                    putJsonArray("patches") {
                        add(buildJsonObject {
                            put("oldStart", 1); put("oldLines", 2); put("newStart", 1); put("newLines", 3)
                            putJsonArray("lines") {
                                add(JsonPrimitive(" ctx")); add(JsonPrimitive("-old")); add(JsonPrimitive("+new")); add(JsonPrimitive("+new2"))
                            }
                        })
                    }
                })
            }
        }
        val r = FileChangesResult.decode(obj)
        assertEquals(2, r.files)
        assertEquals(1, r.items.size)
        assertEquals("active", r.state)
        val patch = r.items[0].patches.single()
        assertEquals(1, patch.oldStart)
        assertEquals(listOf(" ctx", "-old", "+new", "+new2"), patch.lines)
    }

    @Test
    fun `fileChanges result without state decodes (optional key)`() {
        val obj = buildJsonObject {
            put("files", 0); put("additions", 0); put("deletions", 0)
            putJsonArray("items") {}
        }
        assertEquals(null, FileChangesResult.decode(obj).state)
    }

    @Test
    fun `fileChanges missing items fails closed`() {
        val obj = buildJsonObject { put("files", 0); put("additions", 0); put("deletions", 0) }
        assertFailsWith<ProtocolException> { FileChangesResult.decode(obj) }
    }

    @Test
    fun `begin params validate wire grammar`() {
        val valid = V4Attachment.BeginParams(
            connectionId = "conn-1", uploadId = "up-1", sessionId = "sess",
            fileName = "photo.png", mime = "image/png", totalBytes = 600_000, totalChunks = 2,
            checksum = "sha256:" + "a".repeat(64),
        )
        valid.validate()
        val committed = V4Attachment.BeginResult.decode(buildJsonObject {
            put("uploadId", "up-1"); put("state", "committed"); put("nextChunkIndex", 2); put("ref", "att-1")
        })
        assertTrue(committed is V4Attachment.BeginResult.Committed && committed.ref == "att-1")
        val staging = V4Attachment.BeginResult.decode(buildJsonObject {
            put("uploadId", "up-1"); put("state", "staging"); put("nextChunkIndex", 0)
        })
        assertTrue(staging is V4Attachment.BeginResult.Staging && staging.nextChunkIndex == 0)
    }

    @Test
    fun `begin params reject oversize and bad shapes (fail closed pre-wire)`() {
        fun params(totalBytes: Long, totalChunks: Int) = V4Attachment.BeginParams(
            connectionId = "c", uploadId = "u", sessionId = "s", fileName = "f",
            mime = "image/png", totalBytes = totalBytes, totalChunks = totalChunks,
            checksum = "sha256:" + "a".repeat(64),
        )
        assertFailsWith<IllegalArgumentException> { params(AttachmentLimits.MAX_BYTES + 1, 64).validate() }
        assertFailsWith<IllegalArgumentException> { params(100, AttachmentLimits.MAX_CHUNKS + 1).validate() }
        assertFailsWith<IllegalArgumentException> { params(0, 1).validate() } // zero-byte ⇔ zero chunks
        assertFailsWith<IllegalArgumentException> {
            params(100, 1).copy(checksum = "md5:nope").validate()
        }
        assertFailsWith<IllegalArgumentException> {
            params(100, 1).copy(mime = "not-a-mime").validate()
        }
    }

    @Test
    fun `chunk base64 length probe`() {
        val chunk = V4Attachment.ChunkParams("c", "u", "s", 0, "")
        assertEquals(0, chunk.decodedByteLength())
        // 4 base64 chars → 3 bytes; "== " padding honored.
        assertEquals(3, chunk.copy(dataBase64 = "AAAA").decodedByteLength())
        assertEquals(2, chunk.copy(dataBase64 = "AAA=").decodedByteLength())
        assertEquals(1, chunk.copy(dataBase64 = "AA==").decodedByteLength())
        assertFailsWith<IllegalArgumentException> { chunk.copy(dataBase64 = "AAAAA").decodedByteLength() }
    }

    @Test
    fun `chunk result decodes`() {
        val r = V4Attachment.ChunkResult.decode(buildJsonObject { put("uploadId", "u"); put("nextChunkIndex", 5) })
        assertEquals(5, r.nextChunkIndex)
    }

    @Test
    fun `read params validate pairing rules`() {
        V4Attachment.ReadParams(sessionId = "s", ref = "r", offset = 0, limit = 512 * 1024).validate()
        assertFailsWith<IllegalArgumentException> {
            V4Attachment.ReadParams("s", "r", offset = 0, limit = 512 * 1024 + 1).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            V4Attachment.ReadParams("s", "r", targetRowId = 1, offset = 0, limit = 10).validate() // target w/o index
        }
        assertFailsWith<IllegalArgumentException> {
            V4Attachment.ReadParams("s", "r", attachmentIndex = 0, offset = 0, limit = 10).validate() // index w/o target
        }
    }

    @Test
    fun `read result media gate fails closed on non-previewable type (INV-14)`() {
        fun readResult(mediaType: String) = buildJsonObject {
            put("dataBase64", "AAAA"); put("mediaType", mediaType); put("totalBytes", 3)
            put("nextOffset", JsonPrimitive(null as Long?))
        }
        assertEquals(null, V4Attachment.ReadResult.decode(readResult("image/png")).nextOffset)
        assertFailsWith<ProtocolException> { V4Attachment.ReadResult.decode(readResult("text/plain")) }
        // PDF with params still passes the verified gate.
        V4Attachment.ReadResult.decode(readResult("application/pdf;charset=binary"))
    }
}
