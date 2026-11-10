package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RIG-P3 (vc52 Phase 10 / F-37-D) mock self-test: the rowsRange handler must serve a
 * real tail-window PAGE — the rows STRICTLY OLDER than beforeRowId, keyed to the same
 * id space as the rowsWindowSchema snapshot (ids 1..storeTop) — with hasMore true while
 * rows remain above the page. The old handler ignored beforeRowId (every call returned
 * the newest rows 1..limit with hasMore=false), so the phone's Load-earlier leg could
 * never page backwards and two calls returned the SAME page.
 */
class MockZCodeRowsRangeSelfTest {

    private val TOP = 2001L // MOCK_ROWS_TOTAL=2001 → the store is row ids 1..2001

    @Test
    fun `two consecutive paging calls yield two distinct adjacent older pages`() {
        val first = MockZCode.rowsRangePage(TOP, beforeRowId = 1682, limit = 50)
        assertEquals(1632L..1681L, first, "the page above the cached window (1682..2001)")
        val second = MockZCode.rowsRangePage(TOP, beforeRowId = 1682 - 50, limit = 50)
        assertEquals(1582L..1631L, second, "the NEXT page must be strictly older and adjacent")
        assertTrue(first.toList().intersect(second.toList()).isEmpty(), "pages must not overlap")
    }

    @Test
    fun `page clamps at the store floor and reports rows remain above`() {
        val page = MockZCode.rowsRangePage(TOP, beforeRowId = 30, limit = 50)
        assertEquals(1L..29L, page)
        assertEquals(1L, page.first)
    }

    @Test
    fun `null beforeRowId serves the newest tail page (production range-read semantics)`() {
        assertEquals(1992L..2001L, MockZCode.rowsRangePage(TOP, beforeRowId = null, limit = 10))
    }

    @Test
    fun `page never crosses the store top`() {
        assertEquals(1992L..2001L, MockZCode.rowsRangePage(TOP, beforeRowId = 3000, limit = 10))
    }

    @Test
    fun `hasMore is false only at the floor`() {
        val mid = MockZCode.rowsRangePage(TOP, beforeRowId = 1682, limit = 50)
        assertTrue(mid.first > MockZCode.ROW_ID_FLOOR, "rows remain above a mid-store page")
        val bottom = MockZCode.rowsRangePage(TOP, beforeRowId = 1, limit = 50)
        assertTrue(bottom.isEmpty(), "nothing is older than the floor row")
    }

    @Test
    fun `snapshotRowIdTop matches the snapshot id scheme`() {
        assertEquals(2001L, MockZCode.snapshotRowIdTop(rowCount = 2001, rowIdBase = 1L))
        assertEquals(2001L, MockZCode.snapshotRowIdTop(rowCount = 320, rowIdBase = 1682L))
    }

    @Test
    fun `the tail-window arm pages THROUGH the window base (the exit-gate shape)`() {
        // MOCK_ROWS_TOTAL=2001 + MOCK_ROWS_WINDOW=320: window ids 1682..2001.
        val first = MockZCode.rowsRangePage(storeTop = MockZCode.snapshotRowIdTop(320, 1682L), beforeRowId = 1682, limit = 50)
        assertEquals(1632L..1681L, first, "the page immediately older than the window base")
        val second = MockZCode.rowsRangePage(storeTop = MockZCode.snapshotRowIdTop(320, 1682L), beforeRowId = 1632, limit = 50)
        assertEquals(1582L..1631L, second, "the next page must continue downward, NOT jump below the store")
    }

    @Test
    fun `limit is floored at one (never a negative or zero-width page)`() {
        assertEquals(1681L..1681L, MockZCode.rowsRangePage(TOP, beforeRowId = 1682, limit = 0))
    }
}
