package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * p42 (pass-42) mock self-tests, two planes:
 *
 * 1. The tasks SEARCH hit shape (debt #27): the desktop's `rowToTaskListItem` attaches
 *    `searchSnippets` (matched-context strings, `searchSnippet` = the first) to every
 *    SEARCH hit and the phone's tasks-search pane renders them under the title — the
 *    mock's listTaskList served title-filtered rows with NO snippet fields, so the
 *    snippet row could never render. The plain (no-search) list shape must NOT change
 *    (pass-38/39 receipts decoded it).
 * 2. The MANY-CHANGES git fixture (MOCK_GIT_MANY): 50 unstaged nested rows + 2 staged —
 *    crossing GitScreen's J1.2 gates (SEARCH_GATE=10, TREE_GATE=40) so the per-section
 *    search field and the collapsible directory tree are drivable on the rig; the
 *    served-section derivation (p41) must agree with the seeded write tier.
 *
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass42SearchTreeSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 11 })
        return MockZCode(passHash = passHash, expectedSid = "d_p42s")
    }

    private fun lastPayload(m: MockZCode): JsonObject? =
        m.promiseResponsesForTest().lastOrNull()
            ?.let { (it.third as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json as? JsonObject }

    private fun itemsOf(m: MockZCode): List<JsonObject> {
        val payload = lastPayload(m)
        val items = payload?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { it as? JsonObject }
    }

    @Test
    fun `search hits carry searchSnippets + searchSnippet (desktop rowToTaskListItem shape)`() {
        val m = mock()
        m.script.directTasks = listOf(
            buildJsonObject {
                put("taskId", JsonPrimitive("t1")); put("title", JsonPrimitive("Synthetic One")); put("workspacePath", JsonPrimitive("/synthetic/ws-one"))
            },
            buildJsonObject {
                put("taskId", JsonPrimitive("t2")); put("title", JsonPrimitive("Unrelated row")); put("workspacePath", JsonPrimitive("/synthetic/ws-one"))
            },
        )
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject { put("search", JsonPrimitive("syn")) })
        val items = itemsOf(m)
        assertEquals(1, items.size, "the title filter must keep only the matching row")
        val snippets = items.single()["searchSnippets"] as? JsonArray
        assertTrue(snippets != null && snippets.isNotEmpty(), "search hits must carry searchSnippets (the phone renders them under the title)")
        val snippet = (snippets!!.single() as JsonPrimitive).content
        assertTrue(snippet.lowercase().contains("syn"), "the snippet is the matched-context window — it must contain the query")
        assertEquals(snippet, (items.single()["searchSnippet"] as? JsonPrimitive)?.content,
            "searchSnippet is the first snippet (desktop-faithful)")
    }

    @Test
    fun `the plain list (no search) serves NO snippet fields (pass-38-39 shape unchanged)`() {
        val m = mock()
        m.script.directTasks = listOf(buildJsonObject {
            put("taskId", JsonPrimitive("t1")); put("title", JsonPrimitive("Synthetic One")); put("workspacePath", JsonPrimitive("/ws"))
        })
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject { })
        val items = itemsOf(m)
        assertEquals(1, items.size)
        assertTrue(items.single()["searchSnippets"] == null, "the plain task list must stay snippet-free")
        assertTrue(items.single()["searchSnippet"] == null)
    }

    @Test
    fun `a no-match search filters to zero rows (the phone's empty face input)`() {
        val m = mock()
        m.script.directTasks = listOf(buildJsonObject {
            put("taskId", JsonPrimitive("t1")); put("title", JsonPrimitive("Synthetic One")); put("workspacePath", JsonPrimitive("/ws"))
        })
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject { put("search", JsonPrimitive("zzz")) })
        val payload = lastPayload(m)
        assertEquals(0, ((payload?.get("total") as? JsonPrimitive)?.content?.toIntOrNull() ?: -1))
        assertTrue(itemsOf(m).isEmpty())
    }

    @Test
    fun `manyChanges crosses BOTH J12 gates on the unstaged section (10 and 40)`() {
        val fixture = RigGitFixtures.manyChanges()
        val rows = fixture.mapNotNull { it as? JsonObject }
        val unstaged = rows.filter { (it["section"] as? JsonPrimitive)?.content == "unstaged" }
        val staged = rows.filter { (it["section"] as? JsonPrimitive)?.content == "staged" }
        assertTrue(unstaged.size > 40, "unstaged (${unstaged.size}) must exceed TREE_GATE=40 for the collapsible tree")
        assertTrue(unstaged.size > 10, "unstaged must exceed SEARCH_GATE=10 for the per-section search field")
        assertTrue(staged.size >= 2, "the staged tier needs rows for the seedWriteTier handoff")
        assertTrue(unstaged.all { (it["kind"] as? JsonPrimitive)?.content == "modified" },
            "the p41 served-section rule re-sections kind=added rows to untracked — the many fixture must stay modified to keep one section above the gates")
        val nested = unstaged.mapNotNull { (it["workspaceRelativePath"] as? JsonPrimitive)?.content }
            .filter { it.contains('/') && !it.startsWith("rootfile") }
        assertTrue(nested.size >= 30, "the tree grouping needs real directory depth (got ${nested.size} nested rows)")
        assertTrue(unstaged.any { !(it["workspaceRelativePath"] as? JsonPrimitive)!!.content.contains('/') },
            "root-level files must exist — the synthetic root's files bucket")
    }

    @Test
    fun `the MOCK_GIT_MANY arming shape serves the staged tier from the seeded write tier`() {
        val m = mock()
        // The launcher arming (MockRelayMain, MOCK_GIT + MOCK_GIT_MANY): the MANY fixture
        // replaces gitChanges and re-seeds the write tier from THAT fixture.
        m.script.gitChanges = RigGitFixtures.manyChanges()
        RigGitFixtures.seedWriteTier(m.script, RigGitFixtures.manyChanges())
        val served = m.servedGitChangesForTest().mapNotNull { it as? JsonObject }
        val stagedServed = served.filter { (it["section"] as? JsonPrimitive)?.content == "staged" }
        val unstagedServed = served.filter { (it["section"] as? JsonPrimitive)?.content == "unstaged" }
        assertEquals(2, stagedServed.size, "the seeded staged rows must serve staged")
        assertEquals(44, unstagedServed.size, "all unstaged rows must keep their working-tree section")
    }

    @Test
    fun `the getDiff fixture carries the decoder contract (path + availability patch + patch)`() {
        val d = RigGitFixtures.diff()
        assertEquals("src/version.ts", (d["path"] as JsonPrimitive).content)
        assertEquals("patch", (d["availability"] as JsonPrimitive).content,
            "availability is the decoder's discriminator — the pass-5-era diff-only literal decoded to 'unavailable'")
        assertTrue(((d["patch"] as JsonPrimitive).content).startsWith("diff --git"),
            "the unified patch must ride the patch key (Phase9Tools.gitDiff reads it, never diff)")
    }

    @Test
    fun `listTaskList wire log fires on search (the relay-log receipt surface)`() {
        val m = mock()
        m.script.directTasks = listOf(buildJsonObject {
            put("taskId", JsonPrimitive("t1")); put("title", JsonPrimitive("Synthetic One")); put("workspacePath", JsonPrimitive("/ws"))
        })
        // The println receipt is observed on the relay stdout live; here we pin that a
        // searched call ANSWERS (the log path executes without throwing).
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject { put("search", JsonPrimitive("one")) })
        val last = m.promiseResponsesForTest().lastOrNull()
        assertTrue(last != null, "the searched listTaskList must answer")
    }
}
