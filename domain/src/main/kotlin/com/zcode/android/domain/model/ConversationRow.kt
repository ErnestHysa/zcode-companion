package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Typed conversation rows — architectural.md §16/§7.4.6, ported from the VERIFIED
 * rows.ts schema (reference clone v3.14.3 @ 29628c9, packages/shared/src/
 * zcode-protocol-v4/rows.ts; read 2026-09-28).
 *
 * Unknown-tolerance policy (INV-8 + INV-14 + §39.3, exact):
 *  - UNKNOWN row KIND → [ConversationRow.Unknown] (tolerated, raw retained, COUNTED).
 *  - UNKNOWN enum value inside a known kind → tolerant enum case carrying the raw
 *    wire string (e.g. [ToolCallStatus.UNKNOWN]); never a crash, never guessed.
 *  - KNOWN kind with a REQUIRED field missing/wrong-typed → [RowDecodeException]
 *    (a known schema violation is rejected SAFELY — caller resyncs, §39.3/INV-14;
 *    never a lenient degraded interpretation).
 *  - Extra/unknown FIELDS → tolerated and preserved: every row keeps its full
 *    `raw` JsonObject (§16 unknown-tolerance rule).
 */
sealed class ConversationRow {
    abstract val rowId: Long
    abstract val turnId: String
    abstract val kind: String
    /** Event-log ordering currency (rows.ts rowBaseFields.createdAtSeq). */
    abstract val createdAtSeq: Long
    /** Full wire object — unknown fields ride along here (INV-8). */
    abstract val raw: JsonObject

    /** rows.ts rowBaseFields (all kinds). */
    data class Base(
        val entityId: String? = null,
        val productTurnId: String? = null,
        val visibility: String? = null,
        val createdAt: Double,
        val createdAtSeq: Long,
        val actions: Actions? = null,
    ) {
        data class Actions(
            val canFork: Boolean = false,
            val canEdit: Boolean = false,
            val canRetry: Boolean = false,
            val canRewindFiles: Boolean = false,
            val editDisposition: String? = null,
        )
    }

    /** Turn boundary + authoritative timing + per-turn file summary (§7.4.6). */
    data class TurnHeader(
        override val rowId: Long,
        override val turnId: String,
        val base: Base,
        val origin: String,
        val executionKind: String?,
        val state: TurnState,
        val startedAt: Double,
        val endedAt: Double?,
        val activeMs: Long?,
        val sourceCommandId: String?,
        val historyRoundCount: Long?,
        val fileChanges: FileChanges?,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = KIND
    override val createdAtSeq: Long get() = base.createdAtSeq

        /** TurnFileChangeSummary shape verified in rows.ts fileChanges sub-schema. */
        data class FileChanges(val additions: Long, val deletions: Long, val files: Long, val state: String?)

        companion object { const val KIND = "turnHeader" }
    }

    /** User input — origin-aware (`origin`/`originMeta`, no text sniffing, §7.4.6). */
    data class UserInput(
        override val rowId: Long,
        override val turnId: String,
        val base: Base,
        val text: String,
        /** Index from which `text` is engine-appended epilogue (0 = none). */
        val epilogueStart: Long?,
        val origin: String,
        val originMeta: OriginMeta?,
        val guided: Boolean,
        val sourceCommandId: String?,
        val attachments: List<AttachmentMeta>,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = KIND
    override val createdAtSeq: Long get() = base.createdAtSeq

        data class OriginMeta(val backgroundSource: String?, val workId: String?, val senderSessionId: String?, val senderLabel: String?)
        data class AttachmentMeta(val ref: String, val fileName: String, val mime: String, val bytes: Long, val previewRef: String?)

        companion object { const val KIND = "userInput" }
    }

    data class AssistantText(
        override val rowId: Long,
        override val turnId: String,
        val base: Base,
        val assistantResponseId: String?,
        val text: String,
        val state: TextState,
        val model: String?,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = KIND
    override val createdAtSeq: Long get() = base.createdAtSeq
        companion object { const val KIND = "assistantText" }
    }

    data class Reasoning(
        override val rowId: Long,
        override val turnId: String,
        val base: Base,
        val assistantResponseId: String?,
        val text: String,
        val state: TextState,
        val durationMs: Long?,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = KIND
    override val createdAtSeq: Long get() = base.createdAtSeq
        companion object { const val KIND = "reasoning" }
    }

    data class ToolCall(
        override val rowId: Long,
        override val turnId: String,
        val base: Base,
        val assistantResponseId: String?,
        val toolCallId: String,
        val toolName: String,
        val status: ToolCallStatus,
        val inputText: String,
        val input: JsonObject?,
        val outputPreview: JsonObject?,
        val error: ToolError?,
        val approvalInteractionId: String?,
        val startedAt: Double?,
        val endedAt: Double?,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = KIND
    override val createdAtSeq: Long get() = base.createdAtSeq

        data class ToolError(val code: String, val message: String)

        companion object { const val KIND = "toolCall" }
    }

    data class Artifact(
        override val rowId: Long,
        override val turnId: String,
        val base: Base,
        val artifactVersionId: String,
        val logicalArtifactKey: String,
        val displayName: String,
        val artifactType: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
        val ref: String,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = KIND
    override val createdAtSeq: Long get() = base.createdAtSeq
        companion object { const val KIND = "artifact" }
    }

    data class Subagent(
        override val rowId: Long,
        override val turnId: String,
        val base: Base,
        val parentToolCallId: String?,
        val subagentType: String,
        val status: SubagentStatus,
        val summaryText: String,
        val childSessionId: String?,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = KIND
    override val createdAtSeq: Long get() = base.createdAtSeq
        companion object { const val KIND = "subagent" }
    }

    data class HookInvocation(
        override val rowId: Long,
        override val turnId: String,
        val base: Base,
        val hookInvocationId: String,
        val hookEventName: String,
        val hookCount: Long,
        val state: HookState,
        val lane: String,
        val executions: JsonArray,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = KIND
    override val createdAtSeq: Long get() = base.createdAtSeq
        companion object { const val KIND = "hookInvocation" }
    }

    /** Timeline marker — payload kept typed-lite (type + raw) per rows.ts union. */
    data class TimelineMarker(
        override val rowId: Long,
        override val turnId: String,
        val base: Base,
        val markerType: String,
        val lane: String?,
        val marker: JsonObject,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = KIND
    override val createdAtSeq: Long get() = base.createdAtSeq
        companion object { const val KIND = "timelineMarker" }
    }

    /** Unknown kind — INV-8: tolerated, raw retained, COUNTED (see [Decoder]). */
    data class Unknown(
        override val rowId: Long,
        override val turnId: String,
        val unknownKind: String,
        override val raw: JsonObject,
    ) : ConversationRow() {
        override val kind: String get() = unknownKind
    override val createdAtSeq: Long get() = 0
    }

    // ---------------------------------------------------------------- enums (tolerant)

    /** rows.ts turnHeader.state — unknown values preserved raw (INV-8). */
    enum class TurnState(val wire: String) {
        RUNNING("running"),
        COMPLETED_SUCCESS("completedSuccess"),
        COMPLETED_INTERRUPTED("completedInterrupted"),
        FAILED("failed"),
        UNKNOWN("");

        companion object {
            fun fromWire(v: String?): TurnState = entries.firstOrNull { it.wire == v } ?: UNKNOWN
        }
    }

    /** assistantText/reasoning state. */
    enum class TextState(val wire: String) {
        STREAMING("streaming"),
        COMPLETE("complete"),
        INTERRUPTED("interrupted"),
        FAILED("failed"),
        UNKNOWN("");

        companion object {
            fun fromWire(v: String?): TextState = entries.firstOrNull { it.wire == v } ?: UNKNOWN
        }
    }

    /** rows.ts toolCall.status — unknown values preserved raw (INV-8, §19.5 fallback). */
    enum class ToolCallStatus(val wire: String) {
        INPUT_STREAMING("inputStreaming"),
        PENDING_APPROVAL("pendingApproval"),
        RUNNING("running"),
        SUCCESS("success"),
        ERROR("error"),
        CANCELLED("cancelled"),
        UNKNOWN("");

        companion object {
            fun fromWire(v: String?): ToolCallStatus = entries.firstOrNull { it.wire == v } ?: UNKNOWN
        }
    }

    enum class SubagentStatus(val wire: String) {
        RUNNING("running"),
        SUCCESS("success"),
        FAILED("failed"),
        CANCELLED("cancelled"),
        UNKNOWN("");

        companion object {
            fun fromWire(v: String?): SubagentStatus = entries.firstOrNull { it.wire == v } ?: UNKNOWN
        }
    }

    enum class HookState(val wire: String) {
        RUNNING("running"),
        COMPLETED("completed"),
        FAILED("failed"),
        UNKNOWN("");

        companion object {
            fun fromWire(v: String?): HookState = entries.firstOrNull { it.wire == v } ?: UNKNOWN
        }
    }

    /**
     * Typed decoder over the verified schema. Required-field violations of KNOWN kinds
     * throw [RowDecodeException] (fail closed, INV-14); unknown kinds/fields/enums
     * tolerate (INV-8).
     */
    object Decoder {
        val unknownKindCount: Long get() = unknownKindCounter

        @Volatile
        private var unknownKindCounter: Long = 0

        fun decode(obj: JsonObject): ConversationRow {
            val rowId = obj.long("rowId")
            val turnId = obj.string("turnId")
            val kind = obj.string("kind")
            val base = decodeBase(obj)
            return when (kind) {
                TurnHeader.KIND -> TurnHeader(
                    rowId = rowId,
                    turnId = turnId,
                    base = base,
                    origin = obj.string("origin"),
                    executionKind = obj.strOrNull("executionKind"),
                    state = TurnState.fromWire(obj.strOrNull("state")),
                    startedAt = obj.double("startedAt"),
                    endedAt = obj.doubleOrNull("endedAt"),
                    activeMs = obj.longOrNull("activeMs"),
                    sourceCommandId = obj.strOrNull("sourceCommandId"),
                    historyRoundCount = obj.longOrNull("historyRoundCount"),
                    fileChanges = (obj["fileChanges"] as? JsonObject)?.let { fc ->
                        TurnHeader.FileChanges(
                            additions = fc.long("additions"),
                            deletions = fc.long("deletions"),
                            files = fc.long("files"),
                            state = fc.strOrNull("state"),
                        )
                    },
                    raw = obj,
                )
                UserInput.KIND -> UserInput(
                    rowId = rowId,
                    turnId = turnId,
                    base = base,
                    text = obj.string("text"),
                    epilogueStart = obj.longOrNull("epilogueStart"),
                    origin = obj.string("origin"),
                    originMeta = (obj["originMeta"] as? JsonObject)?.let { om ->
                        UserInput.OriginMeta(
                            backgroundSource = om.strOrNull("backgroundSource"),
                            workId = om.strOrNull("workId"),
                            senderSessionId = om.strOrNull("senderSessionId"),
                            senderLabel = om.strOrNull("senderLabel"),
                        )
                    },
                    guided = obj["guided"] is JsonPrimitive && (obj["guided"] as JsonPrimitive).content == "true",
                    sourceCommandId = obj.strOrNull("sourceCommandId"),
                    attachments = (obj["attachments"] as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        ?.map { a ->
                            UserInput.AttachmentMeta(
                                ref = a.string("ref"),
                                fileName = a.string("fileName"),
                                mime = a.string("mime"),
                                bytes = a.long("bytes"),
                                previewRef = a.strOrNull("previewRef"),
                            )
                        }
                        ?: emptyList(),
                    raw = obj,
                )
                AssistantText.KIND -> AssistantText(
                    rowId = rowId,
                    turnId = turnId,
                    base = base,
                    assistantResponseId = obj.strOrNull("assistantResponseId"),
                    text = obj.string("text"),
                    state = TextState.fromWire(obj.strOrNull("state")),
                    model = obj.strOrNull("model"),
                    raw = obj,
                )
                Reasoning.KIND -> Reasoning(
                    rowId = rowId,
                    turnId = turnId,
                    base = base,
                    assistantResponseId = obj.strOrNull("assistantResponseId"),
                    text = obj.string("text"),
                    state = TextState.fromWire(obj.strOrNull("state")),
                    durationMs = obj.longOrNull("durationMs"),
                    raw = obj,
                )
                ToolCall.KIND -> ToolCall(
                    rowId = rowId,
                    turnId = turnId,
                    base = base,
                    assistantResponseId = obj.strOrNull("assistantResponseId"),
                    toolCallId = obj.string("toolCallId"),
                    toolName = obj.string("toolName"),
                    status = ToolCallStatus.fromWire(obj.strOrNull("status")),
                    inputText = obj.string("inputText"),
                    input = obj["input"] as? JsonObject,
                    outputPreview = obj["outputPreview"] as? JsonObject,
                    error = (obj["error"] as? JsonObject)?.let { e ->
                        ToolCall.ToolError(code = e.string("code"), message = e.string("message"))
                    },
                    approvalInteractionId = obj.strOrNull("approvalInteractionId"),
                    startedAt = obj.doubleOrNull("startedAt"),
                    endedAt = obj.doubleOrNull("endedAt"),
                    raw = obj,
                )
                Artifact.KIND -> Artifact(
                    rowId = rowId,
                    turnId = turnId,
                    base = base,
                    artifactVersionId = obj.string("artifactVersionId"),
                    logicalArtifactKey = obj.string("logicalArtifactKey"),
                    displayName = obj.string("displayName"),
                    artifactType = obj.string("artifactType"),
                    mimeType = obj.string("mimeType"),
                    sizeBytes = obj.long("sizeBytes"),
                    sha256 = obj.string("sha256"),
                    ref = obj.string("ref"),
                    raw = obj,
                )
                Subagent.KIND -> Subagent(
                    rowId = rowId,
                    turnId = turnId,
                    base = base,
                    parentToolCallId = obj.strOrNull("parentToolCallId"),
                    subagentType = obj.string("subagentType"),
                    status = SubagentStatus.fromWire(obj.strOrNull("status")),
                    summaryText = obj.string("summaryText"),
                    childSessionId = obj.strOrNull("childSessionId"),
                    raw = obj,
                )
                HookInvocation.KIND -> HookInvocation(
                    rowId = rowId,
                    turnId = turnId,
                    base = base,
                    hookInvocationId = obj.string("hookInvocationId"),
                    hookEventName = obj.string("hookEventName"),
                    hookCount = obj.long("hookCount"),
                    state = HookState.fromWire(obj.strOrNull("state")),
                    lane = obj.string("lane"),
                    executions = obj["executions"] as? JsonArray ?: JsonArray(emptyList()),
                    raw = obj,
                )
                TimelineMarker.KIND -> TimelineMarker(
                    rowId = rowId,
                    turnId = turnId,
                    base = base,
                    markerType = ((obj["marker"] as? JsonObject)?.get("type") as? JsonPrimitive)?.content ?: "unknown",
                    lane = obj.strOrNull("lane"),
                    marker = obj["marker"] as? JsonObject ?: JsonObject(emptyMap()),
                    raw = obj,
                )
                else -> {
                    unknownKindCounter++
                    Unknown(rowId = rowId, turnId = turnId, unknownKind = kind, raw = obj)
                }
            }
        }

        private fun decodeBase(obj: JsonObject): Base = Base(
            entityId = obj.strOrNull("entityId"),
            productTurnId = obj.strOrNull("productTurnId"),
            visibility = obj.strOrNull("visibility"),
            createdAt = obj.double("createdAt"),
            createdAtSeq = obj.long("createdAtSeq"),
            actions = (obj["actions"] as? JsonObject)?.let { a ->
                Base.Actions(
                    canFork = a.boolOrFalse("canFork"),
                    canEdit = a.boolOrFalse("canEdit"),
                    canRetry = a.boolOrFalse("canRetry"),
                    canRewindFiles = a.boolOrFalse("canRewindFiles"),
                    editDisposition = a.strOrNull("editDisposition"),
                )
            },
        )
    }

    /** Known row kinds (§7.4.6 / rows.ts discriminated union) — UI fallback routing. */
    object Kinds {
        val ALL = setOf(
            TurnHeader.KIND, UserInput.KIND, AssistantText.KIND, Reasoning.KIND,
            ToolCall.KIND, Artifact.KIND, Subagent.KIND, HookInvocation.KIND, TimelineMarker.KIND,
        )
    }
}

/** Typed decode fault for a KNOWN-kind schema violation (§39.3 → resync, never merge). */
class RowDecodeException(message: String) : Exception(message)

// ------------------------------------------------------------------ json helpers (domain-local)

internal fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: throw RowDecodeException("required field `$key` missing or non-primitive")

internal fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content

internal fun JsonObject.double(key: String): Double =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: throw RowDecodeException("required numeric field `$key` missing")

internal fun JsonObject.doubleOrNull(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

internal fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: throw RowDecodeException("required numeric field `$key` missing")

internal fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.boolOrFalse(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
