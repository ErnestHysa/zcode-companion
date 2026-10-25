package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.v4.CommandEnvelope
import com.zcode.android.core.protocol.v4.CommandTypes
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.AgentFacadeClient
import com.zcode.android.domain.model.RewindPreview
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A3.11 (Phase 5B) wire legs over the REAL transport ↔ mock: the rewind preview rides
 * `conversationFileRewindPreviewV4` with BOTH CAS currencies (baseRevision + baseLogEpoch)
 * and decodes the nt shape; the git-checkpoint trio round-trips create → diff → restore
 * (with the conflict branch flipping on force).
 */
class RewindCheckpointWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid: String get() = "d_rv_${System.identityHashCode(this)}"
    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect(setup: (MockZCode.Script) -> Unit = {}): AgentFacadeClient {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        setup(mock.script)
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(id = "ep-rv", kind = EndpointKind.RELAY_ZAI, displayName = "RvMockBook", wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws", deviceSid = sid, deviceMid = "mock-desktop-mid"),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
                transport.subscribeSession("synth-task-1")
            }
        }
        return AgentFacadeClient(transport)
    }

    @Test
    fun `rewind preview carries both CAS currencies and decodes the nt shape`() {
        val facade = connect {
            it.rewindPreview = buildJsonObject {
                put("canApply", JsonPrimitive(true))
                put("safeFiles", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                    put("action", JsonPrimitive("restore")); put("path", JsonPrimitive("src/version.ts"))
                    put("operationCount", JsonPrimitive(1)); put("toolNames", kotlinx.serialization.json.JsonArray(emptyList()))
                })))
                put("unsafeFiles", kotlinx.serialization.json.JsonArray(emptyList()))
                put("ignoredFiles", kotlinx.serialization.json.JsonArray(emptyList()))
            }
        }
        runBlocking {
            val ackEnvelope = CommandEnvelope.build(
                clientId = transport.commandClientId ?: "ep-rv", sessionId = "synth-task-1",
                type = CommandTypes.APPLY_FILE_REWIND, payload = buildJsonObject { }, issuedAt = 1L,
                baseRevision = 5, baseLogEpoch = "epoch-1",
            )
            // (the send itself is not the point here — the currencies are bound from the projection)
            assertEquals(5, ackEnvelope.baseRevision)
            val preview = RewindPreview.decode(facade.fileRewindPreview("synth-task-1", 12, "ent-12", baseRevision = 5, baseLogEpoch = "epoch-1"))
            assertTrue(preview.canApply)
            assertEquals(listOf("src/version.ts"), preview.safeFiles.map { it.path })
        }
        val arg = mock.v4CallsForTest().last { it.first == "conversationFileRewindPreviewV4" }.second
        assertEquals("5", (arg["baseRevision"] as JsonPrimitive).content)
        assertEquals("epoch-1", (arg["baseLogEpoch"] as JsonPrimitive).content)
        assertEquals("ent-12", ((arg["target"] as kotlinx.serialization.json.JsonObject)["entityId"] as JsonPrimitive).content)
    }

    @Test
    fun `git-checkpoint trio round-trips create diff restore and the conflict flips on force`() {
        connect { it.checkpointConflicts = true }
        val service = com.zcode.android.data.session.GitCheckpointClient(transport)
        runBlocking {
            val meta = service.createCheckpoint("/synthetic/ws")
            assertEquals("ckpt-1", (meta["checkpointId"] as JsonPrimitive).content)
            val diff = service.diffCheckpoints("/synthetic/ws", "ckpt-1", "ckpt-1")
            assertEquals(listOf("src/version.ts", "scratch/probe.txt"), com.zcode.android.domain.model.CheckpointDiff.decode(diff).files)
            val refused = service.restoreBetweenCheckpoints("/synthetic/ws", "ckpt-1", "ckpt-1", force = false)
            assertFalse(com.zcode.android.domain.model.CheckpointRestore.decode(refused).success)
            assertEquals(listOf("src/config.ts"), com.zcode.android.domain.model.CheckpointRestore.decode(refused).conflicts)
            val forced = service.restoreBetweenCheckpoints("/synthetic/ws", "ckpt-1", "ckpt-1", force = true)
            assertTrue(com.zcode.android.domain.model.CheckpointRestore.decode(forced).success)
        }
    }
}
