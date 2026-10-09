package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.RecoveryCoordinator
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 7 write-path E2E (§43.2-7.2/7.3/7.4/7.5 exit gates) over the REAL
 * RelayTransport ↔ MockZCode (INV-15: synthetic only): send → authoritative rows
 * land; approval card answers via resolveInteraction; resolved-elsewhere race;
 * stop; queue CAS ops; draft lifecycle through the port.
 */
class ConnectionHubWritePathTest {

    private val sid = "d_writepath"
    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })

    // Fresh mock per test (start/stop is one-shot on MockWebServer).
    private lateinit var mock: MockZCode

    // Fresh per test (shared state would poison the cross-test runs — the write
    // path leaves durable traces in every one of these stores).
    private lateinit var scope: CoroutineScope
    private lateinit var transport: RelayTransport
    private lateinit var hub: ConnectionHub
    private lateinit var port: DefaultConversationPort
    private lateinit var outbox: InMemoryOutboxRepository
    private lateinit var conversations: FakeConversationRepository
    private lateinit var tasks: FakeTaskRepository

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        runCatching { mock.stop() }
        if (this::scope.isInitialized) scope.cancel()
    }

    private suspend fun connectAndOpen(): com.zcode.android.domain.model.Task {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        outbox = InMemoryOutboxRepository()
        conversations = FakeConversationRepository()
        tasks = FakeTaskRepository()
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        mock.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "synth-task-1",
                title = "Write path demo",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
            ),
        )
        // Phase 7: the mock mutates its session on accepted commands + carries state keys.
        mock.script.commandEffects = true
        mock.script.emitStateKeys = true
        mock.script.snapshotRowsWindowShape = true
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
                desktopName = "Phase7MockBook",
                appVersion = "3.14.3",
            ),
        )
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
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
            outbox = outbox,
            drafts = InMemoryDraftRepository(),
            scope = scope,
            log = ZLog(sink = {}),
        )
        port = DefaultConversationPort(hub, conversations, vault, attachmentCacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "phase7-test-attachments"))
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        val task = try {
            withTimeout(15_000) { tasks.snapshot.first { list -> list.isNotEmpty() } }.first()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError(
                    "bootstrap tasks never landed; taskLoadError=" + hub.taskLoadError.value +
                        " phase=" + hub.phase.value, e,
                )
            }
            throw e
        }
        assertNotNull(hub.openConversation(task))
        // The conversation plane is ALIVE: the initial snapshot's rows land.
        try {
            withTimeout(20_000) { conversations.rowsFor(task.taskId).first { it.isNotEmpty() } }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError(
                    "conversation plane dead; diag=" + hub.diagnosticsSnapshot().let { d ->
                        "caps=" + d.capabilities + " journal=" + d.journalSubscriptions +
                            " sessions=" + d.sessions.joinToString { s -> s.sessionId.take(12) + "/" + s.cachedRows }
                    },
                    e,
                )
            }
            throw e
        }
        return task
    }

    @Test
    fun `sendText lands as authoritative rows and the overlay reconciles (U4, §17-5)`() = runBlocking {
        val task = connectAndOpen()
        val outcome = port.sendPrompt(task.taskId, "please refactor the parser")
        assertEquals(ConversationPort.SendOutcome.ACCEPTED, outcome)
        assertEquals(1, outbox.entries.size)
        assertEquals(OutboxStateMachine.State.ACKED, outbox.entries.values.single().state)
        // The mock's command effect appends the userInput row — the projection shows it.
        val inboundKinds = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val tap = scope.launch {
            transport.inbound.collect { f ->
                inboundKinds.merge(f.javaClass.simpleName, 1, Int::plus)
                if (f is com.zcode.android.core.transport.InboundFrame.TopicFrameArrived) {
                    inboundKinds.merge("topic:" + f.topic + ":" + f.deliveryKind + ":" + f.fromSeq + "-" + f.toSeq, 1, Int::plus)
                }
            }
        }
        val rows = try {
            withTimeout(20_000) {
                conversations.rowsFor(task.taskId).first { list -> list.any { it is ConversationRow.UserInput } }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                val mgr = hub.coordinatorOrNull()?.manager(task.taskId)
                throw AssertionError(
                    "no effect rows; mockFrames=" + mock.logicalFramesSent + " inbound=" + inboundKinds +
                        " stale=" + mgr?.stale?.value + " taskState=" + mgr?.taskState?.value +
                        " epoch=" + conversations.epochFor(task.taskId) +
                        " cached=" + mgr?.cachedRowCount() +
                        " sessionState=" + hub.sessionProjectionState(task.taskId).first(),
                    e,
                )
            }
            throw e
        }
        tap.cancel()
        val userRow = rows.filterIsInstance<ConversationRow.UserInput>().single()
        assertEquals("please refactor the parser", userRow.text)
        assertTrue(rows.any { it is ConversationRow.AssistantText }) // the mock's reply row
        // The session state picked up the control patch (canStop / enqueue routing).
        val state = withTimeout(20_000) {
            hub.sessionProjectionState(task.taskId).first { it.control?.canStop == true }
        }
        assertEquals("stoppable", state.control?.stopState?.wire)
        assertEquals(com.zcode.android.domain.model.InputRouting.Mode.ENQUEUE, state.inputRouting?.mode)
    }

    @Test
    fun `commands carry the production wire shape - workspace target beside the envelope (vc21 fix)`() = runBlocking {
        val task = connectAndOpen()
        assertEquals(ConversationPort.SendOutcome.ACCEPTED, port.sendPrompt(task.taskId, "wire shape probe"))
        // vc21 fix: the bare-envelope shape would fail the desktop's params.envelope
        // lookups — the wrapper {workspacePath, envelope} is now mandatory.
        org.junit.Assert.assertNotNull(
            "workspacePath missing on the command arg",
            mock.lastCommandWorkspacePath,
        )
        org.junit.Assert.assertEquals(task.workspace.raw, mock.lastCommandWorkspacePath)
        // FIELD (vc22): envelope.clientId MUST equal the hello-bound id — the device
        // rejected every command with fault.command.clientMismatch before this fix.
        org.junit.Assert.assertNotNull(mock.boundClientId, "clientHello never bound a clientId")
        org.junit.Assert.assertEquals(mock.boundClientId, mock.lastCommandClientId)
    }

    @Test
    fun `createSession creates a task in an existing workspace (parity, 6-1)`() = runBlocking {
        connectAndOpen()
        val before = hub.currentTasks().size
        val outcome = assertNotNull(hub.commandCoordinatorOrNull())
            .createSession(workspacePath = "/synthetic/ws", workspaceIdentity = null, firstText = "brand new task from the phone")
        assertTrue(
            outcome is com.zcode.android.data.session.CommandCoordinator.Outcome.Accepted,
            "createSession not accepted: $outcome",
        )
        // The mock's effect appends the new task + pushes workspace-list-updated —
        // the projection must show it (with §7.11 key = path here).
        val created = withTimeout(20_000) {
            kotlinx.coroutines.flow.flow {
                while (true) {
                    val match = hub.currentTasks().firstOrNull { it.taskId.startsWith("sess-created-") }
                    if (match != null) { emit(match); break }
                    kotlinx.coroutines.delay(100)
                }
            }.first()
        }
        assertEquals("brand new task from the phone", created.title)
        org.junit.Assert.assertNotNull(outcome)
        org.junit.Assert.assertTrue(hub.currentTasks().size >= before)
        // The create call registered the workspace scope listen + carried the target.
        org.junit.Assert.assertEquals("/synthetic/ws", mock.lastCommandWorkspacePath)
    }

    @Test
    fun `pending approval arrives via pendingInteractions and allowOnce resolves it (U8, G2a)`() = runBlocking {
        val task = connectAndOpen()
        // Inject the pending interaction through the verified state.updated patch.
        mock.pushPendingPermission("i-approval-1", "Bash")
        val openInteraction = try {
            withTimeout(20_000) {
                hub.sessionProjectionState(task.taskId).first { it.pendingInteractions?.isNotEmpty() == true }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError(
                    "pending never landed; state=" + hub.sessionProjectionState(task.taskId).first() +
                        " hub=" + System.identityHashCode(hub) + " coord=" + System.identityHashCode(hub.coordinatorOrNull()) + " mgr=" + System.identityHashCode(hub.coordinatorOrNull()?.manager(task.taskId)) +
                        " coordMgrs=" + hub.coordinatorOrNull()?.managedSessions() +
                        " mockFrames=" + mock.logicalFramesSent +
                        " emit=" + mock.script.emitStateKeys,
                    e,
                )
            }
            throw e
        }
        val pending = openInteraction.pendingInteractions!!.single()
        assertEquals("permission", pending.kind.wire)
        assertEquals("Bash", pending.permission?.toolName)
        // Answer through the fail-closed gate.
        val coordinator = assertNotNull(hub.interactionCoordinatorOrNull())
        val outcome = coordinator.submit(
            com.zcode.android.domain.ids.InteractionId("i-approval-1"),
            "allowOnce",
        )
        assertTrue(outcome is com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome.Submitted)
        // The mock's effect clears the pending interaction + flips the tool row.
        val resolved = withTimeout(20_000) {
            hub.sessionProjectionState(task.taskId).first { it.pendingInteractions?.isEmpty() == true }
        }
        assertTrue(resolved.pendingInteractions!!.isEmpty())
    }

    @Test
    fun `resolved-elsewhere race — mock-side resolution flips the card and blocks our submit (§20-3)`() = runBlocking {
        val task = connectAndOpen()
        mock.pushPendingPermission("i-race-1", "Edit")
        withTimeout(20_000) {
            hub.sessionProjectionState(task.taskId).first { it.pendingInteractions?.isNotEmpty() == true }
        }
        // The desktop resolves WITHOUT our command (race driver):
        mock.resolveInteractionElsewhere()
        withTimeout(20_000) {
            hub.sessionProjectionState(task.taskId).first { it.pendingInteractions?.isEmpty() == true }
        }
        // Our late tap is a typed no-op (machine RESOLVED → SUBMITTING is illegal).
        val coordinator = assertNotNull(hub.interactionCoordinatorOrNull())
        val outcome = coordinator.submit(com.zcode.android.domain.ids.InteractionId("i-race-1"), "allowOnce")
        assertEquals(
            com.zcode.android.data.session.InteractionCoordinator.SubmitOutcome.DuplicateNoOp,
            outcome,
        )
        assertEquals(0, outbox.entries.size) // nothing was ever sent — INV-4
    }

    @Test
    fun `stop flips the authoritative control block (U24)`() = runBlocking {
        val task = connectAndOpen()
        // Make the session stoppable first (a send turns the mock running).
        assertEquals(ConversationPort.SendOutcome.ACCEPTED, port.sendPrompt(task.taskId, "wake up"))
        withTimeout(20_000) {
            hub.sessionProjectionState(task.taskId).first { it.control?.canStop == true }
        }
        val outcome = port.stopGeneration(task.taskId)
        assertEquals(ConversationPort.SendOutcome.ACCEPTED, outcome)
        val state = withTimeout(20_000) {
            hub.sessionProjectionState(task.taskId).first { it.control?.canStop == false }
        }
        assertEquals(com.zcode.android.domain.model.SessionControl.StopState.IDLE, state.control?.stopState)
    }

    @Test
    fun `queue edits are CAS-guarded and stale bases are typed rejections (§7-5)`() = runBlocking {
        val task = connectAndOpen()
        // The port carries the snapshot's revision as the CAS base — accepted.
        // (Wait for the §9 revision currency to land — the snapshot may still be
        // in flight right after attach.)
        withTimeout(20_000) {
            hub.sessionProjectionState(task.taskId).first { it.revision != null }
        }
        val outcome = port.sendQueuedNow(task.taskId, "q-1")
        assertEquals(ConversationPort.SendOutcome.ACCEPTED, outcome)
        // A base BELOW the published revision acks `stale` (verified CAS, typed).
        val hubLevel = assertNotNull(hub.commandCoordinatorOrNull())
        val stale = hubLevel.sendQueuedNow(task.taskId, "q-2", baseRevision = 1)
        assertTrue(stale is com.zcode.android.data.session.CommandCoordinator.Outcome.Stale)
        // A future base is accepted (mock: equal-or-above passes CAS).
        val ok = hubLevel.sendQueuedNow(task.taskId, "q-3", baseRevision = 1_000_000)
        assertTrue(ok is com.zcode.android.data.session.CommandCoordinator.Outcome.Accepted)
    }

    @Test
    fun `draft persists through the port scope and clears on accepted send (E8, §17-4)`() = runBlocking {
        val task = connectAndOpen()
        // The draft lifecycle is the ViewModel contract (§17.4: debounced save;
        // the accepted send IS the discard — E8: never lost on failure paths).
        val vm = com.zcode.android.features.conversation.ConversationViewModel(port, scope, task.taskId)
        try {
            withTimeout(10_000) { vm.state.first { it.draft.isEmpty() } }
        } catch (e: Exception) {
            throw AssertionError("wait1 vm-ready failed: " + vm.state.value, e)
        }
        vm.onDraftChange("draft survives process death")
        // The §17.4 300 ms debounce persists it through the port scope:
        try {
            withTimeout(10_000) {
                port.observeDraft(task.taskId).first { it == "draft survives process death" }
            }
        } catch (e: Exception) {
            throw AssertionError("wait2 draft-persisted failed", e)
        }
        // A FRESH observer (process-death class) still sees the persisted draft:
        assertEquals("draft survives process death", port.observeDraft(task.taskId).first())
        vm.send()
        // The send is asynchronous (§17.5 ACCEPT → transmit); wait for the outbox.
        withTimeout(10_000) {
            kotlinx.coroutines.flow.flow { while (outbox.entries.isEmpty()) kotlinx.coroutines.delay(50); emit(true) }.first()
        }
        // The draft is gone once the message is accepted (the send IS the discard).
        try {
            val cleared = withTimeout(10_000) {
                port.observeDraft(task.taskId).first { it == null }
            }
            assertEquals(null, cleared)
        } catch (e: Exception) {
            throw AssertionError(
                "wait3 draft-cleared failed; notice=" + vm.state.value.sendNotice +
                    " outbox=" + outbox.entries.values.map { it.type + "/" + it.state } +
                    " pending=" + hub.commandCoordinatorOrNull()?.pendingSends?.value,
                e,
            )
        }
    }

    @Test
    fun `offline sends are BLOCKED — nothing queued, nothing minted (§17-6)`() = runBlocking {
        connectAndOpen()
        // Kill the endpoint (not a clean client close — the transport's clean-close
        // classification deliberately does not enter the reconnect loop).
        mock.stop()
        withTimeout(30_000) { hub.phase.first { it != ConnectionPhase.CONNECTED } }
        val outcome = port.sendPrompt("synth-task-1", "should not queue")
        assertEquals(ConversationPort.SendOutcome.BLOCKED_OFFLINE, outcome)
        assertEquals(0, outbox.entries.size)
    }
}

