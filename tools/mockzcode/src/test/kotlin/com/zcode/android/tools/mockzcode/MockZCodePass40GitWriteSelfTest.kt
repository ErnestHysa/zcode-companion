package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * p40 (pass-40) mock self-tests: the ADR-021 write tier's acks must be VISIBLE to the
 * phone's next getChanges — until this, stage/unstage/discard mutated script lists the
 * served fixture never read (the recorded ack-without-mutate class): stage never moved
 * a row to the Staged tab, discard never removed it, commit left all three rows served.
 * The phone sends workspaceRelativePath keys ("src/version.ts"); the fixture rows key
 * `path` absolutely — the derivation matches all spellings. Synthetic content only.
 */
class MockZCodePass40GitWriteSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 11 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_p40")
        m.script.gitChanges = RigGitFixtures.changes()
        return m
    }

    private fun rel(obj: kotlinx.serialization.json.JsonObject): String? =
        (obj["workspaceRelativePath"] as? JsonPrimitive)?.content

    private fun sectionOf(served: kotlinx.serialization.json.JsonArray, relPath: String): String? =
        served.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .filter { rel(it) == relPath }
            .singleOrNull()
            ?.let { (it["section"] as? JsonPrimitive)?.content }

    @Test
    fun `stage flips the served section to staged (the row MOVES tabs)`() {
        val m = mock()
        m.gitPromiseForTest(1, "stagePaths", buildJsonObject {
            put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("src/version.ts"))))
        })
        assertEquals("staged", sectionOf(m.servedGitChangesForTest(), "src/version.ts"),
            "the staged ack must MOVE the row to the staged tab on the next fetch")
        assertTrue("src/version.ts" in m.script.stagedPaths)
    }

    @Test
    fun `unstage restores the fixture section`() {
        val m = mock()
        m.gitPromiseForTest(1, "stagePaths", buildJsonObject {
            put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("src/version.ts"))))
        })
        m.gitPromiseForTest(2, "unstagePaths", buildJsonObject {
            put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("src/version.ts"))))
        })
        assertEquals("unstaged", sectionOf(m.servedGitChangesForTest(), "src/version.ts"),
            "unstage must return the row to its fixture section")
    }

    @Test
    fun `discard drops the row from the served view`() {
        val m = mock()
        m.gitPromiseForTest(1, "discardPaths", buildJsonObject {
            put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("scratch/probe.txt"))))
            put("staged", JsonPrimitive(false))
        })
        val served = m.servedGitChangesForTest().mapNotNull { (it as? kotlinx.serialization.json.JsonObject) }
        assertTrue(served.none { rel(it) == "scratch/probe.txt" },
            "a discarded path must leave the changes list")
        assertEquals(2, served.size)
    }

    @Test
    fun `commit stagedOnly consumes the staged view only`() {
        val m = mock()
        m.gitPromiseForTest(1, "stagePaths", buildJsonObject {
            put("paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("src/version.ts"))))
        })
        m.gitPromiseForTest(2, "commit", buildJsonObject {
            put("message", JsonPrimitive("chore(rig): p40 staged commit"))
            put("stagedOnly", JsonPrimitive(true))
        })
        val served = m.servedGitChangesForTest().mapNotNull { (it as? kotlinx.serialization.json.JsonObject) }
        assertTrue(served.none { rel(it) == "src/version.ts" }, "the staged row commits away")
        assertTrue(served.none { rel(it) == "src/index.ts" }, "the fixture-staged row commits away")
        assertTrue(served.any { rel(it) == "scratch/probe.txt" }, "untracked work stays uncommitted")
    }

    @Test
    fun `commit includeUnstaged consumes everything still served`() {
        val m = mock()
        m.gitPromiseForTest(1, "commit", buildJsonObject {
            put("message", JsonPrimitive("chore(rig): p40 sweep commit"))
            put("stagedOnly", JsonPrimitive(false))
        })
        assertTrue(m.servedGitChangesForTest().isEmpty(), "includeUnstaged clears the list")
    }

    @Test
    fun `a blank-message commit mutates nothing (the desktop's refusal)`() {
        val m = mock()
        m.gitPromiseForTest(1, "commit", buildJsonObject {
            put("message", JsonPrimitive("  "))
            put("stagedOnly", JsonPrimitive(false))
        })
        assertTrue(m.script.committedPaths.isEmpty(), "the refused commit must not consume rows")
        assertEquals(3, m.servedGitChangesForTest().size)
    }
}
