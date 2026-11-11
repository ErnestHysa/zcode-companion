package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * G60 (vc52 Phase 12): the per-task token usage — `getTaskTokenUsage` (the
 * `v4/conversation/usage` response, `U2`, .strict(), fresh asar 4a6080ed…):
 * `{sessionId, totalTokens, inputTokens, outputTokens, reasoningTokens,
 * cacheCreationTokens, cacheReadTokens, modelRequestCount, modelErrorCount,
 * inputBaselineBySource: record(string,int)}`. This is the usage piece the
 * pass-24 upgrade left open (the desktop's tokenDebug developer panel source).
 */
data class TaskTokenUsage(
    val sessionId: String,
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
    val modelRequestCount: Long,
    val modelErrorCount: Long,
) {
    companion object {
        fun decode(o: JsonObject): TaskTokenUsage? {
            val sid = o.strOrNull("sessionId") ?: return null
            fun n(key: String): Long = o.longOrNull(key) ?: 0L
            return TaskTokenUsage(
                sessionId = sid,
                totalTokens = n("totalTokens"),
                inputTokens = n("inputTokens"),
                outputTokens = n("outputTokens"),
                reasoningTokens = n("reasoningTokens"),
                cacheCreationTokens = n("cacheCreationTokens"),
                cacheReadTokens = n("cacheReadTokens"),
                modelRequestCount = n("modelRequestCount"),
                modelErrorCount = n("modelErrorCount"),
            )
        }

        private fun JsonObject.strOrNull(key: String): String? =
            (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        private fun JsonObject.longOrNull(key: String): Long? =
            (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
    }
}
