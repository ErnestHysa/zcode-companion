package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass-16 mock-rig self-tests — the three debts/arms this pass fixes:
 *  - debt #2: [MockZCode.Script.seedWorkspaceFiles] also seeds default readTextFile
 *    content for EVERY seeded path (the listing-only seed made every non-README
 *    open fault fault.file.notFound on the §23.4 Files viewer);
 *  - debt #5: pending-interaction options carry HUMAN labels (the desktop sends
 *    `label: c.name`; the mock echoed the raw optionId and the phone renders the
 *    label — "allowOnce" on the rig was mock-fidelity, not app behavior);
 *  - the pass-16 stall arm (MOCK_TURN_STALL): the silent journal grows the resync
 *    opLog WITHOUT emitting (no DATA frames) — the frames>0 backlog the watchdog
 *    resync replays, plus the subscribe-anchored once-per-process arming.
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass16RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 16 }), expectedSid = "d_p16")
        block?.let { m.script.it() }
        return m
    }

    private fun jstr(el: JsonElement?, key: String): String? =
        ((el as? JsonObject)?.get(key) as? JsonPrimitive)?.content

    private fun optionsOf(entry: JsonElement?): List<JsonObject>? {
        val payload = (entry as? JsonObject)?.get("payload") as? JsonObject
        val options = payload?.get("options") as? JsonArray
        return options?.mapNotNull { it as? JsonObject }
    }

    private fun approvalEntry(m: MockZCode, id: String): JsonObject? =
        m.pendingListForTest().firstOrNull { jstr(it, "interactionId") == id } as? JsonObject

    // ---- debt #2: seeded content ----

    @Test
    fun `seedWorkspaceFiles seeds content for every path (debt #2)`() {
        val m = mock()
        m.script.seedWorkspaceFiles(listOf("src/main.rs", "src/lib.rs", "docs/plan.md", "README.md", "Cargo.toml"))
        for (p in listOf("src/main.rs", "src/lib.rs", "docs/plan.md", "README.md", "Cargo.toml")) {
            val content = m.script.fileText[p]
            assertTrue(!content.isNullOrBlank(), "seeded path $p must carry default readTextFile content")
            assertTrue(content.contains(p.substringAfterLast('/')), "content for $p should name the file")
        }
        // The stat projection (size) also sees the seeded bytes now.
        assertTrue(m.script.fileText.getValue("Cargo.toml").toByteArray().size > 0)
    }

    @Test
    fun `explicit fileText entries keep precedence over the default seed (debt #2)`() {
        val m = mock()
        m.script.fileText["README.md"] = "explicit pass-21 fixture"
        m.script.seedWorkspaceFiles(listOf("README.md", "src/lib.rs"))
        assertEquals("explicit pass-21 fixture", m.script.fileText["README.md"])
        assertTrue(!m.script.fileText.getValue("src/lib.rs").isNullOrBlank())
    }

    // ---- debt #5: human option labels ----

    @Test
    fun `pending permission options carry human labels (debt #5)`() {
        val m = mock()
        m.script.pendingPermission = MockZCode.PendingPermission("mock-approval-1", "Bash")
        val options = optionsOf(approvalEntry(m, "mock-approval-1"))
        assertEquals(3, options?.size, "the default three kernel options")
        val labels = options!!.map { jstr(it, "label") }
        assertEquals(listOf("Allow once", "Allow always", "Deny"), labels, "human labels, not the raw ids")
        val ids = options.map { jstr(it, "optionId") }
        assertEquals(listOf("allowOnce", "allowAlways", "deny"), ids, "the optionIds stay kernel ids")
        val kinds = options.map { jstr(it, "kind") }
        assertEquals(listOf("allowOnce", "allowAlways", "deny"), kinds, "kind stays semantic")
    }

    @Test
    fun `unknown option ids pass through verbatim (debt #5 fallback)`() {
        val m = mock {
            pendingPermission = MockZCode.PendingPermission(
                "mock-approval-2", "Bash", options = listOf("allowSession"),
            )
        }
        val options = optionsOf(approvalEntry(m, "mock-approval-2"))
        assertEquals("allowSession", options!!.first().let { jstr(it, "label") }, "unknown ids fall back verbatim (desktop names them too)")
    }

    // ---- the pass-16 stall arm ----

    @Test
    fun `silent journal grows the opLog without emitting (frames-over-zero backlog)`() {
        val m = mock()
        m.activeSessionForTest("sess-stall")
        val before = m.dataFramesSentForTest()
        val beforeLog = m.opLogForTest("sess-stall").size
        val rowId = m.journalStallBacklogRowForTest("sess-stall", "stall-leg backlog frame 1 — journaled, never emitted")
        assertTrue(rowId > 0)
        assertEquals(beforeLog + 1, m.opLogForTest("sess-stall").size, "the silent row IS journalled")
        assertEquals(before, m.dataFramesSentForTest(), "the silent row is NOT emitted (no DATA frame)")
        val frame = m.opLogForTest("sess-stall").last()
        assertTrue(frame.ops.any { jstr(it, "op") == "row.appended" })
    }

    @Test
    fun `stall arming is off without the arm and flips once with it`() {
        val off = mock()
        off.activeSessionForTest("sess-stall")
        off.armTurnStallOnSubscribe()
        assertTrue(!off.turnStallArmedForTest(), "no-op without script.turnStall")
        val on = mock { turnStall = true }
        on.activeSessionForTest("sess-stall")
        on.armTurnStallOnSubscribe()
        assertTrue(on.turnStallArmedForTest())
        on.armTurnStallOnSubscribe()
        assertTrue(on.turnStallArmedForTest(), "once-per-process guard holds")
    }

    @Test
    fun `stall delays are armable`() {
        val m = mock {
            turnStall = true
            turnStallLiveFrames = 4
            turnStallEveryMs = 250L
        }
        assertEquals(4, m.script.turnStallLiveFrames)
        assertEquals(250L, m.script.turnStallEveryMs)
    }
}
