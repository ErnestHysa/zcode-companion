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
