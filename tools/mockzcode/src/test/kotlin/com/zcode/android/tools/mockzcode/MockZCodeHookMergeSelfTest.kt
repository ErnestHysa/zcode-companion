package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U4 (verification pass 2, 2026-10-21, rig tooling): with MOCK_HOOKS armed next to
 * MOCK_APPROVAL/MOCK_QUESTION, the conversation showed NO pinned approval or question
 * card while the inbox badge said 1 — the hook-review branch REPLACED the state block's
 * `pendingInteractions` with its single workspaceHookReview entry, dropping the
 * permission + userInput interactions built just above it. The G3-mock clobber class
 * (key-preserving merge), on the interactions key. The desktop carries them together;
 * so must the mock.
 */
class MockZCodeHookMergeSelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_selftest_u4")
        script(m.script)
        return m
    }

    @Test
    fun `a scripted hook review MERGES into pendingInteractions beside the permission and the question`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.pendingPermission = MockZCode.PendingPermission("i-perm", toolName = "Bash")
            s.pendingUserInput = MockZCode.PendingUserInput("i-q", options = emptyList(), freeText = false)
            s.hookReview = JsonObject(mapOf("interactionId" to JsonPrimitive("hook-1"), "items" to JsonArray(emptyList())))
        }
        val pending = m.stateBlockForTest()!!["pendingInteractions"] as JsonArray
        val kinds = pending.map { (it.jsonObject["kind"] as JsonPrimitive).content }
        assertEquals(listOf("permission", "userInput", "workspaceHookReview"), kinds)
    }

    @Test
    fun `a resolved approval KEEPS the scripted hook review alive (the patch is not an empty list)`() {
        val m = mock { s ->
            s.pendingPermission = MockZCode.PendingPermission("i-perm", toolName = "Bash")
            s.hookReview = JsonObject(
                mapOf(
                    "interactionId" to JsonPrimitive("hook-1"),
                    "reviewFlowId" to JsonPrimitive("flow-1"),
                    "items" to JsonArray(emptyList()),
                ),
            )
        }
        val before = m.opLogForTest("synth-task-1").size
        m.applyCommandEffectForTest(
            "resolveInteraction",
            JsonObject(mapOf("payload" to JsonObject(mapOf("interactionId" to JsonPrimitive("i-perm"))))),
        )
        // The effect journals on the routing session (RIG-5 rule) — read the journaled
        // state.updated patch: the answered interaction resolves, the hook SURVIVES.
        // (The resolve also journals row-appended frames after it — pick the last
        // state.updated op, not just the last frame.)
        val frames = m.opLogForTest("synth-task-1")
        assertTrue(frames.size > before, "the resolve must journal an effect frame")
        val statePatch = frames.asReversed()
            .flatMap { it.ops }
            .map { it.jsonObject }
            .first { it["op"]?.toString()?.contains("state.updated") == true }["patch"]!!.jsonObject
        val kinds = (statePatch["pendingInteractions"] as JsonArray)
            .map { (it.jsonObject["kind"] as JsonPrimitive).content }
        assertEquals(
            listOf("workspaceHookReview"), kinds,
            "the answered interaction resolves; the scripted hook must survive the replacement",
        )
    }
}
