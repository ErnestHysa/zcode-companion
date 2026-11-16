package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * p41 (pass-41) mock self-tests: a FIXTURE-STAGED row must be unstagable. Until the
 * arming seeded [MockScript.stagedPaths] from the fixture's staged rows (and the write
 * tier canonicalized the phone's relative spellings), `unstagePaths` removed from an
 * empty set while `servedGitChanges()` kept serving the row's STATIC fixture section —
 * the live receipt: unstagePaths ok → the refetch served index.ts still under
 * Staged (1). Synthetic content only.
 */
class MockZCodePass41GitUnstageSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 13 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_p41g")
        m.script.gitChanges = RigGitFixtures.changes()
        // The MOCK_GIT arming seed (MockRelayMain): staged rows enter the write tier.
        RigGitFixtures.changes().mapNotNull { it as? JsonObject }
            .filter { (it["section"] as? JsonPrimitive)?.content == "staged" }
            .mapNotNull { (it["path"] as? JsonPrimitive)?.content }
            .forEach { m.script.stagedPaths.add(it) }
        return m
    }

    private fun sectionOf(served: kotlinx.serialization.json.JsonArray, relPath: String): String? =
        served.mapNotNull { it as? JsonObject }
            .filter { (it["workspaceRelativePath"] as? JsonPrimitive)?.content == relPath }
            .singleOrNull()
            ?.let { (it["section"] as? JsonPrimitive)?.content }

    @Test
    fun `fixture-staged rows are seeded into the write tier (index serves staged)`() {
        val m = mock()
        assertEquals("staged", sectionOf(m.servedGitChangesForTest(), "src/index.ts"),
            "the fixture's pre-staged row must serve staged BEFORE any write")
    }

    @Test
    fun `unstagePaths with the phone's relative spelling demotes the fixture-staged row`() {
        val m = mock()
        m.gitPromiseForTest(1, "unstagePaths", kotlinx.serialization.json.buildJsonObject {
            put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("src/index.ts"))))
        })
        assertEquals("unstaged", sectionOf(m.servedGitChangesForTest(), "src/index.ts"),
            "the unstaged fixture-staged row must LEAVE the Staged tab (kind modified → unstaged)")
        assertTrue("src/index.ts" !in m.script.stagedPaths)
    }

    @Test
    fun `stage then unstage round-trip still restores the working-tree section (pass-40 guard)`() {
        val m = mock()
        m.gitPromiseForTest(1, "stagePaths", kotlinx.serialization.json.buildJsonObject {
            put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("src/version.ts"))))
        })
        assertEquals("staged", sectionOf(m.servedGitChangesForTest(), "src/version.ts"))
        m.gitPromiseForTest(2, "unstagePaths", kotlinx.serialization.json.buildJsonObject {
            put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("src/version.ts"))))
        })
        assertEquals("unstaged", sectionOf(m.servedGitChangesForTest(), "src/version.ts"))
    }

    @Test
    fun `untracked fixture row survives the section re-derivation`() {
        val m = mock()
        assertEquals("untracked", sectionOf(m.servedGitChangesForTest(), "scratch/probe.txt"),
            "kind added → untracked must survive the p41 re-derivation")
    }
}
