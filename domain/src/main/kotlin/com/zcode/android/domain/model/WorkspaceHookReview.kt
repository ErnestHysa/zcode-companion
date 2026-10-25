package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ADR-024 (gaps-and-polish Phase 5D, A3.20): the workspaceHookReview interaction —
 * the zod `yS` schema (host/chunk-4RHDQOPX.js ≈486560, .strict()) decoded tolerantly
 * (INV-8: unknown event/trustState values keep the RAW and are NEVER actionable —
 * unknown trust states render verbatim and cannot be trusted from the phone).
 *
 * `warningCode` is literally "workspace_hooks_execute_code": hooks execute shell code on
 * every matching lifecycle event — trust is PERMISSION for persistent code execution.
 */
data class WorkspaceHookReview(
    val reviewFlowId: String,
    val generation: Long,
    val interactionId: String,
    val sessionId: String,
    val taskId: String,
    val runId: String,
    val workspaceIdentity: String,
    val workspaceLabel: String,
    val bundleDigest: String,
    val createdAtMs: Long,
    val deadlineAtMs: Long,
    val workspacePath: String?,
    val remoteSessionId: String?,
    val summary: Summary,
    val items: List<Item>,
) {
    data class Summary(val eventCount: Int, val hookCount: Int, val pendingCount: Int)

    data class Item(
        val reviewItemId: String,
        val event: String,
        val matcher: String?,
        val type: String,
        val displayName: String,
        val displayCommand: String,
        val sourcePath: String,
        val executionMode: String,
        val configuredEnabled: Boolean,
        val editable: Boolean,
        val trustState: String,
    ) {
        /** Only the desktop's pending classes are actionable from the phone (ADR-024). */
        val actionable: Boolean
            get() = trustState == "pending_trust" || trustState == "revoked" || trustState == "stale_digest"

        companion object {
            fun decode(o: JsonObject): Item? {
                val id = o.hkStr("reviewItemId") ?: return null
                return Item(
                    reviewItemId = id,
                    event = o.hkStr("event") ?: "unknown",
                    matcher = o.hkStr("matcher"),
                    type = o.hkStr("type") ?: "command",
                    displayName = o.hkStr("displayName") ?: id,
                    displayCommand = o.hkStr("displayCommand") ?: "",
                    sourcePath = o.hkStr("sourcePath") ?: "",
                    executionMode = o.hkStr("executionMode") ?: "foreground",
                    configuredEnabled = o.hkBool("configuredEnabled") ?: true,
                    editable = o.hkBool("editable") ?: false,
                    trustState = o.hkStr("trustState") ?: "unknown",
                )
            }
        }
    }

    /** The `hS` decision arm: trust_selected with the checked ids (never a trust-all). */
    data class Decision(val action: String, val reviewItemIds: List<String>)

    companion object {
        /** The desktop's `_T` trust-state enum — UNKNOWN beyond these (kept raw, inert). */
        val TRUST_STATES = setOf(
            "not_applicable", "pending_trust", "trusted_persistent",
            "blocked_untrusted", "blocked_policy", "revoked", "stale_digest",
        )

        fun decode(o: JsonObject): WorkspaceHookReview? {
            val flowId = o.hkStr("reviewFlowId") ?: return null
            val interactionId = o.hkStr("interactionId") ?: return null
            val items = (o["items"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(Item::decode) } ?: emptyList()
            val sum = o["summary"] as? JsonObject
            return WorkspaceHookReview(
                reviewFlowId = flowId,
                generation = o.hkLong("generation") ?: 1,
                interactionId = interactionId,
                sessionId = o.hkStr("sessionId") ?: "",
                taskId = o.hkStr("taskId") ?: "",
                runId = o.hkStr("runId") ?: "",
                workspaceIdentity = o.hkStr("workspaceIdentity") ?: "",
                workspaceLabel = o.hkStr("workspaceLabel") ?: "",
                bundleDigest = o.hkStr("bundleDigest") ?: "",
                createdAtMs = o.hkLong("createdAt") ?: 0,
                deadlineAtMs = o.hkLong("deadlineAt") ?: 0,
                workspacePath = o.hkStr("workspacePath"),
                remoteSessionId = o.hkStr("remoteSessionId"),
                summary = Summary(
                    eventCount = sum?.hkLong("eventCount")?.toInt() ?: 0,
                    hookCount = sum?.hkLong("hookCount")?.toInt() ?: items.size,
                    pendingCount = sum?.hkLong("pendingCount")?.toInt() ?: items.count { it.actionable },
                ),
                items = items,
            )
        }
    }
}

private fun JsonObject.hkPrim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
internal fun JsonObject.hkStr(key: String): String? = hkPrim(key)?.content?.takeIf { it.isNotBlank() }
internal fun JsonObject.hkLong(key: String): Long? = hkPrim(key)?.content?.toDoubleOrNull()?.toLong()
internal fun JsonObject.hkBool(key: String): Boolean? = hkPrim(key)?.content?.toBooleanStrictOrNull()
