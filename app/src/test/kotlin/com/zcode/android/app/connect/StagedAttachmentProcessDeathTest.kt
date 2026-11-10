package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * N-BH-2 (vc52 Phase 10) END-TO-END seam pin over the REAL RelayTransport ↔ MockZCode
 * (INV-15): the eager upload commits server-side at Done, so a process death used to
 * orphan the upload silently (the text draft restored, the staged sketch chip did
 * not). Now the READY refs ride the draft row: a commit PERSISTS them and a fresh
 * port instance (the process-death sim) SEEDS its chips back from the persisted
 * refs; an explicit chip removal un-persists them.
 */
class StagedAttachmentProcessDeathTest {

    private val sid = "d_stagedattach"
    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    private lateinit var mock: MockZCode
    private lateinit var scope: CoroutineScope
    private lateinit var transport: RelayTransport
    private lateinit var hub: ConnectionHub
    private lateinit var port: DefaultConversationPort
    private lateinit var tasks: FakeTaskRepository
    private lateinit var vault: CredentialVault

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        runCatching { mock.stop() }
        if (this::scope.isInitialized) scope.cancel()
    }

    private suspend fun connectAndOpen(): com.zcode.android.domain.model.Task {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        mock.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "synth-task-1",
                title = "Staged attach demo",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
            ),
        )
        mock.script.commandEffects = true
        mock.script.emitStateKeys = true
        mock.script.snapshotRowsWindowShape = true
        val url = mock.start()
        vault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), ZLog(sink = {}))
        vault.seal(
            PairingLink(
                endpointOrigin = url.removeSuffix("/ws").replaceFirst("ws://", "http://"),
                route = "/remote/v4",
                deviceSid = sid,
                passHash = passHash,
                issuedAtMs = System.currentTimeMillis(),
                deviceMid = "mock-desktop-mid",
                desktopName = "StagedAttachBook",
                appVersion = "3.14.3",
            ),
        )
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        hub = ConnectionHub(
            transport = transport,
            directTransport = testDirectTransport(),
            preferences = FakePreferencesRepository(),
            vault = vault,
            taskRepo = FakeTaskRepository().also { tasks = it },
            conversationRepo = FakeConversationRepository(),
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = FakeEndpointRepository(),
            outbox = InMemoryOutboxRepository(),
            drafts = InMemoryDraftRepository(),
            scope = scope,
            log = ZLog(sink = {}),
        )
        port = DefaultConversationPort(hub, FakeConversationRepository(), vault, attachmentCacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "n-bh2-test-attachments"))
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        val task = withTimeout(15_000) {
            tasks.snapshot.first { it.isNotEmpty() }.first()
        }
        assertNotNull(hub.openConversation(task))
        return task
    }

    @Test
    fun `a committed upload persists its ref and a fresh port seeds the chip back`() = runBlocking {
        val task = connectAndOpen()
        val bytes = ByteArray(2048) { (it % 251).toByte() }

        // The eager upload: begin → staging → chunk → commit over the REAL relay.
        val result = port.uploadAttachment(task.taskId, "sketch-1.png", "image/png", bytes, "up-nbh2-1") { _, _ -> }
        assertTrue(result is ConversationPort.UploadResult.Committed, "the upload must commit: $result")

        // The chips restore for a FRESH port instance (the process-death sim): the
        // persisted refs seed the composer strip without any user action.
        val port2 = DefaultConversationPort(hub, FakeConversationRepository(), vault, attachmentCacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "n-bh2-test-attachments"))
        val restored = withTimeout(20_000) {
            port2.observeComposerAttachments(task.taskId).first { it.isNotEmpty() }
        }
        assertEquals(1, restored.size)
        assertEquals("up-nbh2-1", restored[0].uploadId)
        assertEquals("ready", restored[0].state)
        assertNotNull(restored[0].ref)
        assertEquals("sketch-1.png", restored[0].fileName)

        // An explicit chip removal un-persists: another fresh port sees NO chips.
        port2.removeUpload("up-nbh2-1")
        val port3 = DefaultConversationPort(hub, FakeConversationRepository(), vault, attachmentCacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "n-bh2-test-attachments"))
        val afterRemove = withTimeout(20_000) {
            port3.observeComposerAttachments(task.taskId).first { it.isEmpty() }
        }
        assertEquals(0, afterRemove.size)
    }
}
