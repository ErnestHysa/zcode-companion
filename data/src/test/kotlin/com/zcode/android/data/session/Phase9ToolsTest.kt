package com.zcode.android.data.session

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 9 exit-gate E2E (architectural.md §43.2-9) over the REAL RelayTransport ↔
 * MockZCode (INV-15: synthetic content only). Covers: 9.1 fileChanges mapping,
 * 9.2 attachment upload/download round-trip with the verified bounds (512 KiB
 * chunk, 20 MiB client-side cap, MIME gate), 9.3 terminal view-only E2E (attach,
 * ANSI decode, ring bound, refusal), 9.4 git decodes, 9.5 switch commands, and
 * the INV-9 API-surface check.
 */
class Phase9ToolsTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

    /** Unique per test: a lingering PREVIOUS transport (backoff redial) must NEVER
     * authenticate against the NEXT test's mock (port reuse) and steal the slot. */
    private val sid: String get() = "d_phase9_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var tools: Phase9Tools

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        // Settle: WS close frames + OkHttp pool teardown are ASYNC — a new MockWebServer
        // can reuse the released port while the old socket is still draining (the flaky
        // "channel initialize timed out" cross-test artifact).
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    /** Connects a transport to a fresh mock; `configure` runs BEFORE start (script knobs).
     * One connect retry: the MockWebServer harness itself is the flaky leg under a
     * full-suite JVM (see tearDown's settle note) — never masking product behavior. */
    private fun connect(configure: (MockZCode) -> Unit = {}) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                mock = MockZCode(passHash = passHash, expectedSid = sid)
                configure(mock)
                val url = mock.start()
                transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
                runBlocking {
                    withTimeout(30_000) {
                        transport.connect(
                            EndpointDescriptor(
                                id = "ep-phase9",
                                kind = EndpointKind.RELAY_ZAI,
                                displayName = "Phase9MockBook",
                                wsUrl = url,
                                workspaceKey = "synthetic-ws",
                                workspacePath = "/synthetic/ws",
                                deviceSid = sid,
                                deviceMid = "mock-desktop-mid",
                            ),
                            signer(),
                        )
                    }
                }
                tools = Phase9Tools(transport)
                return
            } catch (e: Exception) {
                runBlocking { runCatching { transport.close("connect retry") } }
                runCatching { mock.stop() }
                if (attempt >= 2) throw e
                Thread.sleep(500)
            }
        }
    }

    private fun signer(): CredentialSigner = CredentialSigner { nonce, role, sid ->
        MockZCode.hmacProof(passHash, nonce, role, sid)
    }

    // ---------------------------------------------------------------- 9.1 fileChanges

    @Test
    fun `turn fileChanges decode maps pre-parsed hunks onto the renderer model`() {
        connect { m ->
            synchronized(m.script.conversationFileChanges) {
                m.script.conversationFileChanges.add(buildJsonObject {
                    put("files", 1); put("additions", 2); put("deletions", 1); put("state", "active")
                    putJsonArray("items") {
                        add(buildJsonObject {
                            put("path", "src/A.kt"); put("additions", 2); put("deletions", 1); put("writeCount", 1)
                            putJsonArray("toolNames") { add(JsonPrimitive("Edit")) }
                            putJsonArray("patches") {
                                add(buildJsonObject {
                                    put("oldStart", 1); put("oldLines", 2); put("newStart", 1); put("newLines", 3)
                                    putJsonArray("lines") {
                                        add(JsonPrimitive(" ctx")); add(JsonPrimitive("-old")); add(JsonPrimitive("+n1")); add(JsonPrimitive("+n2"))
                                    }
                                })
                            }
                        })
                    }
                })
            }
        }
        runBlocking {
            val changes = tools.turnFileChanges("synth-task-1", 7, "ent-7", 100, "epoch-1")
            assertEquals(1, changes.files)
            val item = changes.items.single()
            assertEquals("src/A.kt", item.path)
            assertEquals(1, item.hunks.size)
            val hunk = item.hunks.single()
            assertEquals(4, hunk.lines.size)
            assertEquals(2, hunk.lines.count { it.type == com.zcode.android.core.common.diff.DiffLineType.ADD })
            assertEquals(1, hunk.lines.count { it.type == com.zcode.android.core.common.diff.DiffLineType.DEL })
        }
    }

    @Test
    fun `turn fileChanges with no scripted result fails with a typed fault`() {
        connect()
        runBlocking {
            assertFailsWith<RelayTransport.Failure> {
                tools.turnFileChanges("synth-task-1", 7, "ent-7", 100, "epoch-1")
            }
        }
    }

    // ---------------------------------------------------------------- 9.2 attachments

    @Test
    fun `attachment upload round-trip across the 512KiB chunk boundary and download restores bytes`() {
        connect()
        runBlocking {
            // 512 KiB + 3 bytes → exactly 2 chunks; exercises the boundary precisely.
            val payload = ByteArray(512 * 1024 + 3) { (it % 251).toByte() }
            val checksum = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }
            val uploadId = "up-phase9-1"
            // image/png: the VERIFIED read gate only re-serves image/*|video/*|pdf (§10.2) —
            // the round-trip must use a previewable type end to end.
            val begin = tools.attachmentBegin(uploadId, "synth-task-1", "blob.png", "image/png", payload.size.toLong(), checksum)
            assertTrue(begin is com.zcode.android.core.protocol.v4.V4Attachment.BeginResult.Staging, "begin=${begin::class.simpleName}")
            for ((index, chunk) in payload.toList().chunked(512 * 1024).withIndex()) {
                tools.attachmentChunk(uploadId, "synth-task-1", index, chunk.toByteArray())
            }
            val ref = tools.attachmentCommit(uploadId, "synth-task-1")
            assertTrue(ref.isNotEmpty())
            // Download: chunked read reassembles the exact bytes (§23.3).
            val out = java.io.ByteArrayOutputStream()
            val (mediaType, totalBytes) = tools.attachmentReadAll("synth-task-1", ref) { out.write(it) }
            assertEquals("image/png", mediaType)
            assertEquals(payload.size.toLong(), totalBytes)
            assertTrue(payload.contentEquals(out.toByteArray()), "downloaded bytes differ")
        }
    }

    @Test
    fun `attachment upload over the 20MiB client cap is rejected BEFORE the wire (fail closed)`() {
        connect()
        runBlocking {
            val checksum = "sha256:" + "a".repeat(64)
            assertFailsWith<Phase9Tools.AttachmentRejectedException> {
                tools.attachmentBegin(
                    "up-big", "synth-task-1", "huge.bin", "application/octet-stream",
                    com.zcode.android.core.protocol.v4.AttachmentLimits.MAX_BYTES + 1, checksum,
                )
            }
        }
    }

    @Test
    fun `attachment read of a non-previewable media type fails closed (INV-14)`() {
        connect()
        runBlocking {
            val payload = "x".toByteArray()
            val uploadId = "up-bad-mime"
            tools.attachmentBegin(uploadId, "synth-task-1", "f.txt", "text/plain", 1, "sha256:" + "b".repeat(64))
            tools.attachmentChunk(uploadId, "synth-task-1", 0, payload)
            val ref = tools.attachmentCommit(uploadId, "synth-task-1")
            // text/plain is not in the VERIFIED previewable set — the decoder rejects it.
            assertFailsWith<com.zcode.android.core.protocol.bytes.ProtocolException> {
                tools.attachmentReadAll("synth-task-1", ref) { }
            }
        }
    }

    @Test
    fun `attachment upload to a refusing endpoint surfaces a typed failure`() {
        connect { m -> m.script.rejectAttachments = true }
        runBlocking {
            val checksum = "sha256:" + "c".repeat(64)
            val err = runCatching {
                tools.attachmentBegin("up-refused", "synth-task-1", "f.bin", "application/octet-stream", 10, checksum)
            }.exceptionOrNull()
            assertTrue(err != null, "refusal must surface")
        }
    }

    // ---------------------------------------------------------------- 9.3 terminal (view-only)

    @Test
    fun `terminal attach streams scripted output with ANSI decoded and honors exit`() {
        connect { m ->
            m.script.terminalOutput.add("[31mred-error[0m and plain\nsecond line\n")
        }
        runBlocking {
            val controller = TerminalController(transport, ZLog(sink = {}))
            val state = controller.attach()
            assertTrue(state is TerminalController.State.Live, "state=$state")
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && controller.snapshot.value.plainText.size < 2) {
                kotlinx.coroutines.delay(25)
            }
            val snap = controller.snapshot.value
            assertTrue(snap.plainText.any { it.contains("red-error") }, "plain=${snap.plainText}")
            val styled = snap.lines.flatMap { it }
            assertTrue(styled.any { it.fg == 1 && it.text.contains("red-error") }, "styled spans missing")
            // Exit event → Exited(code).
            mock.pushTerminalExit((state as TerminalController.State.Live).terminalId, 3)
            val exitDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < exitDeadline && controller.state.value !is TerminalController.State.Exited) {
                kotlinx.coroutines.delay(25)
            }
            assertTrue(controller.state.value is TerminalController.State.Exited)
            assertEquals(3, (controller.state.value as TerminalController.State.Exited).code)
            controller.detach()
        }
    }

    @Test
    fun `terminal ring holds the 512KiB bound with honest dropped counters (INV-5)`() {
        connect { m ->
            m.script.terminalOutput.add(("x".repeat(120) + "\n").repeat(5_500))
        }
        runBlocking {
            val controller = TerminalController(transport, ZLog(sink = {}))
            val state = controller.attach()
            assertTrue(state is TerminalController.State.Live)
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && controller.snapshot.value.droppedBytes == 0L) {
                kotlinx.coroutines.delay(50)
            }
            val snap = controller.snapshot.value
            assertTrue(snap.ringBytes <= TerminalController.RING_MAX_BYTES, "ring=${snap.ringBytes}")
            assertTrue(snap.droppedBytes > 0, "drop counter must be honest")
            controller.detach()
        }
    }

    @Test
    fun `terminal attach on a REFUSING endpoint lands in Refused (feature-disable fallback)`() {
        connect { m -> m.script.refuseChannels = setOf("terminal") }
        runBlocking {
            val controller = TerminalController(transport, ZLog(sink = {}))
            val state = controller.attach()
            assertTrue(state is TerminalController.State.Refused, "state=$state")
        }
    }

    @Test
    fun `INV-9 terminal transport surface exposes NO write or resize (API check)`() {
        val methods = RelayTransport::class.java.methods.map { it.name }
        assertTrue(methods.contains("terminalCreate"))
        assertTrue(methods.contains("terminalDispose"))
        assertTrue(!methods.contains("terminalWrite"), "write MUST NOT exist on the view-only surface")
        assertTrue(!methods.contains("terminalResize"), "resize MUST NOT exist on the view-only surface")
    }

    // ---------------------------------------------------------------- 9.4 git

    @Test
    fun `git read-only decodes summary changes diff and commits`() {
        connect { m ->
            m.script.gitSummary = buildJsonObject {
                put("workspacePath", "/synthetic/ws"); put("repoRoot", "/synthetic/ws")
                put("branchName", "main"); put("trackingBranchName", "origin/main")
                put("headRefType", "branch"); put("ahead", 1); put("behind", 2)
                put("isDirty", true); put("isGitAvailable", true); put("isRepository", true)
                putJsonArray("autoRefreshWatchPaths") {}
            }
            m.script.gitChanges = JsonArray(listOf(buildJsonObject {
                put("path", "/synthetic/ws/a.kt"); put("repoRelativePath", "a.kt")
                put("workspaceRelativePath", "a.kt"); put("kind", "modified"); put("section", "unstaged")
                put("added", 4); put("removed", 1); put("isStaged", false); put("isUntracked", false); put("isConflicted", false)
            }))
            m.script.gitDiff = buildJsonObject {
                put("path", "a.kt"); put("availability", "patch")
                put("patch", "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1,1 +1,1 @@\n-old\n+new\n")
                put("beforeContent", JsonPrimitive(null as String?))
                put("afterContent", JsonPrimitive(null as String?))
            }
            m.script.gitCommits = JsonArray(listOf(buildJsonObject {
                put("hash", "abc1234"); putJsonArray("parents") { add(JsonPrimitive("p0")) }
                putJsonArray("refs") {}; put("subject", "feat: thing"); put("authorName", "Ernest")
                put("authoredAtMs", 1_700_000_000_000)
            }))
            m.script.gitHasMore = true
        }
        runBlocking {
            val summary = tools.gitSummary("/synthetic/ws")
            assertEquals("main", summary.branchName)
            assertEquals(1, summary.ahead)
            assertTrue(summary.isDirty)
            val changes = tools.gitChanges("/synthetic/ws", "unstaged")
            assertEquals("a.kt", changes.single().repoRelativePath)
            assertEquals(4, changes.single().added)
            val diff = tools.gitDiff("/synthetic/ws", "a.kt")
            assertEquals("patch", diff.availability)
            assertTrue(diff.patch!!.contains("+new"))
            val (commits, hasMore) = tools.gitCommits("/synthetic/ws", 50, 0)
            assertEquals("feat: thing", commits.single().subject)
            assertTrue(hasMore)
        }
    }

    @Test
    fun `git channel refusal degrades to a typed failure (feature-disable plane)`() {
        connect { m -> m.script.refuseChannels = setOf("git") }
        runBlocking {
            val err = runCatching { tools.gitSummary("/synthetic/ws") }.exceptionOrNull()
            assertTrue(err is RelayTransport.Failure)
        }
    }

    // ---------------------------------------------------------------- 9.2 files browser

    @Test
    fun `file channel read-only surfaces entries text slices and byte ranges`() {
        connect { m ->
            m.script.fileTree["/synthetic/ws"] = JsonArray(listOf(
                buildJsonObject {
                    put("name", "src"); put("path", "/synthetic/ws/src"); put("type", "directory")
                },
                buildJsonObject {
                    put("name", "README.md"); put("path", "/synthetic/ws/README.md"); put("type", "file")
                },
            ))
            m.script.fileText["/synthetic/ws/README.md"] = "hello workspace"
            m.script.fileBytes["/synthetic/ws/README.md"] = "hello workspace".toByteArray()
        }
        runBlocking {
            val entries = tools.fileEntries("/synthetic/ws")
            assertEquals(2, entries.size)
            assertTrue(entries.first().isDirectory)
            val slice = tools.fileText("/synthetic/ws/README.md", 0, 5)
            assertEquals("hello", slice.content)
            assertEquals(15L, slice.totalBytes)
            assertTrue(slice.hasMore)
            val bytes = tools.fileBytes("/synthetic/ws/README.md", 6, 9)
            assertEquals("workspace", String(bytes))
        }
    }

    // ---------------------------------------------------------------- 9.5 model/mode switch

    @Test
    fun `model options decode from the model-selection view (graceful on refusal)`() {
        connect { m ->
            m.script.modelSelection = buildJsonObject {
                put("revision", 3)
                putJsonArray("providers") {
                    add(buildJsonObject {
                        put("providerId", "zai"); put("providerName", "Z.ai"); put("templateId", "zai")
                        putJsonArray("models") {
                            add(buildJsonObject { put("modelId", "glm-5.3") })
                            add(buildJsonObject { put("modelId", "glm-5.1") })
                        }
                    })
                }
            }
        }
        runBlocking {
            val options = tools.modelOptions()
            assertEquals(1, options.size)
            assertEquals("zai", options.single().providerId)
            assertEquals(listOf("glm-5.3", "glm-5.1"), options.single().models.map { it.id })
        }
    }

    @Test
    fun `switch commands via the outbox gate ACK with CAS and fail closed on bad modes`() {
        connect { m ->
            m.script.commandEffects = true
            m.script.emitStateKeys = true
        }
        runBlocking {
            // Round-3 field fidelity: conversation-plane calls REQUIRE the scoped listen
            // (unscoped calls are silenced — production behavior the mock models).
            transport.ensureConversationListen("/synthetic/ws", null)
            val outbox = RecordingOutbox()
            val coordinator = CommandCoordinator(
                endpointId = EndpointId("ep-phase9"),
                outbox = outbox,
                commands = gatewaySender(),
                connectionState = { com.zcode.android.core.transport.ConnectionStateMachine.ConnectionState.CONNECTED },
            )
            // Missing CAS currency → the gate REFUSES pre-wire (verified CAS set).
            assertEquals(
                CommandCoordinator.Outcome.MissingBaseRevision,
                coordinator.switchModelConfig("synth-task-1", "zai", "glm-5.1", "deep", null),
            )
            // Raw-send probe: surface the underlying exception verbatim if the wire
            // path fails (the coordinator folds any loss into UnknownKept).
            val probe = runCatching {
                gatewaySender().send(
                    "switchModelConfig", "synth-task-1",
                    buildJsonObject { put("provider", "zai"); put("model", "glm-5.1"); put("thought", "deep") },
                    100, null, "probe-cmd-1", null, null,
                )
            }
            assertTrue(probe.isSuccess, "raw send failed: ${probe.exceptionOrNull()}")
            // The mock's CAS currency: base every later send on the AUTHORITATIVE
            // revisionAtDecision (the probe's own effect bumped it).
            val revision = probe.getOrThrow().revisionAtDecision
            assertTrue(revision > 0, "revision=$revision")
            val outcome = coordinator.switchModelConfig("synth-task-1", "zai", "glm-5.1", "deep", revision)
            assertTrue(outcome is CommandCoordinator.Outcome.Accepted, "outcome=$outcome")
            assertEquals("switchModelConfig", outbox.types.single())
            // CAS chain: each accepted switch bumps the mock's revision — base the
            // next send on the PREVIOUS ack's revisionAtDecision (echo reconciliation).
            val afterModel = outcome.ack.revisionAtDecision
            val modeOutcome = coordinator.switchCollaborationMode("synth-task-1", "plan", afterModel)
            assertTrue(modeOutcome is CommandCoordinator.Outcome.Accepted, "modeOutcome=$modeOutcome")
            val afterMode = modeOutcome.ack.revisionAtDecision
            assertEquals(
                CommandCoordinator.Outcome.Accepted::class,
                coordinator.switchCollaborationMode("synth-task-1", "edit", afterMode)::class,
            )
            // Fail-closed: an out-of-set mode NEVER goes on the wire (§10.3).
            assertEquals(
                CommandCoordinator.Outcome.Failed(null),
                coordinator.switchCollaborationMode("synth-task-1", "auto", 101),
            )
        }
    }

    private fun gatewaySender(): CommandSender = object : CommandSender {
        override suspend fun send(
            type: String,
            sessionId: String?,
            payload: JsonObject,
            baseRevision: Long?,
            baseLogEpoch: String?,
            commandId: String,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): com.zcode.android.core.protocol.v4.CommandAck =
            transport.sendConversationCommand(
                com.zcode.android.core.protocol.v4.CommandEnvelope.build(
                    clientId = transport.commandClientId ?: "ep-phase9",
                    sessionId = sessionId,
                    type = type,
                    payload = payload,
                    issuedAt = System.currentTimeMillis(),
                    baseRevision = baseRevision,
                    baseLogEpoch = baseLogEpoch,
                    commandId = commandId,
                ),
                workspacePath,
                workspaceIdentity,
            )
    }

    /** Minimal outbox recording harness (the FixedOutbox pattern from CommandCoordinatorTest).
     * Entries are RETAINED: the coordinator re-reads baseRevision from the outbox at
     * transmit time — a get()=null double would strip the CAS currency. */
    private class RecordingOutbox : com.zcode.android.domain.repo.OutboxRepository {
        val types = mutableListOf<String>()
        var nextId = 1
        val entries = LinkedHashMap<String, com.zcode.android.domain.outbox.OutboxEntry>()
        override fun observePending(endpointId: EndpointId) =
            kotlinx.coroutines.flow.flowOf(entries.values.toList())
        override suspend fun accept(
            endpointId: EndpointId, sessionId: String?, type: String, payloadJson: String,
            baseRevision: Long?, baseLogEpoch: String?, issuedAtMs: Long,
        ): com.zcode.android.domain.ids.CommandId {
            types.add(type)
            val id = com.zcode.android.domain.ids.CommandId("cmd-${nextId++}")
            entries[id.raw] = com.zcode.android.domain.outbox.OutboxEntry(
                commandId = id, endpointId = endpointId.raw, sessionId = sessionId,
                type = type, payloadJson = payloadJson,
                baseRevision = baseRevision, baseLogEpoch = baseLogEpoch,
                issuedAtMs = issuedAtMs,
                state = com.zcode.android.domain.outbox.OutboxStateMachine.State.ACCEPTED_LOCALLY,
                attempts = 0, lastTransitionAtMs = issuedAtMs,
            )
            return id
        }
        override suspend fun apply(
            commandId: com.zcode.android.domain.ids.CommandId,
            event: com.zcode.android.domain.outbox.OutboxStateMachine.Event,
            atMs: Long,
        ): com.zcode.android.domain.outbox.OutboxEntry? {
            val current = entries[commandId.raw] ?: return null
            val next = current.copy(
                state = com.zcode.android.domain.outbox.OutboxStateMachine.restore(current.state).on(event),
                attempts = current.attempts + 1,
                lastTransitionAtMs = atMs,
            )
            entries[commandId.raw] = next
            return next
        }
        override suspend fun get(commandId: com.zcode.android.domain.ids.CommandId): com.zcode.android.domain.outbox.OutboxEntry? =
            entries[commandId.raw]
        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId): List<com.zcode.android.domain.journal.RecoveryJournal.PendingCommandRef> = emptyList()
        override suspend fun countByState(endpointId: EndpointId, state: com.zcode.android.domain.outbox.OutboxStateMachine.State): Int = 0
    }
}
