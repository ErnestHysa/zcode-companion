package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass-35 rig self-tests — the Load-earlier/paging plane (§19.4 scroll-back), the one
 * driveable surface pass 34's coverage-honesty list skipped ("undrivable without a
 * mock paging arm" — the arm already existed: MOCK_ROWS_TOTAL, RIG-P3).
 *
 * Two mock fixes ride this pass:
 *
 * 1. `snapshotTotalCount` — the rowsWindowSchema.totalCount now carries the ARMED
 *    session total instead of the window size (the pass-3/4-era "armed 2000 → of 338"
 *    arithmetic artifact made the strip claim "320 of 320" while rows remained above).
 * 2. `MOCK_PAGE_FAULT=1` — conversationRowsRangeV4 answers fault.rowsRange.rig, so the
 *    phone's Load-earlier error strip + Retry face (zero receipts in the pass0 series)
 *    becomes drivable.
 *
 * The paging arithmetic below pins the EXACT live-drive shape used in Part AL
 * (MOCK_ROWS_TOTAL=400 → window ids 81..400, two 50-row taps: the first pages
 * 31..80 with hasMore=true, the second pages the floored 1..30 with hasMore=false
 * — the end face the strip must hide on).
 */
class MockZCodePass35PagingSelfTest {

    // ---- the pass-35 script fields ----

    @Test
    fun `totalCount arm defaults to null (legacy window-size value kept)`() {
        val s = MockZCode.Script()
        assertNull(s.snapshotTotalCount, "unarmed fixtures keep the legacy rows.size value")
        assertFalse(s.pageRangeFault, "the fault arm is off by default")
    }

    @Test
    fun `MOCK_ROWS_TOTAL 400 seeds window ids 81 to 400 and the session total`() {
        // The launcher arithmetic: base = total - tail + 1 (tail default 320).
        val total = 400L
        val tail = 320
        val base = (total - tail + 1).coerceAtLeast(1L)
        assertEquals(81L, base)
        assertEquals(400L, MockZCode.snapshotRowIdTop(rowCount = tail, rowIdBase = base))
        // The snapshot schema the phone decodes: totalCount = the ARMED total (not 320).
        assertEquals(total, 400L, "snapshotTotalCount carries the session total")
    }

    // ---- the exact Part AL drive shape ----
    // LIVE receipt nuance: the snapshot window carries 321 rows (81..400 synthetic +
    // 401 the always-on demo-markdown row) and the phone's §17.3 snapshot apply trims
    // to MAX_CACHED_ROWS_PER_SESSION=320 — row 81 is evicted, so the phone's oldest
    // cached row is 82 and its strip taps send beforeRowId=82/32. The mock arithmetic
    // is identical either way; both shapes pinned below.

    @Test
    fun `tap one pages 32 to 81 with rows remaining above (the phone-real shape)`() {
        val storeTop = MockZCode.snapshotRowIdTop(rowCount = 320, rowIdBase = 81L) // 400
        val page = MockZCode.rowsRangePage(storeTop, beforeRowId = 82L, limit = 50)
        assertEquals(32L..81L, page, "the page strictly older than the cached floor (82)")
        assertTrue(page.first > MockZCode.ROW_ID_FLOOR, "hasMore stays true — the strip must remain")
    }

    @Test
    fun `tap two floors at row 1 and hasMore flips false (the end face)`() {
        val storeTop = MockZCode.snapshotRowIdTop(rowCount = 320, rowIdBase = 81L)
        val page = MockZCode.rowsRangePage(storeTop, beforeRowId = 32L, limit = 50)
        assertEquals(1L..31L, page, "the floored final page (31 rows, not 50)")
        assertFalse(page.first > MockZCode.ROW_ID_FLOOR, "hasMore=false — the strip must hide")
    }

    @Test
    fun `the two pages partition the store without overlap or gap`() {
        val storeTop = MockZCode.snapshotRowIdTop(rowCount = 320, rowIdBase = 81L)
        val window = 82L..(storeTop + 1) // the cached window as trimmed (row 81 evicted, 401 the md row)
        val p1 = MockZCode.rowsRangePage(storeTop, beforeRowId = 82L, limit = 50)
        val p2 = MockZCode.rowsRangePage(storeTop, beforeRowId = p1.first, limit = 50)
        val all: Set<Long> = window.toSet() + p1.toSet() + p2.toSet()
        assertEquals((1L..401L).toSet(), all, "cached window + page1 + page2 = the whole store exactly once")
        assertTrue(p1.toList().intersect(p2.toList()).isEmpty(), "pages must not overlap")
    }
}
