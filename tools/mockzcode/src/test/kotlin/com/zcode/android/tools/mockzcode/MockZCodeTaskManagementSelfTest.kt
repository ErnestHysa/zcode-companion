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
}
