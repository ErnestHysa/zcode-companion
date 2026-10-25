package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5A (A3.8–A3.10) mock self-tests: the task-management state the rig leg relies on
 * is a real round-trip — pinned/unread/archived flags decorate the metas the list words
 * return, groups persist title/color + membership, and the off-peak paused set is
 * reflected in the next `list`. Synthetic content only (INV-15/16).
 */
class MockZCodeTaskManagementSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        return MockZCode(passHash = passHash, expectedSid = "d_mgmt")
    }

    @Test
    fun `script state defaults are empty and mutable`() {
        val m = mock()
        assertTrue(m.script.pinnedTaskIds.isEmpty())
        assertTrue(m.script.taskGroups.isEmpty())
        assertTrue(m.script.offPeakPausedIds.isEmpty())
        m.script.pinnedTaskIds.add("t1")
        m.script.archivedTaskIds.add("t2")
        m.script.unreadTaskIds["t1"] = 5L
        m.script.offPeakPausedIds.add("op-1")
        assertEquals(setOf("t1"), m.script.pinnedTaskIds)
        assertEquals(5L, m.script.unreadTaskIds["t1"])
    }

    @Test
    fun `directTasks rows carry the fields the metas need`() {
        val m = mock()
        m.script.directTasks = listOf(buildJsonObject {
            put("taskId", JsonPrimitive("t1")); put("title", JsonPrimitive("One")); put("workspacePath", JsonPrimitive("/ws"))
        })
        assertEquals("t1", (m.script.directTasks.single()["taskId"] as JsonPrimitive).content)
    }

    @Test
    fun `the relay arming populates directTasks so the pinned-unread-group legs render (T1)`() {
        val m = mock()
        com.zcode.android.tools.mockzcode.armTaskManagementRig(m)
        // T1: every management word (listPinnedTasks / listArchivedTasks / the grouped
        // view) decorates rows FILTERED FROM script.directTasks — ids/groups alone armed
        // nothing and the A3.8–A3.10 surfaces never rendered on the rig.
        val ids = m.script.directTasks.mapNotNull { (it["taskId"] as? JsonPrimitive)?.content }
        assertEquals(listOf("synth-task-1", "synth-task-2", "synth-task-3"), ids)
        // Task-1 mirrors the bootstrap row's identity keys so the listTasks upsert
        // refreshes the projection row in place (no duplicate).
        val first = m.script.directTasks.first()
        assertEquals("Synthetic task", (first["title"] as JsonPrimitive).content)
        assertEquals("idle", (first["displayStatus"] as JsonPrimitive).content)
        assertEquals("/synthetic/ws-one", (first["workspacePath"] as JsonPrimitive).content)
        assertEquals("Synthetic One", (first["workspaceLabel"] as JsonPrimitive).content)
        // And the flags the decorations read stay armed against rows that exist.
        assertTrue("synth-task-1" in m.script.pinnedTaskIds)
        assertTrue(m.script.unreadTaskIds.containsKey("synth-task-2"))
        assertEquals("synth-task-3", m.script.taskGroupMembers["task-group-rig"]?.single())
        // A3.8: the pinned/unread flags must ALSO ride the BOOTSTRAP rows — the main
        // list's flags come from the sessions bootstrap (BootstrapParser), not from
        // the management words.
        val boot = m.script.bootstrapTasks.orEmpty()
        assertEquals(listOf("synth-task-1", "synth-task-2", "synth-task-3"), boot.map { it.taskId })
        assertTrue(boot.first().pinned)
        assertTrue((boot[1].unreadAtMs ?: 0L) > 0L)
        assertTrue(!boot[2].pinned && boot[2].unreadAtMs == null)
        assertTrue((boot.first().toJson()["pinned"] as JsonPrimitive).content.toBoolean())
        assertTrue(boot[1].toJson().containsKey("unreadAt"))
        assertTrue(!boot[2].toJson().containsKey("pinned"))
    }
}
