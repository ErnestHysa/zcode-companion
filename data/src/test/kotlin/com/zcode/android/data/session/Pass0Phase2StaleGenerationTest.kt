package com.zcode.android.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.InteractionRepositoryImpl
import com.zcode.android.data.repo.OutboxRepositoryImpl
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.repo.InteractionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
 * F-P1-B (pass-0 Phase 2; stamps ×5, pass 13 P.A5 the sharpest): stale-generation
 * interactions stayed answerable against a desktop that had moved on — and the
 * closeStored sweep was PARTIAL IN BOTH DIRECTIONS (the machine-less question row
 * got resolved by the generation swap while the machine-owned approval survived).
 *
 * The full sweep coverage after the Phase-2 fixes (F-36-B's machine-less
 * closeStored + F-P0-B's state-plane ownership + Phase-1's F-P13-A re-subscribe
 * delivering the fresh list + F-P6-A's open-TTL for the long-lived ghost class):
 * when the authoritative (new-generation) `pendingInteractions` list arrives
 * WITHOUT a stale id, EVERY attributable row expires — machine-less AND
 * machine-owned — and the mock's typed unknown-id gate (MOCK_UNKNOWN_INTERACTION_IDS,
 * rig fix 91ec7ade) answers any survivor honestly. Pins run against the REAL Room
 * store (column writes are invisible to recording fakes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class Pass0Phase2StaleGenerationTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var interactions: InteractionRepositoryImpl
    private val ep = EndpointId("ep")

    /** Recent delivery time — the F-P6-A open-TTL must not eat the fixtures. */
    private val deliveredAt = System.currentTimeMillis() - 60_000

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

    private fun request(id: String, createdAt: Long = 1_000L) = PermissionRequest(
        interactionId = InteractionId(id),
        sessionId = "synth-task-1",
        toolName = "Bash",
        reason = "echo synthetic",
        options = listOf(PermissionOption("allowOnce"), PermissionOption("deny")),
        createdAtMs = createdAt,
        raw = buildJsonObject {
            put("interactionId", id)
            put("toolName", "Bash")
            putJsonArray("options") {
                add(buildJsonObject { put("optionId", "allowOnce"); put("label", "Allow once"); put("kind", "allowOnce") })
            }
        },
    )

    /** gen-A's authoritative state: the two stale ids are pending. */
    private fun genAState() = stateWith("mock-approval-1-gena", "q-rig-1-gena")

    /** gen-B's authoritative state: DIFFERENT nonce, empty slots for the old ids. */
    private fun genBState() = stateWith("mock-approval-1-genb")

    private fun stateWith(vararg ids: String): SessionProjectionState = SessionProjectionState.fromSnapshot(
        buildJsonObject {
            putJsonArray("pendingInteractions") {
                for (id in ids) {
                    add(
                        buildJsonObject {
                            put("interactionId", id)
                            put("kind", "permission")
                            put("createdAt", deliveredAt.toDouble())
                            putJsonObject("payload") {
                                put("kind", "permission")
                                put("toolName", "Bash")
                                put("summary", "echo synthetic")
                                putJsonArray("options") {
                                    add(buildJsonObject { put("optionId", "allowOnce"); put("label", "Allow once"); put("kind", "allowOnce") })
                                    add(buildJsonObject { put("optionId", "deny"); put("label", "No"); put("kind", "deny") })
                                }
                            }
                        },
                    )
                }
            }
        },
    )

    private fun coordinator(sender: CommandSender) = InteractionCoordinator(
        endpointId = ep,
        interactions = interactions,
        outbox = OutboxRepositoryImpl(db),
        commands = sender,
    )

    @Test
    fun `F-P1-B - the generation swap expires stale interactions in BOTH directions - machine-less AND machine-owned`() = runTest {
        // gen A delivers both interactions (store rows + cards).
        val sender = AckingSender()
        val coordinator = coordinator(sender)
        coordinator.reconcileFromSessionState("synth-task-1", genAState())
        assertEquals(2, interactions.observeOpen(ep).first().size)
        assertNotNull(coordinator.machineStateForTest(InteractionId("mock-approval-1-gena")))

        // gen B (a fresh nonce) authoritative list drops BOTH stale ids.
        coordinator.reconcileFromSessionState("synth-task-1", genBState())

        val open = interactions.observeOpen(ep).first().map { it.interactionId.raw }.toSet()
        assertEquals(
            "the sweep must cover every attributable row — the machine-less AND the machine-owned one",
            setOf("mock-approval-1-genb"),
            open,
        )
        for (stale in listOf("mock-approval-1-gena", "q-rig-1-gena")) {
            val stored = interactions.loadStored(ep, InteractionId(stale))
            assertNotNull(stored)
            assertTrue(
                "a stale-generation row must be terminal (got ${stored!!.record.lifecycle})",
                stored.record.lifecycle.isTerminal,
            )
        }
    }

    @Test
    fun `F-P1-B - an expired stale row is unanswerable (AlreadyResolved, no wire)`() = runTest {
        val sender = AckingSender()
        val coordinator = coordinator(sender)
        coordinator.reconcileFromSessionState("synth-task-1", genAState())
        coordinator.reconcileFromSessionState("synth-task-1", genBState())

        val outcome = coordinator.submitFromStore(InteractionId("q-rig-1-gena"), "allowOnce")
        assertTrue(
            "a stale-generation survivor must answer as the typed AlreadyResolved; got $outcome",
            outcome is InteractionCoordinator.SubmitOutcome.AlreadyResolved,
        )
        assertTrue("no wire call may ride a stale-generation row", sender.sent.isEmpty())
    }

    @Test
    fun `F-P1-B - the mock's typed unknown-id gate lands FAILED_TERMINAL honestly (the P-A5 chain)`() = runTest {
        // The mock (MOCK_UNKNOWN_INTERACTION_IDS=1, rig fix 91ec7ade) answers an
        // unknown id with {status:"rejected", reasonCode:"fault.interaction.unknownId"}.
        val sender = RejectingSender("fault.interaction.unknownId")
        val coordinator = coordinator(sender)
        coordinator.reconcileFromSessionState("synth-task-1", genAState())

        val outcome = coordinator.submit(InteractionId("mock-approval-1-gena"), "allowOnce")
        val ack = (outcome as InteractionCoordinator.SubmitOutcome.Submitted).ack
        assertEquals("rejected", ack.status)
        assertEquals("fault.interaction.unknownId", ack.reasonCode)

        // The store is HONEST (the P.A5 DB arbiter): the row FAILED, stamped.
        val stored = interactions.loadStored(ep, InteractionId("mock-approval-1-gena"))
        assertEquals(InteractionLifecycle.FAILED, stored!!.record.lifecycle)
        assertNotNull("the FAILED row carries its terminal stamp", stored.record.resolvedAtMs)
        // The inbox clears (nothing open remains answerable for that id).
        assertTrue(
            interactions.observeOpen(ep).first().none { it.interactionId.raw == "mock-approval-1-gena" },
        )
    }

    private class AckingSender : CommandSender {
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

    /** The mock's typed unknown-id rejection shape. */
    private class RejectingSender(private val reasonCode: String) : CommandSender {
        override suspend fun send(
            type: String,
            sessionId: String?,
            payload: JsonObject,
            baseRevision: Long?,
            baseLogEpoch: String?,
            commandId: String,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): CommandAck = CommandAck(commandId, "rejected", 0, reasonCode, null, null)
    }
}
