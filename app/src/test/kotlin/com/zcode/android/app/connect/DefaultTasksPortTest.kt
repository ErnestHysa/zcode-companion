package com.zcode.android.app.connect

import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * FIELD regression (2026-09-30, "blank task list on first pair"): the port's `tasks`
 * flow is collected ONCE by TasksViewModel's `stateIn(Eagerly)` for the app's lifetime.
 * The old cold `flow { }` emitted emptyList() and COMPLETED when the vault had no
 * metadata — the exact state during a fresh pairing — so the list never updated again
 * even after bootstrap applied 493 tasks. The flow must stay live across the
 * unpaired → paired transition (hub.endpoint keyed flatMapLatest).
 */
class DefaultTasksPortTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
    private val sid = "d_TasksPortSyntheticSid"

    private lateinit var transport: RelayTransport

    private fun newVault(): CredentialVault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), ZLog(sink = {}))

    private fun pairingLink(url: String) = PairingLink(
        endpointOrigin = url.removeSuffix("/ws").replaceFirst("ws://", "http://"),
        route = "/remote/v4",
        deviceSid = sid,
        passHash = passHash,
        issuedAtMs = System.currentTimeMillis(),
        deviceMid = "mock-desktop-mid",
        desktopName = "PortTestBook",
        appVersion = "3.14.4",
    )

    private fun task(id: String, endpointId: String) = Task(
        taskId = id,
        title = "Port regression $id",
        status = TaskStatus.RUNNING,
        workspace = WorkspaceIdentityKey("/synthetic/ws"),
        createdAtMs = System.currentTimeMillis(),
        updatedAtMs = System.currentTimeMillis(),
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `tasks flow stays live across a fresh pairing - unpaired empty then rows land on the same collection`() = runBlocking {
        val vault = newVault()
        val repo = FakeTaskRepository()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        val hub = ConnectionHub(
            transport = transport,
            vault = vault,
            taskRepo = repo,
            conversationRepo = FakeConversationRepository(),
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = FakeEndpointRepository(),
            outbox = InMemoryOutboxRepository(),
            drafts = InMemoryDraftRepository(),
            scope = scope,
            log = ZLog(sink = {}),
        )
        val port = DefaultTasksPort(hub, vault, repo)
        val seen = ArrayList<List<Task>>()
        val collector = scope.launch { port.tasks.collect { seen.add(it) } }
        try {
            // Unpaired: one empty emission arrives, and the collection STAYS alive.
            withTimeout(5_000) {
                while (seen.isEmpty()) delay(10)
            }
            assertEquals(emptyList(), seen.last())
            assertTrue(collector.isActive, "tasks flow completed while unpaired — the list could never recover")

            // Fresh pairing completes: seal → hub adopts the endpoint → rows land on
            // the SAME live collection (the old flow was already dead here).
            vault.seal(pairingLink("ws://127.0.0.1:1/ws"))
            hub.onPairingCompleted()
            val endpointId = vault.loadMetadata()!!.endpointId
            repo.replaceAll(
                EndpointId(endpointId),
                WorkspaceIdentityKey("/synthetic/ws"),
                listOf(task("port-regression-1", endpointId)),
                etag = null,
                atMs = System.currentTimeMillis(),
            )
            withTimeout(5_000) {
                while (seen.last().isEmpty()) delay(10)
            }
            assertEquals(1, seen.last().size)
            assertEquals("port-regression-1", seen.last().first().taskId)
            assertTrue(collector.isActive)
        } finally {
            collector.cancel()
            runBlocking { runCatching { transport.close("test end") } }
        }
    }
}
