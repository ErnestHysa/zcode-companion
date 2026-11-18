package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G102: the `task-groups` channel (desktop 3.18.1's createTaskGroupService shapes)
 * backed by the SAME in-memory state the legacy zcode-task group verbs used — the
 * migration's rig arm. save/ungroup/move round-trip the shared state; move
 * validates the source group (task-group.stale-source) like the real service.
 */
class MockZCodeTaskGroupsSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 11 })
        return MockZCode(passHash = passHash, expectedSid = "d_p3groups")
    }

    private fun moveArg(taskId: String, source: String?, target: String?) = RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
        put("node", buildJsonObject {
            put("type", JsonPrimitive("task"))
            put("task", buildJsonObject { put("taskId", JsonPrimitive(taskId)) })
        })
        if (source == null) put("sourceGroupId", JsonNull) else put("sourceGroupId", JsonPrimitive(source))
        if (target == null) put("groupId", JsonNull) else put("groupId", JsonPrimitive(target))
    })))

    @Test
    fun `save creates without an id and updates with one`() {
        val m = mock()
        m.taskGroupsPromiseForTest(1, "save", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
            put("title", JsonPrimitive("Backend")); put("color", JsonPrimitive("blue"))
        }))))
        assertEquals(1, m.script.taskGroups.size)
        val created = m.script.taskGroups.single()
        assertEquals("Backend", (created["title"] as JsonPrimitive).content)
        assertEquals("blue", (created["color"] as JsonPrimitive).content)
        val gid = (created["id"] as JsonPrimitive).content

        // Rename keeps the color (absent fields keep the stored value).
        m.taskGroupsPromiseForTest(2, "save", RpcValue.Arr(listOf(RpcValue.Obj(buildJsonObject {
            put("id", JsonPrimitive(gid)); put("title", JsonPrimitive("Core"))
        }))))
        val updated = m.script.taskGroups.single()
        assertEquals("Core", (updated["title"] as JsonPrimitive).content)
        assertEquals("blue", (updated["color"] as JsonPrimitive).content)
        assertEquals(gid, (updated["id"] as JsonPrimitive).content)
    }

    @Test
    fun `ungroup deletes the group and its membership`() {
        val m = mock()
        m.script.taskGroups.add(buildJsonObject { put("id", JsonPrimitive("g1")); put("title", JsonPrimitive("Backend")) })
        m.script.taskGroupMembers["g1"] = mutableListOf("t1")
        m.taskGroupsPromiseForTest(1, "ungroup", RpcValue.Arr(listOf(RpcValue.Str("g1"))))
        assertTrue(m.script.taskGroups.isEmpty())
        assertTrue(m.script.taskGroupMembers.isEmpty())
    }

    @Test
    fun `move validates the source group and relocates the task`() {
        val m = mock()
        m.script.taskGroups.add(buildJsonObject { put("id", JsonPrimitive("g1")); put("title", JsonPrimitive("A")) })
        m.script.taskGroups.add(buildJsonObject { put("id", JsonPrimitive("g2")); put("title", JsonPrimitive("B")) })
        m.script.taskGroupMembers["g1"] = mutableListOf("t1", "t2")

        // A stale source faults (the desktop's task-group.stale-source) — no mutation.
        m.taskGroupsPromiseForTest(1, "move", moveArg("t1", source = "g2", target = "g2"))
        assertEquals(listOf("t1", "t2"), m.script.taskGroupMembers["g1"].orEmpty())

        // The correct source moves the task.
        m.taskGroupsPromiseForTest(2, "move", moveArg("t1", source = "g1", target = "g2"))
        assertEquals(listOf("t2"), m.script.taskGroupMembers["g1"].orEmpty())
        assertEquals(listOf("t1"), m.script.taskGroupMembers["g2"].orEmpty())

        // Moving OUT of a group (groupId null) unassigns it.
        m.taskGroupsPromiseForTest(3, "move", moveArg("t1", source = "g2", target = null))
        assertEquals(emptyList(), m.script.taskGroupMembers["g2"].orEmpty())
    }
}
