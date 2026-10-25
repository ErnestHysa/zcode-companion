package com.zcode.android.domain.model

import com.zcode.android.core.common.log.ZLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * L3 (gaps-and-polish Phase 3B): ONE bounded preview line for an open interaction, so
 * the approvals inbox is never approve-blind. Derived from the retained payload — the
 * verified wire's `detail` object (snapshot pendingInteraction / permission event; the
 * §20.1 vocabulary name `input` is accepted too), then the `summary`/`reason` line, or
 * the first question (user-input):
 *
 *  - well-known keys first (`command` for Bash, `file_path`/`path` for file tools,
 *    `pattern`/`query`/`url`/`description` for search/fetch classes);
 *  - otherwise the first string field as `key: value` — honest about an unknown shape,
 *    never the raw JSON blob — then the desktop's summary line;
 *  - whitespace collapsed to one line, secrets scrubbed with the diagnostics rules
 *    ([ZLog.scrubStructural]), hard-bounded to [MAX_CHARS] with an ellipsis.
 *
 * Pure and total: malformed payloads yield null (the card then shows the tool name
 * only, exactly as before) — assistant/tool payloads are untrusted (T15).
 */
object InteractionPreview {

    const val MAX_CHARS = 140

    private val PERMISSION_KEYS = listOf(
        "command", "cmd", "file_path", "filePath", "path", "notebook_path",
        "pattern", "query", "url", "description", "prompt",
    )

    private val SUMMARY_KEYS = listOf("summary", "reason")

    private val QUESTION_KEYS = listOf("question", "prompt", "title", "message")

    fun of(toolName: String?, payload: JsonObject?): String? {
        if (payload == null) return null
        val raw = runCatching { derive(payload) }.getOrNull() ?: return null
        return bound(raw)
    }

    private fun derive(payload: JsonObject): String? {
        // Permission: the verified wire carries the tool input as `detail` (snapshot
        // pendingInteraction + permission event — InteractionCoordinator maps it to
        // PermissionRequest.input); `input` is accepted as the §20.1 vocabulary name.
        val input = (payload["detail"] as? JsonObject) ?: (payload["input"] as? JsonObject)
        if (input != null) {
            for (key in PERMISSION_KEYS) {
                val v = (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!v.isNullOrBlank()) return v
            }
            val first = input.entries.firstOrNull { (_, v) -> (v as? JsonPrimitive)?.isString == true && v.content.isNotBlank() }
            if (first != null) return "${first.key}: ${(first.value as JsonPrimitive).content}"
        }
        // The toolCall-ROW shape (the snapshot path stores the row itself): its
        // input line IS the command the user is approving.
        val inputText = (payload["inputText"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!inputText.isNullOrBlank()) return inputText
        // Permission without a usable detail: the desktop's summary/reason line.
        for (key in SUMMARY_KEYS) {
            val v = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!v.isNullOrBlank()) return v
        }
        // User-input: the first question's text.
        (payload["questions"] as? JsonArray)?.firstOrNull()?.let { q ->
            val obj = q as? JsonObject ?: return null
            for (key in QUESTION_KEYS) {
                val v = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!v.isNullOrBlank()) return v
            }
        }
        for (key in QUESTION_KEYS) {
            val v = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun bound(text: String): String? {
        val oneLine = text.replace(WHITESPACE, " ").trim()
        if (oneLine.isEmpty()) return null
        val scrubbed = ZLog.scrubStructural(oneLine)
        return if (scrubbed.length <= MAX_CHARS) scrubbed else scrubbed.take(MAX_CHARS - 1) + "…"
    }

    private val WHITESPACE = Regex("\\s+")
}
