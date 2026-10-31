package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GAP-A3/G14 (vc51) — the unsubscribe-on-exit pins. The recorded defect: the host
 * serves `unsubscribeConversationV4`/`unsubscribeWorkspaceConfigV4` and the phone
 * had ZERO call sites — switching tasks left stale session subscriptions armed
 * (wasted frames, stale-stream risk after reconnects because the transport's
 * recover() pass re-subscribed every dropped record).
 *
 * Three layers pinned here:
 *  1. WIRE — gateway.unsubscribe over the REAL RelayTransport ↔ MockZCode loopback
 *     sends `unsubscribeConversationV4 {workspacePath, workspaceIdentity?,
 *     subscriptionId}` (the desktop adapter's owned-subscription shape) and drops
 *     the transport's subscription record (subscriptionEpoch → null = recover()
 *     will not re-subscribe the dead topic);
 *  2. PLANE — an unknown session unsubscribe is a silent no-op (no wire traffic);
 *  3. COORDINATOR — RecoveryCoordinator.detach removes the manager + watchdog state
 *     so the stream watchdog can never resubscribe a detached session.
 */
class ConversationUnsubscribeTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
    private val sid: String get() = "d_unsub_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var gateway: RelayConversationGateway
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
        scope.cancel()
    }

    private fun connect(): RelayConversationGateway {
        var last: Exception? = null
        for (attempt in 0..2) {
            try {
                mock = MockZCode(passHash, sid)
                val url = mock.start()
                transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
                runBlocking {
                    withTimeout(30_000) {
                        transport.connect(
                            EndpointDescriptor(
                                id = "ep-unsub",
                                kind = EndpointKind.RELAY_ZAI,
                                displayName = "UnsubMockBook",
                                wsUrl = url,
                                workspaceKey = "synthetic-ws",
                                workspacePath = "/synthetic/ws",
                                deviceSid = sid,
                                deviceMid = "mock-desktop-mid",
                            ),
                            signer(),
                        )
                    }
                }
                gateway = RelayConversationGateway(transport, clientId = "ep-unsub", traceLog = null)
                gateway.startForwarding(scope)
                return gateway
            } catch (e: Exception) {
                last = e
                runBlocking { runCatching { transport.close("connect retry") } }
                runCatching { mock.stop() }
                Thread.sleep(500)
            }
        }
        throw last ?: IllegalStateException("connect failed without a recorded error")
    }

    private fun signer(): CredentialSigner = CredentialSigner { nonce, role, s ->
        MockZCode.hmacProof(passHash, nonce, role, s)
    }

    @Test
    fun `unsubscribe sends the wire call with the ack's subscriptionId and drops the local record`() {
        val gw = connect()
        val session = "sess-unsub-1"
        runBlocking {
            val ack = gw.subscribe(session, workspacePath = "/synthetic/ws", workspaceIdentity = "synthetic-ws")
            assertEquals("sub-mock-$session", ack.subscriptionId)
            assertEquals("epoch-mock-1", transport.subscriptionEpoch(session))

            gw.unsubscribe(session, workspacePath = "/synthetic/ws", workspaceIdentity = "synthetic-ws")
        }
        val call = mock.v4CallsForTest().firstOrNull { it.first == "unsubscribeConversationV4" }
        assertTrue(call != null, "the teardown must reach the wire as unsubscribeConversationV4 (GAP-A3: zero call sites was the defect)")
        assertEquals("sub-mock-$session", call!!.second["subscriptionId"]?.toString()?.trim('"'))
        assertEquals("/synthetic/ws", call.second["workspacePath"]?.toString()?.trim('"'))
        assertNull(
            transport.subscriptionEpoch(session),
            "the local record must drop — recover() re-subscribes only still-owned topics (no duplicate stale rows after a reconnect)",
        )
    }

    @Test
    fun `unsubscribe of a never-subscribed session sends nothing`() {
        val gw = connect()
        runBlocking {
            gw.unsubscribe("sess-never-subscribed", workspacePath = "/synthetic/ws", workspaceIdentity = null)
        }
        assertTrue(
            mock.v4CallsForTest().none { it.first == "unsubscribeConversationV4" },
            "no record = no wire traffic (best-effort teardown)",
        )
    }

    @Test
    fun `workspace-config unsubscribe rides unsubscribeWorkspaceConfigV4`() {
        val gw = connect()
        runBlocking {
            gw.subscribeWorkspaceConfig("/synthetic/ws", "synthetic-ws")
            gw.unsubscribeWorkspaceConfig("/synthetic/ws", "synthetic-ws")
        }
        val call = mock.v4CallsForTest().firstOrNull { it.first == "unsubscribeWorkspaceConfigV4" }
        assertTrue(call != null, "the catalog plane exposes the second host word (G14)")
    }
}

/** The coordinator layer: detach removes the manager + watchdog state and unsubscribes. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [30])
class RecoveryCoordinatorDetachTest {

    private class RecordingGateway : ConversationGateway {
        val unsubscribes = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String?, String?>>()
        val subscribeCalls = java.util.concurrent.ConcurrentLinkedQueue<String>()
        override val topicEvents = kotlinx.coroutines.flow.MutableSharedFlow<GatewayTopicEvent>()
        override fun startForwarding(scope: CoroutineScope) = Unit
        override suspend fun subscribe(
            sessionId: String,
            visibility: String?,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): GatewayAck {
            subscribeCalls.add(sessionId)
            return GatewayAck("sub-$sessionId", "snapshot", "epoch-1")
        }

        override suspend fun resync(
            sessionId: String,
            baseEpoch: String?,
            baseSeq: Long?,
            forceSnapshot: Boolean,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): GatewayAck = GatewayAck("sub-$sessionId", "snapshot", "epoch-1")

        override suspend fun rowsRange(sessionId: String, beforeRowId: Long?, limit: Int) =
            GatewayPage(emptyList(), 0, "epoch-1", false)

        override suspend fun unsubscribe(
            sessionId: String,
            workspacePath: String?,
            workspaceIdentity: String?,
        ) {
            unsubscribes.add(Triple(sessionId, workspacePath, workspaceIdentity))
        }

        override suspend fun subscribeWorkspaceConfig(
            workspacePath: String?,
            workspaceIdentity: String?,
        ): GatewayAck = GatewayAck("sub-cfg", "snapshot", "cfg-epoch-1")

        override fun currentSubscriptionEpoch(sessionId: String): String? = "epoch-1"

        override suspend fun send(
            type: String,
            sessionId: String?,
            payload: kotlinx.serialization.json.JsonObject,
            baseRevision: Long?,
            baseLogEpoch: String?,
            commandId: String,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): com.zcode.android.core.protocol.v4.CommandAck =
            com.zcode.android.core.protocol.v4.CommandAck(commandId, "accepted", 0, null, null, null)
    }

    @Test
    fun `detach removes the manager and unsubscribes with the session's workspace target`() = runBlocking {
        val gw = RecordingGateway()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            com.zcode.android.data.db.ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        val endpoint = EndpointId("ep-detach")
        val interactions = InteractionCoordinator(
            endpointId = endpoint,
            interactions = com.zcode.android.data.repo.InteractionRepositoryImpl(db, clock = System::currentTimeMillis),
            outbox = com.zcode.android.data.repo.OutboxRepositoryImpl(db),
            commands = gw,
        )
        val coordinator = RecoveryCoordinator(
            endpointId = endpoint,
            gateway = gw,
            store = com.zcode.android.data.repo.ConversationRepositoryImpl(db, clock = System::currentTimeMillis),
            journalRepo = com.zcode.android.data.repo.JournalRepositoryImpl(db, clock = System::currentTimeMillis),
            interactions = interactions,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        coordinator.startTargets(
            listOf(RecoveryCoordinator.SessionTarget("sess-detach-1", "/ws/a", "ws-a")),
        )
        assertTrue(coordinator.manager("sess-detach-1") != null)

        coordinator.detach("sess-detach-1")

        assertTrue(coordinator.manager("sess-detach-1") == null, "the detached manager must leave the projection plane")
        val un = gw.unsubscribes.firstOrNull { it.first == "sess-detach-1" }
        assertTrue(un != null, "detach unsubscribes on the wire (GAP-A3)")
        assertEquals("/ws/a", un!!.second)
        assertEquals("ws-a", un.third)
        // Re-attach is fresh: startTargets subscribes again after a detach.
        coordinator.startTargets(
            listOf(RecoveryCoordinator.SessionTarget("sess-detach-1", "/ws/a", "ws-a")),
        )
        assertTrue(coordinator.manager("sess-detach-1") != null, "re-entry re-attaches (the open-dedupe was cleared)")
        db.close()
    }
}
