package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * GAP-12 (android-native-gaps.md; docs/protocol/v4-wire.md §11.2) — the
 * workspace-config topic end-to-end through the REAL hub + gateway + transport
 * against MockZCode (INV-15-clean): openConversation subscribes the workspace's
 * config topic; the catalog lands in the hub workspace-scoped bucket; a session
 * RE-ATTACH (replay/restore class) does NOT clear it (the desktop's
 * preserve-for-replay rule, zcodeTaskServiceAdapter.ts:1566-1569).
 */
class ConnectionHubSlashCatalogTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val sid = "d_Phase6SyntheticSid1"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var hub: ConnectionHub
    private lateinit var scope: CoroutineScope
    private val tasks = FakeTaskRepository()
    private val conversations = FakeConversationRepository()

    @Before
    fun setUp() {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        mock.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "sess-cfg-1",
                title = "Catalog task one",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
            ),
            MockZCode.MockTask(
                taskId = "sess-cfg-2",
                title = "Catalog task two (same workspace)",
                displayStatus = "idle",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
            ),
        )
        // The scripted workspace-config catalog (workspaceConfigSnapshotSchema shape;
        // the fixture-decoded field shapes are pinned in V4WorkspaceConfigTest).
        mock.script.workspaceConfigState = Json.parseToJsonElement(
            """
            {"configOptions":[{"id":"model","name":"Model","type":"select","currentValue":"glm-5.3"}],
             "slashCommands":[
               {"name":"compact","description":"Compact the conversation","inputHint":"[instructions]","source":"builtin"},
               {"name":"deploy","description":"Deploy the workspace","inputHint":"<env>","source":"custom"}]}
            """.trimIndent(),
        ).jsonObject
        val url = mock.start()
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
                desktopName = "Phase6MockBook",
                appVersion = "3.14.3",
            ),
        )
        transport = RelayTransport(wsFactory = com.zcode.android.core.transport.OkHttpWsFactory(), log = ZLog(sink = {}))
        hub = ConnectionHub(
            transport = transport,
            directTransport = testDirectTransport(),
            preferences = FakePreferencesRepository(),
            vault = vault,
            taskRepo = tasks,
            conversationRepo = conversations,
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = FakeEndpointRepository(),
            outbox = InMemoryOutboxRepository(),
            drafts = InMemoryDraftRepository(),
            scope = scope,
            log = ZLog(sink = {}),
        )
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        runCatching { mock.stop() }
        scope.cancel()
    }

    @Test
    fun `catalog lands from the workspace-config topic and survives session re-attach`() = runBlocking {
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        withTimeout(15_000) { hub.refreshTasks() }
        val task = withTimeout(15_000) {
            tasks.snapshot.first { list -> list.any { it.taskId == "sess-cfg-1" } }.first { it.taskId == "sess-cfg-1" }
        }

        // Open session one → the workspace catalog subscribes + lands.
        hub.openConversation(task)
        val wsKey = task.workspace.raw
        val catalog = withTimeout(20_000) {
            hub.workspaceCatalog(wsKey).first { it != null && it.slashCommands.isNotEmpty() }!!
        }
        assertEquals(2, catalog.slashCommands.size)
        assertEquals(listOf("compact", "deploy"), catalog.slashCommands.map { it.name })
        assertEquals("builtin", catalog.slashCommands[0].source)
        assertEquals("<env>", catalog.slashCommands[1].inputHint)

        // Preserve-for-replay: a SECOND session attach in the same workspace
        // (replayable/restore class) must NOT backfill the catalog to empty.
        val task2 = tasks.snapshot.first().first { it.taskId == "sess-cfg-2" }
        hub.openConversation(task2)
        val after = withTimeout(10_000) {
            hub.workspaceCatalog(wsKey).first { it != null && it.slashCommands.isNotEmpty() }!!
        }
        assertEquals(2, after.slashCommands.size, "catalog preserved across session re-attach")
        assertTrue(after.slashCommands.map { it.name } == listOf("compact", "deploy"))
        Unit
    }

    @Test
    fun `workspace without a config topic keeps an honest empty catalog`() = runBlocking {
        mock.script.workspaceConfigState = null
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        withTimeout(15_000) { hub.refreshTasks() }
        val task = withTimeout(15_000) {
            tasks.snapshot.first { list -> list.any { it.taskId == "sess-cfg-1" } }.first { it.taskId == "sess-cfg-1" }
        }
        hub.openConversation(task)
        // No catalog ever arrives — the flow stays null (the menu renders nothing;
        // the desktop's EMPTY_SLASH_COMMANDS parity).
        assertNull(hub.workspaceCatalog(task.workspace.raw).first())
        Unit
    }
}
