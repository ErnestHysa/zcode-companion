package com.zcode.android.core.protocol.v4

import com.zcode.android.core.protocol.bytes.ProtocolException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

/**
 * v4 command plane — port of packages/shared/src/zcode-protocol-v4/command.ts
 * (v3.14.3, commit 29628c9; CAS sets verified verbatim 2026-09-28).
 *
 * Idempotency (P4/INV-4): every Android-originated mutation carries a client-generated
 * uuid v7 commandId, STABLE across retries. CAS-guarded commands must carry
 * baseRevision (COMMANDS_REQUIRING_BASE_REVISION) and row-targeting commands
 * baseLogEpoch (ROW_TARGETING_COMMANDS) — parse fails otherwise (fail closed).
 */

/** Command types (keys of commandPayloadSchemas, command.ts:44). */
object CommandTypes {
    const val CREATE_SESSION = "createSession"
    const val CREATE_SELECTION_SIDE_SESSION = "createSelectionSideSession"
    const val SEND_TEXT = "sendText"
    const val SEND_GOAL_COMMAND = "sendGoalCommand"
    const val STOP = "stop"
    const val COMPACT = "compact"
    const val FORK_ASSISTANT = "forkAssistant"
    const val APPLY_FILE_REWIND = "applyFileRewind"
    const val EDIT_USER_QUERY = "editUserQuery"
    const val RETRY_TURN = "retryTurn"
    const val SET_ASSISTANT_FEEDBACK = "setAssistantFeedback"
    const val SEND_QUEUED_NOW = "sendQueuedNow"
    const val EDIT_QUEUE_ITEM = "editQueueItem"
    const val REORDER_QUEUE_ITEM = "reorderQueueItem"
    const val DELETE_QUEUE_ITEM = "deleteQueueItem"
    const val SET_AUTO_DRAIN = "setAutoDrain"
    const val RESOLVE_INTERACTION = "resolveInteraction"
    const val RESPOND_WORKSPACE_HOOK_REVIEW = "respondWorkspaceHookReview"
    const val TOGGLE_WORKSPACE_HOOK_REVIEW_ITEM = "toggleWorkspaceHookReviewItem"
    const val REVOKE_WORKSPACE_HOOK_TRUST = "revokeWorkspaceHookTrust"
    const val REQUEST_WORKSPACE_HOOK_REVIEW = "requestWorkspaceHookReview"
    const val SNOOZE_INTERACTION_AUTO_RESOLUTION = "snoozeInteractionAutoResolution"
    const val SWITCH_MODEL_CONFIG = "switchModelConfig"
    const val SWITCH_COLLABORATION_MODE = "switchCollaborationMode"
    const val SET_FOLLOWUP_MODE = "setFollowupMode"
    const val PAUSE_GOAL = "pauseGoal"
    const val RESUME_GOAL = "resumeGoal"
    const val CANCEL_BACKGROUND_WORK = "cancelBackgroundWork"
    const val CANCEL_WORKFLOW_RUN = "cancelWorkflowRun"
    const val RESUME_WORKFLOW_RUN = "resumeWorkflowRun"
    const val START_SAVED_WORKFLOW = "startSavedWorkflow"
    const val AMEND_WORKFLOW_RUN_SETTINGS = "amendWorkflowRunSettings"
    const val RENAME_SESSION = "renameSession"
    const val DELETE_SESSION = "deleteSession"
}

/** command.ts:296 — CAS ✓ commands: the envelope MUST carry baseRevision. */
val COMMANDS_REQUIRING_BASE_REVISION: Set<String> = setOf(
    CommandTypes.APPLY_FILE_REWIND,
    CommandTypes.FORK_ASSISTANT,
    CommandTypes.EDIT_USER_QUERY,
    CommandTypes.RETRY_TURN,
    CommandTypes.SET_ASSISTANT_FEEDBACK,
    CommandTypes.SEND_QUEUED_NOW,
    CommandTypes.EDIT_QUEUE_ITEM,
    CommandTypes.REORDER_QUEUE_ITEM,
    CommandTypes.DELETE_QUEUE_ITEM,
    CommandTypes.SET_AUTO_DRAIN,
    CommandTypes.SWITCH_MODEL_CONFIG,
    CommandTypes.SWITCH_COLLABORATION_MODE,
    CommandTypes.SET_FOLLOWUP_MODE,
    CommandTypes.PAUSE_GOAL,
    CommandTypes.RESUME_GOAL,
)

/** command.ts:314 — row-targeting commands additionally require baseLogEpoch. */
val ROW_TARGETING_COMMANDS: Set<String> = setOf(
    CommandTypes.APPLY_FILE_REWIND,
    CommandTypes.FORK_ASSISTANT,
    CommandTypes.EDIT_USER_QUERY,
    CommandTypes.RETRY_TURN,
    CommandTypes.SET_ASSISTANT_FEEDBACK,
)

/** commandEnvelopeSchema (command.ts:323). */
data class CommandEnvelope(
    val commandId: String,
    val clientId: String,
    val sessionId: String?,
    val type: String,
    val payload: JsonObject,
    val issuedAt: Long,
    val baseRevision: Long? = null,
    val baseLogEpoch: String? = null,
) {
    fun encode(): JsonObject = buildJsonObject {
        put("commandId", JsonPrimitive(commandId))
        put("clientId", JsonPrimitive(clientId))
        if (sessionId == null) put("sessionId", kotlinx.serialization.json.JsonNull) else put("sessionId", JsonPrimitive(sessionId))
        baseRevision?.let { put("baseRevision", JsonPrimitive(it)) }
        baseLogEpoch?.let { put("baseLogEpoch", JsonPrimitive(it)) }
        put("type", JsonPrimitive(type))
        put("payload", payload)
        put("issuedAt", JsonPrimitive(issuedAt))
    }

    companion object {
        /**
         * build with CAS validation (parseCommandEnvelope semantics, command.ts:342):
         * CAS-guarded / row-targeting types reject a missing base BEFORE anything goes
         * on the wire — the failure is local and typed, never a lenient send.
         */
        fun build(
            clientId: String,
            sessionId: String?,
            type: String,
            payload: JsonObject,
            issuedAt: Long,
            baseRevision: Long? = null,
            baseLogEpoch: String? = null,
            commandId: String = UuidV7.generate(),
        ): CommandEnvelope {
            val envelope = CommandEnvelope(commandId, clientId, sessionId, type, payload, issuedAt, baseRevision, baseLogEpoch)
            validateCas(envelope)
            return envelope
        }

        fun validateCas(envelope: CommandEnvelope) {
            if (envelope.type in COMMANDS_REQUIRING_BASE_REVISION && envelope.baseRevision == null) {
                throw ProtocolException("CAS command ${envelope.type} requires baseRevision")
            }
            if (envelope.type in ROW_TARGETING_COMMANDS && envelope.baseLogEpoch.isNullOrBlank()) {
                throw ProtocolException("row-targeting command ${envelope.type} requires baseLogEpoch")
            }
        }
    }
}

/** commandAckSchema (command.ts:430) — idempotent, deduped by commandId. */
data class CommandAck(
    val commandId: String,
    val status: String,
    val revisionAtDecision: Long,
    val reasonCode: String?,
    val message: String?,
    val result: JsonObject?,
) {
    companion object {
        val STATUSES = setOf("accepted", "rejected", "stale", "duplicate", "noop", "failed")

        fun decode(obj: JsonObject): CommandAck {
            val status = (obj["status"] as? JsonPrimitive)?.content
                ?: throw ProtocolException("commandAck.status missing")
            if (status !in STATUSES) throw ProtocolException("commandAck.status invalid: $status")
            val revision = (obj["revisionAtDecision"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: throw ProtocolException("commandAck.revisionAtDecision missing")
            return CommandAck(
                commandId = (obj["commandId"] as? JsonPrimitive)?.content
                    ?: throw ProtocolException("commandAck.commandId missing"),
                status = status,
                revisionAtDecision = revision,
                reasonCode = obj.strOrNull("reasonCode"),
                message = obj.strOrNull("message"),
                result = obj["result"] as? JsonObject,
            )
        }

        private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content
    }
}

/**
 * RFC 9562 UUIDv7 — unix-ms timestamp (48 bits, big-endian), version 7, RFC variant,
 * 62 random bits. Command ids stay stable across retries because the CALLER holds the
 * envelope (outbox, Phase 4); this generator only mints fresh ids.
 */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(timeMs: Long = System.currentTimeMillis(), random: SecureRandom = this.random): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        // 48-bit big-endian timestamp
        bytes[0] = ((timeMs ushr 40) and 0xFF).toByte()
        bytes[1] = ((timeMs ushr 32) and 0xFF).toByte()
        bytes[2] = ((timeMs ushr 24) and 0xFF).toByte()
        bytes[3] = ((timeMs ushr 16) and 0xFF).toByte()
        bytes[4] = ((timeMs ushr 8) and 0xFF).toByte()
        bytes[5] = (timeMs and 0xFF).toByte()
        // version 7
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        // variant 10xx
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private const val UUID_V7_REGEX =
        "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"

    fun isValidFormat(id: String): Boolean = Regex(UUID_V7_REGEX).matches(id)
}
