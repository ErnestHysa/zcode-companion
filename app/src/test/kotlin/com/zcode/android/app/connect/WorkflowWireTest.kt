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
import com.zcode.android.domain.model.SavedWorkflowList
import com.zcode.android.domain.model.SavedWorkflowRun
import com.zcode.android.domain.model.WorkflowRunEventsPage
import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.domain.model.WorkflowRunSummary
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A3.3 + L6.4 (Phase 5A) wire legs over the REAL transport ↔ mock: the seven run words
 * and the three hub words ride `zcode-agent` in the workspace-target form and decode
 * through the domain models; `startSavedWorkflow` is a v4 COMMAND whose ack carries the
 * typed Bu reason (not_found) or a `result.runId` — and the new run shows up in the next
 * `conversationWorkflowRunsV4` + `listSavedWorkflowRuns` reads (a real round-trip).
 */
class WorkflowWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid: String get() = "d_wf_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var facade: AgentFacadeClient

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect(setup: (MockZCode.Script) -> Unit = {}) {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        setup(mock.script)
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(
                        id = "ep-wf", kind = EndpointKind.RELAY_ZAI, displayName = "WfMockBook", wsUrl = url,
                        workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws", deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
            }
        }
        facade = AgentFacadeClient(transport)
    }

    private fun savedList() = buildJsonObject {
        put("workflows", JsonArray(listOf(buildJsonObject {
            put("name", JsonPrimitive("release-notes")); put("description", JsonPrimitive("Draft notes")); put("scope", JsonPrimitive("project")); put("path", JsonPrimitive("/ws/rn.ts"))
            put("args", buildJsonObject { put("version", buildJsonObject { put("type", JsonPrimitive("string")); put("required", JsonPrimitive(true)) }) })
        })))
        put("invalid", JsonArray(emptyList())); put("dir", JsonPrimitive("/ws/.zcode/workflows"))
    }

    private suspend fun startWorkflow(name: String): com.zcode.android.core.protocol.v4.CommandAck {
        val envelope = CommandEnvelope.build(
            clientId = transport.commandClientId ?: "ep-wf", sessionId = "synth-task-1",
            type = CommandTypes.START_SAVED_WORKFLOW,
            payload = buildJsonObject { put("name", JsonPrimitive(name)); put("scope", JsonPrimitive("project")); put("args", buildJsonObject { put("version", JsonPrimitive("1.2.0")) }) },
            issuedAt = 1L,
        )
        return transport.sendConversationCommand(envelope)
    }

    @Test
    fun `hub words decode and startSavedWorkflow round-trips into runs and saved runs`() {
        connect { it.savedWorkflows = savedList() }
        runBlocking {
            transport.subscribeSession("synth-task-1")
            val list = SavedWorkflowList.decode(facade.listSavedWorkflows())
            assertEquals(listOf("release-notes"), list.workflows.map { it.name })
            assertEquals(listOf("version"), list.workflows.single().requiredArgs.map { it.name })
            val get = facade.getSavedWorkflow("release-notes")
            assertEquals("true", (get["ok"] as JsonPrimitive).content)
            assertTrue(SavedWorkflowRun.decodeList(facade.listSavedWorkflowRuns()).isEmpty())
            assertTrue(WorkflowRunSummary.decodeList(facade.workflowRuns("synth-task-1")).isEmpty())

            val ack = startWorkflow("release-notes")
            assertEquals("accepted", ack.status)
            val runId = (ack.result?.get("runId") as JsonPrimitive).content
            assertNotNull(runId)

            val runs = WorkflowRunSummary.decodeList(facade.workflowRuns("synth-task-1"))
            assertEquals(listOf(runId), runs.map { it.runId })
            assertEquals(WorkflowRunStatus.RUNNING, runs.single().status)
            assertEquals("release-notes", runs.single().label)
            val events = WorkflowRunEventsPage.decode(facade.workflowRunEvents("synth-task-1", runId))
            assertEquals(listOf("run.started"), events.events.map { it.type })
            val saved = SavedWorkflowRun.decodeList(facade.listSavedWorkflowRuns("release-notes"))
            assertEquals(listOf(runId), saved.map { it.runId })
            // the start payload the mock saw carries name/scope/args verbatim
            val sent = mock.script.startedWorkflows.single()
            assertEquals("1.2.0", ((sent["args"] as JsonObject)["version"] as JsonPrimitive).content)
            assertEquals("project", (sent["scope"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `unknown workflow is rejected with the typed not_found reason`() {
        connect { it.savedWorkflows = savedList() }
        runBlocking {
            transport.subscribeSession("synth-task-1")
            val ack = startWorkflow("does-not-exist")
            assertEquals("rejected", ack.status)
            assertEquals("not_found", ack.reasonCode)
        }
    }

    @Test
    fun `run reads carry the workspace target and sessionId`() {
        connect { it.savedWorkflows = savedList() }
        runBlocking {
            transport.subscribeSession("synth-task-1")
            facade.workflowRuns("synth-task-1", limit = 8)
            facade.workflowRunArtifacts("synth-task-1", "wf-run-x")
            facade.workflowRunArtifactData("synth-task-1", "wf-run-x", "a1", afterSequence = 3, limit = 10)
        }
        val calls = mock.v4CallsForTest().filter { it.first.startsWith("conversationWorkflowRun") }
        assertEquals(listOf("conversationWorkflowRunsV4", "conversationWorkflowRunArtifactsV4", "conversationWorkflowRunArtifactDataV4"), calls.map { it.first })
        val runsArg = calls[0].second
        assertEquals("/synthetic/ws", (runsArg["workspacePath"] as JsonPrimitive).content)
        assertEquals("synth-task-1", (runsArg["sessionId"] as JsonPrimitive).content)
        assertEquals("8", (runsArg["limit"] as JsonPrimitive).content)
        val dataArg = calls[2].second
        assertEquals("3", (dataArg["afterSequence"] as JsonPrimitive).content)
        assertEquals("a1", (dataArg["artifactId"] as JsonPrimitive).content)
    }
}
