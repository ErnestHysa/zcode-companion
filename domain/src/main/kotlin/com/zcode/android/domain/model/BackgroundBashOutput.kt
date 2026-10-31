package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * G15 (vc51) — the background-bash output read (background-bash-output.ts):
 * `backgroundBashOutputV4 {sessionId, workId}` → the typed union. The OUTPUT
 * member carries a bounded snapshot (≤8 KiB, truncated flag + outputPath);
 * the unavailable/unsupported/read_failed members carry the workId + code.
 */
data class BackgroundBashOutput(
    val workId: String,
    val status: String,
    val output: String,
    val truncated: Boolean,
    val outputPath: String,
) {
    companion object {
        /** Typed union decode; null = neither member shape matched (INV-8). */
        fun decodeResult(obj: JsonObject): Any? {
            val workId = obj.strOrNull("workId") ?: return null
            return when (obj.strOrNull("kind")) {
                "output" -> BackgroundBashOutput(
                    workId = workId,
                    status = obj.strOrNull("status") ?: "",
                    output = obj.strOrNull("output") ?: "",
                    truncated = obj.primitiveBool("truncated") ?: false,
                    outputPath = obj.strOrNull("outputPath") ?: "",
                )
                "unavailable", "unsupported", "read_failed" -> BackgroundBashOutputUnavailable(
                    kind = obj.strOrNull("kind")!!,
                    workId = workId,
                    code = obj.strOrNull("code"),
                )
                else -> null
            }
        }
    }
}

/** The unavailable/unsupported/read_failed union members. */
data class BackgroundBashOutputUnavailable(
    val kind: String,
    val workId: String,
    val code: String?,
)
