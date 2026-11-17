package com.zcode.android.app.connect

import com.zcode.android.core.common.error.UserMessageKey
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * F-P9-B (pass-0 Phase 1): the typed disconnected-REMOTE face never rendered LIVE —
 * the hub logged `hub.connectFailed :: SESSION workspace-bridge-error:
 * remote-disconnected` while `hub.lastError` stayed null, so the Connection Center's
 * G67 card (`connection_remote_reconnect`, render condition
 * `lastError.messageKey == REMOTE_WORKSPACE_DISCONNECTED`) never rendered and the
 * recovery verb (workspace-reconnect-request) was UI-unreachable. The pass-9 receipt
 * ran against the LIVE rig (Part L.A2, MOCK_REMOTE_DOWN_MS); this harness arms the
 * mock's remote-down fixture and asserts the typed error reaches `hub.lastError`
 * end-to-end on the hub's own connect path (the card render itself is pinned by
 * ConnectionCenterRemoteReconnectCardTest).
 */
class ConnectionHubTypedErrorFaceTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 17 })
    private val sid = "d_TypedFaceSynthetic1"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var hub: ConnectionHub
    private lateinit var scope: CoroutineScope

    @AfterTest
    fun tearDown() {
        if (!::scope.isInitialized) return
        runBlocking { runCatching { transport.close("test end") } }
        scope.cancel()
        mock.stop()
    }

    @Test
    fun `F-P9-B - the typed remote-disconnected error reaches the hub surface on the live connect path`() = runBlocking {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        val url = mock.start()
        mock.armRemoteDown() // every bridge-open fails with the typed disconnected error
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val vault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), ZLog(sink = {}))
        vault.seal(
            PairingLink(
                endpointOrigin = url.removeSuffix("/ws").replaceFirst("ws://", "http://"),
                route = "/remote/v4",
                deviceSid = sid,
                passHash = passHash,
                issuedAtMs = System.currentTimeMillis(),
                deviceMid = "mock-desktop-mid",
                desktopName = "TypedFaceMockBook",
                appVersion = "3.14.3",
            ),
        )
        // Fast-cycling transport config + a TINY attempt budget: the ladder exhausts
        // quickly and emits the GENERIC degraded-budget error ("Still offline —
        // showing cached state") — the pass-9 live overwrite that buried the typed
        // card. The pin asserts the TYPED key SURVIVES the exhaustion.
        transport = RelayTransport(
            wsFactory = OkHttpWsFactory(),
            log = ZLog(sink = {}),
            reconnectPolicyOverride = com.zcode.android.core.transport.ReconnectPolicy(
                clock = System::currentTimeMillis,
                baseDelayMs = 10,
                maxDelayMs = 20,
                budgetMs = 1,
            ),
            config = RelayTransport.Config(
                connectTimeoutMs = 3_000,
                authTimeoutMs = 3_000,
                bridgeTimeoutMs = 3_000,
                channelInitTimeoutMs = 3_000,
                controlRequestTimeoutMs = 3_000,
                heartbeatIntervalMs = 200,
                heartbeatWatchdogMs = 1_500,
                v4CallTimeoutMs = 3_000,
                bridgeHandshakeTimeoutMs = 4_000,
            ),
        )
        hub = ConnectionHub(
            transport = transport,
            directTransport = testDirectTransport(),
            preferences = FakePreferencesRepository(),
            vault = vault,
            taskRepo = FakeTaskRepository(),
            conversationRepo = FakeConversationRepository(),
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = FakeEndpointRepository(),
            outbox = InMemoryOutboxRepository(),
            drafts = InMemoryDraftRepository(),
            scope = scope,
            log = ZLog(sink = {}),
        )
        hub.start()
        // The ladder exhausts under the armed remote-down fixture → DEGRADED — and the
        // typed error must STILL be the hub's lastError (the card's render condition),
        // never overwritten by the generic budget-exhausted face.
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.DEGRADED } }
        delay(500) // let the exhaustion emit land
        val error = hub.lastError.value
        assertNotNull(error, "the typed error survives the budget exhaustion (never a silent face)")
        assertEquals(
            UserMessageKey.REMOTE_WORKSPACE_DISCONNECTED,
            error.messageKey,
            "the card's render condition carries the typed key — the generic degraded-budget face never buries it",
        )
        Unit
    }
}
