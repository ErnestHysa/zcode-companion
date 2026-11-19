package com.zcode.android.data.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.ConversationRepositoryImpl
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.WorkspaceHookReview
import com.zcode.android.domain.repo.ConversationCacheBounds
import com.zcode.android.domain.repo.ConversationStoreOp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-P36-A + N-BM-1 (pass-0 Phase 8): the REAL manager + REAL store harness for the
 * paging plane.
 *
 * F-P36-A: a page that reaches the floor (total > cache bound) writes hasMore=false
 * through the page response — honest at that instant — but the next bound trim
 * (snapshot / delta append) evicts the fetched HEAD back to the cap, and the
 * session's remaining head rows became unreachable for the whole session (the strip
 * hid while cache min > window top; route re-entry did NOT heal). The fix re-derives
 * the plane from the store at every bound-changing apply.
 *
 * N-BM-1: the strip total was a subscribe-time snapshot ("370 of 339", "420 of 339")
 * — the live extent derivation (maxCachedRowId - windowFirstRowId + 1) keeps it
 * current as rows append.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class Pass0Phase8TrimSurvivalTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var repo: ConversationRepositoryImpl
    private lateinit var manager: ConversationSessionManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ConversationRepositoryImpl(db)
        manager = ConversationSessionManager(
            sessionId = SessionId("sess-paging"),
            endpointId = EndpointId("ep"),
            store = repo,
            journalSink = { _, _, _, _ -> },
            escalate = { _, _, _ -> },
            onHookReviewCaptured = { _: WorkspaceHookReview -> },
            log = ZLog(sink = {}),
        )
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

    private suspend fun storedIds(): List<Long> =
        repo.observeRows(SessionId("sess-paging")).first().map { it.rowId }

    @Test
    fun `F-P36-A - the strip survives the post-page trim - page to floor, delta append, derive`() = runTest {
        val cap = ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION
        // The recorded shape: a 2,001-row session, window = the newest cap rows.
        manager.handle(
            GatewayTopicEvent.Snapshot(
                topic = "conversation/sess-paging",
                sessionId = "sess-paging",
                logEpoch = "epoch-A",
                fromSeq = 0,
                toSeq = 2001,
                rows = rows(1682L..2001L),
                totalCount = 2001,
                firstRowId = 1,
            ),
        )
        assertTrue(manager.hasMoreHistory.value, "more history above the window at open")
        assertEquals(2001L, manager.totalRows.value)

        // The page path (the PORT's shape): repo applies directly; the manager is
        // bypassed — then the port's fix re-derives from the store.
        repo.apply(ConversationStoreOp.PageFetched(SessionId("sess-paging"), "page-epoch", rows(1632L..1681L)))
        manager.rederivePagingFromStore()
        assertTrue(manager.hasMoreHistory.value, "pages above the floor keep the strip")

        // Page to the FLOOR: the cache now reaches the session top → the strip hides
        // (honest at this instant — the pre-fix manager NEVER derived this: it stayed
        // true forever, showing a dead affordance at the true end).
        repo.apply(ConversationStoreOp.PageFetched(SessionId("sess-paging"), "page-epoch", rows(1L..1631L)))
        manager.rederivePagingFromStore()
        assertFalse(
            manager.hasMoreHistory.value,
            "at the floor (cache min == windowFirstRowId) the strip hides",
        )
        assertTrue(repo.cachedRowCount(SessionId("sess-paging")) > cap, "total > cache bound (the trim's precondition)")

        // THE receipted face: a bound trim re-opens history — the delta append trims
        // the fetched head back to the cap. Pre-fix NOTHING re-derived (the VM's page
        // write had poisoned its mirror and the manager's snapshot value never
        // re-emitted); the strip hid while cache min > window top.
        repo.apply(
            ConversationStoreOp.RowUpserted(
                SessionId("sess-paging"), "epoch-A",
                ConversationRow.Decoder.decode(rowJson(2002L)),
            ),
        )
        manager.rederivePagingFromStore()
        val min = repo.minCachedRowId(SessionId("sess-paging"))
        assertTrue(
            min != null && min > 1L,
            "the trim evicted the fetched head — cache min ($min) > windowFirstRowId (1)",
        )
        assertTrue(
            manager.hasMoreHistory.value,
            "F-P36-A: the strip SURVIVES the post-page trim (head rows reachable again)",
        )
    }

    @Test
    fun `F-P36-A - the delta path re-derives through handleDeltas (the trim is manager-visible)`() = runTest {
        manager.handle(
            GatewayTopicEvent.Snapshot(
                topic = "conversation/sess-paging",
                sessionId = "sess-paging",
                logEpoch = "epoch-A",
                fromSeq = 0,
                toSeq = 2001,
                rows = rows(1682L..2001L),
                totalCount = 2001,
                firstRowId = 1,
            ),
        )
        // Page to the floor + the honest hide.
        repo.apply(ConversationStoreOp.PageFetched(SessionId("sess-paging"), "page-epoch", rows(1L..1681L)))
        manager.rederivePagingFromStore()
        assertFalse(manager.hasMoreHistory.value)
        // A LIVE delta (the manager's own path) appends the tail → enforceBound trims
        // the head → the re-derive inside handleDeltas flips the strip back on.
        val appended = ConversationRow.Decoder.decode(rowJson(2002L))
        manager.handle(
            GatewayTopicEvent.Deltas(
                topic = "conversation/sess-paging",
                sessionId = "sess-paging",
                logEpoch = "epoch-A",
                fromSeq = 2001,
                toSeq = 2002,
                ops = listOf(com.zcode.android.domain.reducer.ProjectionOp.RowAppended(appended)),
            ),
        )
        assertTrue(
            manager.hasMoreHistory.value,
            "F-P36-A: the delta-path trim re-opens the strip (cache min > window top)",
        )
    }

    @Test
    fun `N-BM-1 - the total re-derives live as rows append - never the frozen subscribe snapshot`() = runTest {
        manager.handle(
            GatewayTopicEvent.Snapshot(
                topic = "conversation/sess-paging",
                sessionId = "sess-paging",
                logEpoch = "epoch-A",
                fromSeq = 0,
                toSeq = 400,
                rows = rows(341L..400L),
                totalCount = 400,
                firstRowId = 1,
            ),
        )
        assertEquals(400L, manager.totalRows.value)
        // A page lands (older rows — the extent does not change)…
        repo.apply(ConversationStoreOp.PageFetched(SessionId("sess-paging"), "page-epoch", rows(291L..340L)))
        manager.rederivePagingFromStore()
        assertEquals(
            400L, manager.totalRows.value,
            "a pure-backward page does not inflate the total",
        )
        // …then deltas APPEND (the receipted face: the shown total stayed frozen at
        // the snapshot value — "370 of 339" class)…
        repo.apply(
            ConversationStoreOp.RowUpserted(
                SessionId("sess-paging"), "epoch-A",
                ConversationRow.Decoder.decode(rowJson(401L)),
            ),
        )
        manager.rederivePagingFromStore()
        assertEquals(401L, manager.totalRows.value, "the total follows the live extent")
        repo.apply(
            ConversationStoreOp.RowUpserted(
                SessionId("sess-paging"), "epoch-A",
                ConversationRow.Decoder.decode(rowJson(402L)),
            ),
        )
        manager.rederivePagingFromStore()
        assertEquals(402L, manager.totalRows.value)
        // …and a rewind snapshot RESETS the total (authoritative), which the
        // max-merge never contradicts.
        manager.handle(
            GatewayTopicEvent.Snapshot(
                topic = "conversation/sess-paging",
                sessionId = "sess-paging",
                logEpoch = "epoch-B",
                fromSeq = 0,
                toSeq = 100,
                rows = rows(41L..100L),
                totalCount = 100,
                firstRowId = 1,
            ),
        )
        assertEquals(100L, manager.totalRows.value, "a rewind snapshot resets the total")
    }
}
