package com.zcode.android.tools.mockzcode

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass-23 rig self-tests — the Part-Y coverage-honesty arms:
 *
 * 1. The MOCK_COMPACT_COMMAND passthrough half: under startNow/null routing the
 *    host's vMc RUNS the compaction turn, so the mock now pushes the host-faithful
 *    manual projectMarker pair — `running:manual` immediately, `completed:manual`
 *    (token counts) after a delay — both joining the authoritative appendedRows
 *    tail (the debt-#17 contract: resync snapshots keep pushed rows). The generic
 *    accepted ack still answers (the compact stays un-queued, un-rejected).
 * 2. The stale-family regression pin: MOCK_CAS_STALE_TYPES still intercepts the
 *    pass-20/22 verbs, and the pass-23 additions (setAutoDrain, setFollowupMode,
 *    pauseGoal, resumeGoal — all CAS-guarded in CommandCoordinator) intercept with
 *    the same host ack shape and mutate nothing.
 *
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass23RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 23 }), expectedSid = "d_p23")
        m.activeSessionForTest("sess-p23")
        block?.let { m.script.it() }
        return m
    }

    // ---- the compact passthrough marker pair ----

    @Test
    fun `compact passthrough pushes the running marker now and the completed marker after the delay`() = runBlocking {
        val m = mock {
            compactCommandArm = true
            compactRunMarkerDelayMs = 10L
        }
        assertEquals(0, m.appendedRowsForTest().size)
        m.pushCompactRunMarkers()
        val running = m.appendedRowsForTest().values.single()
        val runningMarker = running["marker"] as? JsonObject
        assertEquals("manual", runningMarker?.get("origin")?.toString()?.trim('"'))
        assertEquals("running", runningMarker?.get("status")?.toString()?.trim('"'))
        // the completed marker lands after the (shrunk) delay
        delay(200)
        val rows = m.appendedRowsForTest().values.toList()
        assertEquals(2, rows.size, "running + completed markers both ride appendedRows")
        val completedMarker = rows.last()["marker"] as? JsonObject
        assertEquals("manual", completedMarker?.get("origin")?.toString()?.trim('"'))
        assertEquals("completed", completedMarker?.get("status")?.toString()?.trim('"'))
        assertEquals("compact", completedMarker?.get("type")?.toString()?.trim('"'))
        assertEquals(164_000, completedMarker?.get("tokensBefore")?.toString()?.toIntOrNull())
        assertEquals(38_500, completedMarker?.get("tokensAfter")?.toString()?.toIntOrNull())
    }

    @Test
    fun `compact passthrough decision is unchanged (no queue item, no rejection)`() {
        val m = mock {
            compactCommandArm = true
            inputRoutingMode = "startNow"
        }
        val before = m.revisionForTest()
        assertTrue(m.compactCommandDecision() is MockZCode.CompactDecision.Passthrough)
        assertEquals(before, m.revisionForTest(), "the passthrough decision itself mutates nothing")
        assertEquals(0, m.script.queueItems.orEmpty().size)
    }

    // ---- the stale-family additions (the four pass-23 verbs, all CAS-guarded) ----

    @Test
    fun `stale arm intercepts the four goal-and-queue steering verbs with the host ack shape`() {
        val m = mock {
            casStaleTypes = setOf("setAutoDrain", "setFollowupMode", "pauseGoal", "resumeGoal")
        }
        for (type in listOf("setAutoDrain", "setFollowupMode", "pauseGoal", "resumeGoal")) {
            val ack = m.casStaleAck(type, null)
            assertEquals("stale", ack?.get("status")?.toString()?.trim('"'), type)
            assertEquals("proto.staleRevision", ack?.get("reasonCode")?.toString()?.trim('"'), type)
        }
    }

    @Test
    fun `stale arm never intercepts the compact command (the verified set stands)`() {
        val m = mock {
            casStaleTypes = setOf("setAutoDrain", "setFollowupMode", "pauseGoal", "resumeGoal", "compact")
        }
        assertEquals(null, m.casStaleAck("compact", null), "the compact command is NOT CAS-guarded — Y.B.3")
    }
}
