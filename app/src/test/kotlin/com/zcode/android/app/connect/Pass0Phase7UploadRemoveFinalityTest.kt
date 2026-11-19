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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F-P31-A (pass-0 Phase 7) — the mid-upload user-tap Remove is FINAL. The upload
 * job kept running after `removeUpload` (the composer-scope coroutine / the retry
 * launch were untracked), so its per-chunk progress upsert RE-ADDED the removed
 * chip and the transaction's failure upsert re-added it again — the removed chip
 * "resurrected" ~15 s later as ✗ FAILED under a banner. The second, worse variant
 * (pass 32): a server that KEEPS the staging entry after the abort accepts every
 * post-abort chunk, the commit SUCCEEDS, and the chip resurrects as ✓ READY under
 * NO banner at all — and the resurrected chip SENDS end-to-end.
 *
 * The pins drive the REAL RelayTransport ↔ MockZCode pair over both abort classes
 * and assert the chip NEVER comes back after the remove.
 */
class Pass0Phase7UploadRemoveFinalityTest {

    private val sid = "d_uploadremovefinality"
    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    private lateinit var mock: MockZCode
    private lateinit var scope: CoroutineScope
    private lateinit var transport: RelayTransport
    private lateinit var hub: ConnectionHub
    private lateinit var port: DefaultConversationPort
    private lateinit var tasks: FakeTaskRepository

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        runCatching { mock.stop() }
        if (this::scope.isInitialized) scope.cancel()
    }

    /** The StagedAttachmentProcessDeathTest harness: real relay, connected hub, open conversation. */
    private suspend fun connectAndOpen(): com.zcode.android.domain.model.Task {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        mock.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "synth-task-1",
                title = "Upload remove finality",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
            ),
        )
        mock.script.commandEffects = true
        mock.script.emitStateKeys = true
        mock.script.snapshotRowsWindowShape = true
        // Widen every chunk window so the remove reliably lands mid-transaction.
        mock.script.attachmentChunkDelayMs = 400
        val url = mock.start()
        val vault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), ZLog(sink = {}))
        vault.seal(
            PairingLink(
                endpointOrigin = url.removeSuffix("/ws").replaceFirst("ws://", "http://"),
                route = "/remote/v4",
                deviceSid = sid,
                passHash = passHash,
                issuedAtMs = System.currentTimeMillis(),
                deviceMid = "mock-desktop-mid",
                desktopName = "UploadRemoveBook",
                appVersion = "3.14.5",
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
        port = DefaultConversationPort(hub, FakeConversationRepository(), vault, attachmentCacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "f-p31-a-attachments"))
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        val task = withTimeout(15_000) {
            tasks.snapshot.first { it.isNotEmpty() }.first()
        }
        assertNotNull(hub.openConversation(task))
        return task
    }

    /** Two chunks + delay = a ~1 s transaction window for the mid-flight remove. */
    private val twoChunkPayload = ByteArray(512 * 1024 + 64) { (it % 251).toByte() }

    /** The chip is on the strip in the given state (or absent when empty). */
    private suspend fun awaitChip(uploadId: String, state: String? = null): ConversationPort.ComposerAttachment =
        withTimeout(20_000) {
            port.observeComposerAttachments("synth-task-1")
                .first { list -> list.any { it.uploadId == uploadId && (state == null || it.state == state) } }
                .first { it.uploadId == uploadId }
        }

    private suspend fun awaitChipGone(uploadId: String) = withTimeout(20_000) {
        port.observeComposerAttachments("synth-task-1").first { list -> list.none { it.uploadId == uploadId } }
    }

    /**
     * The resurrection assertion: after the remove, the chip must NEVER re-appear —
     * polled well beyond the transaction's remaining window (3 s ≫ 400 ms/chunk).
     */
    private suspend fun assertChipStaysGone(uploadId: String) {
        awaitChipGone(uploadId)
        var resurrected: ConversationPort.ComposerAttachment? = null
        repeat(30) {
            delay(100)
            val now = port.observeComposerAttachments("synth-task-1").first()
            now.firstOrNull { it.uploadId == uploadId }?.let { resurrected = it }
            delay(100)
        }
        assertTrue(
            resurrected == null,
            "the removed chip RESURRECTED (${resurrected?.state}) — the user's Remove must be final",
        )
    }

    @Test
    fun `a mid-upload remove keeps the chip gone across the faulting-abort class`() = runBlocking {
        val task = connectAndOpen()
        // The server-faithful abort: the staging entry is DELETED, so any post-abort
        // chunk faults fault.attachment.unknownUpload (the pass-31 resurrection driver).
        mock.script.attachmentKeepAfterAbort = false
        val uploadId = "up-fp31a-fault"
        val job = scope.launch {
            runCatching { port.uploadAttachment(task.taskId, "rig-p31a.bin", "application/octet-stream", twoChunkPayload, uploadId) { _, _ -> } }
        }
        awaitChip(uploadId, state = "uploading")
        port.removeUpload(uploadId)
        assertChipStaysGone(uploadId)
        withTimeout(20_000) { job.join() }
    }

    @Test
    fun `a mid-upload remove keeps the chip gone when the server keeps accepting post-abort chunks`() = runBlocking {
        val task = connectAndOpen()
        // The pass-32 arm: the abort is KEPT, post-abort chunks are accepted, the
        // commit SUCCEEDS — the silent ✓ READY resurrection driver.
        mock.script.attachmentKeepAfterAbort = true
        val uploadId = "up-fp31a-kept"
        val job = scope.launch {
            runCatching { port.uploadAttachment(task.taskId, "rig-p31b.bin", "application/octet-stream", twoChunkPayload, uploadId) { _, _ -> } }
        }
        awaitChip(uploadId, state = "uploading")
        port.removeUpload(uploadId)
        assertChipStaysGone(uploadId)
        withTimeout(20_000) { job.join() }
    }
}
