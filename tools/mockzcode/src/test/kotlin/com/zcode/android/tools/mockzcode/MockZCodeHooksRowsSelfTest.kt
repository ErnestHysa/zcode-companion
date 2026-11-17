package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G71 (vc52 Phase 12) mock self-test: the `hooksRowsDemo` arm seeds the hooks-monitor
 * snapshot rows (two hookInvocation groups with executions — completed/blocked/failed/
 * skipped + running — and one subagent row carrying childSessionId), and the
 * `requestWorkspaceHookReview` command effect RE-ISSUES the review (the resolved flag
 * flips back + the patch carries the whole pending list) — the phone's Settings
 * → Hooks Refresh leg is live on the rig.
 */
class MockZCodeHooksRowsSelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_selftest_g71")
        script(m.script)
        return m
    }

    @Test
    fun `hooksRowsDemo seeds two hookInvocation groups and a subagent row`() {
        val m = mock { s -> s.hooksRowsDemo = true }
        val rows = m.snapshotRowsForTestSeam("synth-task-1", 2)
        val hooks = rows.map { it.jsonObject }.filter { (it["kind"] as JsonPrimitive).content == "hookInvocation" }
        assertEquals(2, hooks.size)
        val events = hooks.map { (it["hookEventName"] as JsonPrimitive).content }
        assertEquals(listOf("PreToolUse", "SessionStart"), events)
        val first = hooks[0]["executions"] as JsonArray
        assertEquals(4, first.size, "completed + blocked + failed + skipped execution")
        val states = first.map { e -> (e.jsonObject["outcome"] as? JsonPrimitive)?.content }
        assertEquals(listOf("success", "blocked", "failed", "success"), states)
        assertTrue(
            ((first[1].jsonObject["blockReason"] as? JsonPrimitive)?.content ?: "").isNotEmpty(),
            "the blocked execution carries its blockReason",
        )
        val skipped = first[3].jsonObject
        assertEquals(false, (skipped["didExecute"] as JsonPrimitive).content.toBoolean())
        val subagents = rows.map { it.jsonObject }.filter { (it["kind"] as JsonPrimitive).content == "subagent" }
        assertEquals(1, subagents.size)
        assertEquals("sess-child-9", (subagents[0]["childSessionId"] as JsonPrimitive).content)
    }

    @Test
    fun `the row kinds are schema-faithful without the arm`() {
        val m = mock()
        val row = m.schemaRow("synth-task-1", 41, MockZCode.RowKind.HOOK_INVOCATION, "PreToolUse").jsonObject
        assertEquals("hookInvocation", (row["kind"] as JsonPrimitive).content)
        assertNotNull(row["hookInvocationId"])
        assertNotNull(row["executions"])
        val sub = m.schemaRow("synth-task-1", 42, MockZCode.RowKind.SUBAGENT, "explore").jsonObject
        assertEquals("subagent", (sub["kind"] as JsonPrimitive).content)
        assertNotNull(sub["subagentType"])
    }

    @Test
    fun `requestWorkspaceHookReview re-issues the resolved review`() {
        val m = mock { s ->
            s.hookReview = JsonObject(
                mapOf(
                    "interactionId" to JsonPrimitive("hook-1"),
                    "reviewFlowId" to JsonPrimitive("flow-1"),
                    "items" to JsonArray(emptyList()),
                ),
            )
        }
        // Resolve first (the trust card answered).
        m.applyCommandEffectForTest(
            "respondWorkspaceHookReview",
            JsonObject(
                mapOf(
                    "payload" to JsonObject(
                        mapOf(
                            "interactionId" to JsonPrimitive("hook-1"),
                            "decision" to JsonObject(
                                mapOf(
                                    "action" to JsonPrimitive("trust_selected"),
                                    "reviewItemIds" to JsonArray(emptyList()),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(m.script.hookReviewsResolved, "the answered review is resolved")
        // The G71 Refresh verb re-issues it.
        m.applyCommandEffectForTest(
            "requestWorkspaceHookReview",
            JsonObject(mapOf("payload" to JsonObject(mapOf("sessionId" to JsonPrimitive("synth-task-1"))))),
        )
        assertTrue(!m.script.hookReviewsResolved, "the resolved flag flips back")
        val pending = m.pendingListForTest()
        val kinds = pending.map { (it.jsonObject["kind"] as JsonPrimitive).content }
        assertTrue("workspaceHookReview" in kinds, "the review interaction re-posts beside any other slots")
    }

    @Test
    fun `un-armed rows stay the synthetic pair (no hook rows)`() {
        val m = mock()
        val rows = m.snapshotRowsForTestSeam("synth-task-1", 4)
        assertTrue(rows.map { it.jsonObject }.none { (it["kind"] as JsonPrimitive).content == "hookInvocation" })
    }

    @Test
    fun `pass-44 refuse arm answers the typed fault for the listed hook verb only`() {
        val m = mock { s -> s.refuseCommandTypes = setOf("requestWorkspaceHookReview") }
        assertEquals(
            "fault.command.rigRefused:requestWorkspaceHookReview",
            m.refuseReasonFor("requestWorkspaceHookReview"),
            "the armed hook verb is refuse-listed",
        )
        assertNull(m.refuseReasonFor("sendText"), "unlisted types dispatch normally")
        // The re-issue effect is a dispatch-order property: the refuse answer happens
        // BEFORE applyCommandEffect, so an armed type never mutates the review state.
        assertTrue(m.script.hookReviewsResolved == false, "no effect ran for the refused verb")
    }

    @Test
    fun `pass-45 hooks-second seeds the second task's review beside the first`() {
        val m = mock { s ->
            s.hookReview = JsonObject(
                mapOf(
                    "interactionId" to JsonPrimitive("hook-1"),
                    "reviewFlowId" to JsonPrimitive("flow-1"),
                    "taskId" to JsonPrimitive("synth-task-1"),
                    "items" to JsonArray(emptyList()),
                ),
            )
            s.hookReviewSecond = JsonObject(
                mapOf(
                    "interactionId" to JsonPrimitive("hook-2"),
                    "reviewFlowId" to JsonPrimitive("flow-2"),
                    "taskId" to JsonPrimitive("synth-task-2"),
                    "items" to JsonArray(emptyList()),
                ),
            )
        }
        val pending = m.pendingListForTest()
        val reviews = pending.map { it.jsonObject }.filter { (it["kind"] as JsonPrimitive).content == "workspaceHookReview" }
        assertEquals(2, reviews.size, "both reviews serve in the pending list")
        val taskIds = reviews.map { (it.jsonObject["payload"]!!.jsonObject["taskId"] as JsonPrimitive).content }
        assertEquals(listOf("synth-task-1", "synth-task-2"), taskIds, "each review keys its own task")
        // Second-seed-only composition: MOCK_HOOKS_SECOND alone still serves its review.
        val onlySecond = mock { s ->
            s.hookReviewSecond = JsonObject(
                mapOf(
                    "interactionId" to JsonPrimitive("hook-2s"),
                    "reviewFlowId" to JsonPrimitive("flow-2s"),
                    "taskId" to JsonPrimitive("synth-task-2"),
                    "items" to JsonArray(emptyList()),
                ),
            )
        }
        val pending2 = onlySecond.pendingListForTest()
        val reviews2 = pending2.map { it.jsonObject }.filter { (it["kind"] as JsonPrimitive).content == "workspaceHookReview" }
        assertEquals(1, reviews2.size, "the second seed alone serves exactly its own review")
        assertEquals("synth-task-2", (reviews2[0]["payload"]!!.jsonObject["taskId"] as JsonPrimitive).content)
    }
}
