package com.zcode.android.features.conversation.port

import com.zcode.android.domain.model.ConversationRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * F-P3-B / N-BM-2 (pass-0 Phase 5): the UNSCRIPTED child's transcript decode.
 *
 * An automation run's session the task corpus does not carry never opens/subscribes,
 * so its timeline starved on skeletons forever. The hydrate composes the
 * already-consumed `readSessionMessages` verb (the search plane's read —
 * `fetchSessionMessages`, consumed at [ConversationPort.fetchSessionMessages]) into
 * local timeline rows: `{role, content}` message objects → userInput/assistantText
 * rows through [ConversationRow.Decoder] (INV-8: the full wire object rides `raw`).
 *
 * The session's IDENTITY title (N-BM-2): the desktop names untitled sessions by their
 * first user message — that head is the title; a session with no user message falls
 * back to the caller's fallback (the raw session id — the only identity it has).
 */
internal object UnscriptedTranscript {

    /** `readSessionMessages` items (`{role, content}`) → timeline rows (stable local ids). */
    fun rowsFor(messages: List<JsonObject>): List<ConversationRow> =
        messages.mapIndexedNotNull { index, message ->
            val role = message.str("role") ?: return@mapIndexedNotNull null
            val content = message.content() ?: return@mapIndexedNotNull null
            val kind = when (role.lowercase()) {
                "user" -> "userInput"
                "assistant" -> "assistantText"
                // tool/system message shapes are not timeline rows — tolerate and skip.
                else -> return@mapIndexedNotNull null
            }
            ConversationRow.Decoder.decode(
                buildJsonObject {
                    put("rowId", JsonPrimitive((index + 1).toLong()))
                    put("turnId", JsonPrimitive("unscripted"))
                    put("kind", JsonPrimitive(kind))
                    put("createdAt", JsonPrimitive((index + 1) * 1000.0))
                    put("createdAtSeq", JsonPrimitive((index + 1).toLong()))
                    if (kind == "userInput") {
                        put("text", JsonPrimitive(content))
                        put("origin", JsonPrimitive("user"))
                        put("guided", JsonPrimitive(false))
                    } else {
                        put("assistantResponseId", JsonPrimitive("unscripted-$index"))
                        put("text", JsonPrimitive(content))
                        put("state", JsonPrimitive("complete"))
                    }
                },
            )
        }

    /** The session's identity title: the first user message's head, else [fallback]. */
    fun titleFor(messages: List<JsonObject>, fallback: String?): String? =
        messages.firstOrNull { it.str("role")?.lowercase() == "user" }
            ?.content()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > TITLE_HEAD) it.take(TITLE_HEAD - 1) + "…" else it }
            ?: fallback

    private const val TITLE_HEAD = 80

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    /** The message's readable text (the search plane's field set: content/text/summary). */
    private fun JsonObject.content(): String? =
        listOf("content", "text", "summary").firstNotNullOfOrNull { str(it) }
}
