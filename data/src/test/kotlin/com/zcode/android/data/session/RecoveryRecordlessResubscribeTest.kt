package com.zcode.android.data.session

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.v4.ConnectionFlowState
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.InboundFrame
import com.zcode.android.core.transport.OutboundFrame
import com.zcode.android.core.transport.Transport
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.ConversationRepositoryImpl
import com.zcode.android.data.repo.InteractionRepositoryImpl
import com.zcode.android.data.repo.JournalRepositoryImpl
import com.zcode.android.data.repo.OutboxRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.ConversationRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-P13-A (pass-0 Phase 1): the workflowRun delta plane's post-close-cycle death.
 * The detach path drops the local subscription record even though the unsubscribe
 * send fails on the dead socket (the V4Plane.kt:88 contract), the recovery
 * re-materializes conversations via `relay.recovered … snapshotReplace=true` issuing
 * NO subscribeConversationV4, so the subscribe-ACK epoch record is never re-stamped —
 * every server-initiated delta then drops at the gateway's null-epoch branch for the
 * life of the connection (pass-13, 2× receipts; the pass-44 patch variant at the same
 * gateway). The STAY-OPEN boundary (pass 15) is HEALTHY and must stay so.
 *
 * Three layers pinned:
 *  1. the CONNECTED sweep — a MANAGED session that lost its record is re-subscribed
 *     on the next CONNECTED (the epoch re-stamp);
 *  2. the record-less delta event — a delta arriving for a live record-less pane
 *     re-anchors it (cooldown-bounded) instead of dropping forever;
 *  3. the stay-open boundary — a record-BEARING session is never re-subscribed
 *     (no churn; the Phase-6 re-subscribe-stales-live-projection finding).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RecoveryRecordlessResubscribeTest {

    private companion object {
        val EP = EndpointId("ep-recordless")
    }

    private class FakeGateway : ConversationGateway {
        val bus = MutableSharedFlow<GatewayTopicEvent>(extraBufferCapacity = 64)
        override val topicEvents = bus
        val subscribeCalls = java.util.concurrent.ConcurrentLinkedQueue<String>()
        /** null = the record-less state (the lost epoch); else the current ack epoch. */
        @Volatile var currentEpoch: String? = "epoch-1"
        var subscribeFails: Boolean = false

        override fun startForwarding(scope: CoroutineScope) = Unit

        override suspend fun subscribe(
            sessionId: String,
            visibility: String?,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): GatewayAck {
            if (subscribeFails) throw com.zcode.android.core.transport.TransportFailure(
                com.zcode.android.core.common.error.AppError(
                    category = com.zcode.android.core.common.error.ErrorCategory.NETWORK,
                    userMessage = "down",
                    developerDiagnostic = "subscribe failed (fixture)",
                    retryable = com.zcode.android.core.common.error.AppError.Retryable.AUTO,
                    recoverable = true,
                ),
            )
            subscribeCalls.add(sessionId)
            currentEpoch = "epoch-resub-" + subscribeCalls.size
            return GatewayAck("sub-$sessionId", "snapshot", currentEpoch!!)
        }

        override suspend fun resync(
            sessionId: String,
            baseEpoch: String?,
            baseSeq: Long?,
            forceSnapshot: Boolean,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): GatewayAck = GatewayAck("sub-resync", "snapshot", currentEpoch ?: "epoch-unset")

        override suspend fun subscribeWorkspaceConfig(
            workspacePath: String?,
            workspaceIdentity: String?,
        ): GatewayAck = GatewayAck("sub-cfg", "snapshot", "cfg-epoch-unset")

        override suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int): GatewayPage =
            GatewayPage(emptyList(), 0, currentEpoch ?: "epoch-unset", false)

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
        ): com.zcode.android.core.protocol.v4.CommandAck =
            com.zcode.android.core.protocol.v4.CommandAck(commandId, "accepted", 0, null, null, null)
    }

    private class FakeTransport : Transport {
        override val states = MutableStateFlow(ConnectionStateMachine.ConnectionState.CONNECTED)
        override val capabilities = MutableStateFlow<com.zcode.android.core.transport.CapabilitySnapshot?>(null)
        override val flowPressure = MutableStateFlow(ConnectionFlowState.DRAINED)
        override val inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 16)
        override val appErrors = MutableSharedFlow<com.zcode.android.core.common.error.AppError>(extraBufferCapacity = 16)
        override val cuaPermissionObservations =
            MutableSharedFlow<com.zcode.android.core.protocol.v4.CuaPermissionObservation>(extraBufferCapacity = 8)
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
        if (System.getenv("ZCODE_RR_DEBUG") != null) println("[rr] ${e.level} ${e.event} ${e.details ?: ""}")
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

    private fun harness(clock: () -> Long): Triple<RecoveryCoordinator, FakeGateway, FakeTransport> {
        val gateway = FakeGateway()
        val transport = FakeTransport()
        val interactions = InteractionCoordinator(
            EP,
            InteractionRepositoryImpl(db, clock = System::currentTimeMillis),
            OutboxRepositoryImpl(db),
            gateway,
            clock = clock,
            log = log,
        )
        val coordinator = RecoveryCoordinator(
            endpointId = EP,
            gateway = gateway,
            store = ConversationRepositoryImpl(db, clock = System::currentTimeMillis),
            journalRepo = journal,
            interactions = interactions,
            transport = transport,
            scope = scope,
            clock = clock,
            log = log,
            config = RecoveryCoordinator.Config(holdTickMs = 10),
        )
        return Triple(coordinator, gateway, transport)
    }

    /** Pin 1: the CONNECTED sweep re-subscribes a managed session that lost its record. */
    @Test
    fun `a managed session that lost its subscription record is re-subscribed on the next CONNECTED`() = runBlocking {
        val (coordinator, gateway, transport) = harness(clock = System::currentTimeMillis)
        gateway.currentEpoch = "epoch-1"
        coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget("S", "/ws/a", "ws-a")))
        assertEquals(1, gateway.subscribeCalls.size, "the attach subscribe")

        // The close-cycle unlink: the record dropped while the manager stays live (the
        // pass-13 shape the recovery's re-materialize skipped).
        gateway.currentEpoch = null
        gateway.subscribeCalls.clear()

        // The recovery cycle: dip to RECONNECTING, come back to CONNECTED.
        transport.states.value = ConnectionStateMachine.ConnectionState.RECONNECTING
        transport.states.value = ConnectionStateMachine.ConnectionState.CONNECTED
        withTimeout(10_000) {
            while (gateway.subscribeCalls.isEmpty()) delay(25)
        }
        assertEquals(
            "S",
            gateway.subscribeCalls.last(),
            "the record-less managed session is re-subscribed on CONNECTED (the epoch re-stamp F-P13-A needs)",
        )
        coordinator.close()
    }

    /** Pin 2: a record-less delta for a live manager re-anchors the session mid-connection. */
    @Test
    fun `a record-less delta for a live pane re-anchors the session instead of dropping forever`() = runBlocking {
        var now = 1_000_000L
        val (coordinator, gateway, _) = harness(clock = { now })
        gateway.currentEpoch = "epoch-1"
        coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget("S", "/ws/a", "ws-a")))
        assertEquals(1, gateway.subscribeCalls.size, "the attach subscribe")

        // The record was lost mid-connection (the close-cycle unlink on the OLD socket).
        gateway.currentEpoch = null
        gateway.subscribeCalls.clear()

        // A server-initiated delta arrives for the record-less live pane: the gateway
        // emits the typed event; the coordinator re-anchors (cooldown: once per window).
        gateway.bus.emit(
            GatewayTopicEvent.RecordlessDelta("conversation/S", "S", "ONLINE", 1, 1),
        )
        withTimeout(10_000) {
            while (gateway.subscribeCalls.isEmpty()) delay(25)
        }
        assertEquals("S", gateway.subscribeCalls.last(), "the re-anchor subscribe")

        // Cooldown: a second delta inside the window must NOT re-subscribe again.
        now += 1_000
        gateway.subscribeCalls.clear()
        gateway.bus.emit(
            GatewayTopicEvent.RecordlessDelta("conversation/S", "S", "ONLINE", 2, 2),
        )
        delay(500)
        assertEquals(0, gateway.subscribeCalls.size, "the cooldown bounds the re-anchor (no chase)")
        coordinator.close()
    }

    /** Pin 3: the stay-open boundary — a record-bearing session is NEVER re-subscribed. */
    @Test
    fun `the stay-open boundary - a record-bearing session is never re-subscribed on CONNECTED`() = runBlocking {
        val (coordinator, gateway, transport) = harness(clock = System::currentTimeMillis)
        gateway.currentEpoch = "epoch-1"
        coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget("S", "/ws/a", "ws-a")))
        assertEquals(1, gateway.subscribeCalls.size, "the attach subscribe")
        gateway.subscribeCalls.clear()

        // The pass-15 stay-open shape: the record survives; recovery cycles change nothing.
        transport.states.value = ConnectionStateMachine.ConnectionState.RECONNECTING
        transport.states.value = ConnectionStateMachine.ConnectionState.CONNECTED
        delay(600)
        assertEquals(
            0,
            gateway.subscribeCalls.size,
            "a live record is never re-subscribed (a RE-subscribe would fault and falsely label a healthy streaming projection stale)",
        )
        coordinator.close()
    }

    /** The gateway's null-epoch branch emits the typed event (the production emission line). */
    @Test
    fun `the real gateway emits RecordlessDelta when a conversation frame has no subscription record`() = runBlocking {
        val inbound = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "inbound", "getInbound" -> inbound
                "subscriptionEpoch" -> null // the record-less state
                "ensureConversationListen" -> Unit
                else -> null
            }
        }
        val transport = java.lang.reflect.Proxy.newProxyInstance(
            com.zcode.android.core.transport.AppTransport::class.java.classLoader,
            arrayOf(com.zcode.android.core.transport.AppTransport::class.java),
            handler,
        ) as com.zcode.android.core.transport.AppTransport
        val gateway = RelayConversationGateway(transport, clientId = "ep-recordless", traceLog = log)
        gateway.startForwarding(scope)
        val events = java.util.concurrent.ConcurrentLinkedQueue<GatewayTopicEvent>()
        val collector = scope.launch {
            gateway.topicEvents.collect { events.add(it) }
        }
        delay(300) // the collector subscribes
        inbound.emit(
            InboundFrame.TopicFrameArrived(
                topic = "conversation/S",
                subscriptionId = "sub-1",
                deliveryKind = com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE,
                fromSeq = 1,
                toSeq = 1,
                frame = com.zcode.android.core.protocol.v4.TopicFrame(
                    topic = "conversation/S",
                    subscriptionId = "sub-1",
                    fromSeq = 1,
                    toSeq = 1,
                    sentAt = 1.0,
                    payload = com.zcode.android.core.protocol.v4.TopicFrame.TopicPayload.Deltas(emptyList()),
                ),
            ),
        )
        withTimeout(5_000) {
            while (events.none { it is GatewayTopicEvent.RecordlessDelta }) delay(25)
        }
        val ev = events.first { it is GatewayTopicEvent.RecordlessDelta } as GatewayTopicEvent.RecordlessDelta
        assertEquals("S", ev.sessionId)
        assertEquals("ONLINE", ev.deliveryKind)
        assertEquals(1L, ev.fromSeq)
        collector.cancel()
        Unit
    }
}

