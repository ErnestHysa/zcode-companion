package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass-46 mock self-test (the MODEL-CALL TRAJECTORY axis + the conflict-slice leg):
 * `seedPhase5bDemo()` is the exact Phase-5B shape MockRelayMain serves (extracted so
 * the arms mutate one source), `applyTrajectoryModes` mutates it per MOCK_TRAJECTORY
 * mode (usage adds a third record with response.usage token columns; truncated flips
 * the badge; empty clears records while the search corpus stays seeded; unavailable
 * nulls the payload → the typed fault), and `checkpointConflictFiles` widens the
 * restore conflict arm's served paths (default stays the pass-27 single file) so the
 * phone's `conflicts.take(3)` body slice is drivable.
 */
class MockZCodePass46TrajectorySelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 46 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_selftest_p46")
        script(m.script)
        return m
    }

    private fun records(m: MockZCode): List<kotlinx.serialization.json.JsonObject> =
        (m.script.modelTrajectory?.get("records") as? JsonArray)
            ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            ?: emptyList()

    @Test
    fun `seedPhase5bDemo seeds the two-record trajectory plus plans and the search corpus`() {
        val m = mock { s -> s.seedPhase5bDemo() }
        assertEquals(2, records(m).size, "req-1 tool_calls + req-2 RateLimitError")
        assertEquals(listOf("req-1", "req-2"), records(m).map { (it["requestId"] as JsonPrimitive).content })
        assertNotNull(m.script.plansState, "the plans projection seeds with the trajectory")
        assertEquals(2, m.script.sessionMessages?.size, "the search corpus seeds two messages")
        assertEquals(false, ((m.script.modelTrajectory?.get("truncated") ?: JsonPrimitive(false)) as JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `usage mode appends a third record carrying response usage token columns`() {
        val m = mock { s ->
            s.seedPhase5bDemo()
            s.applyTrajectoryModes(listOf("usage"))
        }
        val recs = records(m)
        assertEquals(3, recs.size)
        val third = recs.last()
        assertEquals("req-3", (third["requestId"] as JsonPrimitive).content)
        val usage = ((third["response"] as? kotlinx.serialization.json.JsonObject)?.get("usage"))
            ?.let { it as? kotlinx.serialization.json.JsonObject }
        assertNotNull(usage, "the usage record carries response.usage")
        assertEquals("1240", (usage["inputTokens"] as JsonPrimitive).content)
        assertEquals("1596", (usage["totalTokens"] as JsonPrimitive).content)
        assertEquals("800", (usage["cacheReadTokens"] as JsonPrimitive).content)
    }

    @Test
    fun `truncated mode flips the badge and empty clears records but keeps the search corpus`() {
        val m = mock { s ->
            s.seedPhase5bDemo()
            s.applyTrajectoryModes(listOf("truncated", "empty"))
        }
        assertEquals(true, ((m.script.modelTrajectory?.get("truncated") ?: JsonPrimitive(false)) as JsonPrimitive).content.toBoolean())
        assertTrue(records(m).isEmpty(), "records=[] — the honest empty face")
        assertEquals(2, m.script.sessionMessages?.size, "the search corpus stays seeded under the empty face")
    }

    @Test
    fun `unavailable mode nulls the payload (the typed fault face)`() {
        val m = mock { s ->
            s.seedPhase5bDemo()
            s.applyTrajectoryModes(listOf("unavailable"))
        }
        assertNull(m.script.modelTrajectory)
    }

    @Test
    fun `unknown modes are ignored fail-safe and empty-list application is a no-op`() {
        val m = mock { s ->
            s.seedPhase5bDemo()
            s.applyTrajectoryModes(listOf("bogus", ""))
        }
        assertEquals(2, records(m).size, "the seeded shape survives unknown modes")
    }

    @Test
    fun `conflict arm serves the widened file list and the default stays single`() {
        val mDefault = mock { s -> s.checkpointConflicts = true }
        assertEquals(listOf("src/config.ts"), mDefault.checkpointConflictPayloadForTest()["conflicts"]?.let { it as JsonArray }
            ?.map { (it as JsonPrimitive).content }, "back-compat: the pass-27 single conflict")
        val mWide = mock { s ->
            s.checkpointConflicts = true
            s.checkpointConflictFiles = listOf("src/config.ts", "src/main.rs", "docs/notes.md", "scratch/a.txt", "scratch/b.txt")
        }
        val wide = (mWide.checkpointConflictPayloadForTest()["conflicts"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(5, wide.size, "the >3-files shape the phone's take(3) slice must render")
        assertEquals(listOf("src/config.ts", "src/main.rs", "docs/notes.md", "scratch/a.txt", "scratch/b.txt"), wide)
        // the live handler answers the same payload (the seam is what the wire serves)
        mWide.checkpointPromiseForTest(1, "restoreBetweenCheckpoints", kotlinx.serialization.json.buildJsonObject { })
        assertTrue(mWide.script.checkpointConflicts)
    }
}
