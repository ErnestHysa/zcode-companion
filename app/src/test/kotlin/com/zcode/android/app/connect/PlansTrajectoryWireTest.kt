package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.AgentFacadeClient
import com.zcode.android.domain.model.ConversationPlan
import com.zcode.android.domain.model.TrajectoryResult
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
import kotlin.test.assertTrue

/**
 * A3.12 + A3.13 (Phase 5B) wire legs over the REAL transport ↔ mock: `conversationPlansV4`
 * rides `zcode-agent` with the sessionId target; `getModelTrajectory` rides `zcode-task`
 * with the taskId target; `readSessionMessages` returns the bare message array.
 */
class PlansTrajectoryWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid: String get() = "d_pt_${System.identityHashCode(this)}"
    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var facade: AgentFacadeClient

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect() {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        mock.script.plansState = buildJsonObject {
            put("plans", JsonArray(listOf(buildJsonObject {
                put("title", JsonPrimitive("Ship the release"))
                put("steps", JsonArray(listOf(buildJsonObject {
                    put("content", JsonPrimitive("Write the changelog")); put("status", JsonPrimitive("completed"))
                })))
            })))
        }
        mock.script.modelTrajectory = buildJsonObject {
            put("taskId", JsonPrimitive("synth-task-1")); put("truncated", JsonPrimitive(false))
            put("sourceFiles", JsonArray(emptyList()))
            put("records", JsonArray(listOf(buildJsonObject {
                put("requestId", JsonPrimitive("r1")); put("startedAt", JsonPrimitive("2026-10-20T10:00:00Z"))
                put("callSource", buildJsonObject { put("kind", JsonPrimitive("main")) })
                put("model", buildJsonObject { put("modelId", JsonPrimitive("glm-5.3")) })
            })))
        }
        mock.script.sessionMessages = JsonArray(listOf(buildJsonObject {
            put("role", JsonPrimitive("user")); put("content", JsonPrimitive("Ship the release this week"))
        }))
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(id = "ep-pt", kind = EndpointKind.RELAY_ZAI, displayName = "PtMockBook", wsUrl = url, workspaceKey = "synthetic-ws", workspacePath = "/synthetic/ws", deviceSid = sid, deviceMid = "mock-desktop-mid"),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
                transport.subscribeSession("synth-task-1")
            }
        }
        facade = AgentFacadeClient(transport)
    }

    @Test
    fun `plans ride zcode-agent with the session target and decode`() {
        connect()
        runBlocking {
            val plans = ConversationPlan.decodeAll(facade.plans("synth-task-1"))
            assertEquals(listOf("Ship the release"), plans.map { it.title })
            assertEquals(listOf("completed"), plans.single().steps.map { it.status.wire })
        }
        assertEquals("conversationPlansV4", mock.v4CallsForTest().last { it.first == "conversationPlansV4" }.first)
        val arg = mock.v4CallsForTest().last { it.first == "conversationPlansV4" }.second
        assertEquals("synth-task-1", (arg["sessionId"] as JsonPrimitive).content)
        assertEquals(transport.activeWorkspaceTarget?.first, (arg["workspacePath"] as JsonPrimitive).content)
    }

    @Test
    fun `trajectory rides zcode-task with the taskId target and messages return bare`() {
        connect()
        runBlocking {
            val trajectory = TrajectoryResult.decode(facade.modelTrajectory("synth-task-1"))
            assertEquals(listOf("r1"), trajectory.records.map { it.requestId })
            assertEquals("main", trajectory.records.single().callKind)
            assertEquals("glm-5.3", trajectory.records.single().modelId)
            val messages = facade.sessionMessages("synth-task-1")
            assertEquals(1, messages.size)
            assertEquals("user", (messages.single()["role"] as JsonPrimitive).content)
        }
        assertTrue(mock.taskChannelCallsForTest().any { it.first == "getModelTrajectory" })
    }
}
