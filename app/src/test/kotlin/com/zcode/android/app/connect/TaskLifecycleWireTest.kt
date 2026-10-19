package com.zcode.android.app.connect

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.transport.CredentialSigner
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.data.session.TaskServiceClient
import com.zcode.android.tools.mockzcode.MockZCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L6.3+J4 + H1.1 (gaps-and-polish Phase 4D) wire legs over the REAL transport ↔
 * mock: the task lifecycle (deleteTask/renameTask/archiveTask/unarchiveTask — the
 * shipped 3.14.5 facade methods on the `zcode-task` channel, bundle offsets
 * ~436469–447279) and the search surface `listTaskList({kind, workspaceScopes,
 * sortBy, search, limit})` → {items(with searchSnippet), total, hasMore}
 * (zcodeTaskListTypes.ts). Shapes are the workspace-target form.
 */
class TaskLifecycleWireTest {

    private val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val sid: String get() = "d_lifecycle_${System.identityHashCode(this)}"

    private lateinit var mock: MockZCode
    private lateinit var transport: RelayTransport
    private lateinit var tasks: com.zcode.android.data.session.TaskServiceClient

    @After
    fun tearDown() {
        runBlocking { runCatching { transport.close("test end") } }
        Thread.sleep(300)
        runCatching { mock.stop() }
    }

    private fun connect() {
        mock = MockZCode(passHash = passHash, expectedSid = sid)
        val url = mock.start()
        transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = ZLog(sink = {}))
        runBlocking {
            withTimeout(30_000) {
                transport.connect(
                    EndpointDescriptor(
                        id = "ep-lifecycle",
                        kind = EndpointKind.RELAY_ZAI,
                        displayName = "LifecycleMockBook",
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
        tasks = TaskServiceClient(transport)
    }

    private fun signer(): CredentialSigner = CredentialSigner { nonce, role, sid ->
        MockZCode.hmacProof(passHash, nonce, role, sid)
    }

    @Test
    fun `delete rename archive and unarchive ride the zcode-task channel in the workspace-target form`() {
        connect()
        runBlocking {
            tasks.deleteTask("/synthetic/ws", "synthetic-ws", "task-1")
            tasks.renameTask("/synthetic/ws", "synthetic-ws", "task-2", "New title")
            tasks.archiveTask("/synthetic/ws", "synthetic-ws", "task-3", archived = true)
            tasks.archiveTask("/synthetic/ws", "synthetic-ws", "task-4", archived = false)
        }
        val calls = mock.taskChannelCallsForTest()
        assertEquals(listOf("deleteTask", "renameTask", "archiveTask", "archiveTask"), calls.map { it.first })
        val (archiveName, archiveArg) = calls[2]
        assertEquals("archiveTask", archiveName)
        assertEquals("task-3", (archiveArg["taskId"] as JsonPrimitive).content)
        assertEquals("true", (archiveArg["archived"] as JsonPrimitive).content)
        assertEquals("/synthetic/ws", (calls[0].second["workspacePath"] as JsonPrimitive).content)
        assertEquals("New title", (calls[1].second["title"] as JsonPrimitive).content)
    }

    @Test
    fun `listArchivedTasks returns the scripted archived list`() {
        connect()
        runBlocking {
            val archived = tasks.listArchivedTasks("/synthetic/ws", "synthetic-ws")
            assertTrue(archived.isEmpty(), "the mock scripts an empty archive by default")
        }
        assertEquals("listArchivedTasks", mock.taskChannelCallsForTest().single().first)
    }

    @Test
    fun `search rides listTaskList with kind scopes sortBy search and limit`() {
        connect()
        runBlocking {
            val result = tasks.searchTasks("/synthetic/ws", "synthetic-ws", query = "auth", limit = 10)
            assertTrue(result.items.isEmpty(), "the mock scripts no matches by default")
            assertEquals(0, result.total)
        }
        val (name, arg) = mock.taskChannelCallsForTest().single()
        assertEquals("listTaskList", name)
        assertEquals("active", (arg["kind"] as JsonPrimitive).content)
        assertEquals("updated", (arg["sortBy"] as JsonPrimitive).content)
        assertEquals("auth", (arg["search"] as JsonPrimitive).content)
        assertEquals("10", (arg["limit"] as JsonPrimitive).content)
        val scopes = arg["workspaceScopes"] as kotlinx.serialization.json.JsonArray
        val scope = scopes.first().jsonObject
        assertEquals("/synthetic/ws", (scope["workspacePath"] as JsonPrimitive).content)
    }
}
