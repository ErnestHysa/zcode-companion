package com.zcode.android.core.protocol.v4

import com.zcode.android.core.protocol.bytes.ProtocolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v4 conversation plane models — transport.ts subscribe/resync/rowsRange + the topic
 * frame payload (createTopicFrameSchema: snapshot | deltas) + delta op vocabulary
 * (delta.ts) with unknown-tolerance decoding (INV-8). Rows decode generically (raw
 * JsonObject + rowId + kind); typed row models are Phase 4 domain work (§16).
 */

// ---------------------------------------------------------------- topics

/** Topics (transport.ts §3.1): conversation/<id>, sessions-index/<id>, workspace-config/<id>. */
object V4Topics {
    const val CONVERSATION_PREFIX = "conversation/"
    const val SESSIONS_INDEX_PREFIX = "sessions-index/"
    const val WORKSPACE_CONFIG_PREFIX = "workspace-config/"

    fun conversation(sessionId: String) = "$CONVERSATION_PREFIX$sessionId"
    fun sessionsIndex(workspaceId: String) = "$SESSIONS_INDEX_PREFIX$workspaceId"
    fun workspaceConfig(workspaceId: String) = "$WORKSPACE_CONFIG_PREFIX$workspaceId"
}

// ---------------------------------------------------------------- subscribe / resync / rowsRange

data class SubscribeAck(
    val subscriptionId: String,
    /** resume = base valid, deltas continue from base.seq; otherwise snapshot. */
    val mode: String,
    val logEpoch: String,
) {
    companion object {
        fun decode(obj: JsonObject): SubscribeAck = SubscribeAck(
            subscriptionId = obj.string("subscriptionId"),
            mode = obj.string("mode").also { if (it != "snapshot" && it != "resume") throw ProtocolException("subscribeAck.mode invalid: $it") },
            logEpoch = obj.string("logEpoch"),
        )
    }
}

/** v4ConversationSubscribeParamsSchema (client-facing subset the phone may send). */
data class SubscribeParams(
    val topic: String,
    val connectionId: String,
    val clientMode: String = "web-remote-replayable",
    val base: BaseWatermark? = null,
    val visibility: String? = null,
) {
    data class BaseWatermark(val logEpoch: String, val seq: Long)

    fun encode(): JsonObject = buildJsonObject {
        put("topic", JsonPrimitive(topic))
        put("connectionId", JsonPrimitive(connectionId))
        put("clientMode", JsonPrimitive(clientMode))
        base?.let {
            put("base", buildJsonObject {
                put("logEpoch", JsonPrimitive(it.logEpoch))
                put("seq", JsonPrimitive(it.seq))
            })
        }
        visibility?.let { put("visibility", JsonPrimitive(it)) }
    }
}

data class ResyncParams(
    val topic: String,
    val connectionId: String,
    val subscriptionId: String,
    /** null base = force snapshot. */
    val base: BaseWatermark?,
    val forceSnapshot: Boolean = false,
) {
    data class BaseWatermark(val logEpoch: String, val seq: Long)

    fun encode(): JsonObject = buildJsonObject {
        put("topic", JsonPrimitive(topic))
        put("connectionId", JsonPrimitive(connectionId))
        put("subscriptionId", JsonPrimitive(subscriptionId))
        if (base == null) put("base", JsonNull) else put("base", buildJsonObject {
            put("logEpoch", JsonPrimitive(base.logEpoch))
            put("seq", JsonPrimitive(base.seq))
        })
        if (forceSnapshot) put("forceSnapshot", JsonPrimitive(true))
    }
}

/** v4ConversationRowsRangeResultSchema — rows decode generically (Phase 4 types them). */
data class RowsRangeParams(
    val sessionId: String,
    val beforeRowId: Long? = null,
    val limit: Int,
) {
    init {
        if (limit < 1 || limit > V4Limits.ROWS_RANGE_MAX_LIMIT) {
            throw ProtocolException("rowsRange limit $limit outside 1..${V4Limits.ROWS_RANGE_MAX_LIMIT} (INV-13)")
        }
    }

    fun encode(): JsonObject = buildJsonObject {
        put("sessionId", JsonPrimitive(sessionId))
        beforeRowId?.let { put("beforeRowId", JsonPrimitive(it)) }
        put("limit", JsonPrimitive(limit))
    }
}

data class RowsRangeResult(
    val rows: List<ConversationRow>,
    val atSeq: Long,
    val atRevision: Long,
    val atLogEpoch: String,
    val hasMore: Boolean,
) {
    companion object {
        fun decode(obj: JsonObject): RowsRangeResult {
            val rowsArr = obj["rows"] as? kotlinx.serialization.json.JsonArray
                ?: throw ProtocolException("rowsRange.rows missing")
            return RowsRangeResult(
                rows = rowsArr.map { ConversationRow.decode(it as? JsonObject ?: throw ProtocolException("row not object")) },
                atSeq = obj.long("atSeq"),
                atRevision = obj.long("atRevision"),
                atLogEpoch = obj.string("atLogEpoch"),
                hasMore = obj.bool("hasMore"),
            )
        }
    }
}

// ---------------------------------------------------------------- topic frame payload

/**
 * ConversationTopicFrame (transport.ts createTopicFrameSchema): the logical frame
 * carried inside a TopicWireFrame.Complete or a fully assembled fragment set.
 */
data class TopicFrame(
    val topic: String,
    val subscriptionId: String,
    val fromSeq: Long,
    val toSeq: Long,
    val sentAt: Double,
    val payload: TopicPayload,
) {
    sealed class TopicPayload {
        data class Snapshot(val snapshot: JsonObject) : TopicPayload()
        data class Deltas(val deltas: List<ConversationDelta>) : TopicPayload()
    }

    companion object {
        private val tolerantJson = Json { ignoreUnknownKeys = true }

        fun decode(frame: JsonObject): TopicFrame {
            val topic = frame.string("topic")
            if (topic.startsWith(V4Topics.CONVERSATION_PREFIX) && topic.length == V4Topics.CONVERSATION_PREFIX.length) {
                throw ProtocolException("conversation topic requires a session id suffix")
            }
            val payload = frame["payload"] as? JsonObject ?: throw ProtocolException("TopicFrame.payload missing")
            return when ((payload["kind"] as? JsonPrimitive)?.content) {
                "snapshot" -> TopicFrame(
                    topic = topic,
                    subscriptionId = frame.string("subscriptionId"),
                    fromSeq = frame.long("fromSeq"),
                    toSeq = frame.long("toSeq"),
                    sentAt = frame.double("sentAt"),
                    payload = TopicPayload.Snapshot(
                        payload["snapshot"] as? JsonObject ?: throw ProtocolException("snapshot payload missing"),
                    ),
                )
                "deltas" -> TopicFrame(
                    topic = topic,
                    subscriptionId = frame.string("subscriptionId"),
                    fromSeq = frame.long("fromSeq"),
                    toSeq = frame.long("toSeq"),
                    sentAt = frame.double("sentAt"),
                    payload = TopicPayload.Deltas(
                        (payload["deltas"] as? kotlinx.serialization.json.JsonArray
                            ?: throw ProtocolException("deltas payload missing"))
                            .map { ConversationDelta.decode(it as? JsonObject ?: throw ProtocolException("delta not object")) },
                    ),
                )
                else -> throw ProtocolException("TopicFrame payload kind invalid")
            }
        }
    }
}

/**
 * ConversationDelta ops (delta.ts): the verified op set; unknown ops decode as
 * Unknown and are COUNTED, never dropped silently (INV-8).
 */
sealed class ConversationDelta {
    abstract val raw: JsonObject

    data class RowAppended(val row: ConversationRow, override val raw: JsonObject) : ConversationDelta()
    data class RowUpserted(val row: ConversationRow, override val raw: JsonObject) : ConversationDelta()
    data class RowRemoved(val fromRowId: Long, override val raw: JsonObject) : ConversationDelta()
    data class RowDelta(val rowId: Long, val path: String, val append: String, override val raw: JsonObject) : ConversationDelta()
    data class StateUpdated(val patch: JsonObject, override val raw: JsonObject) : ConversationDelta()
    data class Unknown(val op: String?, override val raw: JsonObject) : ConversationDelta()

    companion object {
        var unknownOpCount: Long = 0
            private set

        fun decode(obj: JsonObject): ConversationDelta {
            val op = (obj["op"] as? JsonPrimitive)?.content
            return when (op) {
                "row.appended" -> RowAppended(row(obj), obj)
                "row.upserted" -> RowUpserted(row(obj), obj)
                "row.removed" -> RowRemoved(obj.long("fromRowId"), obj)
                "row.delta" -> RowDelta(obj.long("rowId"), obj.string("path"), obj.string("append"), obj)
                "state.updated" -> StateUpdated(obj["patch"] as? JsonObject ?: throw ProtocolException("state.updated patch missing"), obj)
                else -> { unknownOpCount++; Unknown(op, obj) }
            }
        }

        private fun row(obj: JsonObject): ConversationRow =
            ConversationRow.decode(obj["row"] as? JsonObject ?: throw ProtocolException("delta.row missing"))
    }
}

/** Generic conversation row: identity + kind + raw (INV-8 unknown-kind fallback). */
data class ConversationRow(
    val rowId: Long,
    val kind: String,
    val raw: JsonObject,
) {
    companion object {
        const val KIND_TURN_HEADER = "turnHeader"
        const val KIND_USER_INPUT = "userInput"
        const val KIND_ASSISTANT_TEXT = "assistantText"
        const val KIND_REASONING = "reasoning"
        const val KIND_TOOL_CALL = "toolCall"
        const val KIND_ARTIFACT = "artifact"
        const val KIND_SUBAGENT = "subagent"
        const val KIND_HOOK_INVOCATION = "hookInvocation"
        const val KIND_TIMELINE_MARKER = "timelineMarker"

        val KNOWN_KINDS = setOf(
            KIND_TURN_HEADER, KIND_USER_INPUT, KIND_ASSISTANT_TEXT, KIND_REASONING,
            KIND_TOOL_CALL, KIND_ARTIFACT, KIND_SUBAGENT, KIND_HOOK_INVOCATION, KIND_TIMELINE_MARKER,
        )

        fun decode(obj: JsonObject): ConversationRow = ConversationRow(
            rowId = obj.long("rowId"),
            kind = obj.string("kind"),
            raw = obj,
        )

        /** UI routing: unknown kinds render via the generic fallback (§19.5). */
        fun isKnownKind(kind: String): Boolean = kind in KNOWN_KINDS
    }
}

// ---------------------------------------------------------------- small json helpers

internal fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: throw ProtocolException("expected string field $key")

internal fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: throw ProtocolException("expected numeric field $key")

internal fun JsonObject.double(key: String): Double =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: throw ProtocolException("expected numeric field $key")

internal fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: throw ProtocolException("expected boolean field $key")
