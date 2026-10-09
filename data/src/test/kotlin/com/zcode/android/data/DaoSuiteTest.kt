package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ConversationRowEntity
import com.zcode.android.data.db.DraftEntity
import com.zcode.android.data.db.InteractionEntity
import com.zcode.android.data.db.JournalPendingCommandEntity
import com.zcode.android.data.db.JournalStateEntity
import com.zcode.android.data.db.JournalSubscriptionEntity
import com.zcode.android.data.db.OutboxEntity
import com.zcode.android.data.db.TaskEntity
import com.zcode.android.data.db.TaskWorkspaceMetaEntity
import com.zcode.android.data.db.ZcodeDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §43.2-4.2 DAO suites — every table round-trips through its indices and bounds
 * queries (Robolectric, in-memory Room). These pin the §17.3 cache mechanics the
 * repositories rely on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30]) // ATD-class API like the Phase-3 device leg (SDK 37 sandbox needs Java 21)
class DaoSuiteTest {

    private lateinit var db: ZcodeDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun task(i: Int, ws: String = "ws", updatedAt: Long = i.toLong(), pinned: Boolean = false) = TaskEntity(
        endpointId = "ep", workspaceKey = ws, taskId = "t$i", title = "task $i", statusWire = "running",
        mode = null, model = null, archivedAtMs = null, additions = null, deletions = null, files = null,
        pinned = pinned, createdAtMs = updatedAt, updatedAtMs = updatedAt,
    )

    @Test
    fun `task dao roundtrip - ordering stale sweep and cap victims`() = runTest {
        val dao = db.taskDao()
        dao.upsertAll((1..5).map { task(it, updatedAt = it.toLong()) })
        dao.upsertAll(listOf(task(0, updatedAt = 0L, pinned = true)))

        assertEquals(6, dao.count("ep", "ws"))
        // ordered: pinned first, then updatedAt desc
        assertEquals("t0", dao.observe("ep", "ws").first().first().taskId)
        assertEquals("t5", dao.observe("ep", "ws").first()[1].taskId)

        // stale sweep: cutoff keeps only updatedAt >= cutoff
        dao.putMeta(TaskWorkspaceMetaEntity("ep", "ws", "\"etag-1\"", 100))
        assertEquals("\"etag-1\"", dao.meta("ep", "ws")?.etag)
        assertEquals(2, dao.deleteStale("ep", "ws", cutoffMs = 3)) // t1,t2 (< cutoff; t0 pinned survives)
        assertEquals(4, dao.count("ep", "ws"))

        // cap victims: newest-first OFFSET keep → victims are the OLDEST
        dao.upsertAll((10..20).map { task(it, updatedAt = it.toLong()) })
        val victims = dao.beyondCap("ep", "ws", keep = 5)
        assertTrue(victims.contains("t5") && !victims.contains("t20"))
        assertTrue(victims.contains("t0").not(), "pinned rows are never cap victims")
        dao.deleteByIds("ep", "ws", victims)
        assertEquals(6, dao.count("ep", "ws"))
    }

    @Test
    fun `conversation dao - rows ordering removal truncate and bound victims`() = runTest {
        val dao = db.conversationDao()
        dao.putMeta(com.zcode.android.data.db.ConversationSessionMetaEntity("s1", "epoch-1", 1))
        assertEquals("epoch-1", dao.meta("s1")?.logEpoch)
        assertEquals("epoch-1", dao.observeEpochInternal("s1").first())

        dao.insertRows((1L..10L).map { row(it) })
        assertEquals(10, dao.countRows("s1"))
        assertEquals(1L, dao.listRows("s1").first().rowId)
        assertEquals(10L, dao.observeRows("s1").first().last().rowId)

        // row.removed truncates upward
        assertEquals(3, dao.deleteFromRow("s1", fromRowId = 8))
        assertEquals(7, dao.countRows("s1"))

        // bound victims = lowest rowIds beyond the newest keep
        val victims = dao.rowIdsBeyondCap("s1", keep = 4) // newest-first beyond the keep
        assertEquals(listOf(1L, 2L, 3L), victims.sorted())

        // append text on the stored row json
        assertTrue(dao.appendText("s1", 4) { it.replace("\"x\"", "\"y\"") })
        assertTrue(dao.rowJson("s1", 4)!!.contains("\"y\""))
        assertTrue(!dao.appendText("s1", 999) { it }) // missing row → false (Dropped path)

        // full wipe per epoch replace
        assertEquals(7, dao.deleteAllRows("s1"))
        assertEquals(0, dao.countRows("s1"))
    }

    @Test
    fun `interaction dao - open query resolve and delete`() = runTest {
        val dao = db.interactionDao()
        dao.upsert(interaction("i1", resolvedAt = null, createdAt = 10))
        dao.upsert(interaction("i2", resolvedAt = 20, createdAt = 5))
        assertEquals(listOf("i1"), dao.observeOpen("ep").first().map { it.interactionId })

        assertEquals(1, dao.resolve("ep", "i1", lifecycle = "RESOLVED", resolvedAtMs = 30))
        assertEquals(0, dao.observeOpen("ep").first().size)
        assertNotNull(dao.get("ep", "i2")?.resolvedAtMs)

        dao.deleteByIds("ep", listOf("i2"))
        assertEquals(1, dao.count("ep"))
    }

    @Test
    fun `outbox dao - pending window expire and counts`() = runTest {
        val dao = db.outboxDao()
        dao.upsert(outbox("c1", state = "ACCEPTED_LOCALLY", issuedAt = 100))
        dao.upsert(outbox("c2", state = "TRANSMITTED", issuedAt = 200))
        dao.upsert(outbox("c3", state = "UNKNOWN", issuedAt = 300))
        dao.upsert(outbox("c4", state = "ACKED", issuedAt = 400))
        dao.upsert(outbox("c5", state = "FAILED_RETRYABLE", issuedAt = 150))

        assertEquals(setOf("c1", "c2", "c3", "c5"), dao.liveList("ep").map { it.commandId }.toSet())
        assertEquals(1, dao.countByState("ep", "ACKED"))

        // TTL sweep: live entries (incl. FAILED_RETRYABLE, §17.5) older than cutoff expire
        assertEquals(3, dao.expireOlderThan("ep", cutoffMs = 250, atMs = 1000))
        assertEquals("EXPIRED", dao.get("c1")?.state)
        assertEquals("EXPIRED", dao.get("c2")?.state)
        assertEquals("EXPIRED", dao.get("c5")?.state)
        assertEquals("UNKNOWN", dao.get("c3")?.state)
    }

    @Test
    fun `journal dao - replace semantics per snapshot`() = runTest {
        val dao = db.journalDao()
        dao.putState(JournalStateEntity("ep", 1.0, "{}", 1, 1))
        dao.insertSubscriptions(
            listOf(
                JournalSubscriptionEntity("ep", "conversation/a", "a", 5, "e1"),
                JournalSubscriptionEntity("ep", "conversation/b", "b", 6, "e1"),
            ),
        )
        dao.insertPending(listOf(JournalPendingCommandEntity("c1", "ep", "sendText", "c1", 1)))

        // second snapshot: subscription b gone, c replaced — clear+insert must not leave residue
        dao.clearSubscriptions("ep")
        dao.insertSubscriptions(listOf(JournalSubscriptionEntity("ep", "conversation/a", "a", 9, "e2")))
        dao.clearPending("ep")
        dao.insertPending(listOf(JournalPendingCommandEntity("c2", "ep", "stop", "c2", 2)))

        assertEquals(listOf("conversation/a"), dao.subscriptions("ep").map { it.topic })
        assertEquals(9, dao.subscriptions("ep").single().afterSeq)
        assertEquals(listOf("c2"), dao.pending("ep").map { it.commandId })

        dao.clearEndpoint("ep")
        assertNull(dao.state("ep"))
    }

    @Test
    fun `draft dao - upsert overwrite and delete`() = runTest {
        val dao = db.draftDao()
        dao.upsert(DraftEntity("ep", "ws", "t1", "v1", 1))
        dao.upsert(DraftEntity("ep", "ws", "t1", "v2", 2))
        assertEquals("v2", dao.get("ep", "ws", "t1")?.text)
        assertEquals("v2", dao.observe("ep", "ws", "t1").first()?.text)
        assertEquals(1, dao.delete("ep", "ws", "t1"))
        assertNull(dao.get("ep", "ws", "t1"))
    }

    // ------------------------------------------------------------------ fixtures

    private fun row(rowId: Long) = ConversationRowEntity(
        sessionId = "s1", rowId = rowId, turnId = "turn", kind = "assistantText",
        json = """{"rowId":$rowId,"turnId":"turn","kind":"assistantText","text":"x","state":"complete",
                   "createdAt":1.0,"createdAtSeq":$rowId}""",
        createdAtSeq = rowId,
    )

    private fun interaction(id: String, resolvedAt: Long?, createdAt: Long) = InteractionEntity(
        endpointId = "ep", interactionId = id, sessionId = "s1", kind = "PERMISSION",
        lifecycle = if (resolvedAt == null) "REQUESTED" else "RESOLVED", toolName = "Bash",
        payloadJson = "{}", createdAtMs = createdAt, resolvedAtMs = resolvedAt,
    )

    private fun outbox(id: String, state: String, issuedAt: Long) = OutboxEntity(
        commandId = id, endpointId = "ep", sessionId = "s1", type = "stop", payloadJson = "{}",
        baseRevision = null, baseLogEpoch = null, issuedAtMs = issuedAt, state = state, attempts = 0,
        lastTransitionAtMs = issuedAt, ackStatus = null, ackReasonCode = null, ackMessage = null,
        ackRevisionAtDecision = null,
    )
}
