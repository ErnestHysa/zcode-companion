package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.domain.model.WorkspaceHookReview
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-024 (Phase 5D) wire legs over the REAL transport ↔ mock: the workspaceHookReview
 * payload decodes the yS shape (items with trust states; only the desktop's pending
 * classes actionable); the facade grant echoes the digest pair granted; the v4 review
 * commands flip the mock's per-item trust states (trust_selected → trusted_persistent,
 * revoke → revoked) — the rig loop.
 */
class HooksTrustWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid: String get() = "d_hook_${System.identityHashCode(this)}"
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
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(id = "ep-hook", kind = EndpointKind.RELAY_ZAI, displayName = "HookMockBook", wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws", deviceSid = sid, deviceMid = "mock-desktop-mid"),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
                transport.subscribeSession("synth-task-1")
            }
        }
    }

    private fun hook() = WorkspaceHookReview(
        reviewFlowId = "flow-1", generation = 1, interactionId = "hook-1", sessionId = "synth-task-1",
        taskId = "synth-task-1", runId = "run-1", workspaceIdentity = "synthetic-ws",
        workspaceLabel = "Synthetic Workspace", bundleDigest = "a".repeat(64),
        createdAtMs = 1, deadlineAtMs = 2, workspacePath = "/synthetic/ws", remoteSessionId = null,
        summary = WorkspaceHookReview.Summary(2, 2, 2),
        items = listOf(
            WorkspaceHookReview.Item("h1", "SessionStart", null, "command", "Warm build cache", "echo warming", ".zcode/hooks.json", "foreground", true, true, "pending_trust"),
            WorkspaceHookReview.Item("h2", "Stop", null, "process", "Watcher", "watch build", ".zcode/hooks.json", "background", true, true, "trusted_persistent"),
            WorkspaceHookReview.Item("h3", "Stop", null, "process", "Weird", "???", "x", "background", true, true, "unknown_state"),
        ),
    )

    @Test
    fun `the decode keeps unknown trust states inert and the pending classes actionable`() {
        val h = hook()
        assertTrue(h.items[0].actionable)
        assertTrue(h.items[2].actionable == false || !WorkspaceHookReview.TRUST_STATES.contains("unknown_state"))
        assertTrue(h.items[2].trustState == "unknown_state" && !h.items[2].actionable, "unknown states are kept raw and inert")
        // The decode through the wire payload tolerates the raw word.
        val decoded = WorkspaceHookReview.decode(
            kotlinx.serialization.json.Json.parseToJsonElement(mockPayload()).jsonObject,
        )!!
        assertEquals(2, decoded.summary.hookCount)
        assertEquals("pending_trust", decoded.items.first().trustState)
    }

    private fun mockPayload(): String = """{"kind":"workspaceHookReview","reviewFlowId":"flow-1","generation":1,
        "interactionId":"hook-1","sessionId":"s","taskId":"t","runId":"r","workspaceIdentity":"w",
        "workspaceLabel":"L","bundleDigest":"${"a".repeat(64)}","createdAt":1,"deadlineAt":2,
        "summary":{"eventCount":2,"hookCount":2,"pendingCount":1},
        "items":[{"reviewItemId":"h1","event":"SessionStart","type":"command","displayName":"Warm",
                  "displayCommand":"echo warming","sourcePath":".zcode/hooks.json","executionMode":"foreground",
                  "configuredEnabled":true,"editable":true,"trustState":"pending_trust"}]}"""

    @Test
    fun `the grant echoes the digest pair granted`() {
        connect()
        runBlocking {
            val json = transport.grantWorkspaceHookTrust("/synthetic/ws", "synthetic-ws", "a".repeat(64), "b".repeat(64))
            assertEquals("true", (json["granted"] as JsonPrimitive).content)
            assertEquals("a".repeat(64), (json["bundleDigest"] as JsonPrimitive).content)
            assertEquals("b".repeat(64), (json["hookDeclarationDigest"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `trust_selected flips the mock item to trusted and revoke flips it back`() {
        connect()
        runBlocking {
            val h = hook()
            transport.sendConversationCommand(
                com.zcode.android.core.protocol.v4.CommandEnvelope.build(
                    clientId = transport.commandClientId ?: "ep-hook", sessionId = "synth-task-1",
                    type = com.zcode.android.core.protocol.v4.CommandTypes.RESPOND_WORKSPACE_HOOK_REVIEW,
                    payload = buildJsonObject {
                        put("sessionId", h.sessionId); put("taskId", h.taskId); put("runId", h.runId)
                        put("workspaceIdentity", h.workspaceIdentity); put("bundleDigest", h.bundleDigest)
                        put("reviewFlowId", h.reviewFlowId); put("generation", h.generation)
                        put("interactionId", h.interactionId)
                        put("decision", buildJsonObject {
                            put("action", "trust_selected")
                            put("reviewItemIds", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("h1")) })
                        })
                    },
                    issuedAt = 1L,
                ),
            )
            assertEquals("trusted_persistent", mock.script.hookTrustStates["h1"])
            transport.sendConversationCommand(
                com.zcode.android.core.protocol.v4.CommandEnvelope.build(
                    clientId = transport.commandClientId ?: "ep-hook", sessionId = "synth-task-1",
                    type = com.zcode.android.core.protocol.v4.CommandTypes.REVOKE_WORKSPACE_HOOK_TRUST,
                    payload = buildJsonObject {
                        put("sessionId", h.sessionId); put("workspaceIdentity", h.workspaceIdentity)
                        put("bundleDigest", h.bundleDigest)
                        put("reviewItemIds", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("h1")) })
                    },
                    issuedAt = 2L,
                ),
            )
            assertEquals("revoked", mock.script.hookTrustStates["h1"])
        }
    }
}
