package com.zcode.android.features.conversation.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.zcode.android.domain.model.ConversationRow

/**
 * G5 (vc51): find-in-conversation — the desktop's `useConversationTimelineFind` /
 * `conversationFindIndex` plane. The index is a PURE function over the timeline's rows
 * (user inputs + assistant prose; case-insensitive, every occurrence), so the pin walks
 * rows directly and the UI only maps match → entry for the jump.
 */
data class FindMatch(
    val rowId: Long,
    val rowKind: String,
    /** Occurrence index within the row (0-based) — the per-row ordinal of this match. */
    val occurrence: Int,
    val start: Int,
    val end: Int,
)

/** All matches of [query] over the text-bearing rows, in timeline order (row, then offset). */
fun buildConversationFindIndex(rows: List<ConversationRow>, query: String): List<FindMatch> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    val matches = mutableListOf<FindMatch>()
    for (row in rows) {
        val text = when (row) {
            is ConversationRow.UserInput -> row.text
            is ConversationRow.AssistantText -> row.text
            else -> null
        } ?: continue
        val hay = text.lowercase()
        var from = 0
        var occurrence = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) break
            matches += FindMatch(row.rowId, row.kind, occurrence++, at, at + needle.length)
            from = at + needle.length
        }
    }
    return matches
}

/**
 * G5: render [text] with every [query] occurrence wrapped in [highlight] — the phone's
 * `conversationFindHighlightDom` counterpart for the plain-text renderers (user rows;
 * assistant prose renders markdown and keeps its own pipeline).
 */
fun annotatedWithHighlights(text: String, query: String, highlight: SpanStyle): AnnotatedString {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return AnnotatedString(text)
    val hay = text.lowercase()
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) break
            addStyle(highlight, at, at + needle.length)
            from = at + needle.length
        }
    }
}
