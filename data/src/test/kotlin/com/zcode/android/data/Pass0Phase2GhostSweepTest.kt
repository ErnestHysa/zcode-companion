package com.zcode.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.InteractionRepositoryImpl
import com.zcode.android.data.session.CommandSender
import com.zcode.android.data.session.InteractionCoordinator
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-P6-A's STORE half (pass-0 Phase 2; the card half rides F-P0-B): generation-
 * orphaned ghosts accumulate MONOTONICALLY — the pass-8/9 fixtures
 * (/tmp/pass8/db/p8-2.db, /tmp/p9/db/p9-4.db) carried 5 unresolved rows whose
 * desktop generation was long dead (oldest 2026-11-12 19:59, census 26 h later),
 * all answerable, and the broker posted shade notifications with no honest
 * answer surface behind them.
 *
 * The pin runs against the REAL Room repo (the F-36-B lesson: recording fakes
 * cannot see the sweep's column writes): OPEN rows older than the store's open
 * TTL expire in the sweep — terminal, stamped, unanswerable — while fresh rows
 * survive, and the census (observeOpen) reaches 0 orphans.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class Pass0Phase2GhostSweepTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var interactions: InteractionRepositoryImpl
    private val ep = EndpointId("ep")

    /** 2026-11-12-style ghost census: now minus the recorded 26 h. */
    private val now = 1_800_000_000_000L
    private val ghostAgeMs = 26L * 60 * 60 * 1000

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        interactions = InteractionRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private fun request(id: String, createdAt: Long) = PermissionRequest(
        interactionId = InteractionId(id),
        sessionId = "synth-task-1",
        toolName = "Bash",
        reason = "echo synthetic",
        options = listOf(PermissionOption("allowOnce"), PermissionOption("deny")),
        createdAtMs = createdAt,
        raw = buildJsonObject {
            put("interactionId", id)
            put("toolName", "Bash")
        },
    )

    @Test
    fun `F-P6-A - generation-orphaned OPEN rows expire in the sweep - the census reaches zero orphans`() = runTest {
        // The pass-8/9 ghost shape: 4 open rows from a dead generation + 1 fresh.
        repeat(4) { i ->
            interactions.upsertPermission(request("ghost-$i", now - ghostAgeMs), ep, InteractionLifecycle.DISPLAYED)
        }
        interactions.upsertPermission(request("fresh-1", now - 60_000), ep, InteractionLifecycle.REQUESTED)

        val dropped = interactions.sweep(ep, now)
        assertEquals(4, dropped)

        // The ghosts are EXPIRED with a stamp (terminal, unanswerable).
        for (i in 0 until 4) {
            val stored = interactions.loadStored(ep, InteractionId("ghost-$i"))
            assertNotNull(stored)
            assertEquals(InteractionLifecycle.EXPIRED, stored!!.record.lifecycle)
            assertNotNull("the expired ghost carries a resolution stamp", stored.record.resolvedAtMs)
        }
        // The census: only the fresh row remains open — zero orphans.
        val open = interactions.observeOpen(ep).first()
        assertEquals(setOf("fresh-1"), open.map { it.interactionId.raw }.toSet())
        // The fresh row is untouched.
        val fresh = interactions.loadStored(ep, InteractionId("fresh-1"))
        assertEquals(InteractionLifecycle.REQUESTED, fresh!!.record.lifecycle)
    }

    @Test
    fun `F-P6-A - an expired ghost is unanswerable from the inbox (AlreadyResolved, no wire)`() = runTest {
        interactions.upsertPermission(request("ghost-old", now - ghostAgeMs), ep, InteractionLifecycle.DISPLAYED)
        interactions.sweep(ep, now)

        val sender = RecordingSender()
        val coordinator = InteractionCoordinator(
            endpointId = ep,
            interactions = interactions,
            outbox = NoopOutbox(),
            commands = sender,
        )
        val outcome = coordinator.submitFromStore(InteractionId("ghost-old"), "allowOnce")
        assertTrue(
            "a swept ghost must answer as the typed AlreadyResolved; got $outcome",
            outcome is InteractionCoordinator.SubmitOutcome.AlreadyResolved,
        )
        assertTrue("no wire call may ride an expired ghost", sender.sent.isEmpty())
    }

    private class RecordingSender : CommandSender {
        val sent = mutableListOf<String>()
        override suspend fun send(
            type: String,
            sessionId: String?,
            payload: JsonObject,
            baseRevision: Long?,
            baseLogEpoch: String?,
            commandId: String,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): CommandAck {
            sent.add(type)
            return CommandAck(commandId, "accepted", 1, null, null, null)
        }
    }

    private class NoopOutbox : OutboxRepository {
        override fun observePending(endpointId: EndpointId) = kotlinx.coroutines.flow.flowOf(emptyList<com.zcode.android.domain.outbox.OutboxEntry>())
        override suspend fun accept(
            endpointId: EndpointId,
            sessionId: String?,
            type: String,
            payloadJson: String,
            baseRevision: Long?,
            baseLogEpoch: String?,
            issuedAtMs: Long,
        ) = com.zcode.android.domain.ids.CommandId("cmd-1")
        override suspend fun apply(
            commandId: com.zcode.android.domain.ids.CommandId,
            event: com.zcode.android.domain.outbox.OutboxStateMachine.Event,
            atMs: Long,
        ): com.zcode.android.domain.outbox.OutboxEntry? = null
        override suspend fun get(commandId: com.zcode.android.domain.ids.CommandId): com.zcode.android.domain.outbox.OutboxEntry? = null
        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun sweepTerminal(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId) = emptyList<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef>()
        override suspend fun countByState(endpointId: EndpointId, state: com.zcode.android.domain.outbox.OutboxStateMachine.State): Int = 0
    }
}
