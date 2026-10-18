package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4A (E3.2+I1+N5) mock self-tests: the rig leg's row-action fixtures are
 * schema-faithful — the LAST assistant row carries `actions:{canFork,canRetry}`
 * (true keys only, rows.ts), a completed turn carries fileChanges +
 * `actions.canRewindFiles`, and the four row commands produce the verified ack
 * results / row deltas (command.ts commandResultSchema: forkAssistant → sessionId;
 * applyFileRewind → applied/preview/response). Synthetic content only (INV-15/16).
 */
class MockZCodeRowActionsSelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_rowactions")
        script(m.script)
        return m
    }

    private fun envelope(type: String, payload: JsonObject) = buildJsonObject {
        put("type", type)
        put("commandId", "cmd-1")
        put("payload", payload)
    }

    private fun target(rowId: Long) = buildJsonObject {
        put("target", buildJsonObject { put("rowId", JsonPrimitive(rowId)); put("entityId", "ent-$rowId") })
    }

    @Test
    fun `rowActionsDemo stamps the latest assistant row and a rewindable turn`() {
        val m = mock { s -> s.rowActionsDemo = true; s.commandEffects = true }
        val rows = m.snapshotRowsForTest("sess-1", 4)
        val assistants = rows.filter { (it.jsonObject["kind"] as JsonPrimitive).content == "assistantText" }
        assertTrue(assistants.size >= 2)
        val last = assistants.last().jsonObject["actions"]?.jsonObject
        assertNotNull(last, "the LATEST assistant row carries the flags")
        assertEquals("true", (last["canFork"] as JsonPrimitive).content)
        assertEquals("true", (last["canRetry"] as JsonPrimitive).content)
        assertNull(assistants.first().jsonObject["actions"], "earlier assistant rows carry none (product-projection fidelity)")
        val turn = rows.map { it.jsonObject }.first { (it["kind"] as JsonPrimitive).content == "turnHeader" }
        assertEquals("completedSuccess", (turn["state"] as JsonPrimitive).content)
        assertEquals("2", (turn["fileChanges"]!!.jsonObject["files"] as JsonPrimitive).content)
        assertEquals("true", (turn["actions"]!!.jsonObject["canRewindFiles"] as JsonPrimitive).content)
    }

    @Test
    fun `default scripts carry no row-action flags (legacy suites unchanged)`() {
        val m = mock { s -> s.commandEffects = true }
        val rows = m.snapshotRowsForTest("sess-1", 4)
        assertTrue(rows.none { it.jsonObject["actions"] != null })
        assertTrue(rows.none { (it.jsonObject["kind"] as JsonPrimitive).content == "turnHeader" })
    }

    @Test
    fun `forkAssistant effect names the child session in the ack result and lists the new task`() {
        val m = mock { s -> s.rowActionsDemo = true; s.commandEffects = true }
        val before = m.revisionForTest()
        val result = assertNotNull(m.applyCommandEffectForTest("forkAssistant", envelope("forkAssistant", target(2))))
        assertEquals("forkAssistant", (result["type"] as JsonPrimitive).content)
        val child = (result["sessionId"] as JsonPrimitive).content
        assertTrue(child.startsWith("sess-forked-"))
        assertEquals(child, m.lastForkedSessionId)
        assertTrue(m.revisionForTest() > before, "every effect bumps the CAS revision")
        assertTrue(m.script.bootstrapTasks.orEmpty().any { it.taskId == child }, "the forked child is a listed task")
    }

    @Test
    fun `applyFileRewind effect answers applied + preview and flips the turn to reverted`() {
        val m = mock { s -> s.rowActionsDemo = true; s.commandEffects = true }
        val result = assertNotNull(m.applyCommandEffectForTest("applyFileRewind", envelope("applyFileRewind", target(40))))
        assertEquals("applyFileRewind", (result["type"] as JsonPrimitive).content)
        assertEquals("true", (result["applied"] as JsonPrimitive).content)
        assertNotNull(result["preview"])
        val turn = assertNotNull(m.appendedRowForTest(40))
        assertEquals("reverted", (turn["fileChanges"]!!.jsonObject["state"] as JsonPrimitive).content)
        assertNull(turn["actions"], "a reverted turn no longer advertises canRewindFiles")
    }

    @Test
    fun `setAssistantFeedback echoes like dislike and clears on explicit null`() {
        val m = mock { s -> s.rowActionsDemo = true; s.commandEffects = true }
        m.applyCommandEffectForTest("setAssistantFeedback", envelope("setAssistantFeedback", JsonObject(target(7) + ("feedback" to JsonPrimitive("like")))))
        assertEquals("like", (m.appendedRowForTest(7)!!["feedback"] as JsonPrimitive).content)
        assertEquals("like", m.lastFeedback)
        m.applyCommandEffectForTest("setAssistantFeedback", envelope("setAssistantFeedback", JsonObject(target(7) + ("feedback" to JsonPrimitive("dislike")))))
        assertEquals("dislike", (m.appendedRowForTest(7)!!["feedback"] as JsonPrimitive).content)
        m.applyCommandEffectForTest("setAssistantFeedback", envelope("setAssistantFeedback", JsonObject(target(7) + ("feedback" to JsonNull))))
        assertNull(m.appendedRowForTest(7)!!["feedback"], "explicit null clears the echo")
        assertNull(m.lastFeedback)
    }

    @Test
    fun `retryTurn effect appends a fresh assistant row carrying the flags`() {
        val m = mock { s -> s.rowActionsDemo = true; s.commandEffects = true }
        val before = m.revisionForTest()
        assertNull(m.applyCommandEffectForTest("retryTurn", envelope("retryTurn", target(2))), "retryTurn has no typed result body")
        assertTrue(m.revisionForTest() > before)
        val rows = m.appendedRowsForTest()
        val retried = rows.values.firstOrNull { (it["text"] as? JsonPrimitive)?.content == "retried reply" }
        assertNotNull(retried)
        assertEquals("true", (retried["actions"]!!.jsonObject["canRetry"] as JsonPrimitive).content)
    }

    private fun MockZCode.snapshotRowsForTest(sessionId: String, count: Int): JsonArray = snapshotRowsForTestSeam(sessionId, count)
}
