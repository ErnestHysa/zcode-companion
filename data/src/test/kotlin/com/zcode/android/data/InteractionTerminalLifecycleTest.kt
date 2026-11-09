package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.InteractionRepositoryImpl
import com.zcode.android.data.repo.OutboxRepositoryImpl
import com.zcode.android.data.session.InteractionCoordinator
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.core.protocol.v4.CommandAck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
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
 * F-36-B (vc52 Phase 7) — terminal-but-not-RESOLVED interactions never close.
 * The pass-36 receipt: a two-day-old STALE approval stayed in the inbox (badge 2),
 * was re-posted on background entry, and ANSWERED onto the live wire for an id the
 * desktop never issued. Three sites compounded: the coordinator mirrored
 * `resolvedAtMs` for RESOLVED only, `observeOpen` was `resolvedAtMs IS NULL` (so
 * every non-RESOLVED terminal row was "open"), and the sweep TTL-dropped only
 * stamped rows. Pins against the REAL Room repo (the GAP-A2 lesson — recording
 * fakes cannot see column clobber or sweep order):
 *
 *  1. a STALE row (the recorded shape: NULL stamp) is excluded from `observeOpen`;
 *  2. a coordinator-staled row carries a terminal stamp and TTL-sweeps;
 *  3. cold-start reconcile closes stored open rows the authoritative
 *     `pendingInteractions` list dropped, even with no machine;
 *  4. D3: a self-resolution timestamp is never overwritten by a later reconcile.
 *
 * Plus the F-33-E repo-plane leg: the pass-33 DB fixture (2 DISPLAYED + 2 STALE +
 * 5 RESOLVED) yields badge/inbox = 2 — `observeOpen` and the domain OPEN_ATTENTION
 * predicate agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class InteractionTerminalLifecycleTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var interactions: InteractionRepositoryImpl
    private val ep = EndpointId("ep")

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

    private fun request(
        id: String,
        sessionId: String = "s1",
        createdAt: Long = 1_000L,
    ) = PermissionRequest(
        interactionId = InteractionId(id),
        sessionId = sessionId,
        toolName = "Bash",
        reason = "run tests",
        options = listOf(PermissionOption("allowOnce"), PermissionOption("deny")),
        createdAtMs = createdAt,
        raw = buildJsonObject {
            put("interactionId", id)
            put("toolName", "Bash")
        },
    )

    /** The recorded defect shape: terminal lifecycle, NO resolution stamp. */
    private fun seedTerminalRow(id: String, lifecycle: InteractionLifecycle, sessionId: String = "s1") = runTest {
        interactions.upsertPermission(request(id, sessionId), ep, lifecycle)
    }

    private fun seedResolvedRow(id: String, sessionId: String, at: Long) = runTest {
        interactions.upsertPermission(request(id, sessionId), ep, InteractionLifecycle.DISPLAYED)
        interactions.markResolved(ep, InteractionId(id), at, resolvedBySelf = true)
    }

    // ---- pin 1: observeOpen is lifecycle-based (STALE/EXPIRED/FAILED/CANCELLED are closed) ----

    @Test
    fun `a STALE row with no stamp is excluded from observeOpen - the inbox and badge set`() = runTest {
        seedTerminalRow("stale-1", InteractionLifecycle.STALE)
        seedTerminalRow("failed-1", InteractionLifecycle.FAILED)
        seedTerminalRow("expired-1", InteractionLifecycle.EXPIRED)
        seedTerminalRow("cancelled-1", InteractionLifecycle.CANCELLED)
        interactions.upsertPermission(request("live-1"), ep, InteractionLifecycle.DISPLAYED)
        interactions.upsertPermission(request("live-2"), ep, InteractionLifecycle.REQUESTED)
        val open = interactions.observeOpen(ep).first()
        assertEquals(
            "terminal-but-unstamped rows are CLOSED to the inbox/badge; only the open lifecycle remains",
            setOf("live-1", "live-2"),
            open.map { it.interactionId.raw }.toSet(),
        )
    }

    // ---- pin 2: coordinator terminal writes stamp; the sweep TTL-drops them ----

    @Test
    fun `a coordinator-staled row is terminal-stamped and the sweep drops it after TTL`() = runTest {
        var now = 1_000_000L
        val sent = mutableListOf<String>()
        val coordinator = InteractionCoordinator(
            endpointId = ep,
            interactions = interactions,
            outbox = OutboxRepositoryImpl(db),
            commands = object : com.zcode.android.data.session.CommandSender {
                override suspend fun send(
                    type: String, sessionId: String?, payload: kotlinx.serialization.json.JsonObject,
                    baseRevision: Long?, baseLogEpoch: String?, commandId: String,
                    workspacePath: String?, workspaceIdentity: String?,
                ): CommandAck {
                    sent.add(commandId)
                    return CommandAck(commandId = commandId, status = "accepted", revisionAtDecision = 0, reasonCode = null, message = null, result = null)
                }
            },
            clock = { now },
        )
        coordinator.onPermissionRequested(request("stale-sweep"))
        coordinator.render(InteractionId("stale-sweep"))
        // The rows snapshot no longer carries the approval → the machine STALEs.
        coordinator.reconcileFromSnapshot(rows = emptyList(), sessionId = "s1")
        val stored = interactions.loadStored(ep, InteractionId("stale-sweep"))
        assertEquals("the row is terminal", InteractionLifecycle.STALE, stored?.record?.lifecycle)
        assertNotNull("terminal machine writes STAMP the record (the other four terminal states, GAP-A2's sibling)", stored?.record?.resolvedAtMs)
        // TTL passes → the sweep drops it.
        now = stored!!.record.resolvedAtMs!! + InteractionRepositoryImpl.Bounds().resolvedTtlMs + 1
        val dropped = interactions.sweep(ep, now)
        assertTrue("a terminal row ages out like a resolved one", dropped >= 1)
        assertEquals("the store no longer holds the row", 0, interactions.count(ep))
    }

    // ---- pin 3: cold-start reconcile closes absent stored open rows ----

    @Test
    fun `cold-start reconcile closes stored open rows the authoritative list dropped - no machine`() = runTest {
        // A row persisted by a PREVIOUS process (the pass-36 ghost): DISPLAYED, no machine.
        interactions.upsertPermission(request("ghost-1", sessionId = "sess-1"), ep, InteractionLifecycle.DISPLAYED)
        interactions.upsertPermission(request("ghost-2", sessionId = "sess-1"), ep, InteractionLifecycle.REQUESTED)
        // A DIFFERENT session's open row must survive this session's reconcile.
        interactions.upsertPermission(request("other-session", sessionId = "sess-2"), ep, InteractionLifecycle.DISPLAYED)

        val coordinator = InteractionCoordinator(
            endpointId = ep,
            interactions = interactions,
            outbox = OutboxRepositoryImpl(db),
            commands = object : com.zcode.android.data.session.CommandSender {
                override suspend fun send(
                    type: String, sessionId: String?, payload: kotlinx.serialization.json.JsonObject,
                    baseRevision: Long?, baseLogEpoch: String?, commandId: String,
                    workspacePath: String?, workspaceIdentity: String?,
                ): CommandAck = CommandAck(commandId = commandId, status = "accepted", revisionAtDecision = 0, reasonCode = null, message = null, result = null)
            },
            clock = { 5_000_000L },
        )
        // The authoritative `pendingInteractions` list for sess-1 exists and does NOT
        // contain the stored ghosts → they close even with no machine.
        coordinator.reconcileFromSessionState("sess-1", SessionProjectionState(pendingInteractions = emptyList()))
        val open = interactions.observeOpen(ep).first()
        assertEquals(
            "absent stored open rows of THIS session close; other sessions' rows survive",
            setOf("other-session"),
            open.map { it.interactionId.raw }.toSet(),
        )
    }

    @Test
    fun `an interaction still listed by the authoritative plane survives the cold-start reconcile`() = runTest {
        interactions.upsertPermission(request("still-live", sessionId = "sess-1"), ep, InteractionLifecycle.DISPLAYED)
        val coordinator = InteractionCoordinator(
            endpointId = ep,
            interactions = interactions,
            outbox = OutboxRepositoryImpl(db),
            commands = object : com.zcode.android.data.session.CommandSender {
                override suspend fun send(
                    type: String, sessionId: String?, payload: kotlinx.serialization.json.JsonObject,
                    baseRevision: Long?, baseLogEpoch: String?, commandId: String,
                    workspacePath: String?, workspaceIdentity: String?,
                ): CommandAck = CommandAck(commandId = commandId, status = "accepted", revisionAtDecision = 0, reasonCode = null, message = null, result = null)
            },
            clock = { 5_000_000L },
        )
        val listed = com.zcode.android.domain.model.PendingInteraction(
            interactionId = "still-live",
            kind = com.zcode.android.domain.model.PendingInteraction.Kind.PERMISSION,
            anchorRowId = null,
            createdAtMs = 1_000L,
            permission = com.zcode.android.domain.model.PendingInteraction.PermissionPayload(
                toolCallId = null, toolName = "Bash", summary = "run tests", detail = null,
                freeText = null,
                options = listOf(PermissionOption("allowOnce"), PermissionOption("deny")),
                raw = buildJsonObject { },
            ),
            userInput = null,
            raw = buildJsonObject {
                put("interactionId", "still-live")
                put("kind", "permission")
            },
        )
        coordinator.reconcileFromSessionState("sess-1", SessionProjectionState(pendingInteractions = listOf(listed)))
        val open = interactions.observeOpen(ep).first()
        assertEquals("a listed interaction is never closed by reconcile", listOf("still-live"), open.map { it.interactionId.raw })
    }

    // ---- pin 4: D3 — the self-resolution stamp is never overwritten ----

    @Test
    fun `D3 - a self-resolved timestamp survives later reconciles`() = runTest {
        var now = 1_000_000L
        val resolutions = mutableListOf<Pair<InteractionId, Long>>()
        val repo = object : InteractionRepository by interactions {
            override suspend fun markResolved(endpointId: EndpointId, interactionId: InteractionId, atMs: Long, resolvedBySelf: Boolean) {
                resolutions.add(interactionId to atMs)
                interactions.markResolved(endpointId, interactionId, atMs, resolvedBySelf)
            }
        }
        val coordinator = InteractionCoordinator(
            endpointId = ep,
            interactions = repo,
            outbox = OutboxRepositoryImpl(db),
            commands = object : com.zcode.android.data.session.CommandSender {
                override suspend fun send(
                    type: String, sessionId: String?, payload: kotlinx.serialization.json.JsonObject,
                    baseRevision: Long?, baseLogEpoch: String?, commandId: String,
                    workspacePath: String?, workspaceIdentity: String?,
                ): CommandAck = CommandAck(commandId = commandId, status = "accepted", revisionAtDecision = 0, reasonCode = null, message = null, result = null)
            },
            clock = { now },
        )
        coordinator.onPermissionRequested(request("self-resolved", sessionId = "sess-1"))
        coordinator.render(InteractionId("self-resolved"))
        val first = coordinator.submit(InteractionId("self-resolved"), "allowOnce")
        assertTrue("the answer was accepted", first is InteractionCoordinator.SubmitOutcome.Submitted)
        val selfStamp = resolutions.single().second
        now += 60_000 // the later reconcile happens a minute later
        // The authoritative list drops the id (resolved elsewhere on the desktop).
        coordinator.reconcileFromSessionState("sess-1", SessionProjectionState(pendingInteractions = emptyList()))
        assertEquals(
            "D3: exactly ONE resolution timestamp — a later reconcile never re-stamps it",
            1,
            resolutions.size,
        )
        assertEquals("the preserved stamp is the self-resolution time", selfStamp, resolutions.single().second)
        val stored = interactions.loadStored(ep, InteractionId("self-resolved"))
        assertEquals(selfStamp, stored?.record?.resolvedAtMs)
    }

    // ---- F-33-E repo leg: the pass-33 DB fixture through the shared open predicate ----

    @Test
    fun `pass-33 DB fixture - badge and inbox agree on TWO from 2 DISPLAYED + 2 STALE + 5 RESOLVED`() = runTest {
        repeat(2) { n -> interactions.upsertPermission(request("disp-$n"), ep, InteractionLifecycle.DISPLAYED) }
        repeat(2) { n -> seedTerminalRow("stale-$n", InteractionLifecycle.STALE) }
        repeat(5) { n -> seedResolvedRow("res-$n", sessionId = "s1", at = 900_000L + n) }
        val open = interactions.observeOpen(ep).first()
        assertEquals("badge = 2, inbox = 2 (STALE leaks to neither)", 2, open.size)
        // The shared constant drives the SQL — the pin's fixture uses it verbatim.
        assertEquals(
            setOf("REQUESTED", "DISPLAYED", "SUBMITTING"),
            InteractionLifecycle.OPEN_ATTENTION.map { it.name }.toSet(),
        )
    }

    // ---- the stored row keeps an unanswered terminal id unanswerable (submitFromStore) ----

    @Test
    fun `a stored terminal row is AlreadyResolved - never re-hydrated onto the wire`() = runTest {
        seedTerminalRow("poisoned", InteractionLifecycle.STALE, sessionId = "sess-9")
        var now = 2_000_000L
        val coordinator = InteractionCoordinator(
            endpointId = ep,
            interactions = interactions,
            outbox = OutboxRepositoryImpl(db),
            commands = object : com.zcode.android.data.session.CommandSender {
                override suspend fun send(
                    type: String, sessionId: String?, payload: kotlinx.serialization.json.JsonObject,
                    baseRevision: Long?, baseLogEpoch: String?, commandId: String,
                    workspacePath: String?, workspaceIdentity: String?,
                ): CommandAck {
                    throw AssertionError("a terminal row must never reach the wire")
                }
            },
            clock = { now },
        )
        val outcome = coordinator.submitFromStore(InteractionId("poisoned"), "allowOnce")
        assertTrue(
            "the pass-36 ghost answers as AlreadyResolved (typed), never as a live wire send",
            outcome is InteractionCoordinator.SubmitOutcome.AlreadyResolved,
        )
    }


    // ---- G77 (vc52 Phase 7): the autoResolution deadline persists and expires ----

    private fun fuseState(id: String, deadlineAtMs: Long?): SessionProjectionState =
        SessionProjectionState.fromSnapshot(
            kotlinx.serialization.json.buildJsonObject {
                putJsonArray("pendingInteractions") {
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("interactionId", id)
                            put("kind", "permission")
                            put("anchorRowId", kotlinx.serialization.json.JsonNull)
                            put("createdAt", 1_000.0)
                            deadlineAtMs?.let {
                                put("autoResolution", kotlinx.serialization.json.buildJsonObject {
                                    put("state", "visibleCountdown")
                                    put("startedAt", 1_000.0)
                                    put("visibleAt", 1_000.0)
                                    put("deadlineAt", it.toDouble())
                                })
                            }
                            putJsonObject("payload") {
                                put("kind", "permission")
                                put("toolName", "Bash")
                                putJsonArray("options") {
                                    add(kotlinx.serialization.json.buildJsonObject {
                                        put("optionId", "allowOnce"); put("label", "Allow once"); put("kind", "allowOnce")
                                    })
                                    add(kotlinx.serialization.json.buildJsonObject {
                                        put("optionId", "deny"); put("label", "Deny"); put("kind", "deny")
                                    })
                                }
                            }
                        },
                    )
                }
            },
        )

    @Test
    fun `G77 - the decoded fuse deadline persists into the real Room store`() = runTest {
        val coordinator = InteractionCoordinator(
            endpointId = ep,
            interactions = interactions,
            outbox = OutboxRepositoryImpl(db),
            commands = object : com.zcode.android.data.session.CommandSender {
                override suspend fun send(
                    type: String, sessionId: String?, payload: kotlinx.serialization.json.JsonObject,
                    baseRevision: Long?, baseLogEpoch: String?, commandId: String,
                    workspacePath: String?, workspaceIdentity: String?,
                ): CommandAck = CommandAck(commandId = commandId, status = "accepted", revisionAtDecision = 0, reasonCode = null, message = null, result = null)
            },
            clock = { 1_000_000L },
        )
        coordinator.reconcileFromSessionState("sess-1", fuseState("fused-1", deadlineAtMs = 1_900_000L))
        val stored = interactions.loadStored(ep, InteractionId("fused-1"))
        assertEquals(
            "the fuse deadline round-trips through the REAL serializer (the notification's countdown)",
            1_900_000L, stored?.record?.autoResolutionDeadlineMs,
        )
    }

    @Test
    fun `G77 - an already-expired fuse is respected (EXPIRED card, cleared inbox)`() = runTest {
        val coordinator = InteractionCoordinator(
            endpointId = ep,
            interactions = interactions,
            outbox = OutboxRepositoryImpl(db),
            commands = object : com.zcode.android.data.session.CommandSender {
                override suspend fun send(
                    type: String, sessionId: String?, payload: kotlinx.serialization.json.JsonObject,
                    baseRevision: Long?, baseLogEpoch: String?, commandId: String,
                    workspacePath: String?, workspaceIdentity: String?,
                ): CommandAck {
                    throw AssertionError("an expired fuse must never answer onto the wire")
                }
            },
            clock = { 2_000_000L }, // deadline 1.9M is in the past
        )
        coordinator.reconcileFromSessionState("sess-1", fuseState("fused-expired", deadlineAtMs = 1_900_000L))
        assertEquals(
            "respect an already-expired deadline — the machine is EXPIRED",
            com.zcode.android.domain.state.PermissionStateMachine.State.EXPIRED,
            coordinator.machineStateForTest(InteractionId("fused-expired")),
        )
        val stored = interactions.loadStored(ep, InteractionId("fused-expired"))
        assertEquals(InteractionLifecycle.EXPIRED, stored?.record?.lifecycle)
        assertNotNull("the terminal write STAMPS (F-36-B)", stored?.record?.resolvedAtMs)
        assertEquals("the inbox cleared on expiry", 0, interactions.observeOpen(ep).first().size)
    }

    @Test
    fun `G77 - a live fuse does not expire an answerable card`() = runTest {
        val coordinator = InteractionCoordinator(
            endpointId = ep,
            interactions = interactions,
            outbox = OutboxRepositoryImpl(db),
            commands = object : com.zcode.android.data.session.CommandSender {
                override suspend fun send(
                    type: String, sessionId: String?, payload: kotlinx.serialization.json.JsonObject,
                    baseRevision: Long?, baseLogEpoch: String?, commandId: String,
                    workspacePath: String?, workspaceIdentity: String?,
                ): CommandAck = CommandAck(commandId = commandId, status = "accepted", revisionAtDecision = 0, reasonCode = null, message = null, result = null)
            },
            clock = { 1_000_000L }, // deadline 1.9M still in the future
        )
        coordinator.reconcileFromSessionState("sess-1", fuseState("fused-live", deadlineAtMs = 1_900_000L))
        assertEquals(
            "a live fuse keeps the card answerable",
            com.zcode.android.domain.state.PermissionStateMachine.State.DISPLAYED,
            coordinator.machineStateForTest(InteractionId("fused-live")),
        )
    }
}
