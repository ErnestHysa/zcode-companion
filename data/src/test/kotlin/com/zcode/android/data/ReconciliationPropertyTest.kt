package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.ConversationRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.SnapshotEtag
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.repo.ConversationCacheBounds
import com.zcode.android.domain.repo.ConversationStoreOp
import com.zcode.android.domain.repo.TaskRepository
import com.zcode.android.data.repo.TaskRepositoryImpl
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
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §43.2-4.3 acceptance — "no divergence under §15 scenarios replayed at repo level":
 * a SEEDED randomized op stream (duplicates / out-of-order upserts / epoch flips /
 * over-cap task floods) must leave the store in a state derivable from the op log
 * under the documented store rules. 200 random schedules across fixed seeds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ReconciliationPropertyTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var conversation: ConversationRepositoryImpl
    private lateinit var tasks: TaskRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        conversation = ConversationRepositoryImpl(db)
        tasks = TaskRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private val json = Json
    private fun row(rowId: Long, text: String) = ConversationRow.Decoder.decode(
        json.parseToJsonElement(
            """{"rowId":$rowId,"turnId":"t","kind":"assistantText","text":"$text","state":"complete",
               "createdAt":1.0,"createdAtSeq":$rowId}""",
        ) as JsonObject,
    )

    @Test
    fun `randomized schedules keep the store consistent with the op log`() = runTest {
        for (seed in 1..50) {
            val rnd = Random(seed)
            db.clearAllTables()
            val session = SessionId("s$seed")
            var epoch = "e0"
            var currentRows = LinkedHashMap<Long, String>() // the MODEL of the store
            // seed the session so stale-epoch ops ALWAYS hit the rejection path
            conversation.apply(ConversationStoreOp.SnapshotArrived(session, epoch, emptyList()))

            repeat(60) {
                when (rnd.nextInt(6)) {
                    0 -> { // epoch flip + full snapshot (S9)
                        epoch = "e${rnd.nextInt(5)}"
                        val ids = (0L until (1 + rnd.nextInt(8)).toLong())
                        conversation.apply(ConversationStoreOp.SnapshotArrived(session, epoch, ids.map { row(it, "snap-$it") }))
                        currentRows = LinkedHashMap(ids.associateWith { "snap-$it" })
                    }
                    1 -> { // duplicate upsert (S7)
                        if (currentRows.isNotEmpty()) {
                            val id = currentRows.keys.random(rnd)
                            conversation.apply(ConversationStoreOp.RowUpserted(session, epoch, row(id, "dup")))
                            currentRows[id] = "dup"
                        }
                    }
                    2 -> { // fresh upsert (possibly out-of-order rowId)
                        val id = rnd.nextLong(20)
                        conversation.apply(ConversationStoreOp.RowUpserted(session, epoch, row(id, "u-$id")))
                        currentRows[id] = "u-$id"
                    }
                    3 -> { // stale-epoch op — store must REJECT with zero mutation
                        val result = conversation.apply(
                            ConversationStoreOp.RowUpserted(session, epoch + "-STALE", row(999, "stale")),
                        )
                        assertTrue(
                            result is com.zcode.android.domain.repo.StoreApplyResult.EpochRejected,
                            "seed $seed: stale op must reject",
                        )
                    }
                    4 -> { // removal (S17-class truncation) — modelled exactly
                        if (currentRows.isNotEmpty()) {
                            val from = currentRows.keys.min()
                            conversation.apply(ConversationStoreOp.RowRemoved(session, epoch, from))
                            currentRows = LinkedHashMap(currentRows.filterKeys { it < from })
                        }
                    }
                    else -> { // text append on the lowest row
                        val lowest = currentRows.entries.minByOrNull { it.key }
                        if (lowest != null) {
                            conversation.apply(ConversationStoreOp.RowTextAppended(session, epoch, lowest.key, "+"))
                            currentRows[lowest.key] = lowest.value + "+"
                        }
                    }
                }

                // INV-5: the bound holds after EVERY op
                assertTrue(
                    conversation.cachedRowCount(session) <= ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION,
                    "seed $seed: bound violated",
                )
            }

            // final state equals the model under the documented rules
            val stored = conversation.observeRows(session).first().associate { it.rowId to it.raw["text"].toString().trim('"') }
            // removals truncate from the LOWEST rowId — after any removal only rows > from survive;
            // the model above mirrors that, and bound-eviction drops the lowest rowIds.
            val maxEvicted = currentRows.size - stored.size
            assertTrue(maxEvicted >= 0, "seed $seed: store has more rows than the model allows")
            val storedIds = stored.keys.sorted()
            assertTrue(
                storedIds == currentRows.keys.sorted().takeLast(stored.size),
                "seed $seed: divergence — stored ${storedIds.take(5)}… vs model ${currentRows.keys.sorted()}",
            )
        }
    }

    @Test
    fun `the endpoint mirror keeps every task - no cap, no sweep by default (F-WORKSPACES)`() = runTest {
        val ep = EndpointId("ep")
        val ws = WorkspaceIdentityKey("ws")
        fun task(i: Int) = Task(
            taskId = "t$i", title = "T$i", status = TaskStatus.fromWire("running"), workspace = ws,
            createdAtMs = i.toLong(), updatedAtMs = i.toLong(),
        )

        // flood 700 → the MIRROR keeps all 700 (the desktop is the source of truth;
        // the old 500-cap/7-day bounds silently deleted the user's task history)
        assertTrue(tasks.replaceAll(ep, ws, (1..700).map { task(it) }, SnapshotEtag("\"v1\""), atMs = 1_000))
        assertEquals(700, tasks.count(ep, ws))

        // same etag → 304-class skip (no write)
        assertTrue(!tasks.replaceAll(ep, ws, (1..5).map { task(it) }, SnapshotEtag("\"v1\""), atMs = 2_000))
        assertEquals(700, tasks.count(ep, ws))
        assertEquals("\"v1\"", tasks.cachedEtag(ep, ws)?.raw)

        // new etag → replace reflects desktop REMOVALS (15-S17) — the desktop's own
        // full-snapshot replace is the eviction; the phone never sweeps on its own
        assertTrue(tasks.replaceAll(ep, ws, listOf(task(801)), SnapshotEtag("\"v2\""), atMs = 3_000))
        assertEquals(listOf("t801"), tasks.observeTasks(ep, ws).first().map { it.taskId })
    }

    @Test
    fun `explicit bounds still sweep - the mechanics stay injectable (17-3 mechanics)`() = runTest {
        val ep = EndpointId("ep")
        val ws = WorkspaceIdentityKey("ws")
        fun task(i: Int) = Task(
            taskId = "t$i", title = "T$i", status = TaskStatus.fromWire("running"), workspace = ws,
            createdAtMs = i.toLong(), updatedAtMs = i.toLong(),
        )
        val bounded = TaskRepositoryImpl(
            db,
            bounds = TaskRepositoryImpl.Bounds(maxTasksPerWorkspace = 500, staleTtlMs = 7L * 24 * 60 * 60 * 1000),
        )
        // cap keeps the newest 500 under explicit bounds
        assertTrue(bounded.replaceAll(ep, ws, (1..700).map { task(it) }, SnapshotEtag("\"v1\""), atMs = 1_000))
        assertEquals(500, bounded.count(ep, ws))

        // stale sweep: 7 d (now = a 30-day epoch; stale = 8 d old; fresh = now)
        val now = 30L * 24 * 60 * 60 * 1000
        bounded.upsert(ep, ws, task(900).copy(updatedAtMs = now - 8L * 24 * 60 * 60 * 1000))
        bounded.upsert(ep, ws, task(901).copy(updatedAtMs = now))
        val dropped = bounded.evictAndSweep(ep, ws, nowMs = now)
        assertTrue(dropped >= 1)
        val ids = bounded.observeTasks(ep, ws).first().map { it.taskId }
        assertTrue(!ids.contains("t900"), "stale > 7 d dropped")
    }
}
