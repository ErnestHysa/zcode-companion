package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass-17 mock-rig self-tests — the two debts/arms this pass fixes:
 *  - debt #17: the turn-stall arm's rows (live frames + silent backlog) join the
 *    authoritative `appendedRows` tail so a resync SNAPSHOT carries them (the
 *    desktop's server would include its own pushed rows; before the fix the
 *    watchdog's force-resync made the stall rows vanish from the timeline);
 *  - debt #18: MOCK_INPUT_ROUTING pins the state's inputRouting mode (the desktop
 *    enum: startNow|enqueue|guide|reject|choice) with a STICKY flag that suppresses
 *    the send/stop handler flips — the phone's held-queue CHOICE disposition dialog
 *    (G50) becomes drivable on the rig. Without the arm the flips are unchanged.
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass17RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 17 }), expectedSid = "d_p17")
        block?.let { m.script.it() }
        return m
    }

    // ---- debt #17: stall rows are authoritative (ride the snapshot tail) ----

    @Test
    fun `silent backlog rows join appendedRows (debt #17)`() {
        val m = mock()
        m.activeSessionForTest("sess-stall")
        val rowId = m.journalStallBacklogRowForTest("sess-stall", "stall-leg backlog frame 1 — journaled, never emitted")
        assertTrue(rowId > 0)
        val rows = m.appendedRowsForTest()
        assertTrue(rows.containsKey(rowId.toString()), "the silent journalled row must join appendedRows")
        assertTrue(rows.getValue(rowId.toString()).toString().contains("stall-leg backlog frame 1"))
    }

    @Test
    fun `live stall frames join appendedRows before their delta push (debt #17)`() {
        // The live-frame path adds to appendedRows inside the armed coroutine before
        // pushRowDelta — pinned via the same seam the snapshot merge reads from. The
        // coroutine's first frame lands within the arm's everyMs window; poll briefly.
        val m = mock {
            turnStall = true
            turnStallLiveFrames = 1
            turnStallEveryMs = 150L
        }
        m.activeSessionForTest("sess-stall")
        m.armTurnStallOnSubscribe()
        val deadline = System.currentTimeMillis() + 5_000
        var saw = false
        while (System.currentTimeMillis() < deadline && !saw) {
            saw = m.appendedRowsForTest().values.any { it.toString().contains("stall-leg live frame 1") }
            if (!saw) Thread.sleep(50)
        }
        assertTrue(saw, "the stall arm's live frame must join appendedRows (debt #17)")
    }

    // ---- debt #18: the sticky inputRouting arm ----

    @Test
    fun `routing flips apply when the arm is absent`() {
        val m = mock()
        assertTrue(!m.script.inputRoutingArmSticky, "no arm by default")
        m.applyInputRoutingFlip("enqueue")
        assertEquals("enqueue", m.script.inputRoutingMode, "the send flip applies")
        m.applyInputRoutingFlip("startNow")
        assertEquals("startNow", m.script.inputRoutingMode, "the stop flip applies")
    }

    @Test
    fun `routing flips are suppressed under the sticky arm (debt #18)`() {
        val m = mock()
        m.script.inputRoutingMode = "choice" // what the arm would pin
        m.script.inputRoutingArmSticky = true
        m.applyInputRoutingFlip("enqueue")
        assertEquals("choice", m.script.inputRoutingMode, "the send flip is suppressed")
        m.applyInputRoutingFlip("startNow")
        assertEquals("choice", m.script.inputRoutingMode, "the stop flip is suppressed")
    }

    @Test
    fun `sticky flag defaults off and the armed mode survives both flips`() {
        val m = mock { inputRoutingArmSticky = true; inputRoutingMode = "reject" }
        m.applyInputRoutingFlip("enqueue")
        m.applyInputRoutingFlip("startNow")
        assertEquals("reject", m.script.inputRoutingMode)
    }
}
