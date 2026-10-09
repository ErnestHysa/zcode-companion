package com.zcode.android.core.protocol.v4

import com.zcode.android.core.protocol.bytes.ProtocolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 9 read-surface types — VERIFIED against the reference clone @ 29628c9
 * (docs/protocol/v4-wire.md §10; transport.ts / zcodeAgentService.ts, read
 * 2026-10-03). Same decode discipline as the Phase 2 plane (INV-8/INV-14):
 * required-field violations of KNOWN shapes throw [ProtocolException] (fail
 * closed → safe failure, never lenient); unknown fields ride the raw object.
 */

/** Attachment hard budgets (PROTOCOL_V4_LIMITS, core.ts:84-95) — client-side enforcement (§23). */
object AttachmentLimits {
    const val MAX_BYTES = 20L * 1024 * 1024
    const val CHUNK_MAX_BYTES = 512 * 1024
    const val MAX_CHUNKS = 64
    /** uploadId wire grammar (transport.ts v4AttachmentUploadIdSchema). */
    val UPLOAD_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
    /** checksum wire grammar. */
    val CHECKSUM_REGEX = Regex("^sha256:[0-9a-f]{64}$")
}

/** `v4/conversation/fileChanges` result (transport.ts v4ConversationFileChangesResultSchema). */
data class FileChangesResult(
    val files: Int,
    val additions: Long,
    val deletions: Long,
    /** "active" | "reverted" | absent. */
    val state: String?,
    val items: List<Item>,
) {
    data class Item(
        val path: String,
        val additions: Long,
        val deletions: Long,
        val writeCount: Int,
        val toolNames: List<String>,
        /** Pre-parsed hunks (readonlyDiffHunkSchema) — lines carry their ` `/`+`/`-` marker. */
        val patches: List<Hunk>,
    )

    data class Hunk(val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int, val lines: List<String>)

    companion object {
        fun decode(obj: JsonObject): FileChangesResult {
            val itemsArr = obj["items"] as? JsonArray
                ?: throw ProtocolException("fileChanges.items missing")
            return FileChangesResult(
                files = obj.intOrThrow("files"),
                additions = obj.long("additions"),
                deletions = obj.long("deletions"),
                state = obj.strOrNull("state"),
                items = itemsArr.map { el ->
                    val it = el as? JsonObject ?: throw ProtocolException("fileChanges item not object")
                    Item(
                        path = it.string("path"),
                        additions = it.long("additions"),
                        deletions = it.long("deletions"),
                        writeCount = it.intOrThrow("writeCount"),
                        toolNames = (it["toolNames"] as? JsonArray)?.mapNotNull { t -> (t as? JsonPrimitive)?.content } ?: emptyList(),
                        patches = (it["patches"] as? JsonArray)?.map { p ->
                            val h = p as? JsonObject ?: throw ProtocolException("fileChanges patch not object")
                            Hunk(
                                oldStart = h.intOrThrow("oldStart"),
                                oldLines = h.intOrThrow("oldLines"),
                                newStart = h.intOrThrow("newStart"),
                                newLines = h.intOrThrow("newLines"),
                                lines = (h["lines"] as? JsonArray)?.map { l -> (l as? JsonPrimitive)?.content ?: "" } ?: emptyList(),
                            )
                        } ?: emptyList(),
                    )
                },
            )
        }
    }
}

/**
 * Attachment transaction plane params/results (transport.ts:820-1010). Validation
 * is client-side BEFORE the wire (fail closed): a violating upload never leaves
 * the device — INV-5/§23.2's 20 MiB gate and 512 KiB chunk bound are checked here.
 */
object V4Attachment {

    data class BeginParams(
        val connectionId: String,
        val uploadId: String,
        val sessionId: String,
        val fileName: String,
        val mime: String,
        val totalBytes: Long,
        val totalChunks: Int,
        val checksum: String,
    ) {
        /**
         * Zero-byte ⇔ zero-chunks + every wire grammar — caller surfaces a typed error.
         * connectionId is NOT checked here: the transport injects the live hello value
         * after validation (an empty placeholder is expected at this layer).
         */
        fun validate() {
            require(AttachmentLimits.UPLOAD_ID_REGEX.matches(uploadId)) { "uploadId violates wire grammar" }
            require(sessionId.isNotEmpty()) { "sessionId required" }
            require(fileName.isNotEmpty() && fileName.length <= 255 && !fileName.any { it == '\u0000' || it == '\r' || it == '\n' }) { "fileName violates wire grammar" }
            require(MIME_REGEX.matches(mime)) { "mime violates wire grammar: $mime" }
            require(totalBytes in 0..AttachmentLimits.MAX_BYTES) { "totalBytes exceeds attachmentMaxBytes" }
            require(totalChunks in 0..AttachmentLimits.MAX_CHUNKS) { "totalChunks exceeds attachmentUploadMaxChunks" }
            require(AttachmentLimits.CHECKSUM_REGEX.matches(checksum)) { "checksum must be sha256:<64 hex>" }
            require((totalBytes == 0L) == (totalChunks == 0)) { "zero-byte upload must declare zero chunks" }
        }
    }

    val MIME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*$")

    sealed class BeginResult {
        data class Staging(val uploadId: String, val nextChunkIndex: Int) : BeginResult()
        data class Committed(val uploadId: String, val nextChunkIndex: Int, val ref: String) : BeginResult()

        companion object {
            fun decode(obj: JsonObject): BeginResult {
                val uploadId = obj.string("uploadId")
                val next = obj.intOrThrow("nextChunkIndex")
                return when (obj.string("state")) {
                    "staging" -> Staging(uploadId, next)
                    "committed" -> Committed(uploadId, next, obj.string("ref"))
                    else -> throw ProtocolException("attachment begin state unknown: ${obj.strOrNull("state")}")
                }
            }
        }
    }

    data class ChunkParams(val connectionId: String, val uploadId: String, val sessionId: String, val chunkIndex: Int, val dataBase64: String) {
        /** 512 KiB decoded ceiling checked BEFORE allocation on the caller side too (INV-5). */
        fun decodedByteLength(): Long {
            if (dataBase64.isEmpty()) return 0
            require(dataBase64.length % 4 == 0) { "invalid base64 length" }
            val padding = when {
                dataBase64.endsWith("==") -> 2
                dataBase64.endsWith("=") -> 1
                else -> 0
            }
            return dataBase64.length.toLong() / 4 * 3 - padding
        }
    }

    data class ChunkResult(val uploadId: String, val nextChunkIndex: Int) {
        companion object {
            fun decode(obj: JsonObject) = ChunkResult(obj.string("uploadId"), obj.intOrThrow("nextChunkIndex"))
        }
    }

    data class TerminalParams(val connectionId: String, val uploadId: String, val sessionId: String)

    data class CommitResult(val ref: String) {
        companion object {
            fun decode(obj: JsonObject) = CommitResult(obj.string("ref"))
        }
    }

    data class ReadParams(
        val sessionId: String,
        val ref: String,
        val targetRowId: Long? = null,
        val targetEntityId: String? = null,
        val attachmentIndex: Int? = null,
        val offset: Long,
        val limit: Int,
    ) {
        fun validate() {
            require(ref.isNotEmpty()) { "ref required" }
            require(limit in 1..AttachmentLimits.CHUNK_MAX_BYTES) { "read limit exceeds attachmentChunkMaxBytes" }
            require(offset >= 0) { "offset negative" }
            require((targetRowId == null) == (targetEntityId == null) || (targetRowId != null && targetEntityId != null)) { "target rowId/entityId must be paired" }
            require((targetRowId == null) == (attachmentIndex == null)) { "target and attachmentIndex must be provided together" }
        }
    }

    data class ReadResult(
        val dataBase64: String,
        val mediaType: String,
        val totalBytes: Long,
        /** null = end of stream. */
        val nextOffset: Long?,
    ) {
        companion object {
            fun decode(obj: JsonObject): ReadResult {
                val mediaType = obj.string("mediaType")
                // Verified media gate: image/* | video/* | application/pdf ONLY —
                // anything else is a schema violation (fail closed, INV-14).
                val ok = mediaType.startsWith("image/") || mediaType.startsWith("video/") ||
                    mediaType.substringBefore(';').trim().lowercase() == "application/pdf"
                if (!ok) throw ProtocolException("attachment read mediaType not previewable: $mediaType")
                return ReadResult(
                    dataBase64 = obj.string("dataBase64"),
                    mediaType = mediaType,
                    totalBytes = obj.long("totalBytes"),
                    nextOffset = obj.longOrNull("nextOffset"),
                )
            }
        }
    }
}

// ------------------------------------------------------- json helpers (protocol-local)
// string/long/strOrNull already exist package-internal (V4Conversation.kt); only the
// int/nullable-long probes are new here.

internal fun JsonObject.intOrThrow(key: String): Int =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw ProtocolException("required int field `$key` missing")

internal fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content
