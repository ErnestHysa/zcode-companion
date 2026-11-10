package com.zcode.android.data.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
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
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.reducer.ProjectionOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-37-E (vc52 Phase 10): the stream watchdog re-kicked an idle session every ~90 s
 * FOREVER (14 re-kicks in one idle 30-minute window, each escalating to a RECOVERY
 * frame that alternately re-pulled a full 2,001-row snapshot) because every re-kick's
 * own recovery answer delivered frames that (a) refreshed the frame stamp and (b)
 * RESET the strike counter — the 4-strike cap could never engage against a
 * compliant-but-passive desktop. The fix: a no-change recovery (same epoch/seq head)
 * stands the session down as healthy-idle until a GENUINE delta; per-strike
 * exponential backoff; the kick's echo window cannot reset the ladder.
 *
 * Pins are BUS-driven (the classification lives in the topicEvents collector) with a
 * deterministic fake clock; the responder is scripted SNAPSHOT-ONLY (the recorded
 * face) unless a test explicitly advances the head.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StreamWatchdogIdleStandDownTest {

    private val EP = EndpointId("sd-endpoint")

    private class FakeGateway : ConversationGateway {
        val resyncCalls = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val subscribeCalls = java.util.concurrent.ConcurrentLinkedQueue<String>()
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
            resyncCalls.add(sessionId)
            return GatewayAck("sub-resync", "snapshot", currentEpoch ?: "epoch-unset")
        }

        override suspend fun subscribeWorkspaceConfig(
            workspacePath: String?,
            workspaceIdentity: String?,
        ): GatewayAck = GatewayAck("sub-cfg", "snapshot", "cfg-epoch-unset")

        override suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int) =
            throw UnsupportedOperationException("not used")

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
        ): CommandAck = throw UnsupportedOperationException("not used")

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

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
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

    private val json = Json

    /** A TURN_HEADER running row (the non-terminal gating face of the recorded defect). */
    private fun runningRow(seq: Long, rowId: Long = seq): ConversationRow =
        ConversationRow.Decoder.decode(
            json.parseToJsonElement(
                """{"rowId":$rowId,"turnId":"turn-$seq","kind":"turnHeader","origin":"user","state":"running",
                   "startedAt":1.0,"createdAt":1.0,"createdAtSeq":$rowId}""",
            ) as JsonObject,
        )!!

    private fun bodyRow(rowId: Long): ConversationRow =
        ConversationRow.Decoder.decode(
            json.parseToJsonElement(
                """{"rowId":$rowId,"turnId":"turn-1","kind":"assistantText","text":"synthetic body $rowId",
                   "state":"complete","createdAt":1.0,"createdAtSeq":$rowId}""",
            ) as JsonObject,
        )!!

    private fun snapshot(sessionId: String, epoch: String, toSeq: Long, rows: List<ConversationRow>) =
        GatewayTopicEvent.Snapshot(
            topic = "conversation/$sessionId",
            sessionId = sessionId,
            logEpoch = epoch,
            fromSeq = 0,
            toSeq = toSeq,
            rows = rows,
        )

    private class Rig(val now: Long) {
        var clock = 1_000_000L
        val gateway = FakeGateway()
        val transport = FakeTransport()
        lateinit var conversations: ConversationRepositoryImpl
        lateinit var coordinator: RecoveryCoordinator
    }

    private fun buildRig(startNow: Long, echoGraceMs: Long = 10_000L): Rig {
        val rig = Rig(startNow)
        rig.conversations = ConversationRepositoryImpl(db, clock = System::currentTimeMillis)
        val interactionRepo = InteractionRepositoryImpl(db, clock = System::currentTimeMillis)
        val outbox = OutboxRepositoryImpl(db)
        val interactions = InteractionCoordinator(EP, interactionRepo, outbox, rig.gateway, clock = { rig.clock }, log = ZLog())
        rig.coordinator = RecoveryCoordinator(
            endpointId = EP,
            gateway = rig.gateway,
            store = rig.conversations,
            journalRepo = journal,
            interactions = interactions,
            transport = rig.transport,
            scope = scope,
            clock = { rig.clock },
            log = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = { e ->
                if (System.getenv("ZCODE_SD_DEBUG") != null) println("[sd] ${e.level} ${e.event} ${e.details ?: ""}")
            }),
            config = RecoveryCoordinator.Config(
                holdTickMs = 10,
                streamWatchdogIdleMs = 1_000,
                streamWatchdogResyncGapMs = 2_000,
                watchdogEchoGraceMs = echoGraceMs,
            ),
        )
        return rig
    }

    /** Attach S with an authoritative RUNNING snapshot (head = epoch-new/toSeq). */
    private suspend fun attachRunning(rig: Rig, toSeq: Long = 10) {
        rig.gateway.currentEpoch = "epoch-new"
        rig.coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget("S")))
        rig.coordinator.manager("S")!!.handle(snapshot("S", "epoch-new", toSeq, listOf(runningRow(1))))
        withTimeout(10_000) {
            while (rig.coordinator.manager("S")?.taskState?.value !=
                com.zcode.android.domain.state.TaskStateMachine.State.RUNNING
            ) delay(10)
        }
    }

    private suspend fun awaitResyncs(rig: Rig, count: Int) {
        withTimeout(20_000) {
            while (rig.gateway.resyncCalls.size < count) {
                rig.clock += 250
                delay(10)
            }
        }
    }

    private suspend fun awaitRowCount(rig: Rig, count: Int) {
        withTimeout(20_000) {
            while (rig.conversations.cachedRowCount(SessionId("S")) != count) {
                rig.clock += 100
                delay(10)
            }
        }
    }

    /** Advance the fake clock across idle windows, polling that NOTHING new fires. */
    private suspend fun assertQuiet(rig: Rig, fakeMs: Long) {
        val before = rig.gateway.resyncCalls.size
        var stepped = 0
        while (stepped < fakeMs) {
            rig.clock += 250
            stepped += 250
            delay(5)
        }
        delay(60) // a few real tick windows at the final time
        assertEquals(
            "the watchdog must stay quiet — the recovery proved the session healthy-idle",
            before,
            rig.gateway.resyncCalls.size,
        )
    }

    @Test
    fun `a no-change recovery stands the session down - the recorded 14-re-kick loop bounded`() = runBlocking {
        val rig = buildRig(1_000_000L)
        try {
            attachRunning(rig)
            rig.clock += 1_000
            awaitResyncs(rig, 1)
            // The scripted snapshot-only responder: the recovery answer re-delivers the
            // SAME head (epoch-new, 10) through the bus — the echo class.
            rig.gateway.emit(snapshot("S", "epoch-new", 10, listOf(runningRow(1), bodyRow(2))))
            awaitRowCount(rig, 2)
            // The recorded loop WITHOUT the fix: a re-kick every interval, forever
            // (14 re-kicks over one idle 30-minute walk, alternating full snapshots).
            assertQuiet(rig, fakeMs = 30_000)
            assertFalse("healthy-idle is not stale", rig.coordinator.manager("S")!!.stale.value)
        } finally {
            rig.coordinator.close()
        }
    }

    @Test
    fun `a genuine delta re-arms the watchdog after a stand-down`() = runBlocking {
        val rig = buildRig(2_000_000L)
        try {
            attachRunning(rig)
            rig.clock += 1_000
            awaitResyncs(rig, 1)
            rig.gateway.emit(snapshot("S", "epoch-new", 10, listOf(runningRow(1), bodyRow(2))))
            awaitRowCount(rig, 2)
            assertQuiet(rig, fakeMs = 4_000) // stood down
            // A GENUINE delta: the head ADVANCES (toSeq 11) well past the echo grace.
            rig.clock += 20_000
            rig.gateway.emit(
                GatewayTopicEvent.Deltas(
                    topic = "conversation/S",
                    sessionId = "S",
                    logEpoch = "epoch-new",
                    fromSeq = 10,
                    toSeq = 11,
                    ops = listOf(ProjectionOp.RowAppended(bodyRow(3))),
                ),
            )
            awaitRowCount(rig, 3)
            // Idle again → the re-armed watchdog may kick once more.
            awaitResyncs(rig, 2)
        } finally {
            rig.coordinator.close()
        }
    }

    @Test
    fun `echo answers cannot evade the strike cap - it engages and marks stale`() = runBlocking {
        val rig = buildRig(3_000_000L)
        try {
            attachRunning(rig)
            // A responder whose answer advances the head INSIDE the echo grace each
            // time (the recovery's own delta) — strike-neutral per the fix, so the
            // ladder climbs and the cap engages at the 5th attempt.
            var head = 10L
            for (strike in 1..4) {
                awaitResyncs(rig, strike)
                head += 1
                rig.gateway.emit(
                    GatewayTopicEvent.Deltas(
                        topic = "conversation/S",
                        sessionId = "S",
                        logEpoch = "epoch-new",
                        fromSeq = head - 1,
                        toSeq = head,
                        ops = listOf(ProjectionOp.RowAppended(bodyRow(head))),
                    ),
                )
                // Let the echo land BEFORE advancing past the grace window.
                awaitRowCount(rig, 1 + strike)
                rig.clock += 12_000 // past idle AND the echo grace
            }
            withTimeout(40_000) {
                while (!rig.coordinator.manager("S")!!.stale.value) {
                    rig.clock += 250
                    delay(10)
                }
            }
            rig.clock += 20_000
            delay(80)
            assertEquals(
                "after the cap the watchdog stops re-subscribing (each round can cold-resume server-side)",
                4,
                rig.gateway.resyncCalls.size,
            )
            assertTrue(
                "a capped session is honestly stale until a frame, a reconnect, or the user resets it",
                rig.coordinator.manager("S")!!.stale.value,
            )
        } finally {
            rig.coordinator.close()
        }
    }

    @Test
    fun `a reconnect clears the stand-down`() = runBlocking {
        val rig = buildRig(4_000_000L)
        try {
            attachRunning(rig)
            rig.clock += 1_000
            awaitResyncs(rig, 1)
            rig.gateway.emit(snapshot("S", "epoch-new", 10, listOf(runningRow(1), bodyRow(2))))
            awaitRowCount(rig, 2)
            assertQuiet(rig, fakeMs = 4_000) // stood down
            // The connection cycles: RECONNECTING → CONNECTED — every session re-arms.
            println("[sd-t] flipping transport: was=${rig.transport.states.value}")
            val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val probe = scope.launch {
                rig.transport.states.collect { seen.add(it.name) }
            }
            rig.transport.states.value = ConnectionStateMachine.ConnectionState.RECONNECTING
            delay(300)
            rig.transport.states.value = ConnectionStateMachine.ConnectionState.CONNECTED
            delay(300)
            println("[sd-t] probe saw: $seen  statesNow=${rig.transport.states.value}")
            rig.clock += 5_000
            awaitResyncs(rig, 2)
            probe.cancel()
            delay(100)
            println("[sd-t] probe saw (end): $seen")
        } finally {
            rig.coordinator.close()
        }
    }
}
