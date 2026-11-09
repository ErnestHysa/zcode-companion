package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.ConversationRepositoryImpl
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.repo.ConversationCacheBounds
import com.zcode.android.domain.repo.ConversationStoreOp
import com.zcode.android.domain.repo.StoreApplyResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-37-D (vc52 Phase 10): load-earlier was a SELF-EVICTING NO-OP — `Ports.loadOlder`
 * fetches a page before the store's oldest rowId, `applyPage` inserted it, and
 * `enforceBound` kept the NEWEST [ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION],
 * so the page just fetched (the oldest thing in the store) was evicted in the same
 * transaction: insert 50 rows into a full 320-row store → evict exactly those 50. Net
 * zero. The recorded shape (pass-37 scale leg): a 2,001-row snapshot → Room holds
 * exactly rows 1682..2001; a page lands 1632..1681 and must SURVIVE.
 *
 * Bound contract after this fix: a user-driven backward page keeps
 * [page ∪ newest cap]; the §17.3 cap keeps governing the live tail (snapshot/delta
 * paths still evict), so paging sessions are re-bounded by the next snapshot and
 * stay contiguous (a later stream eviction trims the oldest — never a hole).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConversationLoadOlderBoundTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var repo: ConversationRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ConversationRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private val json = Json
    private fun rowJson(rowId: Long) =
        json.parseToJsonElement(
            """{"rowId":$rowId,"turnId":"turn-$rowId","kind":"assistantText","text":"row-$rowId","state":"complete",
               "createdAt":1.0,"createdAtSeq":$rowId}""",
        ) as JsonObject

    private fun rows(ids: LongRange) = ids.map { ConversationRow.Decoder.decode(rowJson(it)) }

    private fun snapshot(epoch: String, ids: LongRange) =
        ConversationStoreOp.SnapshotArrived(SessionId("s1"), epoch, rows(ids))

    private fun page(ids: LongRange) = ConversationStoreOp.PageFetched(SessionId("s1"), "page-epoch", rows(ids))

    private suspend fun storedIds(): List<Long> = repo.observeRows(SessionId("s1")).first().map { it.rowId }

    @Test
    fun `a page over a FULL store survives - the recorded 320-row receipt shape`() = runTest {
        val cap = ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION
        // The recorded shape: the snapshot window is the newest cap rows (ids
        // 1682..2001) of a 2,001-row conversation.
        repo.apply(snapshot("epoch-A", (2001 - cap + 1).toLong()..2001L))
        assertEquals(cap, repo.cachedRowCount(SessionId("s1")))

        val result = repo.apply(page((2001 - cap + 1 - 50).toLong()..(2001L - cap)))
        assertTrue(result is StoreApplyResult.Applied)
        val ids = storedIds()
        // THE recorded defect face: the page vanished in the same transaction.
        assertTrue(
            (2001 - cap + 1 - 50L) in ids.toSet(),
            "the fetched page must survive applyPage (it WAS evicted — the self-evicting no-op)",
        )
        assertEquals(cap + 50, repo.cachedRowCount(SessionId("s1")), "[page ∪ newest cap]")
        assertEquals((2001 - cap + 1 - 50L), ids.first(), "the store stays contiguous — the page is the new bottom")
    }

    @Test
    fun `two loadOlder calls yield two distinct older pages, contiguously`() = runTest {
        val cap = ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION
        repo.apply(snapshot("epoch-A", 1682L..2001L))
        repo.apply(page(1632L..1681L))
        repo.apply(page(1582L..1631L))
        val ids = storedIds()
        assertEquals(cap + 100, repo.cachedRowCount(SessionId("s1")), "both pages coexist with the live tail")
        assertEquals((1582L..2001L).toList(), ids, "contiguous: no eviction hole between the pages and the tail")
    }

    @Test
    fun `a page on a NON-full store still keeps the count sane`() = runTest {
        // 100..319 = 220 rows (< cap) — the page 50..99 lands below and nothing evicts.
        repo.apply(snapshot("epoch-A", 100L..319L))
        repo.apply(page(50L..99L))
        assertEquals(270, repo.cachedRowCount(SessionId("s1")))
        assertEquals(50L, storedIds().first())
    }

    @Test
    fun `the cap still governs the LIVE tail after pages land (snapshot re-bounds)`() = runTest {
        val cap = ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION
        repo.apply(snapshot("epoch-A", 1682L..2001L))
        repo.apply(page(1632L..1681L))
        repo.apply(page(1582L..1631L))
        assertEquals(cap + 100, repo.cachedRowCount(SessionId("s1")))
        // The next authoritative snapshot REPLACES and re-bounds to cap.
        repo.apply(snapshot("epoch-B", 1900L..2001L))
        assertEquals(cap.coerceAtMost(102), repo.cachedRowCount(SessionId("s1")))
        // And a stream delta still evicts to cap on the guarded path.
        repo.apply(snapshot("epoch-C", 1000L..2001L))
        assertEquals(cap, repo.cachedRowCount(SessionId("s1")), "the §17.3 cap is intact for snapshot churn")
        assertEquals(1682L, storedIds().first())
    }

    @Test
    fun `a stream delta over a paged store evicts the OLDEST (page bottoms) — never a hole`() = runTest {
        repo.apply(snapshot("epoch-A", 1682L..2001L))
        repo.apply(page(1632L..1681L))
        // Live churn: one new row upserts at the tail → plain enforceBound runs.
        repo.apply(
            ConversationStoreOp.RowUpserted(SessionId("s1"), "epoch-A", ConversationRow.Decoder.decode(rowJson(2002L))),
        )
        val ids = storedIds()
        assertEquals(
            ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION,
            ids.size,
            "the guarded path keeps the §17.3 cap",
        )
        assertEquals(ids, ids.sorted(), "the store stays ordered — eviction trims the oldest, no interior hole")
    }
}
