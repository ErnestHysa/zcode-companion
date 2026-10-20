package com.zcode.android.domain

import com.zcode.android.domain.model.GroupedTaskNode
import com.zcode.android.domain.model.TaskGroupColor
import com.zcode.android.domain.model.TaskGroupInfo
import com.zcode.android.domain.model.TaskMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3.8–A3.10 (Phase 5A) decode pins over the bundle-extracted shapes: the group
 * color closed set (unknown → GRAY like the desktop's rowToTaskGroup), the grouped
 * view nodes (group + single task; unknown node types dropped), and the task META
 * item of the pinned/archived lists (unreadAt/pinned/archived read as wire facts).
 */
class TaskManagementDecodeTest {

    @Test
    fun `group color is the desktop's closed set with GRAY as the tolerant default`() {
        assertEquals(TaskGroupColor.PURPLE, TaskGroupColor.fromWire("purple"))
        assertEquals(TaskGroupColor.GRAY, TaskGroupColor.fromWire("magenta"))
        assertEquals(TaskGroupColor.GRAY, TaskGroupColor.fromWire(null))
        assertEquals(
            listOf("gray", "red", "orange", "yellow", "green", "blue", "purple"),
            TaskGroupColor.PALETTE.map { it.wire },
        )
    }

    @Test
    fun `group info decodes rowToTaskGroup and defaults a blank title to New Group`() {
        val g = TaskGroupInfo.decode(Json.parseToJsonElement("""{"id":"task-group-1","title":"  ","color":"blue","createdAt":10,"updatedAt":20}""").jsonObject)!!
        assertEquals("task-group-1", g.id)
        assertEquals("New Group", g.title)
        assertEquals(TaskGroupColor.BLUE, g.color)
        assertEquals(10, g.createdAtMs)
        assertNull(TaskGroupInfo.decode(Json.parseToJsonElement("""{"title":"no id"}""").jsonObject))
    }

    @Test
    fun `grouped view nodes decode groups with member ids and singles and drop unknown types`() {
        val nodes = GroupedTaskNode.decodeAll(
            Json.parseToJsonElement(
                """{"nodes":[
                  {"type":"group","group":{"id":"g1","title":"Backend","color":"green"},"tasks":[{"taskId":"t1"},{"taskId":"t2"}]},
                  {"type":"task","task":{"taskId":"t3","title":"loose"}},
                  {"type":"cron","cron":{}}
                ]}""",
            ).jsonObject,
        )
        assertEquals(2, nodes.size)
        val group = assertIs<GroupedTaskNode.Group>(nodes[0])
        assertEquals("Backend", group.group.title)
        assertEquals(listOf("t1", "t2"), group.taskIds)
        assertEquals("t3", assertIs<GroupedTaskNode.Single>(nodes[1]).taskId)
    }

    @Test
    fun `task meta decodes unread pinned archived as wire facts`() {
        val metas = TaskMeta.decodeAll(
            Json.parseToJsonElement(
                """[{"taskId":"t1","title":"Fix auth","displayStatus":"completed","archived":true,"pinned":false,"unreadAt":1700000000000,
                     "createdAt":1,"updatedAt":2,"workspacePath":"/ws","workspaceIdentity":"ws-id","workspaceLabel":"demo"},
                    {"title":"no id"},
                    {"taskId":"t2","archivedAt":5}]""",
            ) as JsonArray,
        )
        assertEquals(2, metas.size)
        val m = metas[0]
        assertTrue(m.archived)
        assertEquals(1700000000000, m.unreadAtMs)
        assertEquals("completed", m.statusWire)
        assertEquals("demo", m.workspaceLabel)
        assertTrue(metas[1].archived, "archivedAt alone marks archived")
        assertEquals("t2", metas[1].title, "a missing title falls back to the id")
    }
}
