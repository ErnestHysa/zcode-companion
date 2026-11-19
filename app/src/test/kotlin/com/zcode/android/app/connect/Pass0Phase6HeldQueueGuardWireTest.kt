package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.outbox.OutboxStateMachine
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
import kotlin.test.assertTrue

/**
 * Pass-0 Phase 6 — the held-queue guard family at WIRE fidelity (the REAL
 * RelayTransport ↔ MockZCode, the hub's own port stack; INV-15 synthetic only).
 * The pass-19/20 live receipts drove these shapes on the emulator rig; this
 * harness pins them in-suite so no regression survives the next fix round.
 *
 * F-P19-A: under `choice` with a mismatching expectedHeldQueueItemIds, the mock's
 * heldQueueCasArm answers the host-verbatim failure ack
 * `{status:"failed", reasonCode:"guard.heldQueueConfirmationStale", message:"paused
 * queue changed after send confirmation opened"}` and mutates nothing — the port
 * must surface CONFIRMATION_REQUIRED (the desktop's confirmationRequired) while
 * the outbox row stays FAILED_TERMINAL with the verbatim reasonCode (the V.C
 * static shape).
 *
 * F-P20-A: a goal send WITH the trio passes the mock's choice admission
 * (accepted; the payloadJson carries the trio verbatim — the pass-20 arbiter's
 * missing keys are present); the legacy trio-less goal under choice answers the
 * host-verbatim heldQueueDispositionRequired failure ack (control — unchanged).
 *
 * N-BQ-1: the mock's setThoughtLevelStale arm answers the protocol-plane Dl
 * -32009 error — the port must propagate STALE (the previously-dead
 * SESSION_CHANGED face), never the generic FAILED.
 */
class Pass0Phase6HeldQueueGuardWireTest {

    private val sid = "d_p0phase6"
    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })

    private lateinit var mock: MockZCode
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

    /** The WritePath fixture: fresh mock + hub per test, choice-mode held queue armed. */
    private suspend fun connectAndOpen(arms: MockZCode.Script.() -> Unit): com.zcode.android.domain.model.Task {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        outbox = InMemoryOutboxRepository()
        conversations = FakeConversationRepository()
        tasks = FakeTaskRepository()
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        mock.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "synth-task-1",
                title = "Phase 6 guard demo",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
            ),
        )
        mock.script.commandEffects = true
        mock.script.emitStateKeys = true
        mock.script.snapshotRowsWindowShape = true
        // The pass-17 sticky routing arm: the choice mode stays (the send/stop
        // handler flips are suppressed).
        mock.script.inputRoutingMode = "choice"
        mock.script.inputRoutingArmSticky = true
        mock.script.queueItems = listOf(
            MockZCode.QueueItem("q-rig-1", "queued follow-up one"),
            MockZCode.QueueItem("q-rig-2", "queued follow-up two"),
        )
        mock.script.heldQueueCasArm = true
        arms(mock.script)
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
                desktopName = "Phase6MockBook",
                appVersion = "3.18.1",
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
        port = DefaultConversationPort(hub, conversations, vault, attachmentCacheDir = java.io.File(System.getProperty("java.io.tmpdir"), "phase6-test-attachments"))
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        val task = withTimeout(15_000) { tasks.snapshot.first { list -> list.isNotEmpty() } }.first()
        org.junit.Assert.assertNotNull("the conversation plane must open", hub.openConversation(task))
        return task
    }

    // ------------------------------------------------------------------ F-P19-A

    @Test
    fun `F-P19-A - the guard CAS-mismatch ack surfaces CONFIRMATION_REQUIRED with the outbox row FAILED_TERMINAL`() = runBlocking {
        val task = connectAndOpen { }
        // The mismatch is deterministic: the trio names a queue the live one outgrew
        // (the ghost append's post-state — the pass-19 leg-B shape, without the race).
        val outcome = port.sendPrompt(
            task.taskId,
            "p19cas",
            heldQueueDisposition = "keepQueueAndSend",
            requestedDelivery = null,
            expectedHeldQueueItemIds = listOf("q-rig-1"), // live = [q-rig-1, q-rig-2]
        )
        assertEquals(
            ConversationPort.SendOutcome.CONFIRMATION_REQUIRED,
            outcome,
            "the desktop's confirmationRequired identity must reach the surface layer",
        )
        val row = outbox.entries.values.single { it.type == "sendText" }
        assertEquals(OutboxStateMachine.State.FAILED_TERMINAL, row.state, "the V-C static shape: the attempt stores as a host refusal")
        assertEquals("failed", row.ackStatus)
        assertEquals("guard.heldQueueConfirmationStale", row.ackReasonCode)
        assertEquals("paused queue changed after send confirmation opened", row.ackMessage)
    }

    @Test
    fun `F-P19-A - a matching trio still passes the CAS (control - accepted)`() = runBlocking {
        val task = connectAndOpen { }
        val outcome = port.sendPrompt(
            task.taskId,
            "p19cas-ok",
            heldQueueDisposition = "keepQueueAndSend",
            requestedDelivery = null,
            expectedHeldQueueItemIds = listOf("q-rig-1", "q-rig-2"),
        )
        assertEquals(ConversationPort.SendOutcome.ACCEPTED, outcome)
    }

    // ------------------------------------------------------------------ F-P20-A

    @Test
    fun `F-P20-A - the goal send WITH the answered trio passes the choice admission and the payload carries it`() = runBlocking {
        val task = connectAndOpen { }
        val outcome = port.sendGoal(
            task.taskId,
            "finish the ledger",
            "/goal finish the ledger",
            heldQueueDisposition = "keepQueueAndSend",
            expectedHeldQueueItemIds = listOf("q-rig-1", "q-rig-2"),
        )
        assertEquals(
            ConversationPort.SendOutcome.ACCEPTED,
            outcome,
            "the admission gate passes a trio-bearing goal (the pass-20 structural refusal is FIXED)",
        )
        val row = outbox.entries.values.single { it.type == "sendGoalCommand" }
        assertTrue(
            "\"heldQueueDisposition\":\"keepQueueAndSend\"" in row.payloadJson,
            "the disposition rides the wire payload: ${row.payloadJson.take(200)}",
        )
        assertTrue(
            "q-rig-1" in row.payloadJson && "q-rig-2" in row.payloadJson,
            "the CAS ids ride the wire payload: ${row.payloadJson.take(200)}",
        )
    }

    @Test
    fun `F-P20-A - the legacy trio-less goal under choice stays host-refused (control - the admission stands)`() = runBlocking {
        val task = connectAndOpen { }
        val outcome = port.sendGoal(task.taskId, "no trio", "/goal no trio")
        assertEquals(
            ConversationPort.SendOutcome.FAILED,
            outcome,
            "the admission gate itself never loosens — only trio-bearing sends pass",
        )
        val row = outbox.entries.values.single { it.type == "sendGoalCommand" }
        assertEquals(OutboxStateMachine.State.FAILED_TERMINAL, row.state)
        assertEquals("heldQueueDispositionRequired", row.ackReasonCode)
        assertTrue(
            "\"heldQueueDisposition\"" !in row.payloadJson,
            "the legacy call carries no trio keys (the wire shape stays byte-identical)",
        )
    }

    // ------------------------------------------------------------------ N-BQ-1

    @Test
    fun `N-BQ-1 - the -32009 revision-mismatch propagates as STALE through the real transport`() = runBlocking {
        val task = connectAndOpen { setThoughtLevelStale = true }
        val outcome = port.setThoughtLevel(task.taskId, "high")
        assertEquals(
            ConversationPort.SendOutcome.STALE,
            outcome,
            "the effort switch's honest stale face becomes reachable (N-BQ-1 fixed)",
        )
    }

    @Test
    fun `N-BQ-1 - an unscripted setThoughtLevel still accepts (control)`() = runBlocking {
        val task = connectAndOpen { }
        assertEquals(ConversationPort.SendOutcome.ACCEPTED, port.setThoughtLevel(task.taskId, "high"))
    }
}
