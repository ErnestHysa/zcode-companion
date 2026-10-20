package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A3.13 (gaps-and-polish Phase 5B): the model-call trajectory of `getModelTrajectory`
 * (host/index.js `readModelTrajectory`/`mapRecord` ≈372322) — one record per model call:
 * `{requestId, attempt, startedAt, completedAt?, durationMs?, turnId?, traceId?,
 * callSource:{kind, querySource?}, model:{modelId, providerId, role?, source?},
 * request:{toolNames?}, response?:{finishReason?, text?, reasoningText?, …},
 * error?:{name, message, stack?}}`; result `{taskId, available, records[], sourceFiles[],
 * truncated}`. Tolerant (INV-8): every field optional except requestId/startedAt.
 */
data class TrajectoryRecord(
    val requestId: String,
    val attempt: Int,
    val startedAt: String,
    val completedAt: String?,
    val durationMs: Long?,
    val callKind: String,
    val modelId: String?,
    val providerId: String?,
    val finishReason: String?,
    val textHead: String?,
    val toolNames: List<String>,
    val error: String?,
) {
    companion object {
        fun decode(o: JsonObject): TrajectoryRecord? {
            fun str(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
            val requestId = str("requestId") ?: return null
            val startedAt = str("startedAt") ?: return null
            val callSource = o["callSource"] as? JsonObject
            val model = o["model"] as? JsonObject
            val response = o["response"] as? JsonObject
            val error = o["error"] as? JsonObject
            fun inner(obj: JsonObject?, key: String): String? = obj?.let { (it[key] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.takeIf { c -> c.isNotBlank() } }
            return TrajectoryRecord(
                requestId = requestId,
                attempt = str("attempt")?.toIntOrNull() ?: 1,
                startedAt = startedAt,
                completedAt = str("completedAt"),
                durationMs = str("durationMs")?.toDoubleOrNull()?.toLong(),
                callKind = inner(callSource, "kind") ?: "main",
                modelId = inner(model, "modelId"),
                providerId = inner(model, "providerId"),
                finishReason = inner(response, "finishReason"),
                textHead = inner(response, "text")?.take(160),
                toolNames = (response?.get("toolCalls") as? JsonArray)?.mapNotNull { tc ->
                    ((tc as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                } ?: emptyList(),
                error = listOfNotNull(inner(error, "name"), inner(error, "message")).joinToString(": ").takeIf { it.isNotBlank() },
            )
        }

        fun decodeList(el: JsonElement?): List<TrajectoryRecord> {
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> el["records"] as? JsonArray ?: return emptyList()
                else -> return emptyList()
            }
            return arr.mapNotNull { (it as? JsonObject)?.let(::decode) }
        }
    }
}

/** `getModelTrajectory` result: records + the jsonl files they were read from. */
data class TrajectoryResult(
    val records: List<TrajectoryRecord>,
    val sourceFiles: List<String>,
    val truncated: Boolean,
) {
    companion object {
        fun decode(o: JsonObject): TrajectoryResult = TrajectoryResult(
            records = TrajectoryRecord.decodeList(o["records"]),
            sourceFiles = (o["sourceFiles"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content } ?: emptyList(),
            truncated = (o["truncated"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toBooleanStrictOrNull() ?: false,
        )
    }
}
