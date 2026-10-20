package com.zcode.android.features.conversation

import com.zcode.android.domain.model.ConversationRow

/**
 * A3.16 + L6.1 (gaps-and-polish Phase 5C): the share-OUT transcript — the Android system
 * sheet is the delivery mechanism for what the phone can produce. Plain-text, deterministic
 * (INV-16: rows in order, timestamps UTC-agnostic relative-free), tool/reasoning rows
 * collapsed to one quiet line each (a transcript is a reading artifact, not a log).
 */
object TranscriptBuilder {

    fun build(taskTitle: String?, rows: List<ConversationRow>): String {
        val sb = StringBuilder()
        sb.append(taskTitle?.takeIf { it.isNotBlank() } ?: "Conversation")
        sb.append('\n')
        sb.append("—".repeat(12))
        sb.append('\n')
        for (row in rows) {
            when (row) {
                is ConversationRow.UserInput -> {
                    sb.append("\nYou:\n").append(row.text.trim()).append('\n')
                    val names = row.attachments?.mapNotNull { it.fileName }
                    if (!names.isNullOrEmpty()) sb.append("[attached: ").append(names.joinToString(", ")).append("]\n")
                }
                is ConversationRow.AssistantText -> {
                    val text = row.text?.trim().orEmpty()
                    if (text.isNotEmpty()) sb.append("\nAssistant:\n").append(text).append('\n')
                }
                is ConversationRow.ToolCall -> {
                    sb.append("\n[tool: ").append(row.toolName).append("]\n")
                }
                is ConversationRow.Reasoning -> {
                    // collapsed: reasoning stays private-to-the-run in a share artifact
                }
                is ConversationRow.TurnHeader -> {
                    sb.append('\n').append("—".repeat(12)).append('\n')
                }
                else -> Unit
            }
        }
        return sb.toString().trim() + "\n"
    }
}
