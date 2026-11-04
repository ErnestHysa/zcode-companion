package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pass-21 (F-21-B arming-debt fix) mock self-test: `seedWorkspaceFiles` must build the
 * DIRECTORY listings the files.list `readdir` handler serves — the raw path-keys-only
 * seed made `readdir` fault on every directory (the §23.4 Files viewer's only rig
 * outcome was its unavailable degrade). Pins: root listing carries every top-level
 * child (dirs first-class entries), nested directories resolve, files keep their
 * self-listing for `searchWorkspaceFiles`, and no duplicate children appear.
 */
class MockZCodeFileTreeSeedingSelfTest {

    private fun entriesFor(script: MockZCode.Script, dir: String): List<JsonObject> {
        val raw = script.fileTree[dir]
        assertNotNull(raw, "fileTree[$dir] must exist after seeding")
        return (raw as JsonArray).mapNotNull { (it as? JsonObject) }
    }

    @Test
    fun `root listing carries top level children`() {
        val script = MockZCode.Script().also {
            it.seedWorkspaceFiles(listOf("src/main.rs", "src/lib.rs", "docs/plan.md", "README.md", "Cargo.toml"))
        }
        val root = entriesFor(script, "")
        val names = root.map { (it["name"] as JsonPrimitive).content }.sorted()
        assertEquals(listOf("Cargo.toml", "README.md", "docs", "src"), names)
        assertTrue(root.all { !(it["isSymbolicLink"] as JsonPrimitive).content.toBoolean() })
    }

    @Test
    fun `nested directories resolve with their children`() {
        val script = MockZCode.Script().also {
            it.seedWorkspaceFiles(listOf("src/main.rs", "src/lib.rs", "docs/plan.md"))
        }
        val src = entriesFor(script, "src").map { (it["name"] as JsonPrimitive).content }.sorted()
        assertEquals(listOf("lib.rs", "main.rs"), src)
        val docs = entriesFor(script, "docs").map { (it["type"] as JsonPrimitive).content }
        assertEquals(listOf("file"), docs)
    }

    @Test
    fun `directory entries typed and file self-listings kept for search`() {
        val script = MockZCode.Script().also {
            it.seedWorkspaceFiles(listOf("src/main.rs", "README.md"))
        }
        val root = entriesFor(script, "")
        val srcEntry = root.first { (it["name"] as JsonPrimitive).content == "src" }
        assertEquals("directory", (srcEntry["type"] as JsonPrimitive).content)
        assertEquals("src", (srcEntry["path"] as JsonPrimitive).content)
        // Files keep their own (empty) key so searchWorkspaceFiles still filters paths.
        assertTrue(script.fileTree.containsKey("src/main.rs"))
        assertTrue(script.fileTree.containsKey("README.md"))
    }

    @Test
    fun `no duplicate children across mixed depth seeds`() {
        val script = MockZCode.Script().also {
            it.seedWorkspaceFiles(listOf("src/a/main.rs", "src/a/lib.rs", "src/b/util.rs", "src/main.rs"))
        }
        val src = entriesFor(script, "src")
        val names = src.map { (it["name"] as JsonPrimitive).content }
        assertEquals(names.size, names.distinct().size, "duplicate direct children in src listing")
        val a = entriesFor(script, "src/a").map { (it["name"] as JsonPrimitive).content }.sorted()
        assertEquals(listOf("lib.rs", "main.rs"), a)
    }
}
