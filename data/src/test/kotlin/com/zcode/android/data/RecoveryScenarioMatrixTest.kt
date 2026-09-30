package com.zcode.android.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.core.transport.ReconnectPolicy
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.ConversationRepositoryImpl
import com.zcode.android.data.repo.DraftRepositoryImpl
import com.zcode.android.data.repo.InteractionRepositoryImpl
import com.zcode.android.data.repo.JournalRepositoryImpl
import com.zcode.android.data.repo.OutboxRepositoryImpl
import com.zcode.android.data.repo.TaskRepositoryImpl
import com.zcode.android.data.session.GatewayTopicEvent
import com.zcode.android.data.session.InteractionCoordinator
import com.zcode.android.data.session.RecoveryCoordinator
import com.zcode.android.data.session.RelayConversationGateway
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.model.SnapshotEtag
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.recovery.DeepLinkRoute
import com.zcode.android.domain.repo.ConversationStoreOp
import com.zcode.android.domain.repo.DraftRepository
import com.zcode.android.tools.mockzcode.MockZCode
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5.3 acceptance — the §15 scenario matrix S1–S17 (each scenario: correct
 * recovery behavior, stale labels, no ambiguous merge), plus the §15.5 draft/UI-state
 * isolation assertions (Phase 5.2 acceptance) — exercised against MockZCode through
 * the REAL RelayTransport, Room stores, and the Phase 5 recovery subsystem (§35.2:
 * CI runs exclusively against MockZCode, INV-15).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RecoveryScenarioMatrixTest {

    companion object {
        const val PASS_HASH = "c3ludGhldGljLXBhc3NoYXNoLWtleQ=="
        const val SID = "d_syntheticdevice000001"
        const val SESSION = "synth-task-1"
        val EP = EndpointId("ep-matrix")
        val WS = WorkspaceIdentityKey("synthetic-ws-one")
    }

    // ---------------------------------------------------------------- harness

    private lateinit var rootDb: ZcodeDatabase // per-test default db (scenario-local harnesses own theirs)

    @Before
    fun setUp() {
        rootDb = inMemoryDb()
    }

    @After
    fun tearDown() {
        rootDb.close()
    }

    private fun inMemoryDb(): ZcodeDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        ZcodeDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun log(): ZLog = ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = { e ->
        val debug = System.getenv("ZCODE_MATRIX_DEBUG") ?: System.getProperty("ZCODE_MATRIX_DEBUG")
        if (debug != null && debug.isNotEmpty()) println("[zclog] ${e.level} ${e.event} ${e.details ?: ""}")
    })

    private class RecordingNotificationPort : RecoveryCoordinator.NotificationRecordPort {
        val records = ArrayList<NotificationRecord>()
        val dismissed = ArrayList<String>()
        override suspend fun visible(): List<NotificationRecord> = records.toList()
        override suspend fun dismiss(keys: List<String>) { dismissed.addAll(keys); records.removeAll { it.notificationKey in keys } }
    }

    private class Harness(
        val db: ZcodeDatabase,
        val mock: MockZCode,
        val transport: RelayTransport,
        val gateway: RelayConversationGateway,
        val conversations: ConversationRepositoryImpl,
        val journal: JournalRepositoryImpl,
        val outbox: OutboxRepositoryImpl,
        val tasks: TaskRepositoryImpl,
        val drafts: DraftRepositoryImpl,
        val interactionRepo: InteractionRepositoryImpl,
        val interactions: InteractionCoordinator,
        val coordinator: RecoveryCoordinator,
        val notifications: RecordingNotificationPort,
        val scope: CoroutineScope,
        val staleSeen: ConcurrentLinkedQueue<Boolean>,
    ) {
        suspend fun close() {
            coordinator.close()
            transport.close("test done")
            delay(50)
            mock.stop()
            scope.cancel()
            db.close()
        }
    }

    private fun fastConfig(heartbeatMs: Long = 200, watchdogMs: Long = 1_500) = RelayTransport.Config(
        connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
        channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
        heartbeatIntervalMs = heartbeatMs, heartbeatWatchdogMs = watchdogMs,
        ackResendMs = 250, v4CallTimeoutMs = 5_000,
    )

    /** Gentler timeouts for the double-server S2 harness (Robolectric cold-start jitter). */
    private fun slowConfig() = RelayTransport.Config(
        connectTimeoutMs = 15_000, authTimeoutMs = 15_000, bridgeTimeoutMs = 15_000,
        channelInitTimeoutMs = 15_000, controlRequestTimeoutMs = 15_000,
        heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500,
        ackResendMs = 250, v4CallTimeoutMs = 15_000,
    )

    private fun harness(
        db: ZcodeDatabase = inMemoryDb(),
        reconnectBudgetMs: Long = 60_000,
        baseDelayMs: Long = 50,
        rowCount: Int = 8,
    ): Harness {
        val mock = MockZCode(PASS_HASH, SID, workspace = MockZCode.MockWorkspace(rowCount = rowCount))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val conversations = ConversationRepositoryImpl(db, clock = System::currentTimeMillis)
        val journal = JournalRepositoryImpl(db, clock = System::currentTimeMillis)
        val outbox = OutboxRepositoryImpl(db)
        val tasks = TaskRepositoryImpl(db, clock = System::currentTimeMillis)
        val drafts = DraftRepositoryImpl(db)
        val interactionRepo = InteractionRepositoryImpl(db, clock = System::currentTimeMillis)
        val transport = RelayTransport(
            log = log(), config = fastConfig(),
            reconnectPolicyOverride = ReconnectPolicy(baseDelayMs = baseDelayMs, maxDelayMs = 400, budgetMs = reconnectBudgetMs),
            externalScope = scope,
        )
        val gateway = RelayConversationGateway(transport, clientId = EP.raw, traceLog = log())
        val interactions = InteractionCoordinator(EP, interactionRepo, outbox, gateway, clock = System::currentTimeMillis, log = log())
        val notifications = RecordingNotificationPort()
        val coordinator = RecoveryCoordinator(
            endpointId = EP, gateway = gateway, store = conversations, journalRepo = journal,
            interactions = interactions, transport = transport, notificationRecords = notifications,
            scope = scope, clock = System::currentTimeMillis, log = log(),
        )
        val staleSeen = ConcurrentLinkedQueue<Boolean>()
        return Harness(db, mock, transport, gateway, conversations, journal, outbox, tasks, drafts, interactionRepo, interactions, coordinator, notifications, scope, staleSeen)
    }

    private fun endpoint(url: String) = EndpointDescriptor(
        id = EP.raw, kind = EndpointKind.RELAY_ZAI, displayName = "Matrix",
        wsUrl = url, workspaceKey = WS.raw, workspacePath = "/synthetic/ws-one",
        deviceSid = SID, deviceMid = "mock-desktop-mid",
    )

    private fun signer() = CredentialSigner { nonce, role, s -> MockZCode.hmacProof(PASS_HASH, nonce, role, s) }

    private suspend fun connectAndStart(h: Harness, vararg sessions: String = arrayOf(SESSION)) {
        val url = h.mock.start()
        h.transport.connect(endpoint(url), signer())
        h.coordinator.start(sessions.toList())
        watchStale(h)
    }

    private fun watchStale(h: Harness) {
        h.scope.launch {
            h.coordinator.manager(SESSION)?.let { m ->
                m.stale.collect { h.staleSeen.add(it) }
            }
        }
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 12_000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(25)
        }
        assertTrue(cond(), "timeout awaiting: $what")
    }

    private suspend fun awaitState(h: Harness, target: ConnectionStateMachine.ConnectionState, timeoutMs: Long = 15_000) {
        awaitUntil("transport state $target", timeoutMs) { h.transport.states.value == target }
    }

    private fun draftScope() = DraftRepository.DraftScope(EP, WS, SESSION)

    // ---------------------------------------------------------------- S1

    @Test
    fun `S1 connection lost - reconnect, gap-free continuation, honest stale labels`() = runBlocking {
        val h = harness()
        try {
            withTimeout(90_000) {
                connectAndStart(h)
                awaitUntil("snapshot rows cached") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                // server kills the socket after 2 more logical frames → auto-recovery
                h.mock.script.disconnectAfterFrames = 2
                h.mock.pushTopicFrame(SESSION, com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = false, fromSeq = 2, toSeq = 3)
                awaitUntil("reconnected after loss") {
                    h.staleSeen.contains(true) || h.transport.states.value == ConnectionStateMachine.ConnectionState.CONNECTED
                }
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED)
                awaitUntil("stale overlay cleared after recovery") { h.staleSeen.lastOrNull() == false }
                assertEquals(8, h.conversations.cachedRowCount(SessionId(SESSION)), "no duplicate rows after replay (S7-class)")
                assertEquals(0, h.transport.pendingAckCountForTest())
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S2

    @Test
    fun `S2 process death - journal restore, subscribe, projection restored without fabrication`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "matrix-s2-${System.nanoTime()}").apply { mkdirs() }
        var h: Harness? = null
        try {
            withTimeout(90_000) {
                val dbPath = File(dir, "s2.db").absolutePath
                fun openDb() = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZcodeDatabase::class.java, dbPath)
                    .addMigrations(ZcodeDatabase.MIGRATION_1_2)
                    .allowMainThreadQueries().build()
                // "first process"
                val db1 = openDb()
                val mock1 = MockZCode(PASS_HASH, SID)
                val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val conv1 = ConversationRepositoryImpl(db1, clock = System::currentTimeMillis)
                val journal1 = JournalRepositoryImpl(db1, clock = System::currentTimeMillis)
                val t1 = RelayTransport(log = log(), config = slowConfig(), reconnectPolicyOverride = ReconnectPolicy(baseDelayMs = 50, maxDelayMs = 300), externalScope = scope1)
                val g1 = RelayConversationGateway(t1, clientId = EP.raw)
                val inter1 = InteractionCoordinator(EP, InteractionRepositoryImpl(db1, clock = System::currentTimeMillis), OutboxRepositoryImpl(db1), g1, log = log())
                val c1 = RecoveryCoordinator(EP, g1, conv1, journal1, inter1, t1, scope = scope1, log = log())
                val url = mock1.start()
                t1.connect(endpoint(url), signer())
                c1.start(listOf(SESSION))
                awaitUntil("rows + journal") { conv1.cachedRowCount(SessionId(SESSION)) > 0 && (journal1.load(EP)?.subscriptions?.isNotEmpty() == true) }
                val watermark = journal1.load(EP)!!.subscriptions.single()
                assertEquals("epoch-mock-1", watermark.logEpoch)
                val rowsBefore = conv1.cachedRowCount(SessionId(SESSION))
                // "process death": everything dies except the on-disk stores
                c1.close(); t1.close("death"); delay(50); mock1.stop(); scope1.cancel(); db1.close()

                // "second process"
                val db2 = openDb()
                val mock2 = MockZCode(PASS_HASH, SID)
                val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val conv2 = ConversationRepositoryImpl(db2, clock = System::currentTimeMillis)
                val journal2 = JournalRepositoryImpl(db2, clock = System::currentTimeMillis)
                val t2 = RelayTransport(log = log(), config = slowConfig(), reconnectPolicyOverride = ReconnectPolicy(baseDelayMs = 50, maxDelayMs = 300), externalScope = scope2)
                val g2 = RelayConversationGateway(t2, clientId = EP.raw)
                val inter2 = InteractionCoordinator(EP, InteractionRepositoryImpl(db2, clock = System::currentTimeMillis), OutboxRepositoryImpl(db2), g2, log = log())
                val c2 = RecoveryCoordinator(EP, g2, conv2, journal2, inter2, t2, scope = scope2, log = log())
                h = Harness(db2, mock2, t2, g2, conv2, journal2, OutboxRepositoryImpl(db2), TaskRepositoryImpl(db2, clock = System::currentTimeMillis), DraftRepositoryImpl(db2), InteractionRepositoryImpl(db2, clock = System::currentTimeMillis), inter2, c2, RecordingNotificationPort(), scope2, ConcurrentLinkedQueue())
                val url2 = mock2.start()
                t2.connect(endpoint(url2), signer())
                c2.start(listOf(SESSION)) // hydrates from the persisted journal, then subscribes
                awaitUntil("projection restored") { conv2.cachedRowCount(SessionId(SESSION)) > 0 }
                assertEquals(rowsBefore, conv2.cachedRowCount(SessionId(SESSION)), "restored projection matches the pre-death snapshot size")
                awaitState(h!!, ConnectionStateMachine.ConnectionState.CONNECTED)
                c2.close(); t2.close("done"); delay(30); mock2.stop(); scope2.cancel(); db2.close(); h = null
            }
        } finally {
            h?.close()
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- S3

    @Test
    fun `S3 foreground wake - immediate reconnect skips backoff and recovers`() = runBlocking {
        val h = harness(baseDelayMs = 1_000) // auto path would sleep ≥ 1 s
        try {
            withTimeout(90_000) {
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                // socket loss (server-side close) → Reconnecting
                h.mock.script.disconnectAfterFrames = 1
                h.mock.pushTopicFrame(SESSION, com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = false, fromSeq = 2, toSeq = 3)
                awaitUntil("left CONNECTED") { h.transport.states.value != ConnectionStateMachine.ConnectionState.CONNECTED }
                // foreground trigger → immediate
                val start = System.currentTimeMillis()
                h.coordinator.foregroundTrigger()
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED)
                assertTrue(System.currentTimeMillis() - start < 4_000, "immediate trigger recovers fast (took ${System.currentTimeMillis() - start} ms)")
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S4

    @Test
    fun `S4 network transition - fast backoff recovery and duplicated tail deduped`() = runBlocking {
        val h = harness()
        try {
            withTimeout(90_000) {
                h.mock.script.duplicateEvery = 2 // duplicated tail during the flapping window
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                h.coordinator.notifyNetworkAvailable() // probe while connected: harmless
                h.mock.script.disconnectAfterFrames = 1
                h.mock.pushTopicFrame(SESSION, com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = false, fromSeq = 2, toSeq = 3)
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED, 25_000)
                assertEquals(8, h.conversations.cachedRowCount(SessionId(SESSION)), "duplicated tail never double-applied")
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S5

    @Test
    fun `S5 desktop offline - budget to Degraded with cached projection, manual retry recovers`() = runBlocking {
        val h = harness(reconnectBudgetMs = 2_000)
        try {
            withTimeout(90_000) {
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                // desktop goes unreachable: no heartbeat acks + upgrade refusals
                h.mock.script.ackHeartbeats = false
                h.mock.script.rejectConnections = true
                awaitState(h, ConnectionStateMachine.ConnectionState.DEGRADED, 25_000)
                val m = h.coordinator.manager(SESSION)!!
                awaitUntil("stale labeled") { m.stale.value }
                assertEquals(8, h.conversations.cachedRowCount(SessionId(SESSION)), "cached projection stays readable (§15-S5)")
                assertEquals("recovered-state", draftAfter(h), "draft (Android-owned) untouched while degraded")
                // desktop returns; manual retry gets a fresh budget (§14.4.1 Degraded → Reconnecting)
                h.mock.script.ackHeartbeats = true
                h.mock.script.rejectConnections = false
                h.transport.reconnectNow()
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED, 20_000)
                assertTrue(m.stale.value == false)
            }
        } finally { h.close() }
    }

    private suspend fun draftAfter(h: Harness): String? {
        h.drafts.save(draftScope(), "recovered-state", System.currentTimeMillis())
        return h.drafts.observe(draftScope()).first()
    }

    // ---------------------------------------------------------------- S6

    @Test
    fun `S6 relay unreachable - transport-class error, cached projection readable, never silent`() = runBlocking {
        val h = harness()
        try {
            withTimeout(60_000) {
                // a previously-cached projection exists from an earlier session
                val seeded = listOf(ConversationRow.Unknown(1, "t", "gen", buildJsonObject { put("rowId", JsonPrimitive(1)) }))
                h.conversations.apply(ConversationStoreOp.SnapshotArrived(SessionId(SESSION), "epoch-old", seeded))
                h.mock.script.rejectConnections = true
                val url = h.mock.start()
                val failure = runCatching { h.transport.connect(endpoint(url), signer()) }.exceptionOrNull()
                assertNotNull(failure, "unreachable relay must fail the connect")
                val category = (failure as? RelayTransport.Failure)?.error?.category
                assertTrue(
                    category in setOf(ErrorCategory.TRANSPORT, ErrorCategory.TIMEOUT, ErrorCategory.NETWORK),
                    "failure class must be honest: $category",
                )
                assertEquals(1, h.conversations.cachedRowCount(SessionId(SESSION)), "cached projection remains readable")
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S7

    @Test
    fun `S7 duplicated messages - drop + count, projection unchanged`() = runBlocking {
        val h = harness()
        try {
            withTimeout(90_000) {
                h.mock.script.duplicateEvery = 1 // every logical frame twice
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                delay(700) // let duplicates slip through
                assertEquals(8, h.conversations.cachedRowCount(SessionId(SESSION)), "S7: duplicates dropped, no double-apply")
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED)
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S8

    @Test
    fun `S8 out-of-order frames - hold then apply in sequence order`() = runBlocking {
        val h = harness()
        try {
            withTimeout(90_000) {
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                val m = h.coordinator.manager(SESSION)!!
                // frames 4..5 arrive BEFORE 2..3 (cursor at 1): hold, then fill applies in order
                val lateRow = h.mock.schemaRow(SESSION, 99, MockZCode.RowKind.ASSISTANT_TEXT, "late")
                val fillA = h.mock.schemaRow(SESSION, 97, MockZCode.RowKind.ASSISTANT_TEXT, "seq2")
                val fillB = h.mock.schemaRow(SESSION, 98, MockZCode.RowKind.REASONING, "seq3")
                val f45 = MockZCode.DeltaFrame(4, 5, kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("op", JsonPrimitive("row.appended")); put("row", lateRow) },
                )))
                val f23 = MockZCode.DeltaFrame(2, 3, kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("op", JsonPrimitive("row.appended")); put("row", fillA) },
                    buildJsonObject { put("op", JsonPrimitive("row.appended")); put("row", fillB) },
                )))
                h.mock.pushDeltas(SESSION, listOf(f45)) // gap → held
                awaitUntil("held") { m.reducerForTest().isHolding }
                h.mock.pushDeltas(SESSION, listOf(f23)) // contiguous fill → drains the hold in order
                awaitUntil("applied in order") {
                    val ids = h.conversations.observeRows(SessionId(SESSION)).first().map { it.rowId }
                    ids.containsAll(listOf(97L, 98L, 99L))
                }
                assertEquals(11, h.conversations.cachedRowCount(SessionId(SESSION)), "8 snapshot + 3 delta rows, order preserved")
                awaitUntil("journal watermark advanced to 5") {
                    h.journal.load(EP)?.subscriptions?.single()?.afterSeq == 5L
                }
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S9

    @Test
    fun `S9 stale-epoch snapshot - discard and replace as new base, draft retained (15-5d)`() = runBlocking {
        val h = harness()
        try {
            withTimeout(90_000) {
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                h.drafts.save(draftScope(), "precious draft", System.currentTimeMillis())
                // desktop replayed history: the epoch moved; recovery resync gets the new epoch
                h.mock.bumpEpoch("epoch-mock-2")
                h.mock.script.disconnectAfterFrames = 1
                h.mock.pushTopicFrame(SESSION, com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = false, fromSeq = 2, toSeq = 3)
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED, 25_000)
                awaitUntil("epoch replaced") {
                    h.journal.load(EP)?.subscriptions?.single()?.logEpoch == "epoch-mock-2"
                }
                assertEquals(8, h.conversations.cachedRowCount(SessionId(SESSION)), "new epoch snapshot fully replaced the projection")
                assertEquals("precious draft", h.drafts.observe(draftScope()).first(), "§15.5d: snapshot never overwrites drafts")
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S10

    @Test
    fun `S10 sequence gap - hold expires into resync, repeat escalates to full snapshot, never a local fill`() = runBlocking {
        val h = harness()
        try {
            withTimeout(120_000) {
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                val rowsBefore = h.conversations.observeRows(SessionId(SESSION)).first().map { it.rowId }.toSet()
                // a frame whose prefix NEVER arrives: gap 1 → 4..5 is unrepairable by resume
                val row45 = h.mock.schemaRow(SESSION, 95, MockZCode.RowKind.ASSISTANT_TEXT, "gapped")
                h.mock.pushDeltas(SESSION, listOf(MockZCode.DeltaFrame(4, 5, kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("op", JsonPrimitive("row.appended")); put("row", row45) },
                )))))
                // hold expires (5 s) → resync resume → same gap → repeat → FORCED snapshot (§15-S10)
                awaitUntil("forced-snapshot repair: projection equals the authoritative snapshot") {
                    val now = h.conversations.observeRows(SessionId(SESSION)).first().map { it.rowId }.toSet()
                    now == rowsBefore && !h.coordinator.manager(SESSION)!!.stale.value
                }
                assertEquals(8, h.conversations.cachedRowCount(SessionId(SESSION)), "the gapped op was never merged locally")
                assertTrue(h.transport.states.value == ConnectionStateMachine.ConnectionState.CONNECTED)
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S11

    @Test
    fun `S11 partial frame lost - crc fault escalates and recovers via resync`() = runBlocking {
        val h = harness(rowCount = 400) // fragmented snapshot → fragment corruption is reachable
        try {
            withTimeout(120_000) {
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                val errors = ArrayList<com.zcode.android.core.common.error.AppError>()
                val c = h.scope.launch { h.transport.appErrors.collect { errors.add(it) } }
                val m = h.coordinator.manager(SESSION)!!
                h.mock.script.corruptFragmentCrc = true
                h.mock.pushTopicFrame(SESSION, com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = true, fromSeq = 2, toSeq = 3)
                // FIELD-KILLER #9 (vc14): the crc fault is CONTAINED — it surfaces as a
                // counted SERIALIZATION fault and the connection survives (the wire is no
                // longer torn down; the relay would otherwise redeliver the poison forever).
                awaitUntil("corrupted-frame fault surfaced") {
                    errors.any { it.category == ErrorCategory.PROTOCOL || it.category == ErrorCategory.SERIALIZATION }
                }
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED, 25_000)
                awaitUntil("projection healthy after the S11 repair") {
                    h.conversations.cachedRowCount(SessionId(SESSION)) > 0 && !m.stale.value
                }
                c.cancel()
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S12

    @Test
    fun `S12 state changed while disconnected - authoritative terminal state applied + notifications reconciled`() = runBlocking {
        val h = harness()
        try {
            withTimeout(120_000) {
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                val m = h.coordinator.manager(SESSION)!!
                assertEquals(com.zcode.android.domain.state.TaskStateMachine.State.UNKNOWN, m.taskState.value)
                h.notifications.records.add(
                    NotificationRecord("n1", SESSION, NotificationRecord.Kind.TASK_FINISHED, createdAtMs = 1)
                )
                // the desktop completes the task while we are away; the NEXT snapshot carries it
                h.mock.setSessionTurnOutcome("completedSuccess")
                h.mock.script.disconnectAfterFrames = 1
                h.mock.pushTopicFrame(SESSION, com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = false, fromSeq = 2, toSeq = 3)
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED, 25_000)
                awaitUntil("task machine applied authoritative completion") {
                    m.taskState.value == com.zcode.android.domain.state.TaskStateMachine.State.COMPLETED
                }
                awaitUntil("S12 notification reconciliation dismissed the consumed record") {
                    "n1" in h.notifications.dismissed
                }
                assertTrue(m.stale.value == false, "stale overlay cleared with the recovered state")
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S13

    @Test
    fun `S13 permission arrived while disconnected - card reconciled from the snapshot (fail-closed options)`() = runBlocking {
        val h = harness()
        try {
            withTimeout(120_000) {
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                h.mock.setPendingApproval("Bash", "i-s13")
                h.mock.script.disconnectAfterFrames = 1
                h.mock.pushTopicFrame(SESSION, com.zcode.android.core.protocol.v4.DeliveryKind.ONLINE, snapshot = false, fromSeq = 2, toSeq = 3)
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED, 25_000)
                awaitUntil("open interaction reconciled from snapshot") {
                    h.interactions.knownInteractionIds().any { it.raw == "i-s13" }
                }
                val iid = com.zcode.android.domain.ids.InteractionId("i-s13")
                assertEquals(
                    com.zcode.android.domain.state.PermissionStateMachine.State.DISPLAYED,
                    h.interactions.machineStateForTest(iid),
                )
                awaitUntil("interaction store row") {
                    h.interactionRepo.observeOpen(EP).first().any { it.interactionId == iid }
                }
                // fail-closed: an unknown option on the reconciled card is NEVER submittable
                val hostile = h.interactions.submit(iid, "allowAllForever")
                assertTrue(hostile is InteractionCoordinator.SubmitOutcome.InertOption)
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S14

    @Test
    fun `S14 deep link after task changed - graceful routing over data-layer truth`() = runBlocking {
        val h = harness()
        try {
            withTimeout(30_000) {
                // task present + connected → OPEN_TASK
                h.tasks.replaceAll(EP, WS, listOf(task("t1", TaskStatus.RUNNING)), SnapshotEtag("e1"), 1)
                val env1 = DeepLinkRoute.Environment.Paired(EP.raw, connected = true, taskExists = true, interactionKnown = null, existsOnOtherEndpoint = false)
                assertEquals(DeepLinkRoute.Outcome.OPEN_TASK, DeepLinkRoute.resolve(DeepLinkRoute.parse("t1", null), env1))
                // authoritative removal (S17 store effect) → TASK_GONE
                h.tasks.remove(EP, WS, "t1")
                val gone = h.tasks.observeTasks(EP, WS).first().none { it.taskId == "t1" }
                assertTrue(gone)
                val env2 = DeepLinkRoute.Environment.Paired(EP.raw, connected = true, taskExists = false, interactionKnown = null, existsOnOtherEndpoint = false)
                assertEquals(DeepLinkRoute.Outcome.TASK_GONE, DeepLinkRoute.resolve(DeepLinkRoute.parse("t1", "i1"), env2))
            }
        } finally { h.close() }
    }

    private fun task(id: String, status: TaskStatus) = Task(
        taskId = id, title = "T-$id", status = status, workspace = WS, createdAtMs = 1, updatedAtMs = 1,
    )

    // ---------------------------------------------------------------- S15

    @Test
    fun `S15 two permission responses - double tap no-op + resolved-elsewhere wins over pending submit`() = runBlocking {
        val h = harness()
        try {
            withTimeout(30_000) {
                val request = com.zcode.android.domain.model.PermissionRequest(
                    interactionId = com.zcode.android.domain.ids.InteractionId("i-s15"),
                    sessionId = SESSION, toolName = "Bash",
                    options = listOf(
                        com.zcode.android.domain.model.PermissionOption("allowOnce"),
                        com.zcode.android.domain.model.PermissionOption("deny"),
                    ),
                    createdAtMs = 1,
                )
                h.interactions.onPermissionRequested(request)
                h.interactions.render(request.interactionId)
                // double tap: second submit while SUBMITTING is a no-op (mock sender blocks)
                val blocker = BlockingSender()
                val slowInteractions = InteractionCoordinator(EP, h.interactionRepo, h.outbox, blocker, log = log())
                slowInteractions.onPermissionRequested(request)
                slowInteractions.render(request.interactionId)
                var first: InteractionCoordinator.SubmitOutcome? = null
                val submitJob = launch { first = slowInteractions.submit(request.interactionId, "allowOnce") }
                awaitUntil("first submit on the wire") { blocker.sent == 1 }
                val second = slowInteractions.submit(request.interactionId, "deny")
                assertEquals(InteractionCoordinator.SubmitOutcome.DuplicateNoOp, second)
                assertEquals(1, blocker.sent, "double tap never double-submits")
                // resolved-elsewhere while our submit is in flight → pending submit discarded
                slowInteractions.onResolvedElsewhere(request.interactionId)
                blocker.release(com.zcode.android.core.protocol.v4.CommandAck("c", "accepted", 7, null, null, null))
                submitJob.join()
                assertTrue(
                    first is InteractionCoordinator.SubmitOutcome.Submitted,
                    "first submit outcome: $first",
                )
                assertEquals(
                    com.zcode.android.domain.state.PermissionStateMachine.State.RESOLVED,
                    slowInteractions.machineStateForTest(request.interactionId),
                )
                awaitUntil("store records the resolved card once") {
                    h.interactionRepo.count(EP) <= 1
                }
            }
        } finally { h.close() }
    }

    private class BlockingSender : com.zcode.android.data.session.CommandSender {
        var sent = 0
        private val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        private lateinit var ack: com.zcode.android.core.protocol.v4.CommandAck
        override suspend fun send(type: String, sessionId: String?, payload: JsonObject, baseRevision: Long?, baseLogEpoch: String?, commandId: String): com.zcode.android.core.protocol.v4.CommandAck {
            sent++
            gate.await()
            return ack
        }
        fun release(ack: com.zcode.android.core.protocol.v4.CommandAck) { this.ack = ack; gate.complete(Unit) }
    }

    // ---------------------------------------------------------------- S16

    @Test
    fun `S16 send during replay - envelope independent of replay, stale CAS handled, draft preserved`() = runBlocking {
        val h = harness()
        try {
            withTimeout(120_000) {
                connectAndStart(h)
                awaitUntil("rows") { h.conversations.cachedRowCount(SessionId(SESSION)) > 0 }
                h.drafts.save(draftScope(), "in-flight prompt", System.currentTimeMillis())
                // open a gap (replay/hold active): frame 4..5 with 2..3 missing
                val row95 = h.mock.schemaRow(SESSION, 95, MockZCode.RowKind.ASSISTANT_TEXT, "during-replay")
                h.mock.pushDeltas(SESSION, listOf(MockZCode.DeltaFrame(4, 5, kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("op", JsonPrimitive("row.appended")); put("row", row95) },
                )))))
                // sendText is NOT CAS-guarded (verified set): accepted while the projection replays
                val payload = buildJsonObject { put("text", JsonPrimitive("prompt during replay")) }
                val id1 = h.outbox.accept(EP, SESSION, "sendText", payload.toString(), null, null, System.currentTimeMillis())
                h.outbox.apply(id1, OutboxStateMachine.Event.Transmit, System.currentTimeMillis())
                val ack1 = h.gateway.send("sendText", SESSION, payload, null, null, id1.raw)
                assertEquals("accepted", ack1.status)
                h.outbox.apply(id1, OutboxStateMachine.Event.Ack(ack1.status), System.currentTimeMillis())
                assertEquals(OutboxStateMachine.State.ACKED, h.outbox.get(id1)?.state)
                // row-targeting CAS command with a STALE base → status stale → FAILED_STALE, honest user copy path
                val payload2 = buildJsonObject { put("rowId", JsonPrimitive(1)) }
                val id2 = h.outbox.accept(EP, SESSION, "retryTurn", payload2.toString(), baseRevision = 1, baseLogEpoch = "epoch-mock-1", issuedAtMs = System.currentTimeMillis())
                h.outbox.apply(id2, OutboxStateMachine.Event.Transmit, System.currentTimeMillis())
                val ack2 = h.gateway.send("retryTurn", SESSION, payload2, baseRevision = 1, baseLogEpoch = "epoch-mock-1", commandId = id2.raw)
                assertEquals("stale", ack2.status, "mock enforces the verified CAS semantics")
                h.outbox.apply(id2, OutboxStateMachine.Event.Ack(ack2.status), System.currentTimeMillis())
                assertEquals(OutboxStateMachine.State.FAILED_STALE, h.outbox.get(id2)?.state)
                // the draft (Android-owned) survives both outcomes (E8/§15-S16)
                assertEquals("in-flight prompt", h.drafts.observe(draftScope()).first())
            }
        } finally { h.close() }
    }

    // ---------------------------------------------------------------- S18 (round-8 field fix)

    /**
     * Round-8 FIELD-FINDING regression (real phone: "tap a task → can't load, even
     * after Retry"): a session attached while the transport had no connection (or
     * while Degraded) had its subscribe FAIL and was then dropped FOREVER — the
     * coordinator skipped already-managed sessions and the transport's recovery
     * pass had no record of the failed subscribe. The pending-attach drain
     * re-anchors such sessions on the next CONNECTED.
     */
    @Test
    fun `S18 attach while disconnected - pending session re-anchored when transport connects`() = runBlocking {
        val h = harness()
        try {
            withTimeout(90_000) {
                // Mock running, transport NOT connected: the coordinator attaches the
                // session, the subscribe fails ("no endpoint") → must go PENDING.
                h.coordinator.startTargets(listOf(RecoveryCoordinator.SessionTarget(SESSION)))
                awaitUntil("failed attach marked stale (pending)") {
                    h.coordinator.manager(SESSION)?.stale?.value == true
                }
                assertEquals(0, h.conversations.cachedRowCount(SessionId(SESSION)))

                // Bring the transport up → CONNECTED → the drain must re-subscribe
                // and the snapshot must land (pre-fix: never — dropped silently).
                val url = h.mock.start()
                h.transport.connect(endpoint(url), signer())
                awaitState(h, ConnectionStateMachine.ConnectionState.CONNECTED, 30_000)
                awaitUntil("pending session re-anchored and snapshot cached") {
                    h.conversations.cachedRowCount(SessionId(SESSION)) > 0
                }
                assertEquals(false, h.coordinator.manager(SESSION)!!.stale.value)
            }
        } finally {
            h.close()
        }
    }

    // ---------------------------------------------------------------- S17

    @Test
    fun `S17 task archived or deleted remotely - projection reflects removal, local draft preserved`() = runBlocking {
        val h = harness()
        try {
            withTimeout(30_000) {
                h.tasks.replaceAll(EP, WS, listOf(task("t-keep", TaskStatus.IDLE), task("t-gone", TaskStatus.RUNNING)), SnapshotEtag("e1"), 1)
                h.drafts.save(DraftRepository.DraftScope(EP, WS, "t-gone"), "draft for the gone task", 1)
                // authoritative sessions-index delta removes the task (replace reflects desktop truth)
                h.tasks.replaceAll(EP, WS, listOf(task("t-keep", TaskStatus.IDLE)), SnapshotEtag("e2"), 2)
                val ids = h.tasks.observeTasks(EP, WS).first().map { it.taskId }
                assertTrue("t-gone" !in ids, "removed task leaves the projection (desktop wins, INV-1)")
                assertEquals(
                    "draft for the gone task",
                    h.drafts.observe(DraftRepository.DraftScope(EP, WS, "t-gone")).first(),
                    "§15-S17: local draft preserved until the user discards it",
                )
            }
        } finally { h.close() }
    }
}
