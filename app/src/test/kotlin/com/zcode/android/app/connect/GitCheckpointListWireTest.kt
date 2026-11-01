package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.GitCheckpointClient
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * vc51 Phase 4A (CKPT-HYDRATE) wire leg over the REAL transport ↔ mock: the checkpoint
 * HISTORY word (`git-checkpoint.listCheckpoints`) round-trips — empty on a fresh script,
 * and a checkpoint created earlier (the restart leg's shape on the rig) is listed by id,
 * so the Git pane can hydrate its card on entry instead of keeping a mount-local list.
 */
class GitCheckpointListWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
    private val sid: String get() = "d_ckpt_${System.identityHashCode(this)}"
    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect(): GitCheckpointClient {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(id = "ep-ckpt", kind = EndpointKind.RELAY_ZAI, displayName = "CkptMockBook", wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws", deviceSid = sid, deviceMid = "mock-desktop-mid"),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
                transport.subscribeSession("synth-task-1")
            }
        }
        return GitCheckpointClient(transport)
    }

    @Test
    fun `list is empty before any create lands`() {
        val client = connect()
        runBlocking {
            assertTrue(client.listCheckpoints("/synthetic/ws").isEmpty())
        }
    }

    @Test
    fun `a checkpoint created before the hydration is listed by id (restart shape)`() {
        val client = connect()
        runBlocking {
            val created = client.createCheckpoint("/synthetic/ws")
            val createdId = (created["checkpointId"] as kotlinx.serialization.json.JsonPrimitive).content
            // the app restarts here on the rig — the phone re-hydrates on entry
            val listed = client.listCheckpoints("/synthetic/ws")
            assertEquals(1, listed.size)
            val listedId = (listed.single()["checkpointId"] as kotlinx.serialization.json.JsonPrimitive).content
            assertEquals(createdId, listedId)
        }
    }
}
