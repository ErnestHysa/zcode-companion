package com.zcode.android.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.zcode.android.domain.model.ConversationRow

/**
 * G71 (vc52 Phase 12) pin: the `hookInvocation` row's `executions[]` decode typed
 * (chunk-RB4EITEQ `Dk`, .strict(), asar 4a6080ed…) with the desktop chat's `lTn`
 * monitor-state mapping — running/timedOut/cancelled/blocked/failed; null = the
 * completed face. A malformed execution drops THAT entry (never the row).
 */
class HookExecutionDecodeTest {

    private fun execJson(vararg overrides: Pair<String, String>) = JsonObject(
        mutableMapOf(
            "hookRunId" to kotlinx.serialization.json.JsonPrimitive("hr-1"),
            "hookIndex" to kotlinx.serialization.json.JsonPrimitive(0),
            "didExecute" to kotlinx.serialization.json.JsonPrimitive(true),
            "state" to kotlinx.serialization.json.JsonPrimitive("completed"),
            "displayName" to kotlinx.serialization.json.JsonPrimitive("Lint gate"),
            "sourceKind" to kotlinx.serialization.json.JsonPrimitive("project"),
        ).apply { overrides.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) } },
    )

    private fun rowWithExecutions(execs: String): ConversationRow.HookInvocation {
        val json = """
            {"rowId":7,"turnId":"t1","kind":"hookInvocation","entityId":"e1",
             "createdAt":1.0,"createdAtSeq":7,
             "hookInvocationId":"hi-1","hookEventName":"PreToolUse","hookCount":2,
             "state":"completed","lane":"toolAfter","executions":$execs}
        """.trimIndent()
        return ConversationRow.Decoder.decode(Json.parseToJsonElement(json).jsonObject) as ConversationRow.HookInvocation
    }

    @Test
    fun `execution decodes typed with all fields`() {
        val row = rowWithExecutions(
            """[{"hookRunId":"hr-1","hookIndex":1,"didExecute":true,"state":"completed",
                "outcome":"blocked","blockReason":"deny rule","durationMs":95,
                "displayName":"Secrets guard","sourceKind":"user","pluginName":"acme","toolName":"Bash"}]""",
        )
        val e = row.executions.single()
        assertEquals("hr-1", e.hookRunId)
        assertEquals(1, e.hookIndex)
        assertTrue(e.didExecute)
        assertEquals("completed", e.state)
        assertEquals("blocked", e.outcome)
        assertEquals("deny rule", e.blockReason)
        assertEquals(95L, e.durationMs)
        assertEquals("Secrets guard", e.displayName)
        assertEquals("user", e.sourceKind)
        assertEquals("acme", e.pluginName)
        assertEquals("Bash", e.toolName)
    }

    @Test
    fun `monitorState maps the desktop lTn faces`() {
        fun state(vararg kv: Pair<String, String>) =
            ConversationRow.HookExecution.decode(execJson(*kv))!!.monitorState
        assertEquals("running", state("state" to "running"))
        assertEquals("timedOut", state("outcome" to "timed_out"))
        assertEquals("cancelled", state("outcome" to "cancelled"))
        assertEquals("blocked", state("outcome" to "blocked"))
        assertEquals("failed", state("state" to "failed"))
        assertEquals("failed", state("outcome" to "failed"))
        assertNull(state(), "success/completed = the completed face (null)")
    }

    @Test
    fun `missing hookRunId drops the entry not the row`() {
        val row = rowWithExecutions(
            """[{"hookIndex":0,"didExecute":true,"state":"completed","displayName":"x","sourceKind":"user"},
                {"hookRunId":"hr-2","hookIndex":1,"didExecute":true,"state":"completed","displayName":"ok","sourceKind":"project"}]""",
        )
        assertEquals(1, row.executions.size, "the malformed entry drops, the row stays")
        assertEquals("hr-2", row.executions.single().hookRunId)
    }

    @Test
    fun `didExecute defaults false and optional fields stay null`() {
        // Absent didExecute (not "false" — the ABSENT face) decodes false.
        val absent = JsonObject(
            mutableMapOf(
                "hookRunId" to kotlinx.serialization.json.JsonPrimitive("hr-0"),
                "state" to kotlinx.serialization.json.JsonPrimitive("completed"),
                "displayName" to kotlinx.serialization.json.JsonPrimitive("x"),
                "sourceKind" to kotlinx.serialization.json.JsonPrimitive("user"),
            ),
        )
        val e = ConversationRow.HookExecution.decode(absent)!!
        assertTrue(!e.didExecute, "absent didExecute = false (the hidden face)")
        val plain = ConversationRow.HookExecution.decode(execJson())!!
        assertNull(plain.outcome)
        assertNull(plain.blockReason)
        assertNull(plain.durationMs)
        assertNull(plain.pluginName)
        assertNull(plain.toolName)
        assertEquals("Lint gate", plain.displayName)
        assertEquals("project", plain.sourceKind)
    }

    @Test
    fun `executions empty array stays empty (legacy rows render the quiet fallback)`() {
        val row = rowWithExecutions("[]")
        assertTrue(row.executions.isEmpty())
    }
}
