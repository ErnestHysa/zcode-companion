package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.OutboxRepositoryImpl
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.OutboxRepository
import com.zcode.android.core.protocol.v4.UuidV7
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * §43.2-4.4 outbox persistence suites — INV-4 through the PERSISTED path: the minted
 * commandId is stable, illegal transitions throw WITHOUT mutating the row, acks are
 * recorded verbatim, TTL expiry sweeps only live entries, and no path duplicates a
 * server-contacted command.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OutboxRepositoryTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var repo: OutboxRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = OutboxRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private val ep = EndpointId("ep")

    @Test
    fun `accept mints a uuid v7 commandId exactly once and persists ACCEPTED_LOCALLY`() = runTest {
        val id = repo.accept(ep, "s1", "sendText", """{"text":"hi"}""", baseRevision = null, baseLogEpoch = null, issuedAtMs = 1)
        assertTrue(UuidV7.isValidFormat(id.raw), "commandId is uuid v7 (protocol contract)")
        val entry = repo.get(id)
        assertNotNull(entry)
        assertEquals(OutboxStateMachine.State.ACCEPTED_LOCALLY, entry.state)
        assertEquals("sendText", entry.type)
        assertEquals(0, entry.attempts)
        // accept again on the same id is impossible — ids are unique per accept
        val id2 = repo.accept(ep, "s1", "sendText", "{}", null, null, 2)
        assertTrue(id != id2)
    }

    @Test
    fun `INV-4 - illegal transition persists NOTHING`() = runTest {
        val id = repo.accept(ep, null, "stop", "{}", null, null, 1)
        repo.apply(id, OutboxStateMachine.Event.Transmit, atMs = 2)
        // transmit again → machine rejects (double-send class)
        assertFailsWith<OutboxStateMachine.IllegalOutboxTransition> {
            repo.apply(id, OutboxStateMachine.Event.Transmit, atMs = 3)
        }
        val entry = repo.get(id)!!
        assertEquals(OutboxStateMachine.State.TRANSMITTED, entry.state)
        assertEquals(1, entry.attempts, "failed attempt did not touch the row")
    }

    @Test
    fun `full lifecycle with ack detail recorded verbatim`() = runTest {
        val id = repo.accept(ep, "s1", "resolveInteraction", "{}", baseRevision = 7, baseLogEpoch = null, issuedAtMs = 1)
        repo.apply(id, OutboxStateMachine.Event.Transmit, atMs = 2)
        val entry = repo.apply(id, OutboxStateMachine.Event.Ack("accepted", null, "ok", 9), atMs = 3)
        assertEquals(OutboxStateMachine.State.ACKED, entry?.state)
        assertEquals("accepted", entry?.ackStatus)
        assertEquals("ok", entry?.ackMessage)
        assertEquals(9L, entry?.ackRevisionAtDecision)
        assertEquals(0, repo.countByState(ep, OutboxStateMachine.State.TRANSMITTED))
    }

    @Test
    fun `send-lost then user-gated retry keeps the SAME commandId (INV-4)`() = runTest {
        val id = repo.accept(ep, null, "sendText", "{}", null, null, 1)
        repo.apply(id, OutboxStateMachine.Event.Transmit, atMs = 2)
        assertEquals(OutboxStateMachine.State.UNKNOWN, repo.apply(id, OutboxStateMachine.Event.SendLost, 3)?.state)
        // user retries → same row, same commandId, TRANSMITTED again
        val after = repo.apply(id, OutboxStateMachine.Event.UserGatedRetry, atMs = 4)
        assertEquals(OutboxStateMachine.State.TRANSMITTED, after?.state)
        assertEquals(id, after?.commandId)
        assertEquals(2, after?.attempts, "both transmits counted")
    }

    @Test
    fun `TTL sweep expires only live entries (17-5)`() = runTest {
        val live = repo.accept(ep, null, "sendText", "{}", null, null, issuedAtMs = 1_000)
        val acked = repo.accept(ep, null, "stop", "{}", null, null, issuedAtMs = 1_000)
        repo.apply(acked, OutboxStateMachine.Event.Transmit, 1_100)
        repo.apply(acked, OutboxStateMachine.Event.Ack("accepted"), 1_200)

        assertEquals(1, repo.sweepExpired(ep, nowMs = 25 * 60 * 60 * 1000 + 2_000))
        assertEquals(OutboxStateMachine.State.EXPIRED, repo.get(live)?.state)
        assertEquals(OutboxStateMachine.State.ACKED, repo.get(acked)?.state)
    }

    // ---------------------------------------------------------------- N-BE-2

    /**
     * N-BE-2 (vc52 Phase 10): terminal rows (ACKED / FAILED_TERMINAL / FAILED_STALE /
     * EXPIRED) carried the FULL payload (every prompt's text) at rest forever — the
     * recorded receipt was 167 rows in 3 days of rig use with NO delete query
     * anywhere. After the retention window they are purged; live rows of ANY age and
     * fresh terminal rows survive (INV-4: live rows are untouchable; the journal's
     * pendingOutbox projection reads only live states).
     */
    @Test
    fun `terminal sweep purges old terminal rows and keeps live ones (N-BE-2)`() = runTest {
        val liveOld = repo.accept(ep, null, "sendText", """{"text":"still pending"}""", null, null, issuedAtMs = 0)
        val ackedOld = repo.accept(ep, null, "sendText", """{"text":"prompt body at rest"}""", null, null, issuedAtMs = 1_000)
        repo.apply(ackedOld, OutboxStateMachine.Event.Transmit, 1_100)
        repo.apply(ackedOld, OutboxStateMachine.Event.Ack("accepted"), 1_200)
        val failedOld = repo.accept(ep, null, "stop", """{"text":"lost"}""", null, null, issuedAtMs = 2_000)
        repo.apply(failedOld, OutboxStateMachine.Event.Transmit, 2_100)
        repo.apply(failedOld, OutboxStateMachine.Event.Ack("failed"), 2_200) // FAILED_TERMINAL
        // "Fresh" = terminal AFTER the sweep's cutoff (nowMs - ttl = 5_000 here).
        val ackedFresh = repo.accept(ep, null, "stop", "{}", null, null, issuedAtMs = 5_000)
        repo.apply(ackedFresh, OutboxStateMachine.Event.Transmit, 5_100)
        repo.apply(ackedFresh, OutboxStateMachine.Event.Ack("accepted"), 5_200)

        val now = OutboxStateMachine.TERMINAL_RETENTION_MS + 5_000
        val purged = repo.sweepTerminal(ep, nowMs = now)
        assertEquals(2, purged, "the two OLD terminal rows are purged")
        assertNull(repo.get(ackedOld), "the old ACKED row (full prompt text) is gone")
        assertNull(repo.get(failedOld), "the old FAILED_TERMINAL row is gone")
        assertNotNull(repo.get(liveOld), "an OLD LIVE row survives — INV-4 untouchable")
        assertNotNull(repo.get(ackedFresh), "a fresh terminal row survives the window")
        assertEquals(
            0,
            repo.sweepTerminal(ep, nowMs = now),
            "the sweep is idempotent — a second pass purges nothing",
        )
    }

    @Test
    fun `expired rows purge after retention too (N-BE2 - no prompt text lingers)`() = runTest {
        val live = repo.accept(ep, null, "sendText", """{"text":"never acked"}""", null, null, issuedAtMs = 1_000)
        repo.sweepExpired(ep, nowMs = 25 * 60 * 60 * 1000 + 2_000) // EXPIRED at t≈+1d
        assertEquals(OutboxStateMachine.State.EXPIRED, repo.get(live)?.state)
        // One second past the exact boundary — the expiry stamp was 25h+2s.
        val purged = repo.sweepTerminal(ep, nowMs = 25 * 60 * 60 * 1000 + 3_000 + OutboxStateMachine.TERMINAL_RETENTION_MS)
        assertEquals(1, purged, "the expired row's payload does not linger past retention")
        assertNull(repo.get(live))
    }

    @Test
    fun `pendingRefs project the journal pendingOutbox (15-4)`() = runTest {
        val a = repo.accept(ep, null, "sendText", "{}", null, null, 1)
        val b = repo.accept(ep, null, "resolveInteraction", "{}", null, null, 2)
        repo.apply(b, OutboxStateMachine.Event.Transmit, 3)
        repo.apply(b, OutboxStateMachine.Event.Ack("accepted"), 4) // no longer pending

        val refs = repo.pendingRefs(ep)
        assertEquals(listOf(a.raw), refs.map { it.commandId })
        assertEquals("sendText", refs.single().type)
    }

    @Test
    fun `unknown persisted state fails typed (33 storage)`() = runTest {
        val id = repo.accept(ep, null, "stop", "{}", null, null, 1)
        db.outboxDao().upsert(db.outboxDao().get(id.raw)!!.copy(state = "SOMETHING_ELSE"))
        assertFailsWith<com.zcode.android.data.StorageCorruption> { repo.get(id) }
    }

    @Test
    fun `observePending streams live entries only`() = runTest {
        val a = repo.accept(ep, null, "sendText", "{}", null, null, 1)
        val b = repo.accept(ep, null, "stop", "{}", null, null, 2)
        repo.apply(b, OutboxStateMachine.Event.Transmit, 3)
        repo.apply(b, OutboxStateMachine.Event.Ack("accepted"), 4)
        assertEquals(listOf(a.raw), repo.observePending(ep).first().map { it.commandId.raw })
    }
}
