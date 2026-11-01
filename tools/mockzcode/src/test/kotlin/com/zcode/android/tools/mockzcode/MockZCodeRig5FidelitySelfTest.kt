package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RIG-5 (vc51 Phase 5) mock self-tests: the rig-fidelity debts retire ON THE WIRE —
 * (1) command effects route to the CALLING session (the D4.1 artifact: every patch/row
 * was pushed to synth-task-1's stream regardless of which conversation sent the
 * command), (2) the lifecycle verbs archive/unarchive/resume MUTATE the task store so
 * the next list reflects the ack (the recorded ack-without-mutate class of passes
 * 2/6/8), and (3) the git branch acks mutate the served summary + branch list so the
 * Git pane's header follows the switch (the Phase-4 exit-gate note). Synthetic
 * content only (INV-15/16).
 */
class MockZCodeRig5FidelitySelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_rig5")
        script(m.script)
        return m
    }

    private fun envelope(type: String, payload: JsonObject) = buildJsonObject {
        put("type", type)
        put("commandId", "cmd-rig5")
        put("payload", payload)
    }

    private fun task(id: String, title: String, status: String = "idle") = buildJsonObject {
        put("taskId", JsonPrimitive(id))
        put("title", JsonPrimitive(title))
        put("displayStatus", JsonPrimitive(status))
        put("workspacePath", JsonPrimitive("/synthetic/ws-one"))
    }

    // ------------------------------------------------------------ calling-session routing

    @Test
    fun `command effects route to the CALLING session (D4-1 routing artifact)`() {
        val m = mock { it.commandEffects = true }
        // The rig app subscribed synth-task-2 (the conversation the command came from).
        m.activeSessionForTest("synth-task-2")
        val before = m.opLogForTest("synth-task-1").size
        m.applyCommandEffectForTest("sendText", envelope("sendText", buildJsonObject { put("text", JsonPrimitive("cross-session ping")) }))
        // The user row + reply ride synth-task-2's stream — NOT synth-task-1's.
        assertTrue(m.opLogForTest("synth-task-2").isNotEmpty(), "the effect deltas must journal on the calling session")
        val rows = m.appendedRowsForTest().values
        assertTrue(rows.all { ((it["turnId"] as? JsonPrimitive)?.content ?: "").contains("synth-task-2") },
            "authored rows must carry the calling session's id")
        assertEquals(before, m.opLogForTest("synth-task-1").size, "synth-task-1's stream must stay untouched")
    }

    @Test
    fun `without an active subscription the effects keep the default session (existing suites unchanged)`() {
        val m = mock { it.commandEffects = true }
        m.applyCommandEffectForTest("sendText", envelope("sendText", buildJsonObject { put("text", JsonPrimitive("default routing")) }))
        assertTrue(m.opLogForTest("synth-task-1").isNotEmpty(), "the default session still owns the effects")
    }

    @Test
    fun `the pending-interaction push routes to the calling session`() {
        val m = mock { it.emitStateKeys = true } // the relay arms emitStateKeys — the push leg needs the block
        m.activeSessionForTest("synth-task-3")
        m.pushPendingPermission("i-rig5", "Bash")
        assertTrue(m.opLogForTest("synth-task-3").isNotEmpty(), "the interaction patch must journal on the calling session")
        assertEquals("i-rig5", m.pendingPermissionForTest()?.interactionId)
    }

    // ------------------------------------------------------------ task-store mutations

    @Test
    fun `archiveTask and unarchiveTask mutate the archived set the list reads`() {
        val m = mock()
        m.script.directTasks = listOf(task("t1", "One"), task("t2", "Two"))
        m.taskPromiseForTest(1, "archiveTask", buildJsonObject { put("taskId", JsonPrimitive("t1")) })
        assertTrue("t1" in m.script.archivedTaskIds, "the ack must mutate the store the archived list filters")
        assertTrue(m.script.directTasks.mapNotNull { (it["taskId"] as? JsonPrimitive)?.content }.contains("t1"),
            "archiving must not delete the row (the desktop keeps it listable)")
        m.taskPromiseForTest(2, "unarchiveTask", buildJsonObject { put("taskId", JsonPrimitive("t1")) })
        assertFalse("t1" in m.script.archivedTaskIds)
    }

    @Test
    fun `resumeTask flips the row's displayStatus to idle (the continue verb's visible face)`() {
        val m = mock()
        m.script.directTasks = listOf(task("t1", "One", status = "ended"))
        m.taskPromiseForTest(1, "resumeTask", buildJsonObject { put("taskId", JsonPrimitive("t1")) })
        assertTrue("t1" in m.script.resumedTaskIds)
        assertEquals("idle", (m.script.directTasks.single()["displayStatus"] as JsonPrimitive).content)
    }

    // ------------------------------------------------------------ git branch acks mutate the served state

    @Test
    fun `createBranchAndSwitch mutates the served summary and the branch list (Phase-4 gate note)`() {
        val m = mock()
        m.script.gitSummary = buildJsonObject {
            put("repoRoot", JsonPrimitive("/synthetic/repo"))
            put("branchName", JsonPrimitive("main"))
            put("trackingBranchName", JsonPrimitive("origin/main"))
            put("ahead", JsonPrimitive(0)); put("behind", JsonPrimitive(0))
            put("isDirty", JsonPrimitive(false)); put("isGitAvailable", JsonPrimitive(true))
            put("isRepository", JsonPrimitive(true))
        }
        m.gitPromiseForTest(1, "createBranchAndSwitch",
            kotlinx.serialization.json.buildJsonObject { put("targetBranchName", JsonPrimitive("feature/rig5")) })
        assertEquals("feature/rig5", m.script.currentGitBranch)
        // The served summary follows the switch: new branch, no upstream, even counts.
        val merged = m.repositorySummaryForTest()!!
        assertEquals("feature/rig5", (merged["branchName"] as JsonPrimitive).content)
        assertTrue(merged["trackingBranchName"] is JsonNull, "a fresh branch has no upstream")
        // The branch list's head follows too (getLocalBranches).
        assertEquals("feature/rig5", m.localBranchesHeadForTest())
        // A typed issue ack leaves the served state alone (the switch never happened).
        m.script.gitBranchIssue = buildJsonObject { put("kind", JsonPrimitive("uncommitted-changes-would-be-overwritten")) }
        m.gitPromiseForTest(2, "switchBranch", buildJsonObject { put("targetBranchName", JsonPrimitive("main")) })
        assertEquals("feature/rig5", m.script.currentGitBranch, "a refused switch must not mutate the summary")
        m.script.gitBranchIssue = null
    }

    // ------------------------------------------------------------ ack-without-mutate sweep (negative control)

    @Test
    fun `setTaskPinned still round-trips through the pinned list (regression control)`() {
        val m = mock()
        m.script.directTasks = listOf(task("t1", "One"))
        m.taskPromiseForTest(1, "setTaskPinned", buildJsonObject { put("taskId", JsonPrimitive("t1")); put("pinned", JsonPrimitive(true)) })
        assertTrue("t1" in m.script.pinnedTaskIds)
        assertNull(m.script.archivedTaskIds.firstOrNull { it == "t1" })
    }
}
