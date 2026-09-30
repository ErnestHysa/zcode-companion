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
 * §43.2-4.3 repository suites — store-level reconciliation semantics (§15.5/§19.4):
 * snapshots replace atomically; epoch mismatches reject with ZERO mutation; duplicates
 * are idempotent by rowId; the §17.3 per-session bound holds under every write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConversationRepositoryTest {

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
    private fun rowJson(rowId: Long, text: String = "row-$rowId", kind: String = "assistantText") =
        json.parseToJsonElement(
            """{"rowId":$rowId,"turnId":"turn-$rowId","kind":"$kind","text":"$text","state":"complete",
               "createdAt":1.0,"createdAtSeq":$rowId}""",
        ) as JsonObject

    private fun snapshot(epoch: String, ids: LongRange) =
        ConversationStoreOp.SnapshotArrived(
            SessionId("s1"), epoch, ids.map { ConversationRow.Decoder.decode(rowJson(it)) },
        )

    /**
     * FIELD-KILLER #12 (2026-09-30, vc17 field): history pages are a STATELESS,
     * rowId-keyed read-model merge. The desktop ROTATES atLogEpoch routinely (field:
     * storeEpoch=muoigucv… vs pageEpoch=muoj84cf… ten seconds apart on a live
     * conversation) — the old epoch gate discarded EVERY page ("changed on desktop",
     * history dead-ended, 50/50 hit-or-miss). A page carrying a DIFFERENT epoch must
     * still merge by rowId; the meta epoch (governing the live delta pipeline) stays.
     */
    @Test
    fun `page with a rotated epoch still merges by rowId (12)`() = runTest {
        repo.apply(snapshot("epoch-A", 5000L..5039L))
        val op = ConversationStoreOp.PageFetched(
            sessionId = SessionId("s1"),
            logEpoch = "epoch-B-rotated",
            rows = (1L..40L).map { ConversationRow.Decoder.decode(rowJson(it)) },
        )
        val result = repo.apply(op)
        assertTrue(result is StoreApplyResult.Applied, "page must merge despite epoch rotation: $result")
        val rows = repo.observeRows(SessionId("s1")).first()
        assertEquals(80, rows.size, "window + page must coexist")
        assertEquals(1L, rows.first().rowId)
        // The LIVE pipeline epoch (meta) is unchanged by a stateless page read.
        assertEquals("epoch-A", repo.currentEpoch(SessionId("s1")))
    }

    @Test
    fun `snapshot replaces atomically and reports the previous epoch (15-5)`() = runTest {
        val r1 = repo.apply(snapshot("epoch-1", 1L..5L))
        assertTrue(r1 is StoreApplyResult.Replaced && r1.previousEpoch == null)
        assertEquals(5, repo.cachedRowCount(SessionId("s1")))
        assertEquals("epoch-1", repo.currentEpoch(SessionId("s1")))

        val r2 = repo.apply(snapshot("epoch-2", 10L..12L))
        assertTrue(r2 is StoreApplyResult.Replaced && r2.previousEpoch == "epoch-1")
        assertEquals(3, repo.cachedRowCount(SessionId("s1")), "old epoch rows fully discarded")
        assertEquals(listOf(10L, 11L, 12L), repo.observeRows(SessionId("s1")).first().map { it.rowId })
    }

    @Test
    fun `delta op with a stale epoch is REJECTED with zero mutation (15-S9 store level)`() = runTest {
        repo.apply(snapshot("epoch-1", 1L..3L))
        val before = repo.observeRows(SessionId("s1")).first()

        val result = repo.apply(
            ConversationStoreOp.RowUpserted(SessionId("s1"), "epoch-OLD", ConversationRow.Decoder.decode(rowJson(99))),
        )
        assertTrue(result is StoreApplyResult.EpochRejected && result.currentEpoch == "epoch-1")
        assertEquals(before, repo.observeRows(SessionId("s1")).first(), "no merge of ambiguous state")
    }

    @Test
    fun `fresh session adopts the epoch from the first op`() = runTest {
        val result = repo.apply(
            ConversationStoreOp.RowUpserted(SessionId("fresh"), "epoch-A", ConversationRow.Decoder.decode(rowJson(1))),
        )
        assertTrue(result is StoreApplyResult.Applied)
        assertEquals("epoch-A", repo.currentEpoch(SessionId("fresh")))
    }

    @Test
    fun `duplicate upserts are idempotent by rowId (15-S7 store level)`() = runTest {
        repo.apply(snapshot("e", 1L..3L))
        val row = ConversationRow.Decoder.decode(rowJson(2, text = "upserted"))
        repeat(3) { repo.apply(ConversationStoreOp.RowUpserted(SessionId("s1"), "e", row)) }
        assertEquals(3, repo.cachedRowCount(SessionId("s1")))
        assertEquals("upserted", repo.observeRows(SessionId("s1")).first().first { it.rowId == 2L }.raw["text"].toString().trim('"'))
    }

    @Test
    fun `row removal truncates upward - text append patches the cached row`() = runTest {
        repo.apply(snapshot("e", 1L..5L))
        repo.apply(ConversationStoreOp.RowRemoved(SessionId("s1"), "e", fromRowId = 4))
        assertEquals(listOf(1L, 2L, 3L), repo.observeRows(SessionId("s1")).first().map { it.rowId })

        repo.apply(ConversationStoreOp.RowTextAppended(SessionId("s1"), "e", 2, append = " MORE"))
        val texts = repo.observeRows(SessionId("s1")).first().map { it.raw["text"].toString() }
        assertTrue(texts.any { it.contains("row-2 MORE") })

        // append onto a missing row → Dropped (15-S11 store class), no crash
        val dropped = repo.apply(ConversationStoreOp.RowTextAppended(SessionId("s1"), "e", 999, "x"))
        assertEquals(StoreApplyResult.Dropped, dropped)
    }

    @Test
    fun `per-session bound holds - oldest rows evicted beyond 2 windows + 1 page (INV-5)`() = runTest {
        val total = ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION
        repo.apply(snapshot("e", 1L..total.toLong()))
        assertEquals(total, repo.cachedRowCount(SessionId("s1")))

        // one more row arrives → the OLDEST is evicted, newest kept
        repo.apply(ConversationStoreOp.RowUpserted(SessionId("s1"), "e", ConversationRow.Decoder.decode(rowJson(total + 1L))))
        assertEquals(total, repo.cachedRowCount(SessionId("s1")))
        val ids = repo.observeRows(SessionId("s1")).first().map { it.rowId }
        assertEquals((2L..total + 1L), ids.first()..ids.last())
        assertTrue(!ids.contains(1L))
    }

    @Test
    fun `corrupted cached rows drop and COUNT without crashing reads (INV-8)`() = runTest {
        db.conversationDao().putMeta(com.zcode.android.data.db.ConversationSessionMetaEntity("bad", "e", 1))
        db.conversationDao().insertRows(
            listOf(
                com.zcode.android.data.db.ConversationRowEntity("bad", 1, "t", "assistantText", "not json at all", 1),
                com.zcode.android.data.db.ConversationRowEntity("bad", 2, "t", "assistantText", rowJson(2).toString(), 2),
            ),
        )
        val rows = repo.observeRows(SessionId("bad")).first()
        assertEquals(listOf(2L), rows.map { it.rowId })
        assertEquals(1, repo.droppedDecodeCount)
    }
}
