package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.v4.CommandEnvelope
import com.zcode.android.core.protocol.v4.CommandTypes
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * I1 (Phase 5A) wire legs over the REAL transport ↔ mock: `reorderQueueItem` moves the
 * row in the mock's queue (null = end), `setAutoDrain` flips the queue's drain state, and
 * `setFollowupMode` lands in the config echo — the three decoded-unsent words now round-trip.
 */
class QueueWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid: String get() = "d_queue_${System.identityHashCode(this)}"
    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect() {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        mock.script.commandEffects = true
        mock.script.enforceCas = false
        mock.script.queueItems = listOf(MockZCode.QueueItem("q1", "one"), MockZCode.QueueItem("q2", "two"), MockZCode.QueueItem("q3", "three"))
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(id = "ep-queue", kind = EndpointKind.RELAY_ZAI, displayName = "QueueMockBook", wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws", deviceSid = sid, deviceMid = "mock-desktop-mid"),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
                transport.subscribeSession("synth-task-1")
            }
        }
    }

    private suspend fun send(type: String, payload: kotlinx.serialization.json.JsonObject) = transport.sendConversationCommand(
        CommandEnvelope.build(clientId = transport.commandClientId ?: "ep-queue", sessionId = "synth-task-1", type = type, payload = payload, issuedAt = 1L, baseRevision = 1L),
    )

    @Test
    fun `reorder moves the row and null sends it to the end`() {
        connect()
        runBlocking {
            send(CommandTypes.REORDER_QUEUE_ITEM, buildJsonObject { put("queueItemId", JsonPrimitive("q3")); put("beforeQueueItemId", JsonPrimitive("q1")) })
            assertEquals(listOf("q3", "q1", "q2"), mock.script.queueItems!!.map { it.queueItemId })
            send(CommandTypes.REORDER_QUEUE_ITEM, buildJsonObject { put("queueItemId", JsonPrimitive("q3")); put("beforeQueueItemId", JsonNull) })
            assertEquals(listOf("q1", "q2", "q3"), mock.script.queueItems!!.map { it.queueItemId })
        }
    }

    @Test
    fun `setAutoDrain flips the drain state and setFollowupMode lands in the config`() {
        connect()
        runBlocking {
            assertEquals(true, mock.script.autoDrain)
            send(CommandTypes.SET_AUTO_DRAIN, buildJsonObject { put("autoDrain", JsonPrimitive(false)) })
            assertEquals(false, mock.script.autoDrain)
            send(CommandTypes.SET_FOLLOWUP_MODE, buildJsonObject { put("mode", JsonPrimitive("guide")) })
            assertEquals("guide", (mock.script.sessionConfig!!["followupMode"] as JsonPrimitive).content)
        }
    }
}
