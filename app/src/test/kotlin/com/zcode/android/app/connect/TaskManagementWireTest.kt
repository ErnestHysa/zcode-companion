package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.TaskServiceClient
import com.zcode.android.domain.model.GroupedTaskNode
import com.zcode.android.domain.model.TaskGroupInfo
import com.zcode.android.domain.model.TaskMeta
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
 * A3.8–A3.10 (gaps-and-polish Phase 5A) wire legs over the REAL transport ↔ mock: the
 * pin / unread / resume / archived-delete words in the workspace-target form, the group
 * words with `workspaceScopes`, the grouped-view read-modify-write (`topLevelNodes` +
 * `groups[].taskRefs`), and the off-peak `pauseTask`/`continueTask` channel words (bare
 * positional id — the OffPeakTaskService shape). Every mutation is provable as a
 * round-trip because the mock keeps state.
 */
class TaskManagementWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid: String get() = "d_mgmt_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var tasks: TaskServiceClient

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
                        id = "ep-mgmt",
                        kind = EndpointKind.RELAY_ZAI,
                        displayName = "MgmtMockBook",
                        wsUrl = url,
                        workspaceKey = "synthetic-ws",
                        workspacePath = "/synthetic/ws",
                        deviceSid = sid,
                        deviceMid = "mock-desktop-mid",
                    ),
                    CredentialSigner { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) },
                )
            }
        }
        tasks = TaskServiceClient(transport)
    }

    private fun row(id: String, title: String) = buildJsonObject {
        put("taskId", JsonPrimitive(id)); put("title", JsonPrimitive(title))
        put("workspacePath", JsonPrimitive("/synthetic/ws")); put("createdAt", JsonPrimitive(1)); put("updatedAt", JsonPrimitive(2))
    }

    @Test
    fun `pin unread resume ride zcode-task with the workspace target and round-trip through the pinned list`() {
        connect { it.directTasks = listOf(row("t1", "One"), row("t2", "Two")) }
        runBlocking {
            tasks.setTaskPinned("/synthetic/ws", "synthetic-ws", "t1", true)
            val pinned = tasks.listPinnedTasks("/synthetic/ws", "synthetic-ws").mapNotNull(TaskMeta::decode)
            assertEquals(listOf("t1"), pinned.map { it.taskId })
            assertTrue(pinned.single().pinned)
            tasks.setTaskUnread("/synthetic/ws", "synthetic-ws", "t2", unread = true)
            tasks.setTaskUnread("/synthetic/ws", "synthetic-ws", "t2", unread = false, expectedUnreadAt = 123L)
            tasks.resumeTask("/synthetic/ws", "synthetic-ws", "t2")
        }
        val calls = mock.taskChannelCallsForTest()
        assertEquals(listOf("setTaskPinned", "listPinnedTasks", "setTaskUnread", "setTaskUnread", "resumeTask"), calls.map { it.first })
        assertEquals("true", (calls[0].second["pinned"] as JsonPrimitive).content)
        assertEquals("/synthetic/ws", (calls[0].second["workspacePath"] as JsonPrimitive).content)
        assertEquals(null, calls[2].second["expectedUnreadAt"], "mark-unread carries no expectation")
        assertEquals("123", (calls[3].second["expectedUnreadAt"] as JsonPrimitive).content, "mark-read carries the CAS expectation")
        assertEquals("t2", (calls[4].second["taskId"] as JsonPrimitive).content)
    }

    @Test
    fun `groups create rename recolor list move and delete ride zcode-task with workspaceScopes`() {
        connect { it.directTasks = listOf(row("t1", "One"), row("t2", "Two")) }
        runBlocking {
            val created = TaskGroupInfo.decode(tasks.createTaskGroup("/synthetic/ws", "synthetic-ws", "Backend", "green"))
            assertNotNull(created)
            assertEquals("Backend", created.title)
            tasks.renameTaskGroup("/synthetic/ws", "synthetic-ws", created.id, "Core")
            tasks.updateTaskGroupColor("/synthetic/ws", "synthetic-ws", created.id, "red")
            tasks.applyGroupedTaskViewOrder(
                "/synthetic/ws", "synthetic-ws",
                topLevel = listOf(TaskServiceClient.GroupedOrderNode.Group(created.id), TaskServiceClient.GroupedOrderNode.Task("t1")),
                groups = mapOf(created.id to listOf("t2")),
            )
            val nodes = GroupedTaskNode.decodeAll(tasks.listGroupedTaskViewStructure("/synthetic/ws", "synthetic-ws"))
            val group = nodes.filterIsInstance<GroupedTaskNode.Group>().single()
            assertEquals("Core", group.group.title)
            assertEquals("red", group.group.color.wire)
            assertEquals(listOf("t2"), group.taskIds)
            assertEquals(listOf("t1"), nodes.filterIsInstance<GroupedTaskNode.Single>().map { it.taskId })
            tasks.deleteTaskGroup("/synthetic/ws", "synthetic-ws", created.id)
            assertTrue(GroupedTaskNode.decodeAll(tasks.listGroupedTaskViewStructure("/synthetic/ws", "synthetic-ws")).filterIsInstance<GroupedTaskNode.Group>().isEmpty())
        }
        val calls = mock.taskChannelCallsForTest()
        val create = calls.first { it.first == "createTaskGroup" }.second
        assertEquals("Backend", (create["title"] as JsonPrimitive).content)
        val scopes = create["workspaceScopes"] as JsonArray
        assertEquals("/synthetic/ws", ((scopes.first() as JsonObject)["workspacePath"] as JsonPrimitive).content)
        val apply = calls.first { it.first == "applyGroupedTaskViewOrder" }.second
        val top = apply["topLevelNodes"] as JsonArray
        assertEquals("group", ((top[0] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("task", ((top[1] as JsonObject)["type"] as JsonPrimitive).content)
        val groupsArr = apply["groups"] as JsonArray
        val ref = (((groupsArr[0] as JsonObject)["taskRefs"] as JsonArray)[0] as JsonObject)
        assertEquals("t2", (ref["taskId"] as JsonPrimitive).content)
        assertEquals("/synthetic/ws", (ref["workspacePath"] as JsonPrimitive).content)
    }

    @Test
    fun `grouping a pinned task is refused by the host`() {
        connect { it.directTasks = listOf(row("t1", "One")) }
        runBlocking {
            tasks.setTaskPinned("/synthetic/ws", "synthetic-ws", "t1", true)
            val created = TaskGroupInfo.decode(tasks.createTaskGroup("/synthetic/ws", "synthetic-ws", "G", "gray"))!!
            val refused = runCatching {
                tasks.applyGroupedTaskViewOrder("/synthetic/ws", "synthetic-ws", listOf(TaskServiceClient.GroupedOrderNode.Group(created.id)), mapOf(created.id to listOf("t1")))
            }
            assertTrue(refused.isFailure, "the host refuses pinned members (Grouped task order 包含不可见 task)")
        }
    }

    @Test
    fun `archived viewer words - archive then list then delete permanently`() {
        connect { it.directTasks = listOf(row("t1", "One")); it.archivedTaskIds.add("t1") }
        runBlocking {
            val archived = tasks.listArchivedTasks("/synthetic/ws", "synthetic-ws").mapNotNull(TaskMeta::decode)
            assertEquals(listOf("t1"), archived.map { it.taskId })
            assertTrue(archived.single().archived)
            tasks.deleteArchivedTask("/synthetic/ws", "synthetic-ws", "t1")
            assertTrue(tasks.listArchivedTasks("/synthetic/ws", "synthetic-ws").isEmpty())
        }
    }

    @Test
    fun `off-peak pause and continue ride the off-peak-task channel as bare positional ids`() {
        connect {
            it.offPeakTaskList = JsonArray(listOf(buildJsonObject {
                put("id", JsonPrimitive("op-1")); put("offPeakTaskId", JsonPrimitive("op-1")); put("title", JsonPrimitive("Idle job"))
                put("status", JsonPrimitive("queued")); put("createdAt", JsonPrimitive(1)); put("workspaceKey", JsonPrimitive("synthetic-ws"))
            }))
        }
        runBlocking {
            val paused = tasks.offPeakPause("op-1")
            assertEquals("paused", (paused!!["status"] as JsonPrimitive).content)
            val listAfterPause = transport.offPeakTaskList()
            assertEquals("paused", (((listAfterPause["tasks"] as JsonArray)[0] as JsonObject)["status"] as JsonPrimitive).content, "the paused id is reflected in the next list")
            val resumed = tasks.offPeakContinue("op-1")
            assertEquals("queued", (resumed!!["status"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `off-peak cancel rides the off-peak-task channel and lands terminal in the next list (L6.2)`() {
        connect {
            it.offPeakTaskList = JsonArray(listOf(buildJsonObject {
                put("id", JsonPrimitive("op-9")); put("offPeakTaskId", JsonPrimitive("op-9")); put("title", JsonPrimitive("Idle job"))
                put("status", JsonPrimitive("running")); put("createdAt", JsonPrimitive(1)); put("workspaceKey", JsonPrimitive("synthetic-ws"))
            }))
        }
        runBlocking {
            val cancelled = tasks.offPeakCancel("op-9")
            assertEquals("cancelled", (cancelled!!["status"] as JsonPrimitive).content)
            val listAfter = transport.offPeakTaskList()
            assertEquals("cancelled", (((listAfter["tasks"] as JsonArray)[0] as JsonObject)["status"] as JsonPrimitive).content, "the cancelled id is reflected in the next list")
        }
        val calls = mock.taskChannelCallsForTest().filter { it.first == "cancelTask" }
        assertEquals(1, calls.size)
    }

    @Test
    fun `setAssistantMessageFeedback rides the snapshot plane with turnIndex and the nullable feedback`() {
        connect { it.directTasks = listOf(row("t1", "One")) }
        runBlocking {
            val snapshot = tasks.setAssistantMessageFeedback("/synthetic/ws", "synthetic-ws", "t1", turnIndex = 0, feedback = "like")
            assertEquals("t1", (snapshot["taskId"] as JsonPrimitive).content)
            assertEquals("like", ((snapshot["feedbacks"] as JsonArray)[0] as JsonPrimitive).content)
            val cleared = tasks.setAssistantMessageFeedback("/synthetic/ws", "synthetic-ws", "t1", 0, null)
            assertTrue(cleared["feedbacks"] is JsonArray, "the echo carries the (nulled) list")
        }
        val calls = mock.taskChannelCallsForTest().filter { it.first == "setAssistantMessageFeedback" }
        assertEquals(2, calls.size)
        assertEquals("0", (calls[0].second["turnIndex"] as JsonPrimitive).content)
        assertEquals("like", (calls[0].second["feedback"] as JsonPrimitive).content)
        assertTrue(calls[1].second["feedback"] is kotlinx.serialization.json.JsonNull, "clear sends an explicit null")
    }
}
