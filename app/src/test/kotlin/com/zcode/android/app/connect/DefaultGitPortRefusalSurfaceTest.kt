package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.core.transport.TransportFailure
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * F-P5-A (pass-0 Phase 4): refused git reads rendered SILENT-EMPTY sections —
 * headers, zero rows, no error. The render site EXISTS (GitScreen's changesError
 * face + Retry) but is dead-coded behind the port's `?: emptyList()` swallow: ANY
 * getChanges failure mapped to an empty list, so fetchChanges's runCatching never
 * saw a throw. The LIVE shape: the refused read PROPAGATES from the port boundary
 * (the compose-level error-face pins own the screen half).
 */
class DefaultGitPortRefusalSurfaceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 11 })
    private val sid: String get() = "d_gp5a_${System.identityHashCode(this)}"
    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
        scope.cancel()
    }

    private fun connectedPort(refusedMethods: Map<String, String>): GitPortLike {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        refusedMethods.forEach { (method, code) -> mock.script.gitRefusals[method] = code }
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(id = "ep-gp5a", kind = EndpointKind.RELAY_ZAI, displayName = "Gp5aMockBook", wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws", deviceSid = sid, deviceMid = "mock-desktop-mid"),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
            }
        }
        val vault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), ZLog(sink = {}))
        val hub = ConnectionHub(
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
        return DefaultGitPort(hub)
    }

    /** Local alias so the import list stays honest (the port interface is the shape). */
    private typealias GitPortLike = com.zcode.android.features.git.port.GitPort

    @Test
    fun `a refused getChanges PROPAGATES from the port boundary - F-P5-A`() = runBlocking {
        val port = connectedPort(refusedMethods = mapOf("getChanges" to "fault.git.notAvailable"))
        val error = runCatching { port.changes("unstaged") }.exceptionOrNull()
        assertTrue(
            error != null,
            "the refused read must THROW at the port boundary (RED pre-fix: the " +
                "`?: emptyList()` swallow rendered lying empty sections); got $error",
        )
        assertTrue(error is TransportFailure, "the refusal is the typed TransportFailure plane")
        assertTrue(
            (error as TransportFailure).message!!.contains("fault.git.notAvailable"),
            "the fault code must reach the screen's error face verbatim",
        )
    }

    @Test
    fun `an unrefused getChanges still returns its rows - F-P5-A control`() = runBlocking {
        val port = connectedPort(refusedMethods = emptyMap())
        val rows = port.changes("unstaged")
        assertEquals(
            emptyList<String>(),
            rows.map { it.repoRelativePath },
            "the mock's fixture serves zero unstaged rows unrefused — the control proves the rethrow is refusal-conditional",
        )
    }
}
