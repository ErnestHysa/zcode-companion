package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.core.transport.DirectTransport
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.EndpointKind
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 10.2/10.3 — the app-level U22 suite: endpoint registry, add-direct flow
 * (validated before anything is written, §12.2), switchTo across kinds (§11.6),
 * the direct task plane feeding the SAME §7.11/INV-12 identity-keyed projection,
 * and per-endpoint removal with relay fallback. Runs against TWO in-process mocks
 * (relay + direct) — CI never touches a real server (INV-15).
 */
class ConnectionHubDirectEndpointTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid = "d_Phase10SyntheticSid01"
    private val directToken = "phase10-direct-token-xyz"

    private val relayMock = MockZCode(passHash = passHash, expectedSid = sid).apply {
        script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "relay-task-1",
                title = "Relay plane task",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "relay-ws",
            ),
        )
    }
    private val directMock = MockZCode(
        passHash = "unused", expectedSid = "unused",
        mode = MockZCode.Mode.DIRECT, serverToken = directToken,
        workspace = MockZCode.MockWorkspace(workspacePath = "/synthetic/direct-ws", workspaceKey = "direct-ws"),
    )

    private lateinit var scope: CoroutineScope
    private lateinit var vault: CredentialVault
    private lateinit var hub: ConnectionHub
    private lateinit var relayTransport: RelayTransport
    private lateinit var directTransport: DirectTransport
    private val tasks = FakeTaskRepository()
    private val endpoints = FakeEndpointRepository()
    private val prefs = FakePreferencesRepository()

    @AfterTest
    fun tearDown() {
        runBlocking {
            runCatching { relayTransport.close("test end") }
            runCatching { directTransport.close("test end") }
        }
        scope.cancel()
        relayMock.stop()
        directMock.stop()
    }

    private fun taskJson(taskId: String, title: String, status: String, withIdentity: String? = null) = buildJsonObject {
        put("taskId", JsonPrimitive(taskId))
        put("title", JsonPrimitive(title))
        put("status", JsonPrimitive(status))
        put("workspacePath", JsonPrimitive("/synthetic/direct-ws"))
        withIdentity?.let { put("workspaceIdentity", JsonPrimitive(it)) }
        put("createdAt", JsonPrimitive(1_700_000_000_000.0))
        put("updatedAt", JsonPrimitive(1_700_000_400_000.0))
    }

    private suspend fun setup(pairedRelay: Boolean) {
        relayMock.start()
        directMock.script.directTasks = listOf(
            taskJson("direct-task-1", "Direct plane task", "running"),
            taskJson("direct-task-2", "Identity-keyed task", "waiting", withIdentity = "remote-host-identity"),
        )
        directMock.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        vault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), ZLog(sink = {}))
        if (pairedRelay) {
            vault.seal(
                PairingLink(
                    endpointOrigin = relayMock.url.removeSuffix("/ws").replaceFirst("ws://", "http://"),
                    route = "/remote/v4",
                    deviceSid = sid,
                    passHash = passHash,
                    issuedAtMs = System.currentTimeMillis(),
                    deviceMid = "mock-desktop-mid",
                    desktopName = "Phase10MockBook",
                    appVersion = "3.14.3",
                ),
            )
        }
        relayTransport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        directTransport = DirectTransport(
            tokenProvider = { "" },
            wsFactory = OkHttpWsFactory(),
            log = ZLog(sink = {}),
        )
        hub = ConnectionHub(
            transport = relayTransport,
            directTransport = directTransport,
            vault = vault,
            taskRepo = tasks,
            conversationRepo = FakeConversationRepository(),
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = endpoints,
            preferences = prefs,
            outbox = InMemoryOutboxRepository(),
            drafts = InMemoryDraftRepository(),
            scope = scope,
            log = ZLog(sink = {}),
        )
    }

    private suspend fun awaitPhase(target: ConnectionPhase, timeoutMs: Long = 30_000) {
        withTimeout(timeoutMs) {
            while (hub.phase.value != target) delay(50)
        }
    }

    // ---------------------------------------------------------------- add flow

    @Test
    fun `add-direct validates first and persists nothing on a wrong token`() = runBlocking {
        setup(pairedRelay = false)
        val rejected = hub.addDirectEndpoint("NAS", directMock.url, "wrong-token")
        assertIs<ConnectionHub.AddDirectOutcome.Rejected>(rejected)
        assertEquals(
            com.zcode.android.core.common.error.ErrorCategory.AUTH,
            rejected.error.category,
        )
        assertTrue(endpoints.saved.value.isEmpty(), "nothing persisted on a failed validation (§12.2)")
        assertFalse(vault.hasDirectToken("any"))

        val added = hub.addDirectEndpoint("NAS", directMock.url, directToken)
        assertIs<ConnectionHub.AddDirectOutcome.Added>(added)
        val row = endpoints.saved.value.single()
        assertEquals(EndpointKind.DIRECT_SERVER, row.kind)
        assertTrue(vault.hasDirectToken(row.endpointId.raw))
        Unit
    }

    // ---------------------------------------------------------------- U22 switch matrix

    @Test
    fun `switch matrix - relay to direct and back, identity-keyed task plane`() = runBlocking {
        setup(pairedRelay = true)
        hub.start()
        awaitPhase(ConnectionPhase.CONNECTED)
        // Relay plane live: the bootstrap task is projected under its §7.11 key.
        val relayEndpointId = vault.loadMetadata()!!.endpointId
        assertEquals("Relay plane task", hub.currentTasks().single().title)

        val added = hub.addDirectEndpoint("NAS", directMock.url, directToken)
        assertIs<ConnectionHub.AddDirectOutcome.Added>(added)
        val directId = added.endpoint.endpointId.raw

        // ---- switch RELAY → DIRECT
        assertTrue(hub.switchTo(directId))
        awaitPhase(ConnectionPhase.CONNECTED)
        assertEquals(EndpointKind.DIRECT_SERVER, hub.endpoint.value?.kind)
        assertEquals(prefs.current().selectedEndpointId, directId, "selection persists (§11.6)")
        val directTasks = hub.currentTasks()
        assertEquals(2, directTasks.size)
        // INV-12 identity rule: no workspaceIdentity on the row → key = path (§7.11 verbatim)
        val plain = directTasks.first { it.taskId == "direct-task-1" }
        assertEquals(WorkspaceIdentityKey("/synthetic/direct-ws"), plain.workspace)
        // …and workspaceIdentity present → the identity WINS over the path
        val identified = directTasks.first { it.taskId == "direct-task-2" }
        assertEquals(WorkspaceIdentityKey("remote-host-identity"), identified.workspace)
        // Workspaces offered from server-info (label + §7.11 key)
        val ws = hub.workspaces.value.single()
        assertEquals("direct-ws", ws.label)
        assertEquals("/synthetic/direct-ws", ws.path)
        assertEquals("/synthetic/direct-ws", ws.key)
        // server-info surfaced (§11.6 workspace-list source)
        assertEquals("/synthetic/direct-ws", hub.directServerInfo.value?.workspaces?.single()?.path)

        // ---- switch DIRECT → RELAY
        assertTrue(hub.switchTo(relayEndpointId))
        awaitPhase(ConnectionPhase.CONNECTED)
        assertEquals(EndpointKind.RELAY_ZAI, hub.endpoint.value?.kind)
        assertEquals("Relay plane task", hub.currentTasks().single().title)
        assertEquals(relayEndpointId, prefs.current().selectedEndpointId)
        // LIVENESS: the switched-back relay transport must serve FRESH traffic — a
        // state-machine reset miss here left the socket closed but the machine
        // "CONNECTED" (caught by the HistoryPagination device suite, fixed by
        // resetForEndpointSwitch). Mutate the mock, refresh, assert the new row.
        relayMock.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "relay-task-2",
                title = "After switch-back",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "relay-ws",
            ),
        )
        assertTrue(hub.refreshTasks())
        assertTrue(hub.currentTasks().any { it.taskId == "relay-task-2" }, "switch-back must leave a LIVE relay transport")
    }

    @Test
    fun `direct conversation plane works after switch (subscribe lands rows)`() = runBlocking {
        val conversations = FakeConversationRepository()
        setup(pairedRelay = false)
        hub = ConnectionHub(
            transport = relayTransport,
            directTransport = directTransport,
            vault = vault,
            taskRepo = tasks,
            conversationRepo = conversations,
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = endpoints,
            preferences = prefs,
            outbox = InMemoryOutboxRepository(),
            drafts = InMemoryDraftRepository(),
            scope = scope,
            log = ZLog(sink = {}),
        )
        hub.start()
        val added = hub.addDirectEndpoint("NAS", directMock.url, directToken)
        assertIs<ConnectionHub.AddDirectOutcome.Added>(added)
        assertTrue(hub.switchTo(added.endpoint.endpointId.raw))
        awaitPhase(ConnectionPhase.CONNECTED)
        val task = hub.currentTasks().first { it.taskId == "direct-task-1" }
        val coordinator = hub.openConversation(task) as com.zcode.android.data.session.RecoveryCoordinator
        // The session's snapshot must land in the page cache through the SAME
        // pipeline the relay uses (INV-6: the direct transport is invisible above
        // the AppTransport seam).
        withTimeout(20_000) {
            conversations.rowsFor(task.taskId).first { it.isNotEmpty() }
        }
        assertTrue(coordinator.managedSessions().contains(task.taskId))
    }

    @Test
    fun `removing the ACTIVE direct endpoint falls back to the relay pairing`() = runBlocking {
        setup(pairedRelay = true)
        hub.start()
        awaitPhase(ConnectionPhase.CONNECTED)
        val relayEndpointId = vault.loadMetadata()!!.endpointId
        val added = hub.addDirectEndpoint("NAS", directMock.url, directToken)
        assertIs<ConnectionHub.AddDirectOutcome.Added>(added)
        assertTrue(hub.switchTo(added.endpoint.endpointId.raw))
        awaitPhase(ConnectionPhase.CONNECTED)

        hub.removeDirectEndpoint(added.endpoint.endpointId.raw)
        awaitPhase(ConnectionPhase.CONNECTED)
        assertEquals(EndpointKind.RELAY_ZAI, hub.endpoint.value?.kind)
        assertTrue(prefs.current().selectedEndpointId == null || prefs.current().selectedEndpointId == relayEndpointId)
        assertFalse(vault.hasDirectToken(added.endpoint.endpointId.raw))
        assertTrue(hub.currentTasks().any { it.taskId == "relay-task-1" })
    }

    @Test
    fun `cold start honors the persisted direct selection`() = runBlocking {
        setup(pairedRelay = true)
        val added = hub.addDirectEndpoint("NAS", directMock.url, directToken)
        assertIs<ConnectionHub.AddDirectOutcome.Added>(added)
        prefs.update { it.copy(selectedEndpointId = added.endpoint.endpointId.raw) }
        hub.start()
        awaitPhase(ConnectionPhase.CONNECTED)
        assertEquals(EndpointKind.DIRECT_SERVER, hub.endpoint.value?.kind)
        assertTrue(hub.currentTasks().any { it.taskId == "direct-task-1" })
    }

    @Test
    fun `direct connect failure lands in the connection state honestly`() = runBlocking {
        setup(pairedRelay = false)
        directMock.script.directRejectToken = true
        hub.start()
        val added = hub.addDirectEndpoint("NAS", directMock.url, directToken)
        // The ADD validates too — with the rejection knob on it must refuse.
        assertIs<ConnectionHub.AddDirectOutcome.Rejected>(added)
        directMock.script.directRejectToken = false
        val ok = hub.addDirectEndpoint("NAS", directMock.url, directToken)
        assertIs<ConnectionHub.AddDirectOutcome.Added>(ok)
        // Rotate the token server-side: connect must land AUTH/FAILED, not a fake Connected.
        directMock.script.directRejectToken = true
        directTransport.rebindTokenProvider { "stale-token" }
        assertTrue(hub.switchTo(ok.endpoint.endpointId.raw))
        withTimeout(30_000) {
            while (hub.phase.value != ConnectionPhase.FAILED) delay(50)
        }
        assertFalse(hub.phase.value == ConnectionPhase.CONNECTED)
    }
}

