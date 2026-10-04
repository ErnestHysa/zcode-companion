package com.zcode.android.data.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.protocol.v4.ConnectionFlowState
import com.zcode.android.core.transport.InboundFrame
import com.zcode.android.core.transport.OutboundFrame
import com.zcode.android.core.transport.Transport
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.ConversationRepositoryImpl
import com.zcode.android.data.repo.InteractionRepositoryImpl
import com.zcode.android.data.repo.JournalRepositoryImpl
import com.zcode.android.data.repo.OutboxRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.journal.RecoveryJournal
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.repo.ConversationStoreOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FIELD regression (2026-10-04, vc32 Samsung S24 device log): the delivery-stall
 * watchdog escalated with the HYDRATED JOURNAL WATERMARK of a previous connection
 * as the resync base — the server rejected it (fault.subscription.notOwned) and the
 * one chance to re-kick a paused delivery was wasted. The fix: per-session frame
 * stamps, and any escalation whose base is not the CURRENT connection's ack-stamped
 * subscription epoch forces a snapshot (the only base the server honors).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StreamWatchdogStaleBaseTest {

    private val EP = EndpointId("wd-endpoint")

    private class FakeGateway : ConversationGateway {
        data class ResyncCall(val sessionId: String, val baseEpoch: String?, val baseSeq: Long?, val force: Boolean)

        val resyncCalls = java.util.concurrent.ConcurrentLinkedQueue<ResyncCall>()
        val subscribeCalls = java.util.concurrent.ConcurrentLinkedQueue<String>()
        /** When true, resync throws the vc33 serverSpoke fault.subscription.notOwned. */
        @Volatile var resyncFailsNotOwned = false
        @Volatile var currentEpoch: String? = null
        val bus = MutableSharedFlow<GatewayTopicEvent>(extraBufferCapacity = 64)
        override val topicEvents: SharedFlow<GatewayTopicEvent> = bus

        override fun startForwarding(scope: CoroutineScope) = Unit
        override suspend fun subscribe(
            sessionId: String,
            visibility: String?,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): GatewayAck {
            subscribeCalls.add(sessionId)
            return GatewayAck("sub-${sessionId.take(6)}", "snapshot", currentEpoch ?: "epoch-unset")
        }

        override suspend fun resync(
            sessionId: String,
            baseEpoch: String?,
            baseSeq: Long?,
            forceSnapshot: Boolean,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): GatewayAck {
            resyncCalls.add(ResyncCall(sessionId, baseEpoch, baseSeq, forceSnapshot))
            if (resyncFailsNotOwned) {
                throw com.zcode.android.core.transport.TransportFailure(
                    com.zcode.android.core.common.error.AppError(
                        category = com.zcode.android.core.common.error.ErrorCategory.PROTOCOL,
                        userMessage = "Sync problem",
                        developerDiagnostic = "channel error: fault.subscription.notOwned",
                        retryable = com.zcode.android.core.common.error.AppError.Retryable.AUTO,
                        recoverable = true,
                        correlation = com.zcode.android.core.common.error.Correlation(),
                    ),
                )
            }
            return GatewayAck("sub-resync", "snapshot", currentEpoch ?: "epoch-unset")
        }

        override suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int): GatewayPage =
            throw UnsupportedOperationException("not used in this test")

        override fun currentSubscriptionEpoch(sessionId: String): String? = currentEpoch

        override suspend fun send(
            type: String,
            sessionId: String?,
            payload: JsonObject,
            baseRevision: Long?,
            baseLogEpoch: String?,
            commandId: String,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): CommandAck = throw UnsupportedOperationException("not used in this test")

        suspend fun emit(event: GatewayTopicEvent) = bus.emit(event)
    }

    private class FakeTransport : Transport {
        override val states = MutableStateFlow(ConnectionStateMachine.ConnectionState.CONNECTED)
        override val capabilities = MutableStateFlow<com.zcode.android.core.transport.CapabilitySnapshot?>(null)
        override val flowPressure = MutableStateFlow(ConnectionFlowState.DRAINED)
        override val inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 16)
        override val appErrors = MutableSharedFlow<com.zcode.android.core.common.error.AppError>(extraBufferCapacity = 16)
        override suspend fun connect(endpoint: EndpointDescriptor, signer: CredentialSigner) = Unit
        override suspend fun send(frame: OutboundFrame) = Unit
        override suspend fun recover() = Unit
        override suspend fun close(reason: String) = Unit
        override fun reconnectNow() = Unit
        override fun notifyNetworkAvailable() = Unit
        override fun resetForEndpointSwitch() = Unit
    }

    private lateinit var db: ZcodeDatabase
    private lateinit var journal: JournalRepositoryImpl
    private lateinit var scope: CoroutineScope
    private val log = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = { e ->
        if (System.getenv("ZCODE_WD_DEBUG") != null) println("[wd] ${e.level} ${e.event} ${e.details ?: ""}")
    })

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        journal = JournalRepositoryImpl(db, clock = System::currentTimeMillis)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun completedTurnRow(seq: Long) = ConversationRow.TurnHeader(
        rowId = seq,
        turnId = "turn-$seq",
        base = ConversationRow.Base(createdAt = 1.0, createdAtSeq = seq),
        origin = "user",
        executionKind = null,
        state = ConversationRow.TurnState.COMPLETED_SUCCESS,
        startedAt = 1.0,
        endedAt = 2.0,
        activeMs = 1_000,
        sourceCommandId = null,
        historyRoundCount = null,
        fileChanges = null,
        raw = JsonObject(emptyMap()),
    )

    /**
     * Drive the projection DETERMINISTICALLY through the manager's single-writer
     * entry point (no bus/collector real-time race); the bus-forwarding leg is
     * covered by the RecoveryScenarioMatrix suite.
     */
    private suspend fun applyDirect(
        coordinator: RecoveryCoordinator,
        sessionId: String,
        epoch: String,
        toSeq: Long,
        rows: List<ConversationRow>,
    ) {
        coordinator.manager(sessionId)!!.handle(
            GatewayTopicEvent.Snapshot(
                topic = "conversation/$sessionId",
                sessionId = sessionId,
                logEpoch = epoch,
                fromSeq = 0,
                toSeq = toSeq,
                rows = rows,
            ),
        )
    }

    private suspend fun awaitTaskState(coordinator: RecoveryCoordinator, sessionId: String, expected: com.zcode.android.domain.state.TaskStateMachine.State) {
        withTimeout(5_000) {
            while (coordinator.manager(sessionId)?.taskState?.value != expected) kotlinx.coroutines.delay(25)
        }
    }

    private suspend fun snapshot(sessionId: String, epoch: String, toSeq: Long, rows: List<ConversationRow>) =
        GatewayTopicEvent.Snapshot(
            topic = "conversation/$sessionId",
            sessionId = sessionId,
            logEpoch = epoch,
            fromSeq = 0,
            toSeq = toSeq,
            rows = rows,
        )

    private fun runningTurnRow(seq: Long) = completedTurnRow(seq).copy(state = ConversationRow.TurnState.RUNNING)

    private suspend fun harness(
        clock: () -> Long,
        idleMs: Long = 1_000,
        gapMs: Long = 2_000,
    ): Triple<RecoveryCoordinator, FakeGateway, FakeTransport> {
        val conversations = ConversationRepositoryImpl(db, clock = System::currentTimeMillis)
        val interactionRepo = InteractionRepositoryImpl(db, clock = System::currentTimeMillis)
        val outbox = OutboxRepositoryImpl(db)
        val gateway = FakeGateway()
        val transport = FakeTransport()
        val interactions = InteractionCoordinator(EP, interactionRepo, outbox, gateway, clock = clock, log = log)
        val coordinator = RecoveryCoordinator(
            endpointId = EP,
            gateway = gateway,
            store = conversations,
            journalRepo = journal,
            interactions = interactions,
            transport = transport,
            scope = scope,
            clock = clock,
            log = log,
            config = RecoveryCoordinator.Config(
                holdTickMs = 10,
                streamWatchdogIdleMs = idleMs,
                streamWatchdogResyncGapMs = gapMs,
            ),
        )
        return Triple(coordinator, gateway, transport)
    }

    @Test
    fun resyncRefusedByServer_fallsBackToFreshSubscribe() = runBlocking {
        // FIELD (vc33): the desktop rejected EVERY resync with
        // fault.subscription.notOwned — the old path re-sent the SAME failing resync
        // forever (5× in the field). §15-S10: resync failed → FULL SNAPSHOT, which
        // here means a FRESH SUBSCRIBE (a new server-owned subscription).
        var now = 5_000_000L
        val (coordinator, gateway, _) = harness(clock = { now })
        gateway.currentEpoch = "epoch-new"
        gateway.resyncFailsNotOwned = true

        coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget("S")))
        now += 5_000
        // The watchdog escalates once, hits the refused resync, and anchors a fresh
        // subscribe (the attach subscribe is #1; the fallback is #2).
        withTimeout(5_000) {
            while (gateway.subscribeCalls.size < 2) kotlinx.coroutines.delay(25)
        }
        assertTrue("escalation happened", gateway.resyncCalls.isNotEmpty())
        assertEquals("S", gateway.subscribeCalls.last())
        coordinator.close()
    }

    @Test
    fun localConfigOverlay_reflectedUntilEchoReplaces() = runBlocking {
        // §14.2 command-issuer overlay (vc33: accepted switches showed no change).
        var now = 6_000_000L
        val (coordinator, gateway, _) = harness(clock = { now })
        gateway.currentEpoch = "epoch-new"
        coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget("S")))
        applyDirect(coordinator, "S", "epoch-new", toSeq = 10, rows = listOf(runningTurnRow(1)))
        awaitTaskState(coordinator, "S", com.zcode.android.domain.state.TaskStateMachine.State.RUNNING)
        coordinator.applyLocalConfigOverlay("S", provider = "zai", model = "glm-5.3", thought = "high")
        assertEquals(
            "the accepted switch is reflected immediately (echo replaces it later)",
            "glm-5.3",
            coordinator.manager("S")!!.sessionState.value.config?.model,
        )
        // The authoritative echo REPLACES the overlay wholesale (INV-1: desktop wins).
        applyDirect(
            coordinator, "S", "epoch-new", toSeq = 11,
            rows = emptyList(),
        )
        coordinator.close()
    }

    @Test
    fun staleJournalBase_escalationForcesSnapshot_notResync() = runBlocking {
        var now = 1_000_000L
        val (coordinator, gateway, _) = harness(clock = { now })
        // Yesterday's journal: session S sits at epoch-old/100.
        journal.record(
            RecoveryJournal(
                endpointId = EP.raw,
                helloServerTimeMs = null,
                capabilitiesSeen = null,
                subscriptions = listOf(RecoveryJournal.Subscription("conversation/S", "S", 100, "epoch-old")),
                pendingOutbox = emptyList(),
                lastAppliedPerTopic = emptyMap(),
            ),
        )
        // The CURRENT connection's subscription carries a different (new) epoch.
        gateway.currentEpoch = "epoch-new"

        coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget("S")))
        now += 5_000 // past the idle window; NO frames ever arrived

        withTimeout(5_000) {
            while (gateway.resyncCalls.isEmpty()) kotlinx.coroutines.delay(25)
        }
        val call = gateway.resyncCalls.first()
        assertEquals("S", call.sessionId)
        assertEquals("the stale journal base must not resume — FORCE a snapshot", true, call.force)
        assertEquals("epoch-old", call.baseEpoch)
        coordinator.close()
    }

    @Test
    fun currentEpochBase_healthyResume_keepsBaseResync() = runBlocking {
        var now = 2_000_000L
        val (coordinator, gateway, _) = harness(clock = { now })
        gateway.currentEpoch = "epoch-new"

        coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget("S")))
        // The authoritative snapshot lands — reducer watermark is now the CURRENT epoch
        // (observable through the §14.4.2 task machine: UNKNOWN → COMPLETED on apply).
        applyDirect(coordinator, "S", "epoch-new", toSeq = 500, rows = listOf(runningTurnRow(1)))
        awaitTaskState(coordinator, "S", com.zcode.android.domain.state.TaskStateMachine.State.RUNNING)
        now += 5_000

        withTimeout(5_000) {
            while (gateway.resyncCalls.isEmpty()) kotlinx.coroutines.delay(25)
        }
        val call = gateway.resyncCalls.first()
        assertEquals(false, call.force) // current base → stateless resume (the vc24 re-kick)
        assertEquals("epoch-new", call.baseEpoch)
        assertEquals(500L, call.baseSeq)
        coordinator.close()
    }

    @Test
    fun completedTask_isNeverWatchdogEscalated() = runBlocking {
        var now = 3_000_000L
        val (coordinator, gateway, _) = harness(clock = { now })
        gateway.currentEpoch = "epoch-new"

        coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget("S")))
        applyDirect(coordinator, "S", "epoch-new", toSeq = 10, rows = listOf(completedTurnRow(1)))
        awaitTaskState(coordinator, "S", com.zcode.android.domain.state.TaskStateMachine.State.COMPLETED)
        now += 60_000 // long past any idle window
        kotlinx.coroutines.delay(400) // several tick windows
        assertTrue(
            "a COMPLETED task streams nothing — the watchdog must skip it",
            gateway.resyncCalls.isEmpty(),
        )
        coordinator.close()
    }

    @Test
    fun perSessionStamps_quietSessionWatched_whileAnotherStreams() = runBlocking {
        var now = 4_000_000L
        val (coordinator, gateway, _) = harness(clock = { now })
        gateway.currentEpoch = "epoch-new"

        coordinator.startTargets(
            listOf(RecoveryCoordinator.SessionTarget("S1"), RecoveryCoordinator.SessionTarget("S2")),
        )
        // S1 completes (never eligible again); S2 stays quiet-UNKNOWN.
        applyDirect(coordinator, "S1", "epoch-new", toSeq = 5, rows = listOf(completedTurnRow(1)))
        awaitTaskState(coordinator, "S1", com.zcode.android.domain.state.TaskStateMachine.State.COMPLETED)
        // S1 keeps "streaming" — frames every 500 fake-ms — which under the old GLOBAL
        // stamp kept the watchdog permanently silent for S2.
        repeat(10) {
            now += 500
            applyDirect(coordinator, "S1", "epoch-new", toSeq = 6L + it, rows = listOf(completedTurnRow(1)))
        }
        now += 5_000 // S2 has been silent far past the idle window

        withTimeout(5_000) {
            while (gateway.resyncCalls.none { it.sessionId == "S2" }) kotlinx.coroutines.delay(25)
        }
        assertTrue(
            "S1 (completed) must never be escalated",
            gateway.resyncCalls.none { it.sessionId == "S1" },
        )
        coordinator.close()
    }
}
