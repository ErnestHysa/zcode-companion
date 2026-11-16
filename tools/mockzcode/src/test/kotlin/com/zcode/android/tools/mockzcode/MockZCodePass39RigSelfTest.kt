package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass-39 rig arms (the pass-38 coverage list's two undrivable faces):
 * - MOCK_ARCHIVED_FAULT=1 — listArchivedTasks answers the typed RPC ERROR
 *   fault.archived.rig so the archived viewer's UNAVAILABLE face (the null-list
 *   branch) becomes drivable.
 * - MOCK_UNREAD_CAS=1 — setTaskUnread(unread=false) validates the client's
 *   expectedUnreadAt against the stored stamp (the desktop's host-side CAS);
 *   a mismatch answers fault.unread.stale:expectedUnreadAt and mutates nothing.
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass39RigSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        return MockZCode(passHash = passHash, expectedSid = "d_p39")
    }

    private fun replies(m: MockZCode, before: Int) = m.promiseResponsesForTest().drop(before)

    private fun faultOf(m: MockZCode, before: Int): String? {
        val last = replies(m, before).lastOrNull() ?: return null
        if (last.second != com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseError.id) return null
        val obj = (last.third as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json as? JsonObject ?: return null
        return (obj.jsonObject["message"] as? JsonPrimitive)?.content
    }

    @Test
    fun `archived fault arm answers the typed error and mutates nothing`() {
        val m = mock()
        armTaskManagementRig(m)
        m.taskPromiseForTest(1, "archiveTask", JsonObject(mapOf("taskId" to JsonPrimitive("synth-task-3"))))
        m.script.archivedFault = true
        val before = m.promiseResponsesForTest().size
        m.taskPromiseForTest(2, "listArchivedTasks", JsonObject(emptyMap()))
        assertEquals("fault.archived.rig", faultOf(m, before))
        // the archived set is untouched — the fault is answer-shape only
        assertTrue("synth-task-3" in m.script.archivedTaskIds)
        // disarm -> healthy answer again
        m.script.archivedFault = false
        val b2 = m.promiseResponsesForTest().size
        m.taskPromiseForTest(3, "listArchivedTasks", JsonObject(emptyMap()))
        val ok = replies(m, b2).last().second == com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseSuccess.id
        assertTrue(ok)
    }

    @Test
    fun `unread CAS arm rejects a mismatched expectedUnreadAt without mutating`() {
        val m = mock()
        armTaskManagementRig(m)
        val stored = m.script.unreadTaskIds["synth-task-2"]!!
        m.script.unreadCas = true
        val before = m.promiseResponsesForTest().size
        m.taskPromiseForTest(1, "setTaskUnread", JsonObject(mapOf(
            "taskId" to JsonPrimitive("synth-task-2"),
            "unread" to JsonPrimitive(false),
            "expectedUnreadAt" to JsonPrimitive(stored + 1),
        )))
        assertEquals("fault.unread.stale:expectedUnreadAt", faultOf(m, before))
        // nothing mutated: the stamp survives
        assertEquals(stored, m.script.unreadTaskIds["synth-task-2"])
    }

    @Test
    fun `unread CAS arm accepts the matching expectedUnreadAt`() {
        val m = mock()
        armTaskManagementRig(m)
        val stored = m.script.unreadTaskIds["synth-task-2"]!!
        m.script.unreadCas = true
        val before = m.promiseResponsesForTest().size
        m.taskPromiseForTest(1, "setTaskUnread", JsonObject(mapOf(
            "taskId" to JsonPrimitive("synth-task-2"),
            "unread" to JsonPrimitive(false),
            "expectedUnreadAt" to JsonPrimitive(stored),
        )))
        val last = replies(m, before).last()
        assertEquals(com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseSuccess.id, last.second)
        assertTrue("synth-task-2" !in m.script.unreadTaskIds)
    }

    @Test
    fun `unread CAS arm ignores mark-unread and passes through when disarmed`() {
        val m = mock()
        armTaskManagementRig(m)
        m.script.unreadCas = true
        // mark-UNREAD (unread=true) never CAS-checks, even armed
        val b1 = m.promiseResponsesForTest().size
        m.taskPromiseForTest(1, "setTaskUnread", JsonObject(mapOf(
            "taskId" to JsonPrimitive("synth-task-3"), "unread" to JsonPrimitive(true),
        )))
        assertEquals(com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseSuccess.id, replies(m, b1).last().second)
        assertTrue(m.script.unreadTaskIds.containsKey("synth-task-3"))
        // disarmed (the pass-38 behavior): a mismatched mark-read still succeeds
        m.script.unreadCas = false
        val b2 = m.promiseResponsesForTest().size
        m.taskPromiseForTest(2, "setTaskUnread", JsonObject(mapOf(
            "taskId" to JsonPrimitive("synth-task-3"),
            "unread" to JsonPrimitive(false),
            "expectedUnreadAt" to JsonPrimitive(1L),
        )))
        assertEquals(com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseSuccess.id, replies(m, b2).last().second)
        assertTrue("synth-task-3" !in m.script.unreadTaskIds)
    }

    @Test
    fun `force mode mismatches every mark-read without mutating (p39-mock-unread-cas)`() {
        val m = mock()
        armTaskManagementRig(m)
        val stored = m.script.unreadTaskIds["synth-task-2"]!!
        m.script.unreadCas = true
        m.script.unreadCasForce = true
        val before = m.promiseResponsesForTest().size
        // even the CORRECT stamp is refused under force — the desktop-diverged simulator
        m.taskPromiseForTest(1, "setTaskUnread", JsonObject(mapOf(
            "taskId" to JsonPrimitive("synth-task-2"),
            "unread" to JsonPrimitive(false),
            "expectedUnreadAt" to JsonPrimitive(stored),
        )))
        assertEquals("fault.unread.stale:expectedUnreadAt", faultOf(m, before))
        assertEquals(stored, m.script.unreadTaskIds["synth-task-2"])
    }

    @Test
    fun `created task inherits the source workspace label (p39-mock-created-label)`() {
        val m = mock()
        armTaskManagementRig(m)
        val env = buildJsonObject {
            put("type", JsonPrimitive("createSession"))
            put("payload", buildJsonObject {
                put("firstInput", buildJsonObject { put("text", JsonPrimitive("label probe")) })
            })
        }
        m.applyCommandEffectForTest("createSession", env)
        val created = m.bootstrapTasksView().last().jsonObject
        assertEquals("label probe", (created["title"] as JsonPrimitive).content)
        // the sibling seeds' label ("/synthetic/ws-one" -> "Synthetic One"), not the
        // "Synthetic Workspace" default that diverged on the live rig (pass 39).
        assertEquals("Synthetic One", (created["workspaceLabel"] as JsonPrimitive).content)
    }
}
