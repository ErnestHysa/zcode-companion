package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pass-34 rig self-tests — the toolCall row's PER-TOOL-KIND render-matrix arm
 * (Part AK's fresh recon, one level below pass-33's row-kind axis):
 *
 * MOCK_ROW_TOOLS=1 seeds into the snapshot:
 *
 * 1. The CHANGES group precondition — Edit + Write, CONSECUTIVE + SETTLED + path
 *    inputs + durations (startedAt/endedAt) → "Changes · 2 files · 4.3s".
 * 2. The EXECUTE group precondition — Bash + BashOutput (the taxonomy-hole name:
 *    unknown to ToolKindTaxonomy → NO kind subtitle; the substring fallback
 *    buckets it EXECUTE so it GROUPS with Bash).
 * 3. The ERROR card — error{code,message} + actions.canRetry=true (+entityId from
 *    schemaRow) → the G66 recovery card renders with the Retry action.
 * 4. Two >2k-preview Grep rows — the "Show full output" button precondition; one
 *    slice SERVED via toolCallSlices (tool-rig-grepbig), one deliberately absent
 *    (the honest fault.mock.unscripted fallback face).
 * 5. The single-card status faces — pendingApproval (approvalInteractionId set),
 *    cancelled, running, and the empty-string status (decodes to UNKNOWN).
 *
 * Interleaving guarantees no accidental group forms between the singles. The
 * `getTaskSnapshotToolCallsSlice` handler itself is wire-proven live (the receipt);
 * here the seed state is pinned. Default (unarmed) serves none of them.
 */
class MockZCodePass34RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 33 }), expectedSid = "d_p34")
        m.activeSessionForTest("sess-p34")
        block?.let { m.script.it() }
        return m
    }

    private fun toolRows(rows: JsonArray): List<JsonObject> =
        rows.filter { ((it as JsonObject)["kind"] as JsonPrimitive).content == "toolCall" }
            .map { it.jsonObject }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: ""

    // ---- the arm ----

    @Test
    fun `unarmed default serves no tool rows`() {
        val m = mock()
        val rows = toolRows(m.snapshotRowsForTest("sess-p34", 6))
        assertTrue(rows.isEmpty(), "toolCall rows ride the arm only")
        assertFalse(m.script.rowToolsDemo)
    }

    @Test
    fun `armed arm serves the eleven-row census`() {
        val m = mock { rowToolsDemo = true }
        val rows = toolRows(m.snapshotRowsForTest("sess-p34", 6))
        assertEquals(11, rows.size, "2 CHANGES + 2 EXECUTE + grepbig + error + grepfallback + pending + cancelled + running + unknown-status")
        assertEquals(
            listOf("Edit", "Write", "Bash", "BashOutput", "Grep", "TaskUpdate", "Grep", "Bash", "WebSearch", "Glob", "CronList"),
            rows.map { it.str("toolName") },
            "the seeded order IS the grouping plan (groups first, singles interleaved so none bridge)",
        )
    }

    @Test
    fun `changes group precondition - consecutive settled rows with path inputs and durations`() {
        val m = mock { rowToolsDemo = true }
        val rows = toolRows(m.snapshotRowsForTest("sess-p34", 6))
        val edit = rows[0]
        val write = rows[1]
        assertEquals("success", edit.str("status"))
        assertEquals("success", write.str("status"))
        // The ToolGroupUi.of file counter reads path/file_path from the INPUT object.
        assertNotNull(edit["input"], "the file-count face needs a decoded input object")
        assertEquals("src/version.ts", (((edit["input"] as JsonObject)["path"]) as JsonPrimitive).content)
        assertEquals("README.md", (((write["input"] as JsonObject)["file_path"]) as JsonPrimitive).content)
        // Durations: startedAt/endedAt epoch-ms doubles, ended > started.
        for (r in listOf(edit, write)) {
            val s = (r["startedAt"] as JsonPrimitive).content.toDouble()
            val e = (r["endedAt"] as JsonPrimitive).content.toDouble()
            assertTrue(e > s, "duration face needs endedAt > startedAt")
        }
    }

    @Test
    fun `execute group carries the taxonomy-hole name`() {
        val m = mock { rowToolsDemo = true }
        val rows = toolRows(m.snapshotRowsForTest("sess-p34", 6))
        assertEquals("BashOutput", rows[3].str("toolName"))
        assertEquals("success", rows[3].str("status"))
        // The hole: BashOutput is absent from ToolKindTaxonomy.BY_NAME (the app-side
        // 31-name map — spot-checked statically at HEAD), so the card gets NO kind
        // subtitle and the substring fallback ("bash") buckets it EXECUTE.
    }

    @Test
    fun `error card carries code message and the retry capability`() {
        val m = mock { rowToolsDemo = true }
        val rows = toolRows(m.snapshotRowsForTest("sess-p34", 6))
        val err = rows[5]
        assertEquals("TaskUpdate", err.str("toolName"))
        assertEquals("error", err.str("status"))
        val e = err["error"] as JsonObject
        assertEquals("task_write_denied", (e["code"] as JsonPrimitive).content)
        assertTrue(((e["message"] as JsonPrimitive).content).isNotEmpty())
        // G66's Retry gate: actions.canRetry == true && entityId != null (schemaRow always sets one).
        assertEquals("true", (((err["actions"] as JsonObject)["canRetry"]) as JsonPrimitive).content)
        assertTrue(err.str("entityId").startsWith("ent-"))
    }

    @Test
    fun `served slice is seeded only for the big row`() {
        val m = mock { rowToolsDemo = true }
        val rows = toolRows(m.snapshotRowsForTest("sess-p34", 6))
        val big = rows[4]
        val fallback = rows[6]
        assertEquals("tool-rig-grepbig", big.str("toolCallId"))
        assertEquals("tool-rig-grepfallback", fallback.str("toolCallId"))
        // The "Show full output" button renders only when the preview exceeds the 2000-char cap.
        val bigText = (((big["outputPreview"] as JsonObject)["text"]) as JsonPrimitive).content
        val fbText = (((fallback["outputPreview"] as JsonObject)["text"]) as JsonPrimitive).content
        assertTrue(bigText.length > 2_000, "the button precondition: preview > TOOL_PREVIEW_CAP")
        assertTrue(fbText.length > 2_000)
        assertTrue(m.script.toolCallSlices.containsKey("tool-rig-grepbig"), "the SERVED slice body")
        assertFalse(m.script.toolCallSlices.containsKey("tool-rig-grepfallback"), "the fallback face needs its slice ABSENT")
        assertTrue(m.script.toolCallSlices["tool-rig-grepbig"]!!.length > bigText.length, "the served body must differ from the capped preview")
    }

    @Test
    fun `single-card status faces are seeded schema-faithful`() {
        val m = mock { rowToolsDemo = true }
        val rows = toolRows(m.snapshotRowsForTest("sess-p34", 6))
        val pending = rows[7]
        assertEquals("pendingApproval", pending.str("status"))
        assertEquals("inter-rig-tools-1", pending.str("approvalInteractionId"))
        assertEquals("cancelled", rows[8].str("status"))
        assertEquals("running", rows[9].str("status"))
        assertEquals("", rows[10].str("status"), "the empty string decodes to ToolCallStatus.UNKNOWN")
    }
}
