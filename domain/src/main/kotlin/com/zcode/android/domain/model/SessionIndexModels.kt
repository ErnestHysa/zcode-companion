package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * G13 (vc51) — the sessions-index live plane (sessions-index.ts, desktop v3.14.3):
 * the workspace-level "list liveness" feed the desktop sidebar drives its task rows
 * from. The phone previously polled/refreshed per screen; this decode + the hub
 * subscription make cross-session row activity live without manual refresh.
 *
 * Decode discipline = the SessionProjectionState precedent: required faces fail
 * closed per ENTRY (a malformed entry drops THAT entry + counts — the feed is
 * auxiliary, it never corrupts the task projection); unknown fields ride `raw`
 * (INV-8); wire enums stay RAW strings. Deltas conflate latest-wins by sessionId
 * (the desktop's conflation key).
 */

data class SessionIndexEntry(
    val sessionId: String,
    val workspaceId: String,
    val parentSessionId: String?,
    val title: String,
    /** custom = user-renamed; default/generated are not manual titles (kept raw). */
    val titleSource: String?,
    /** sessionPhaseSchema — kept raw (unknown future phases display verbatim). */
    val phase: String,
    val sessionEnded: Boolean,
    /** The list-dot fact (the desktop keeps booleans here to avoid the full plane). */
    val hasBackgroundWork: Boolean,
    /** Bounded run summary for the mini track; null = session has no runs. */
    val workflowActivity: WorkflowActivity?,
    val pendingInteraction: PendingInteractionSummary?,
    val pendingInteractionSummary: InteractionCounts?,
    val goalStatus: String?,
    val lastActivityAtMs: Long,
    val lastAssistantPreview: String?,
    val createdAtMs: Long,
    val raw: JsonObject,
) {
    /** sessions-index-workflow-activity.ts — bounded run summary (raw status strings). */
    data class WorkflowActivity(
        val runId: String?,
        val name: String?,
        val status: String?,
        val raw: JsonObject,
    )

    /** sessionPendingInteractionSummarySchema — lightweight identity, no payloads. */
    data class PendingInteractionSummary(
        val interactionId: String,
        /** permission | userInput (kept raw). */
        val kind: String,
        val toolName: String?,
    )

    /** pendingInteractionSummarySchema — the sidebar counts pair. */
    data class InteractionCounts(
        val permissionCount: Int,
        val userInputCount: Int,
    )

    /** Task-row activity verdict (the desktop's taskListRowActivity semantics). */
    val rowActivity: RowActivity
        get() {
            val counts = pendingInteractionSummary
            val openInteractions = (counts?.permissionCount ?: 0) + (counts?.userInputCount ?: 0)
            return when {
                openInteractions > 0 -> RowActivity.WAITING
                // A terminal session is ENDED even if the phase string is stale-running.
                sessionEnded || phase == "completed" -> RowActivity.ENDED
                phase == "running" -> RowActivity.RUNNING
                else -> RowActivity.IDLE
            }
        }

    enum class RowActivity { RUNNING, WAITING, ENDED, IDLE }

    companion object {
        fun decode(obj: JsonObject): SessionIndexEntry? {
            val sessionId = obj.strOrNull("sessionId") ?: return null
            return SessionIndexEntry(
                sessionId = sessionId,
                workspaceId = obj.strOrNull("workspaceId") ?: "",
                parentSessionId = obj.strOrNull("parentSessionId"),
                title = obj.strOrNull("title") ?: "",
                titleSource = obj.strOrNull("titleSource"),
                phase = obj.strOrNull("phase") ?: "",
                sessionEnded = obj.primitiveBool("sessionEnded") ?: false,
                hasBackgroundWork = obj.primitiveBool("hasBackgroundWork") ?: false,
                workflowActivity = (obj["workflowActivity"] as? JsonObject)?.let { wa ->
                    WorkflowActivity(
                        runId = wa.strOrNull("runId"),
                        name = wa.strOrNull("name"),
                        status = wa.strOrNull("status"),
                        raw = wa,
                    )
                },
                pendingInteraction = (obj["pendingInteraction"] as? JsonObject)?.let { pi ->
                    val id = pi.strOrNull("interactionId") ?: return@let null
                    PendingInteractionSummary(
                        interactionId = id,
                        kind = pi.strOrNull("kind") ?: "",
                        toolName = pi.strOrNull("toolName"),
                    )
                },
                pendingInteractionSummary = (obj["pendingInteractionSummary"] as? JsonObject)?.let { ps ->
                    InteractionCounts(
                        permissionCount = ps.intOrNull("permissionCount") ?: 0,
                        userInputCount = ps.intOrNull("userInputCount") ?: 0,
                    )
                },
                goalStatus = obj.strOrNull("goalStatus"),
                lastActivityAtMs = obj.doubleOrNull("lastActivityAt")?.toLong() ?: 0L,
                lastAssistantPreview = obj.strOrNull("lastAssistantPreview"),
                createdAtMs = obj.doubleOrNull("createdAt")?.toLong() ?: 0L,
                raw = obj,
            )
        }

        private fun JsonObject.intOrNull(key: String): Int? =
            (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

        // json helpers live in ConversationRow.kt (package-internal) — reused above.

        /**
         * Snapshot decode: `{protocolVersion:1, workspaceId, logEpoch, sessions:[…]}`.
         * A malformed ENTRY drops that entry (counted); the snapshot itself never throws.
         */
        fun decodeSnapshot(snapshot: JsonObject): SessionsIndexSnapshot {
            val sessions = (snapshot["sessions"] as? JsonArray)
                ?.mapNotNull { el -> (el as? JsonObject)?.let { decode(it) } }
                .orEmpty()
            return SessionsIndexSnapshot(
                workspaceId = snapshot.strOrNull("workspaceId") ?: "",
                logEpoch = snapshot.strOrNull("logEpoch") ?: "",
                sessions = sessions.associateBy { it.sessionId },
            )
        }
    }
}

data class SessionsIndexSnapshot(
    val workspaceId: String,
    val logEpoch: String,
    val sessions: Map<String, SessionIndexEntry>,
)

/** One index delta (`session.upserted` | `session.removed`, the verified pair). */
sealed class SessionsIndexDelta {
    data class Upserted(val session: SessionIndexEntry) : SessionsIndexDelta()
    data class Removed(val sessionId: String) : SessionsIndexDelta()

    companion object {
        fun decode(obj: JsonObject): SessionsIndexDelta? = when (obj.strOrNull("op")) {
            "session.upserted" -> (obj["session"] as? JsonObject)?.let { so ->
                SessionIndexEntry.decode(so)?.let { Upserted(it) }
            }
            "session.removed" -> obj.strOrNull("sessionId")?.let { Removed(it) }
            else -> null
        }
    }
}
