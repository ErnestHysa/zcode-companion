package com.zcode.android.data.session

import com.zcode.android.core.protocol.v4.CommandAck
import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.journal.RecoveryJournal
import com.zcode.android.domain.model.SharedContextImportState
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.outbox.OutboxEntry
import com.zcode.android.domain.repo.OutboxRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G9 (vc51) — the shared-context handover (context_refs on sendText +
 * discardSharedContext): NOT MODELED at all on the phone before this.
 * Pins: the context_refs payload shape (command.ts: `{kind:"shared_context_
 * import", context_id}`, max 1 fail-closed), the discard verb, and the
 * sendable verdict of the decoded import state.
 */
class CommandCoordinatorContextRefsTest {

    private class RecordingSender : CommandSender {
        val payloads = mutableListOf<Pair<String, JsonObject?>>()
        override suspend fun send(
            type: String,
            sessionId: String?,
            payload: JsonObject,
            baseRevision: Long?,
            baseLogEpoch: String?,
            commandId: String,
            workspacePath: String?,
            workspaceIdentity: String?,
        ): CommandAck = CommandAck(commandId, "accepted", 1, null, null, null).also {
            payloads.add(type to payload)
        }
    }

    private fun outbox(): OutboxRepository = object : OutboxRepository {
        override fun observePending(endpointId: EndpointId): Flow<List<OutboxEntry>> = MutableStateFlow(emptyList())
        override suspend fun accept(
            endpointId: EndpointId, sessionId: String?, type: String, payloadJson: String,
            baseRevision: Long?, baseLogEpoch: String?, issuedAtMs: Long,
        ): CommandId = CommandId(java.util.UUID.randomUUID().toString())
        override suspend fun apply(commandId: CommandId, event: OutboxStateMachine.Event, atMs: Long): OutboxEntry? = null
        override suspend fun get(commandId: CommandId): OutboxEntry? = null
        override suspend fun sweepExpired(endpointId: EndpointId, nowMs: Long, ttlMs: Long): Int = 0
        override suspend fun pendingRefs(endpointId: EndpointId): List<RecoveryJournal.PendingCommandRef> = emptyList()
        override suspend fun countByState(endpointId: EndpointId, state: OutboxStateMachine.State): Int = 0
    }

    @Test
    fun `sendText carries context_refs with the shared_context_import ref`() = runBlocking {
        val sender = RecordingSender()
        CommandCoordinator(
            EndpointId("ep-ctx"), outbox(), sender,
            { ConnectionStateMachine.ConnectionState.CONNECTED },
        ).sendPrompt("sess-1", "continue with this", contextRefs = listOf("shared_context_import" to "ctx-1"))
        val refs = sender.payloads.single().second?.get("context_refs")?.let { it as? kotlinx.serialization.json.JsonArray }
        assertEquals(1, refs?.size)
        val ref = refs?.first() as? JsonObject
        assertEquals("shared_context_import", (ref?.get("kind") as? JsonPrimitive)?.content)
        assertEquals("ctx-1", (ref?.get("context_id") as? JsonPrimitive)?.content)
    }

    @Test
    fun `more than one ref is refused fail-closed - nothing goes on the wire`() = runBlocking {
        val sender = RecordingSender()
        val outcome = CommandCoordinator(
            EndpointId("ep-ctx"), outbox(), sender,
            { ConnectionStateMachine.ConnectionState.CONNECTED },
        ).sendPrompt(
            "sess-1", "text",
            contextRefs = listOf("shared_context_import" to "a", "shared_context_import" to "b"),
        )
        assertTrue(outcome is CommandCoordinator.Outcome.Failed, "the schema caps context_refs at 1 — refuse, never lenient-send")
        assertTrue(sender.payloads.isEmpty())
    }

    @Test
    fun `no refs omits the key entirely`() = runBlocking {
        val sender = RecordingSender()
        CommandCoordinator(
            EndpointId("ep-ctx"), outbox(), sender,
            { ConnectionStateMachine.ConnectionState.CONNECTED },
        ).sendPrompt("sess-1", "plain text")
        assertTrue(sender.payloads.single().second?.containsKey("context_refs") != true)
    }

    @Test
    fun `discardSharedContext rides discardSharedContext with the contextId`() = runBlocking {
        val sender = RecordingSender()
        val outcome = CommandCoordinator(
            EndpointId("ep-ctx"), outbox(), sender,
            { ConnectionStateMachine.ConnectionState.CONNECTED },
        ).discardSharedContext("sess-1", "ctx-1")
        assertTrue(outcome is CommandCoordinator.Outcome.Accepted)
        val (type, payload) = sender.payloads.single()
        assertEquals("discardSharedContext", type)
        assertEquals("ctx-1", (payload?.get("contextId") as? JsonPrimitive)?.content)
    }

    @Test
    fun `the import state's sendable verdict - v2 with id yes, legacy and discarded no`() {
        val v2 = SharedContextImportState.decodeOrNull(buildJsonObject {
            put("contextId", JsonPrimitive("ctx-1"))
            put("title", JsonPrimitive("shared"))
            put("status", JsonPrimitive("pending"))
            put("shareUrl", JsonPrimitive("https://zcode.example/cn/share/abc"))
        })
        assertTrue(v2?.sendable == true)
        val legacy = SharedContextImportState.decodeOrNull(buildJsonObject {
            put("title", JsonPrimitive("legacy only"))
        })
        assertTrue(legacy?.sendable == false, "the legacy shape carries no contextId — never sendable")
        val discarded = SharedContextImportState.decodeOrNull(buildJsonObject {
            put("contextId", JsonPrimitive("ctx-2"))
            put("title", JsonPrimitive("shared"))
            put("status", JsonPrimitive("discarded"))
        })
        assertTrue(discarded?.sendable == false)
    }
}
