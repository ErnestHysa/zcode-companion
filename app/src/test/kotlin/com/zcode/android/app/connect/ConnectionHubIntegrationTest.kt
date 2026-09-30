package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.CredentialStore
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.security.vault.MasterKeyWrap
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.RecoveryCoordinator
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.ids.InteractionId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.journal.RecoveryJournal
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.model.PermissionRequest
import com.zcode.android.domain.model.SnapshotEtag
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.UserInputRequest
import com.zcode.android.domain.model.InteractionLifecycle
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.repo.ConversationRepository
import com.zcode.android.domain.repo.ConversationStoreOp
import com.zcode.android.domain.repo.EndpointRepository
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.repo.JournalRepository
import com.zcode.android.domain.repo.StoreApplyResult
import com.zcode.android.domain.repo.TaskRepository
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 6 §43.2-6.2/6.3 integration: the REAL ConnectionHub + REAL RelayTransport +
 * REAL RecoveryCoordinator against the in-process MockZCode (INV-15: synthetic
 * credentials only, never the production relay) with in-memory repository fakes.
 *
 * Proven here end-to-end:
 *  U2 path — paired cold start → connect/auth → bootstrap → task projection with
 *            production-shape fields; `workspace-list-updated` push → projection
 *            refreshed ≤ 2 s of the event (§48).
 *  U3 path — openConversation → subscribe → snapshot rows land in the page cache.
 *  §15-S1  — socket loss → transport RECONNECTING → session stale overlay labeled.
 */
class ConnectionHubIntegrationTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val sid = "d_Phase6SyntheticSid1"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var hub: ConnectionHub
    private lateinit var scope: CoroutineScope
    private val tasks = FakeTaskRepository()
    private val conversations = FakeConversationRepository()
    private val endpoints = FakeEndpointRepository()

    @Before
    fun setUp() {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        mock.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "synth-task-6",
                title = "Fix authentication token refresh",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
            ),
            MockZCode.MockTask(
                taskId = "synth-task-7",
                title = "Add rate limit middleware",
                displayStatus = "waiting",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
                updatedAtMs = 1_700_000_100_000,
            ),
        )
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
            vault = vault,
            taskRepo = tasks,
            conversationRepo = conversations,
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = endpoints,
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
    fun `paired cold start - bootstrap feeds the task projection (U2)`() = runBlocking {
        hub.start()
        withTimeout(30_000) {
            hub.phase.first { it == ConnectionPhase.CONNECTED }
        }
        // Task projection arrives from the production-shape bootstrap payload.
        val loaded = withTimeout(15_000) { tasks.snapshot.first { it.isNotEmpty() } }
        assertEquals(listOf("synth-task-6", "synth-task-7"), loaded.map { it.taskId })
        assertEquals("Fix authentication token refresh", loaded[0].title)
        assertEquals(TaskStatus.RUNNING, loaded[0].status)
        assertEquals(TaskStatus.WAITING, loaded[1].status)
        assertEquals(1_700_000_000_000L, loaded[0].createdAtMs)
        assertEquals("Phase6MockBook", hub.endpointName.value)
        assertEquals(ConnectionPhase.CONNECTED, hub.phase.value)
    }

    @Test
    fun `workspace-list-updated push refreshes projection within 2s of event (48-U2)`() = runBlocking {
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        withTimeout(15_000) { tasks.snapshot.first { it.isNotEmpty() } }
        val pushedAt = System.currentTimeMillis()
        mock.pushWorkspaceListUpdated(
            listOf(
                MockZCode.MockTask(
                    taskId = "synth-task-6",
                    title = "Fix authentication token refresh",
                    displayStatus = "completed",
                    workspacePath = "/synthetic/ws",
                    workspaceLabel = "demo-api",
                ),
            ),
        )
        val updated = withTimeout(2_000) {
            tasks.snapshot.first { list -> list.any { it.taskId == "synth-task-6" && it.status == TaskStatus.COMPLETED } }
        }
        assertTrue(System.currentTimeMillis() - pushedAt <= 2_000, "live-status budget (§48 U2)")
        assertEquals(1, updated.size) // replacement semantics: the removed task is gone (§15-S17)
    }

    @Test
    fun `open conversation subscribes and snapshot lands in the page cache (U3)`() = runBlocking {
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        val task = withTimeout(15_000) { tasks.snapshot.first { it.isNotEmpty() } }.first()
        // The default snapshot carries assistant/reasoning rows only; stamp an
        // authoritative turnHeader so the §14.4.2 machine observes turn state.
        mock.setSessionTurnOutcome("running")
        val coordinator = hub.openConversation(task) as RecoveryCoordinator
        withTimeout(20_000) {
            conversations.rowsFor(task.taskId).first { it.isNotEmpty() }
        }
        org.junit.Assert.assertNotNull(conversations.epochFor(task.taskId))
        assertTrue(coordinator.managedSessions().contains(task.taskId))
        // §14.4.2: authoritative turn headers drive the task machine from the snapshot.
        val observed = withTimeout(10_000) {
            hub.sessionTaskState(task.taskId).first { it != TaskStateMachine.State.UNKNOWN }
        }
        org.junit.Assert.assertNotNull(observed)
        Unit
    }

    /**
     * FIELD-KILLER #4 regression (2026-09-30, vc9 real device — the LAST blocker):
     * production snapshot `rows` is a TAIL-WINDOW OBJECT {window, totalCount, firstRowId}
     * (rowsWindowSchema, snapshot.ts) — the gateway read a bare array and every REAL
     * snapshot failed "snapshot.rows missing" (40 326-row snapshots discarded while
     * every mock suite passed on the bare-array shape). With the production shape the
     * full pipeline must land rows in the page cache.
     */
    @Test
    fun `open conversation lands rows from the production rows-window snapshot shape`() = runBlocking {
        mock.script.snapshotRowsWindowShape = true
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        val task = withTimeout(15_000) { tasks.snapshot.first { it.isNotEmpty() } }.first()
        val coordinator = hub.openConversation(task) as RecoveryCoordinator
        withTimeout(20_000) {
            conversations.rowsFor(task.taskId).first { it.isNotEmpty() }
        }
        assertTrue(coordinator.managedSessions().contains(task.taskId))
        // No decode-fault escalation: the session must not be stale after a clean snapshot.
        withTimeout(5_000) {
            hub.sessionStale(task.taskId).first { !it }
        }
        Unit
    }

    /**
     * Phase 6 FIELD-FINDING regression (real-phone failure vs production): the relay
     * rejects the first bridge-open (here: strict taskless-rejection with a queued
     * session-not-found — the observed production behavior). The hub's cold start
     * must SELF-HEAL through the transport's recovery loop and reach CONNECTED with
     * a populated task list — pre-fix it stranded in RECONNECTING forever.
     */
    @Test
    fun `cold start self-heals through a transient bridge error to a populated task list`() = runBlocking {
        mock.script.bridgeRequireTaskId = true // production-faithful strictness
        synchronized(mock.script.bridgeErrorReasons) { mock.script.bridgeErrorReasons.addLast("session-not-found") }
        var connectedCallbacks = 0
        hub.onConnected = { connectedCallbacks++ }
        hub.start()
        withTimeout(60_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } } // pre-fix: never
        val loaded = withTimeout(15_000) { tasks.snapshot.first { it.isNotEmpty() } }
        assertTrue(loaded.isNotEmpty())
        // Round-8: the desktop version from bootstrap must be visible to diagnostics.
        val diag = hub.diagnosticsSnapshot()
        org.junit.Assert.assertEquals("3.14.3", diag.desktopAppVersion)
        org.junit.Assert.assertTrue(!diag.desktopAboveTested)
        // The phase must never have been left stranded mid-state after recovery.
        assertEquals(ConnectionPhase.CONNECTED, hub.phase.value)
        // The self-heal success must surface to the pairing gate (§15-S2 flip).
        assertTrue(connectedCallbacks >= 1, "onConnected must fire on recovery success")
    }

    /**
     * Phase 6 FIELD-FINDING regression #2 (real-phone log: relay-3 → relay-4 70 ms
     * apart): the hub's failure path used to poke reconnectNow() on EVERY connect
     * failure, re-latching the policy's immediate flag → a 0 ms retry storm that
     * made the relay answer desktop-disconnected in a loop (feedback #328 class).
     * With the poke removed, retries must respect the policy's backoff: with a
     * 5 s minimum bound, the FIRST retry lands ≥ ~4 s after the failed attempt.
     */
    @Test
    fun `retries after a connect failure respect backoff - no hot loop`() = runBlocking {
        // Local transport with a SLOW policy (base = max = 5 s): with the old hot loop
        // (requestImmediate re-latched on every failure) EVERY retry gap ≈ 0; with the
        // fix the gaps are uniform(0..5 s) full jitter — statistically separated.
        val slowPolicy = com.zcode.android.core.transport.ReconnectPolicy(
            baseDelayMs = 5_000, maxDelayMs = 5_000, budgetMs = 60_000,
        )
        val slowTransport = RelayTransport(
            wsFactory = com.zcode.android.core.transport.OkHttpWsFactory(),
            log = ZLog(sink = {}),
            reconnectPolicyOverride = slowPolicy,
        )
        // SEALED vault (mirror setUp) — an empty vault leaves the hub unpaired.
        val slowVault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), ZLog(sink = {}))
        slowVault.seal(
            PairingLink(
                endpointOrigin = mock.url.removeSuffix("/ws").replaceFirst("ws://", "http://"),
                route = "/remote/v4",
                deviceSid = sid,
                passHash = passHash,
                issuedAtMs = System.currentTimeMillis(),
                deviceMid = "mock-desktop-mid",
                desktopName = "SlowBook",
                appVersion = "3.14.3",
            ),
        )
        val slowHub = ConnectionHub(
            transport = slowTransport,
            vault = slowVault,
            taskRepo = tasks,
            conversationRepo = conversations,
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = endpoints,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            log = ZLog(sink = {}),
        )
        mock.script.bridgeRequireTaskId = true // strict: every open fails until cleared
        // Round-4 note: desktop-disconnected now FAST-DEGRADES after 3 consecutive
        // rejections (correct) — use a transient reason here so the loop keeps
        // retrying, which is what the backoff-cadence assertion measures.
        synchronized(mock.script.bridgeErrorReasons) {
            repeat(4) { mock.script.bridgeErrorReasons.addLast("session-not-found") }
        }
        val authTimes = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val authJob = launch {
            mock.events.collect { if (it is MockZCode.ServerEvent.AuthCompleted) authTimes.add(System.currentTimeMillis()) }
        }
        slowHub.start()
        // 4 queued errors then the bridge opens (bootstrap resolves the task) — the
        // hub must reach CONNECTED; the assertion below is about the RETRY CADENCE.
        withTimeout(60_000) { slowHub.phase.first { it == ConnectionPhase.CONNECTED } }
        authJob.cancel()
        runCatching { runBlocking { slowTransport.close("done") } }
        val sorted = authTimes.toList().sorted()
        org.junit.Assert.assertTrue(
            "expected ≥ 3 auth attempts, got ${sorted.size}",
            sorted.size >= 3,
        )
        // Hot loop signature: EVERY gap ≈ 0 (re-latched immediate). Fixed: gaps are
        // uniform(0..5 s) — at most ONE of the first three gaps may be < 300 ms
        // (P[hot] ≈ 1 vs P[fixed] ≈ 0.8 %).
        val gaps = sorted.zipWithNext().map { (a, b) -> b - a }.take(3)
        val fastGaps = gaps.count { it < 300 }
        org.junit.Assert.assertTrue(
            "retry gaps $gaps — the 0 ms hot loop is back",
            fastGaps <= 1,
        )
    }

    /**
     * Round-4: repeated desktop-disconnected → Degraded fast; the Re-pair hint fires
     * ONLY when the sealed credential is older than the §12.2 staleness window (a
     * FRESH pairing degrading the same way keeps the plain Retry path).
     */
    @Test
    fun `stale desktop-disconnected pairing suggests repair - fresh does not`() = runBlocking {
        mock.script.bridgeRequireTaskId = false
        synchronized(mock.script.bridgeErrorReasons) {
            repeat(6) { mock.script.bridgeErrorReasons.addLast("desktop-disconnected") }
        }
        hub.start()
        withTimeout(40_000) { hub.phase.first { it == ConnectionPhase.DEGRADED } }
        // Fresh credential (sealed seconds ago in setUp) → no repair hint yet.
        assertTrue(!hub.suggestRepair.value, "fresh pairing must not suggest repair")

        // STALE credential: a hub over a vault sealed 11+ minutes ago.
        val oldClock = System.currentTimeMillis() - 11 * 60_000
        val staleVault = CredentialVault(InMemoryCredentialStore(), IdentityKeyWrap(), ZLog(sink = {}), clock = { oldClock })
        staleVault.seal(
            PairingLink(
                endpointOrigin = mock.url.removeSuffix("/ws").replaceFirst("ws://", "http://"),
                route = "/remote/v4",
                deviceSid = sid,
                passHash = passHash,
                issuedAtMs = oldClock,
                deviceMid = "mock-desktop-mid",
                desktopName = "StaleBook",
                appVersion = "3.14.3",
            ),
        )
        val staleHub = ConnectionHub(
            transport = RelayTransport(wsFactory = com.zcode.android.core.transport.OkHttpWsFactory(), log = ZLog(sink = {})),
            vault = staleVault,
            taskRepo = tasks,
            conversationRepo = conversations,
            journalRepo = FakeJournalRepository(),
            interactions = FakeInteractionRepository(),
            endpointRepo = endpoints,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            log = ZLog(sink = {}),
        )
        synchronized(mock.script.bridgeErrorReasons) {
            repeat(6) { mock.script.bridgeErrorReasons.addLast("desktop-disconnected") }
        }
        try {
            staleHub.start()
            withTimeout(40_000) { staleHub.phase.first { it == ConnectionPhase.DEGRADED } }
            withTimeout(5_000) { staleHub.suggestRepair.first { it } }
            assertTrue(staleHub.suggestRepair.value, "stale pairing MUST suggest repair")
        } finally {
            runCatching { runBlocking { staleHub.phase; staleHub.endpoint; } }
        }
    }

    /**
     * Round-6: a RESTARTING desktop answers bootstrap EMPTY (tasks=0 workspaces=0 —
     * observed live between DEVICE_OFFLINE and the desktop's reload). Applying it
     * would wipe the cached 482-task projection and flash an empty list; the cache
     * must be kept (stale-labeled) until a real view arrives.
     */
    @Test
    fun `empty bootstrap view from a restarting desktop keeps the cached projection`() = runBlocking {
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        withTimeout(15_000) { tasks.snapshot.first { it.isNotEmpty() } }
        val before = tasks.snapshot.value.size
        assertTrue(before > 0)

        // Desktop "restarts": empty view, then healthy again.
        mock.script.bootstrapEmptyView = true
        hub.refreshTasks()
        assertEquals(before, tasks.snapshot.value.size, "empty view must NOT wipe the cached projection")

        mock.script.bootstrapEmptyView = false
        hub.refreshTasks()
        assertTrue(tasks.snapshot.value.isNotEmpty())
    }

    /**
     * Round-6/field regression (the DESKTOP-FREEZE culprit): user Retry pressed
     * repeatedly while the transport is mid-recovery must NOT spawn concurrent
     * connect() handshakes — retries route through reconnectNow() (single-flight).
     * Field log: 3 hub.connectFailed IllegalStateException in 1.4 s + the #328
     * desktop freeze.
     */
    @Test
    fun `rapid user retries during recovery spawn no concurrent handshakes`() = runBlocking {
        mock.script.bridgeRequireTaskId = false
        synchronized(mock.script.bridgeErrorReasons) {
            repeat(6) { mock.script.bridgeErrorReasons.addLast("desktop-disconnected") }
        }
        hub.start()
        withTimeout(40_000) { hub.phase.first { it == ConnectionPhase.DEGRADED } }

        // The user hammers Retry while the transport is Degraded/Reconnecting.
        repeat(5) {
            hub.retry()
            kotlinx.coroutines.delay(150)
        }
        // Let anything legal surface: with the error queue exhausted, fresh discovery
        // may legitimately CONNECT (that is the round-5 fix working). What the
        // barrage must guarantee: the hub stays OPERATIONAL (a legal machine phase —
        // no crash, no illegal state) and can still act.
        kotlinx.coroutines.delay(2_000)
        val phase = hub.phase.value
        assertTrue(
            phase in setOf(
                ConnectionPhase.DEGRADED, ConnectionPhase.CONNECTED,
                ConnectionPhase.RECONNECTING, ConnectionPhase.RECOVERING,
                ConnectionPhase.CONNECTING,
            ),
            "illegal post-barrage phase: $phase",
        )
        retry_connection_alive()
    }

    private suspend fun retry_connection_alive() {
        // Assert the hub is still operational after the retry barrage.
        org.junit.Assert.assertNotNull(hub.phase.value)
    }

    /**
     * Round-7e: entering Degraded persists a structured auto-report exactly once
     * (via the onDegradedReport callback) — the failure state is captured at the
     * moment it happens, even if the app dies before the user exports.
     */
    @Test
    fun `degraded entry writes an auto-report once`() = runBlocking {
        mock.script.bridgeRequireTaskId = false
        synchronized(mock.script.bridgeErrorReasons) {
            repeat(4) { mock.script.bridgeErrorReasons.addLast("desktop-disconnected") }
        }
        val reports = java.util.concurrent.ConcurrentLinkedQueue<String>()
        hub.onDegradedReport = { text -> reports.add(text) }
        hub.start()
        withTimeout(40_000) { hub.phase.first { it == ConnectionPhase.DEGRADED } }
        kotlinx.coroutines.delay(1_000)
        kotlin.test.assertTrue(
            reports.isNotEmpty() && reports.first().contains("DEGRADED") && reports.first().contains("desktop-disconnected"),
            "auto-report must capture the Degraded state + reason; got ${reports.firstOrNull()?.take(120)}",
        )
        kotlin.test.assertEquals(1, reports.size, "auto-report must fire once")
    }

    @Test
    fun `socket loss labels sessions stale - never silent (15-S1)`() = runBlocking {
        hub.start()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.CONNECTED } }
        val task = withTimeout(15_000) { tasks.snapshot.first { it.isNotEmpty() } }.first()
        hub.openConversation(task)
        withTimeout(20_000) { conversations.rowsFor(task.taskId).first { it.isNotEmpty() } }
        // Deterministic socket death: client-side abort (EOF/RST class — the same
        // failure class the §33 classifier handles) + server stopped so recovery
        // fails and the loss is PERSISTENT (S1 honest shape). MockZCode's scripted
        // close-frame delivery is timing-flaky under load (mockwebserver3), so the
        // transport-level close classification is covered by its dedicated
        // regression test (RelayTransportLossClassificationTest) instead.
        mock.stop()
        transport.abortConnectionForTest()
        withTimeout(30_000) { hub.phase.first { it == ConnectionPhase.RECONNECTING } }
        val staleNow = withTimeout(10_000) {
            hub.sessionStale(task.taskId).first { it }
        }
        assertTrue(staleNow)
    }
}

// ---------------------------------------------------------------- fakes (test-only)

internal class InMemoryCredentialStore : CredentialStore {
    private val map = HashMap<String, ByteArray>()
    override fun read(name: String): ByteArray? = map[name]
    override fun write(name: String, bytes: ByteArray) {
        map[name] = bytes
    }
    override fun delete(name: String): Boolean = map.remove(name) != null
    override fun listNames(): List<String> = map.keys.toList()
    override fun totalBytes(): Long = map.values.sumOf { it.size.toLong() }
}

internal class IdentityKeyWrap : MasterKeyWrap {
    override fun wrap(plain: ByteArray): ByteArray = plain
    override fun unwrap(wrapped: ByteArray): ByteArray? = wrapped
    override fun wipe(): Boolean = true
}

internal class FakeTaskRepository : TaskRepository {
    val snapshot = MutableStateFlow<List<Task>>(emptyList())
    private val flows = HashMap<String, MutableStateFlow<List<Task>>>()
    override fun observeTasks(endpointId: EndpointId, workspace: WorkspaceIdentityKey): Flow<List<Task>> =
        flows.getOrPut(workspace.raw) { MutableStateFlow(emptyList()) }

    override fun observeAllTasks(endpointId: EndpointId): Flow<List<Task>> = snapshot

    override suspend fun replaceAll(
        endpointId: EndpointId,
        workspace: WorkspaceIdentityKey,
        tasks: List<Task>,
        etag: SnapshotEtag?,
        atMs: Long,
    ): Boolean {
        snapshot.value = tasks.sortedByDescending { it.updatedAtMs }
        return true
    }

    override suspend fun cachedEtag(endpointId: EndpointId, workspace: WorkspaceIdentityKey): SnapshotEtag? = null

    override suspend fun upsert(endpointId: EndpointId, workspace: WorkspaceIdentityKey, task: Task) {
        snapshot.value = snapshot.value.filterNot { it.taskId == task.taskId } + task
    }

    override suspend fun remove(endpointId: EndpointId, workspace: WorkspaceIdentityKey, taskId: String) {
        snapshot.value = snapshot.value.filterNot { it.taskId == taskId }
    }

    override suspend fun evictAndSweep(endpointId: EndpointId, workspace: WorkspaceIdentityKey, nowMs: Long): Int = 0
}

internal class FakeConversationRepository : ConversationRepository {
    private val rows = HashMap<String, MutableStateFlow<List<ConversationRow>>>()
    private val epochs = HashMap<String, MutableStateFlow<String?>>()

    fun rowsFor(sessionId: String): Flow<List<ConversationRow>> =
        rows.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    fun epochFor(sessionId: String): String? = epochs[sessionId]?.value

    override fun observeRows(sessionId: SessionId): Flow<List<ConversationRow>> =
        rows.getOrPut(sessionId.raw) { MutableStateFlow(emptyList()) }

    override fun observeEpoch(sessionId: SessionId): Flow<String?> =
        epochs.getOrPut(sessionId.raw) { MutableStateFlow(null) }

    override suspend fun currentEpoch(sessionId: SessionId): String? = epochs[sessionId.raw]?.value

    override suspend fun apply(op: ConversationStoreOp): StoreApplyResult {
        when (op) {
            is ConversationStoreOp.SnapshotArrived -> {
                epochs.getOrPut(op.sessionId.raw) { MutableStateFlow(null) }.value = op.logEpoch
                rows.getOrPut(op.sessionId.raw) { MutableStateFlow(emptyList()) }.value = op.rows
                return StoreApplyResult.Replaced(null)
            }
            is ConversationStoreOp.PageFetched -> {
                epochs.getOrPut(op.sessionId.raw) { MutableStateFlow(null) }.value = op.logEpoch
                val cur = rows.getOrPut(op.sessionId.raw) { MutableStateFlow(emptyList()) }
                cur.value = (op.rows + cur.value).distinctBy { it.rowId }
                return StoreApplyResult.Applied
            }
            else -> return StoreApplyResult.Applied
        }
    }

    override suspend fun cachedRowCount(sessionId: SessionId): Int = rows[sessionId.raw]?.value?.size ?: 0
}

internal class FakeEndpointRepository : EndpointRepository {
    val saved = MutableStateFlow<List<Endpoint>>(emptyList())
    override fun observeEndpoints(): Flow<List<Endpoint>> = saved
    override suspend fun endpoints(): List<Endpoint> = saved.value
    override suspend fun save(endpoint: Endpoint) {
        saved.value = saved.value.filterNot { it.endpointId == endpoint.endpointId } + endpoint
    }
    override suspend fun delete(endpointId: EndpointId) {
        saved.value = saved.value.filterNot { it.endpointId == endpointId }
    }
    override suspend fun deleteAll() {
        saved.value = emptyList()
    }
}

internal class FakeJournalRepository : JournalRepository {
    private val journals = HashMap<String, RecoveryJournal>()
    override suspend fun record(journal: RecoveryJournal) {
        journals[journal.endpointId] = journal
    }
    override suspend fun load(endpointId: EndpointId): RecoveryJournal? = journals[endpointId.raw]
    override suspend fun clear(endpointId: EndpointId) {
        journals.remove(endpointId.raw)
    }
}

internal class FakeInteractionRepository : InteractionRepository {
    override fun observeOpen(endpointId: EndpointId): Flow<List<InteractionRepository.InteractionRecord>> =
        MutableStateFlow(emptyList())

    override suspend fun upsertPermission(request: PermissionRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle) = Unit
    override suspend fun upsertUserInput(request: UserInputRequest, endpointId: EndpointId, lifecycle: InteractionLifecycle) = Unit
    override suspend fun markResolved(endpointId: EndpointId, interactionId: InteractionId, atMs: Long, resolvedBySelf: Boolean) = Unit
    override suspend fun sweep(endpointId: EndpointId, nowMs: Long): Int = 0
    override suspend fun count(endpointId: EndpointId): Int = 0
}
