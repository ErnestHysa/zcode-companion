package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.security.vault.DirectEndpointMetadata
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.core.transport.DirectTransport
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.model.EndpointKind
import com.zcode.android.domain.repo.EndpointRepository
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * F-P8-A / F-P8-B (pass-0 Phase 1, Sub-phase 1A — endpoint persistence & wedges).
 *
 * F-P8-A: on a cold start with a DIRECT endpoint selected and a relay pairing
 * coexisting, the pairing edge's deferred `persistEndpoint` landed AFTER
 * `resolveActiveEndpoint()` had honored the persisted selection and rewrote the
 * ACTIVE pointer back to the relay SILENTLY (no hub.switched, no log): the direct
 * transport's own recovery was then dropped by the sourceKind filter, the first-sync
 * refresh bootstrapped the never-connected relay, and "First sync failed" showed
 * against a healthy direct server. Pinned here by RECREATING the race
 * deterministically (the resolve parks on the registry gate; the pairing edge's
 * deferred persist is enqueued behind it and lands after the flip).
 *
 * F-P8-B: switching to an endpoint whose transport ALREADY holds a connection (the
 * adopted-connection switch) used to throw ISE `connect() is the first-connection
 * path` — the state machine treated the switch as a cold start.
 */
class ConnectionHubEndpointPersistenceTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 11 })
    private val sid = "d_EndpointPersistSynthetic1"
    private val directToken = "pass0-phase1-direct-token"

    private val directMock = MockZCode(
        passHash = "unused",
        expectedSid = "unused",
        mode = MockZCode.Mode.DIRECT,
        serverToken = directToken,
        workspace = MockZCode.MockWorkspace(workspacePath = "/synthetic/direct-ws", workspaceKey = "direct-ws"),
    )
    private val relayMock = MockZCode(passHash = passHash, expectedSid = sid)

    private lateinit var scope: CoroutineScope
    private lateinit var vault: CredentialVault
    private lateinit var hub: ConnectionHub
    private lateinit var relayTransport: RelayTransport
    private lateinit var directTransport: DirectTransport
    private val tasks = FakeTaskRepository()
    private val endpoints = FakeEndpointRepository()
    private val prefs = FakePreferencesRepository()
    private val logLines = Collections.synchronizedList(ArrayList<String>())

    @AfterTest
    fun tearDown() {
        if (!::scope.isInitialized) return
        runBlocking {
            runCatching { relayTransport.close("test end") }
            runCatching { directTransport.close("test end") }
        }
        scope.cancel()
        relayMock.stop()
        directMock.stop()
    }

    private fun log() = ZLog(sink = { e -> logLines.add("${e.event} ${e.details ?: ""}") })

    /** The §12.4 direct endpoint row + its vault token, ready for resolveActiveEndpoint. */
    private suspend fun seedDirectEndpoint(): Endpoint {
        val row = Endpoint(
            endpointId = EndpointId("ep-direct-pass0"),
            kind = EndpointKind.DIRECT_SERVER,
            displayName = "Pass0DirectBook",
            wsUrl = directMock.url,
            pairedAtMs = System.currentTimeMillis(),
        )
        vault.sealDirectToken(
            directToken,
            DirectEndpointMetadata(
                endpointId = row.endpointId.raw,
                displayName = row.displayName,
                wsUrl = row.wsUrl,
                addedAtMs = System.currentTimeMillis(),
            ),
        )
        endpoints.save(row)
        return row
    }

    private fun sealRelayPairing(url: String) {
        vault.seal(
            PairingLink(
                endpointOrigin = url.removeSuffix("/ws").replaceFirst("ws://", "http://"),
                route = "/remote/v4",
                deviceSid = sid,
                passHash = passHash,
                issuedAtMs = System.currentTimeMillis(),
                deviceMid = "mock-desktop-mid",
                desktopName = "Pass0RelayBook",
                appVersion = "3.14.3",
            ),
        )
    }

    private fun buildHub(endpointRepo: EndpointRepository) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        vault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), log())
        relayTransport = RelayTransport(wsFactory = OkHttpWsFactory(), log = log())
        directTransport = DirectTransport(tokenProvider = { directToken }, wsFactory = OkHttpWsFactory(), log = log())
        hub = ConnectionHub(
            transport = relayTransport,
            directTransport = directTransport,
            vault = vault,
            taskRepo = tasks,
            conversationRepo = FakeConversationRepository(),
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = endpointRepo,
            preferences = prefs,
            outbox = InMemoryOutboxRepository(),
            drafts = InMemoryDraftRepository(),
            scope = scope,
            log = log(),
        )
    }

    /**
     * F-P8-A: seed relay → the resolve picks the persisted DIRECT selection → the
     * pairing edge's deferred relay persist settles AFTER the flip → the ACTIVE
     * pointer must stay DIRECT. Also pinned: `hub.switched` NEVER logs without a user
     * endpoint tap, the hub phase follows the DIRECT transport (no false failed strip:
     * taskLoadError stays null), and Retry reconnects the transport the UI shows.
     */
    @Test
    fun `F-P8-A - the deferred pairing-edge persist never abandons a resolved DIRECT selection`() = runBlocking {
        directMock.start()
        relayMock.start()
        // The race recreation, DETERMINISTIC: gated registry reads order the two
        // contenders (Dispatchers.IO runs them on separate threads — a single gate is
        // a race). gate1 parks the resolve's refreshRegistry; gate2 parks the pairing
        // edge's deferred refreshRegistry. The flip lands BEFORE the persist, exactly
        // the recorded F-P8-A window.
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val gatedCalls = java.util.concurrent.atomic.AtomicInteger()
        val gatedRepo = object : EndpointRepository by endpoints {
            override suspend fun endpoints(): List<Endpoint> {
                when (gatedCalls.getAndIncrement()) {
                    0 -> gate1.await() // resolveActiveEndpoint's refreshRegistry
                    1 -> gate2.await() // onPairingCompleted's deferred refreshRegistry
                }
                return endpoints.endpoints()
            }
        }
        buildHub(gatedRepo)
        seedDirectEndpoint()
        sealRelayPairing(relayMock.url)
        prefs.update { it.copy(selectedEndpointId = "ep-direct-pass0") }

        hub.start() // resolve parks on gate1
        delay(300) // the seed (relay) + the parked resolve settle
        assertEquals(
            EndpointKind.RELAY_ZAI,
            hub.endpoint.value?.kind,
            "the race window: the synchronous seed is the relay pairing",
        )
        // The vault generates the relay endpoint's registry id (NOT the deviceSid) —
        // capture it from the seed so the persist-landed wait matches the saved row.
        val relayEndpointId = hub.endpoint.value!!.endpointId.raw
        // The pairing waypoint fires while the seed is current → the same-id branch →
        // refreshRegistry+persistEndpoint ASYNC (the F-P8-A trigger).
        hub.onPairingCompleted() // the persist parks on gate2
        delay(300)

        gate1.complete(Unit) // the resolve resumes: flips DIRECT, connects
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        gate2.complete(Unit) // NOW the deferred persist lands (after the flip)
        withTimeout(15_000) {
            while (endpoints.saved.value.none { it.endpointId.raw == relayEndpointId }) delay(50)
        }
        delay(500) // let the post-persist assignment race settle

        assertEquals(
            EndpointKind.DIRECT_SERVER,
            hub.endpoint.value?.kind,
            "the deferred relay persist must NOT abandon the resolved DIRECT selection (F-P8-A)",
        )
        assertEquals("Pass0DirectBook", hub.endpointName.value)
        assertNull(hub.taskLoadError.value, "no false 'First sync failed' against a healthy direct server")
        assertTrue(
            directTransport.states.value == ConnectionStateMachine.ConnectionState.CONNECTED,
            "the DIRECT transport the UI shows stays the active connection",
        )
        // F-P8-A's signature: the abandonment was SILENT (no hub.switched, ever).
        assertTrue(
            logLines.none { it.startsWith("hub.switched") },
            "hub.switched must never log without a user endpoint tap: ${logLines.filter { it.startsWith("hub.switched") }}",
        )
        // Retry reconnects the transport the UI shows (the DIRECT one) and holds it.
        hub.retry()
        delay(1_000)
        assertEquals(ConnectionPhase.CONNECTED, hub.phase.value)
        assertEquals(EndpointKind.DIRECT_SERVER, hub.endpoint.value?.kind)
        Unit
    }

    /**
     * F-P8-B: the adopted-connection switch — the direct transport holds a CONNECTED
     * connection while the hub's active pointer says otherwise (the user taps the
     * row). The switch must NOT throw ISE `connect() is the first-connection path`,
     * must complete its refresh, and the connection holds.
     */
    @Test
    fun `F-P8-B - switching to an already-CONNECTED direct endpoint does not throw and holds`() = runBlocking {
        directMock.start()
        relayMock.start()
        buildHub(endpoints)
        val directRow = seedDirectEndpoint()
        sealRelayPairing(relayMock.url)
        // No persisted selection → resolve falls back to the RELAY pairing.
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }

        // Manufacture the adopted-connection state: the direct transport connects
        // OUTSIDE the hub while the active pointer stays on the relay.
        directTransport.connect(
            com.zcode.android.core.transport.EndpointDescriptor(
                id = directRow.endpointId.raw,
                kind = com.zcode.android.core.transport.EndpointKind.DIRECT_SERVER,
                displayName = directRow.displayName,
                wsUrl = directRow.wsUrl,
                deviceSid = null,
                deviceMid = null,
            ),
        ) { _, _, _ -> directToken }
        withTimeout(30_000) {
            while (directTransport.states.value != ConnectionStateMachine.ConnectionState.CONNECTED) delay(50)
        }

        // The user taps the direct row: the switch proceeds (different id) into
        // connectDirect — which must adopt the LIVE connection, not throw ISE.
        val switched = hub.switchTo(directRow.endpointId.raw)
        delay(1_000)
        assertTrue(switched, "the switch to the connected endpoint converges")
        assertEquals(ConnectionPhase.CONNECTED, hub.phase.value)
        assertEquals(
            ConnectionStateMachine.ConnectionState.CONNECTED,
            directTransport.states.value,
            "the adopted connection holds through the switch",
        )
        assertTrue(
            hub.lastRefreshAtMs.value != null,
            "the switch completes its task refresh (the adopted-connection path is not a cold start)",
        )
        assertTrue(
            logLines.none { it.startsWith("hub.directConnectFailed") },
            "no ISE connect()-is-the-first-connection-path failure: ${logLines.filter { it.startsWith("hub.directConnectFailed") }}",
        )
        Unit
    }
}
