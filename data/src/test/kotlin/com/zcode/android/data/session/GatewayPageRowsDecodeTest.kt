package com.zcode.android.data.session

import com.zcode.android.domain.model.ConversationRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * FIELD-KILLER #11 regression (2026-09-30, vc16 field): history PAGES were decoded with
 * the STRICT row decoder — one production row kind the ported schema doesn't model
 * failed the WHOLE page ("load earlier does nothing" while the wire fetch succeeded,
 * gateway.rowsRange rows=50 ok). Page rows now demote failures to retained-raw
 * placeholders — same tolerance as snapshot rows (INV-8); INV-14 holds (content is
 * never fabricated, unknown rows render as placeholders).
 */
class GatewayPageRowsDecodeTest {

    private fun rawRow(id: Long, kind: String, extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) =
        buildJsonObject {
            put("rowId", JsonPrimitive(id))
            put("turnId", JsonPrimitive("t-$id"))
            put("kind", JsonPrimitive(kind))
            put("createdAt", JsonPrimitive(1.0))
            put("createdAtSeq", JsonPrimitive(id))
            extra.forEach { (k, v) -> put(k, v) }
        }

    @Test
    fun `page with one undecodable row yields the rest plus a retained placeholder`() {
        val good = rawRow(1, "assistantText", mapOf("text" to JsonPrimitive("hello"), "state" to JsonPrimitive("complete"), "assistantResponseId" to JsonPrimitive("ar")))
        val bad = rawRow(2, "brandNewKindFromFuture")
        val good2 = rawRow(3, "userInput", mapOf("text" to JsonPrimitive("hi"), "origin" to JsonPrimitive("user"), "guided" to JsonPrimitive(false)))

        val rows = RelayConversationGateway.decodePageRows(listOf(good, bad, good2), "s1")

        assertEquals(3, rows.size, "a bad row must not fail the page")
        assertEquals("hello", (rows[0] as ConversationRow.AssistantText).text)
        // The bad row is tolerated, not dropped: an unknown KIND decodes natively as a
        // retained-raw Unknown (INV-8); a KNOWN kind with a broken required field is
        // demoted by the gateway to a decode-fault placeholder. Both keep the page alive.
        val placeholder = rows[1] as ConversationRow.Unknown
        assertEquals(2L, placeholder.rowId)
        assertTrue(
            placeholder.unknownKind == "brandNewKindFromFuture" || placeholder.unknownKind.startsWith("decode-fault:"),
            placeholder.unknownKind,
        )
        assertEquals("hi", (rows[2] as ConversationRow.UserInput).text)
    }

    @Test
    fun `all-good page decodes fully typed`() {
        val rows = RelayConversationGateway.decodePageRows(
            listOf(rawRow(1, "assistantText", mapOf("text" to JsonPrimitive("a"), "state" to JsonPrimitive("complete"), "assistantResponseId" to JsonPrimitive("x")))),
            "s1",
        )
        assertEquals(1, rows.size)
        assertTrue(rows[0] is ConversationRow.AssistantText)
    }
}
