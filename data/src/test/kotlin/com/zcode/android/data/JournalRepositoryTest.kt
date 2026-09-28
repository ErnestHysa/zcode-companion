package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.JournalDao
import com.zcode.android.data.db.JournalPendingCommandEntity
import com.zcode.android.data.db.JournalStateEntity
import com.zcode.android.data.db.JournalSubscriptionEntity
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.JournalRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.journal.RecoveryJournal
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * §43.2-4.5 journal suites — transactional whole-snapshot writes (§15.4) with
 * crash-consistency (§32.3): a failure mid-write leaves the PREVIOUS COMPLETE
 * snapshot, never a torn one; cold-start replay (§15-S2) restores the journal;
 * clear destroys it with the endpoint identity (§14.4.1 Closed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class JournalRepositoryTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var repo: JournalRepositoryImpl
    private val ep = EndpointId("ep")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = JournalRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private fun journal(afterSeq: Long, epoch: String, pending: Int = 1) = RecoveryJournal(
        endpointId = ep.raw,
        helloServerTimeMs = 1234.5,
        capabilitiesSeen = kotlinx.serialization.json.Json.parseToJsonElement("""{"nativeDialogs":true}""") as kotlinx.serialization.json.JsonObject,
        subscriptions = listOf(RecoveryJournal.Subscription("conversation/s1", "s1", afterSeq, epoch)),
        pendingOutbox = (1..pending).map { RecoveryJournal.PendingCommandRef("cmd-$it", "sendText", "cmd-$it", it.toLong()) },
        lastAppliedPerTopic = mapOf("conversation/s1" to afterSeq),
    )

    @Test
    fun `record and replay - whole snapshot replaces atomically`() = runTest {
        repo.record(journal(afterSeq = 10, epoch = "e1"))
        repo.record(journal(afterSeq = 20, epoch = "e2", pending = 2))

        val loaded = repo.load(ep)!!
        assertEquals(20, loaded.subscriptions.single().afterSeq)
        assertEquals("e2", loaded.subscriptions.single().logEpoch)
        assertEquals(2, loaded.pendingOutbox.size, "old pending list fully replaced, no residue")
        assertEquals(mapOf("conversation/s1" to 20L), loaded.lastAppliedPerTopic)
        assertEquals(RecoveryJournal.JOURNAL_VERSION, loaded.version)
    }

    @Test
    fun `crash mid-write leaves the previous COMPLETE snapshot (32-3 atomicity)`() = runTest {
        repo.record(journal(afterSeq = 10, epoch = "e1"))

        // Deterministic mid-transaction fault: a SQLite trigger ABORTs the FIRST
        // journal_subscriptions insert — i.e., AFTER the journal_state upsert inside
        // the same withTransaction block. Room rolls the whole transaction back.
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER crash_journal_subscriptions BEFORE INSERT ON journal_subscriptions
               BEGIN SELECT RAISE(ABORT, 'simulated crash mid-write'); END""",
        )
        try {
            val thrown = runCatching {
                kotlinx.coroutines.runBlocking { repo.record(journal(afterSeq = 99, epoch = "e-crash")) }
            }.exceptionOrNull()
            assertTrue(thrown != null, "mid-write fault must surface (transaction aborts)")
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER crash_journal_subscriptions")
        }

        // rollback: the previous snapshot is INTACT — no torn e-crash state
        val loaded = repo.load(ep)!!
        assertEquals(10, loaded.subscriptions.single().afterSeq)
        assertEquals("e1", loaded.subscriptions.single().logEpoch)
        assertTrue(!loaded.pendingOutbox.any { it.commandId.startsWith("cmd-9") || it.commandId == "crash" })
    }

    @Test
    fun `cold-start replay from a closed file db (15-S2 process death)`() = runTest {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "journal-replay-${System.nanoTime()}")
        dir.mkdirs()
        val name = "journal.db"
        try {
            val first = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZcodeDatabase::class.java, File(dir, name).absolutePath)
                .allowMainThreadQueries().build()
            JournalRepositoryImpl(first).record(journal(afterSeq = 42, epoch = "e9"))
            first.close() // "process death"

            val second = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZcodeDatabase::class.java, File(dir, name).absolutePath)
                .allowMainThreadQueries().build()
            val replayed = JournalRepositoryImpl(second).load(ep)
            second.close()

            assertTrue(replayed != null)
            assertEquals(42, replayed!!.lastAppliedPerTopic["conversation/s1"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clear destroys the journal with the endpoint identity`() = runTest {
        repo.record(journal(afterSeq = 5, epoch = "e"))
        repo.clear(ep)
        assertNull(repo.load(ep))
    }
}
