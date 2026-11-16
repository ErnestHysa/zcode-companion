package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass-37 rig self-tests — the assistant-row ACTION RAIL axis (Part AN), the plane
 * with ZERO live receipts in the pass0 series before this pass. Two mock changes ride:
 *
 * 1. `MOCK_FEEDBACK_FAIL=1` — setAssistantFeedback answers the host's failed ack
 *    {status:"failed", reasonCode:"fault.feedback.fail"} (the F-P19-A failed-ack
 *    shape), so the phone's keyed FEEDBACK_REFUSED notice drives. Dispatcher-level,
 *    like the pass-20 stale arm.
 * 2. The feedback echo's generated-row fallback — the echo previously re-synthesized
 *    a bare `schemaRow(..., "synthetic")` base for any rowId not in appendedRows, and
 *    the phone REPLACES the whole row on row.upserted — so liking a snapshot/page row
 *    would have visibly re-texted it. The fallback now consults `generatedRows`
 *    (every row schemaRow generated, putIfAbsent) so the echoed upsert preserves the
 *    row's real shape.
 */
class MockZCodePass37RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 37 }), expectedSid = "d_p37")
        m.activeSessionForTest("sess-p37")
        block?.let { m.script.it() }
        return m
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: ""

    /** The wire shape the phone sends: target{rowId, entityId} + feedback. */
    private fun feedbackEnv(rowId: Long, entityId: String, feedback: String?): JsonObject =
        buildJsonObject {
            put("type", "setAssistantFeedback")
            put("commandId", "c-p37-fb-$rowId")
            putJsonObject("payload") {
                putJsonObject("target") {
                    put("rowId", rowId.toString())
                    put("entityId", entityId)
                }
                put("feedback", feedback?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }

    // ---- the MOCK_FEEDBACK_FAIL arm ----

    @Test
    fun `feedback fail arm defaults off`() {
        val m = mock()
        assertFalse(m.script.feedbackFail, "unarmed fixtures keep the echo happy path")
        assertNull(m.feedbackFailAck("c-1"), "unarmed -> the dispatcher falls through to the effect")
    }

    @Test
    fun `feedback fail arm answers the F-P19-A failed ack shape`() {
        val m = mock()
        m.script.feedbackFail = true
        val ack = m.feedbackFailAck("c-p37-fb-401")
        assertNotNull(ack)
        assertEquals("failed", ack["status"]?.jsonPrimitive?.content)
        assertEquals("fault.feedback.fail", ack["reasonCode"]?.jsonPrimitive?.content)
        assertEquals("c-p37-fb-401", ack["commandId"]?.jsonPrimitive?.content)
        assertNotNull(ack["revisionAtDecision"], "the host's ack carries revisionAtDecision")
    }

    @Test
    fun `feedback fail ack falls back to unknown commandId`() {
        val m = mock()
        m.script.feedbackFail = true
        assertEquals("unknown", m.feedbackFailAck(null)?.get("commandId")?.jsonPrimitive?.content)
    }

    // ---- the generated-row fallback fidelity ----

    @Test
    fun `generatedRows records the FIRST generation (putIfAbsent)`() {
        val m = mock()
        val first = m.schemaRow("sess-p37", 200, MockZCode.RowKind.ASSISTANT_TEXT, "synthetic 120")
        val second = m.schemaRow("sess-p37", 200, MockZCode.RowKind.ASSISTANT_TEXT, "a LATER regen text")
        assertEquals(first.str("text"), m.generatedRowForTest(200)?.str("text"), "putIfAbsent: the row the phone received is the record")
        assertTrue(second.str("text") != m.generatedRowForTest(200)?.str("text"), "regeneration must not shift the record")
    }

    @Test
    fun `never-generated rowId has no record (the synthesized base stays the last resort)`() {
        val m = mock()
        assertNull(m.generatedRowForTest(999_999))
    }

    @Test
    fun `feedback echo on a SNAPSHOT row preserves its real shape`() {
        val m = mock { snapshotRowIdBase = 81L }
        // Generate the snapshot rows (as the subscribe would) — the armed-rig shape
        // (base 81): ids 81..86, assistants at the even ids 82, 84, 86, text
        // "synthetic $i" where i = id - base + 1.
        m.snapshotRowsForTest("sess-p37", 6)
        val snapshotText = m.generatedRowForTest(84)?.str("text") ?: ""
        assertEquals("synthetic 4", snapshotText, "fixture sanity: text = synthetic (id - base + 1)")
        m.applyCommandEffectForTest("setAssistantFeedback", feedbackEnv(84, "ent-84", "like"))
        val echoed = m.appendedRowForTest(84)
        assertNotNull(echoed, "the echo upserts the row")
        assertEquals(snapshotText, echoed.str("text"), "the echoed row keeps the REAL text (not the bare 'synthetic' base)")
        assertEquals("assistantText", echoed.str("kind"), "the echoed row keeps the REAL kind")
        assertEquals("like", echoed.str("feedback"), "the echo rides the row")
        assertEquals("like", m.lastFeedback, "the pass-4A observability seam tracks the echo")
    }

    @Test
    fun `feedback echo null clears the field`() {
        val m = mock { snapshotRowIdBase = 81L }
        m.snapshotRowsForTest("sess-p37", 6)
        m.applyCommandEffectForTest("setAssistantFeedback", feedbackEnv(84, "ent-84", "like"))
        m.applyCommandEffectForTest("setAssistantFeedback", feedbackEnv(84, "ent-84", null))
        val echoed = m.appendedRowForTest(84)
        assertNotNull(echoed)
        assertNull(echoed["feedback"], "explicit JsonNull clears (the key is REMOVED, not set to null)")
        assertNull(m.lastFeedback)
    }

    @Test
    fun `second echo mutates the authoritative appended copy (flips accumulate)`() {
        val m = mock { snapshotRowIdBase = 81L }
        m.snapshotRowsForTest("sess-p37", 6)
        m.applyCommandEffectForTest("setAssistantFeedback", feedbackEnv(84, "ent-84", "like"))
        m.applyCommandEffectForTest("setAssistantFeedback", feedbackEnv(84, "ent-84", "dislike"))
        val echoed = m.appendedRowForTest(84)
        assertEquals("dislike", echoed?.str("feedback"), "like -> dislike flip lands")
        assertEquals("synthetic 4", echoed?.str("text"), "the text survives the flip (base chained from appendedRows)")
    }

    @Test
    fun `feedback echo on a page row preserves the page scheme`() {
        val m = mock()
        // The pass-35 page rows carry the STORE-ID text scheme ("synthetic row $rid");
        // generating one through schemaRow is exactly what the range handler does.
        m.schemaRow("sess-p37", 45, MockZCode.RowKind.ASSISTANT_TEXT, "synthetic row 45")
        m.applyCommandEffectForTest("setAssistantFeedback", feedbackEnv(45, "ent-45", "like"))
        val echoed = m.appendedRowForTest(45)
        assertEquals("synthetic row 45", echoed?.str("text"), "the page scheme survives the echo")
        assertEquals("like", echoed?.str("feedback"))
    }

    @Test
    fun `client-invented rowId still synthesizes the bare base (unchanged last resort)`() {
        val m = mock()
        m.applyCommandEffectForTest("setAssistantFeedback", feedbackEnv(77_000, "ent-77000", "like"))
        val echoed = m.appendedRowForTest(77_000)
        assertNotNull(echoed, "the fallback still answers (never a dropped echo)")
        assertEquals("synthetic", echoed.str("text"), "the historical synthesized shape")
        assertEquals("like", echoed.str("feedback"))
    }
}
