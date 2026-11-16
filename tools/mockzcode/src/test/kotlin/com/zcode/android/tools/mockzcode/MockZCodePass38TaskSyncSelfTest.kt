package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass-38 (p38-mock-decorate): the CONTROL (bootstrap) task view and the CHANNEL metas
 * must read the SAME live pin/unread/rename/delete state. The phone's post-mutation
 * refresh re-applies the control view, so the stale raw-MockTask view visually reverted
 * every ok'd mutation (live receipt: mark-read ok → "Marked read" → the unread dot and
 * the menu label still claimed unread; DB row untouched). Synthetic content only
 * (INV-15/16).
 */
class MockZCodePass38TaskSyncSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        return MockZCode(passHash = passHash, expectedSid = "d_p38")
    }

    private fun okReplied(m: MockZCode, repliesBefore: Int): Boolean {
        val replies = m.promiseResponsesForTest().drop(repliesBefore)
        val last = replies.lastOrNull() ?: return false
        return last.second == com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseSuccess.id
    }

    @Test
    fun `control view reflects the live pin and unread sets`() {
        val m = mock()
        armTaskManagementRig(m)
        // Seed state: task-1 pinned, task-2 unread — both visible on the control view.
        val v0 = m.bootstrapTasksView()
        assertTrue((v0[0].jsonObject["pinned"] as JsonPrimitive).content.toBoolean())
        assertTrue(v0[1].jsonObject["unreadAt"] !is JsonNull)
        // The channel handlers mutate ONLY the sets — the control view must follow.
        m.script.unreadTaskIds.remove("synth-task-2")
        m.script.pinnedTaskIds.remove("synth-task-1")
        val v1 = m.bootstrapTasksView()
        assertEquals(JsonNull, v1[1].jsonObject["unreadAt"])
        assertEquals("false", (v1[0].jsonObject["pinned"] as JsonPrimitive).content)
    }

    @Test
    fun `renameTask syncs both the channel list and the control view`() {
        val m = mock()
        armTaskManagementRig(m)
        val before = m.promiseResponsesForTest().size
        m.taskPromiseForTest(1, "renameTask", JsonObject(mapOf("taskId" to JsonPrimitive("synth-task-3"), "title" to JsonPrimitive("Renamed p38"))))
        assertTrue(okReplied(m, before))
        assertTrue(m.script.directTasks.any { (it.jsonObject["title"] as? JsonPrimitive)?.content == "Renamed p38" })
        assertTrue(m.bootstrapTasksView().any { (it.jsonObject["title"] as? JsonPrimitive)?.content == "Renamed p38" })
    }

    @Test
    fun `deleteTask removes the row from both surfaces`() {
        val m = mock()
        armTaskManagementRig(m)
        val before = m.promiseResponsesForTest().size
        m.taskPromiseForTest(1, "deleteTask", JsonObject(mapOf("taskId" to JsonPrimitive("synth-task-3"))))
        assertTrue(okReplied(m, before))
        assertTrue(m.script.directTasks.none { (it.jsonObject["taskId"] as? JsonPrimitive)?.content == "synth-task-3" })
        assertTrue(m.bootstrapTasksView().none { (it.jsonObject["taskId"] as? JsonPrimitive)?.content == "synth-task-3" })
    }

    @Test
    fun `setTaskPinned round-trip shows on a refreshed control view`() {
        val m = mock()
        armTaskManagementRig(m)
        val before = m.promiseResponsesForTest().size
        // unpin task-1 (the seeded pinned row), pin task-3 — both flags move on the view.
        m.taskPromiseForTest(1, "setTaskPinned", JsonObject(mapOf("taskId" to JsonPrimitive("synth-task-1"), "pinned" to JsonPrimitive(false))))
        m.taskPromiseForTest(2, "setTaskPinned", JsonObject(mapOf("taskId" to JsonPrimitive("synth-task-3"), "pinned" to JsonPrimitive(true))))
        assertTrue(okReplied(m, before))
        val view = m.bootstrapTasksView()
        assertEquals("false", (view[0].jsonObject["pinned"] as JsonPrimitive).content)
        assertEquals("true", (view[2].jsonObject["pinned"] as JsonPrimitive).content)
    }

    @Test
    fun `control view excludes archived rows - the refresh cannot resurrect the archive`() {
        val m = mock()
        armTaskManagementRig(m)
        assertEquals(3, m.bootstrapTasksView().size)
        val before = m.promiseResponsesForTest().size
        m.taskPromiseForTest(9, "archiveTask", JsonObject(mapOf("taskId" to JsonPrimitive("synth-task-3"))))
        assertTrue(okReplied(m, before))
        val view = m.bootstrapTasksView()
        assertEquals(2, view.size)
        assertTrue(view.none { (it.jsonObject["taskId"] as? JsonPrimitive)?.content == "synth-task-3" })
        // unarchive restores the row to the view
        m.taskPromiseForTest(10, "unarchiveTask", JsonObject(mapOf("taskId" to JsonPrimitive("synth-task-3"))))
        assertEquals(3, m.bootstrapTasksView().size)
    }

    @Test
    fun `deleteArchivedTask removes the row from the control view - no resurrection`() {
        val m = mock()
        armTaskManagementRig(m)
        m.taskPromiseForTest(11, "archiveTask", JsonObject(mapOf("taskId" to JsonPrimitive("synth-task-3"))))
        val before = m.promiseResponsesForTest().size
        m.taskPromiseForTest(12, "deleteArchivedTask", JsonObject(mapOf("taskId" to JsonPrimitive("synth-task-3"))))
        assertTrue(okReplied(m, before))
        assertTrue(m.bootstrapTasksView().none { (it.jsonObject["taskId"] as? JsonPrimitive)?.content == "synth-task-3" })
        assertTrue(m.script.directTasks.none { (it.jsonObject["taskId"] as? JsonPrimitive)?.content == "synth-task-3" })
    }
}
