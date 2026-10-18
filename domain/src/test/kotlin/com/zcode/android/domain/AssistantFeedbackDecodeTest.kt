package com.zcode.android.domain

import com.zcode.android.domain.model.ConversationRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * E3.2+I1+N5 (gaps-and-polish Phase 4A): rows.ts `assistantTextRowSchema.feedback:
 * z.enum(["like","dislike"]).optional()` was never decoded on the phone — the
 * desktop renders Liked/Disliked from it (chat.message.liked/disliked). The
 * decoder now carries it (tolerant: absent → null, unknown value → raw string
 * retained per INV-8 — never a crash, never a guess).
 */
class AssistantFeedbackDecodeTest {

    private fun row(feedbackJson: String) = ConversationRow.Decoder.decode(
        Json.parseToJsonElement(
            """{"rowId":3,"turnId":"t1","kind":"assistantText","createdAt":1700000000000,"createdAtSeq":3,
                "text":"hello","state":"complete"$feedbackJson}""",
        ).jsonObject,
    )

    @Test
    fun `like and dislike decode onto the assistant row`() {
        assertEquals("like", assertIs<ConversationRow.AssistantText>(row(""","feedback":"like"""")).feedback)
        assertEquals("dislike", assertIs<ConversationRow.AssistantText>(row(""","feedback":"dislike"""")).feedback)
    }

    @Test
    fun `absent feedback is null (no feedback given)`() {
        assertNull(assertIs<ConversationRow.AssistantText>(row("")).feedback)
    }

    @Test
    fun `unknown feedback value is retained raw, never dropped`() {
        assertEquals("meh", assertIs<ConversationRow.AssistantText>(row(""","feedback":"meh"""")).feedback)
    }
}
