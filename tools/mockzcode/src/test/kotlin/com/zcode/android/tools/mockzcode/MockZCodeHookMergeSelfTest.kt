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

    /**
     * Pass-14 rig find: resolving ONE interaction must clear only THAT slot. The old
     * handler nulled pendingPermission AND pendingUserInput unconditionally, so answering
     * an approval silently resolved the sibling AskUserQuestion beside it (live case:
     * mock-approval-push-1 answered with "Allow once" → q-rig-1 resolved by-elsewhere and
     * the card vanished with no wire command for the question).
     */
    @Test
    fun `a resolved approval KEEPS the sibling user-input interaction alive`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.pendingPermission = MockZCode.PendingPermission("i-perm", toolName = "Bash")
            s.pendingUserInput = MockZCode.PendingUserInput("i-q", options = emptyList(), freeText = false)
        }
        val before = m.opLogForTest("synth-task-1").size
        m.applyCommandEffectForTest(
            "resolveInteraction",
            JsonObject(mapOf("payload" to JsonObject(mapOf("interactionId" to JsonPrimitive("i-perm"))))),
        )
        val frames = m.opLogForTest("synth-task-1")
        assertTrue(frames.size > before, "the resolve must journal an effect frame")
        val statePatch = frames.asReversed()
            .flatMap { it.ops }
            .map { it.jsonObject }
            .first { it["op"]?.toString()?.contains("state.updated") == true }["patch"]!!.jsonObject
        val kinds = (statePatch["pendingInteractions"] as JsonArray)
            .map { (it.jsonObject["kind"] as JsonPrimitive).content }
        assertEquals(
            listOf("userInput"), kinds,
            "answering the permission must not resolve the sibling question",
        )
        // Ground truth on the script slots too: the un-answered one is still armed.
        assertEquals("i-q", m.pendingUserInputForTest()?.interactionId)
        assertEquals(null, m.pendingPermissionForTest())
    }

    /** And the inverse: answering the QUESTION must keep the permission alive. */
    @Test
    fun `a resolved question KEEPS the sibling permission alive`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.pendingPermission = MockZCode.PendingPermission("i-perm", toolName = "Bash")
            s.pendingUserInput = MockZCode.PendingUserInput("i-q", options = emptyList(), freeText = false)
        }
        m.applyCommandEffectForTest(
            "resolveInteraction",
            JsonObject(mapOf("payload" to JsonObject(mapOf("interactionId" to JsonPrimitive("i-q"))))),
        )
        assertEquals("i-perm", m.pendingPermissionForTest()?.interactionId)
        assertEquals(null, m.pendingUserInputForTest())
    }

    /**
     * Pass-15 rig find: `respondWorkspaceHookReview` set `script.hookReviewsResolved`
     * but NOTHING read the flag — `scriptedHookInteraction()` sat in
     * `pendingInteractionList()` unconditionally, so the next full-state emission (any
     * later command's patch, a reconnect snapshot) re-asserted the RESPONDED review as
     * pending. Live case (vc51 pass 15): trust-confirm → grantWorkspaceHookTrust ok →
     * "Trusted 2 hook(s)" → Send-now follow-up command → the trust card CAME BACK
     * ("Trust 2 selected hook(s)") because the patch carried the hook interaction
     * again. The app is contract-correct (key-level replacement trusts the list);
     * the mock must stop re-asserting a review the session already responded to.
     */
    @Test
    fun `a RESPONDED hook review stays resolved in every later snapshot and patch`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.hookReview = JsonObject(
                mapOf(
                    "interactionId" to JsonPrimitive("hook-1"),
                    "reviewFlowId" to JsonPrimitive("flow-1"),
                    "items" to JsonArray(emptyList()),
                ),
            )
        }
        m.applyCommandEffectForTest(
            "respondWorkspaceHookReview",
            JsonObject(mapOf("payload" to JsonObject(mapOf(
                "decision" to JsonObject(mapOf("reviewItemIds" to JsonArray(emptyList()))),
            )))),
        )
        // 1. The snapshot must not offer the responded review as pending.
        val blockKinds = (m.stateBlockForTest()!!["pendingInteractions"] as JsonArray)
            .map { (it.jsonObject["kind"] as JsonPrimitive).content }
        assertEquals(
            emptyList<String>(), blockKinds,
            "a responded hook review must not ride the connection snapshot as pending",
        )
        // 2. A LATER full patch (any following effect) must not resurrect it either.
        m.applyCommandEffectForTest(
            "resolveInteraction",
            JsonObject(mapOf("payload" to JsonObject(mapOf("interactionId" to JsonPrimitive("i-other"))))),
        )
        val frames = m.opLogForTest("synth-task-1")
        val statePatch = frames.asReversed()
            .flatMap { it.ops }
            .map { it.jsonObject }
            .first { it["op"]?.toString()?.contains("state.updated") == true }["patch"]!!.jsonObject
        val patchKinds = ((statePatch["pendingInteractions"] as? JsonArray) ?: JsonArray(emptyList()))
            .map { (it.jsonObject["kind"] as JsonPrimitive).content }
        assertEquals(
            emptyList<String>(), patchKinds,
            "a responded hook review must never re-appear as pending in a later patch",
        )
    }
}
