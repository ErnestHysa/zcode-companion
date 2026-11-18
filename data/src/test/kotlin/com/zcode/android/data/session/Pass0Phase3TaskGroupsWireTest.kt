package com.zcode.android.data.session

import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import com.zcode.android.core.transport.AppTransport
import com.zcode.android.domain.model.GroupedTaskNode
import com.zcode.android.domain.model.TaskGroupColor
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G102 (pass-38 P3): WIRE DRIFT — desktop 3.18.1 REMOVED the legacy group verbs
 * from the `zcode-task` channel (`createTaskGroup`/`renameTaskGroup`/
 * `updateTaskGroupColor`/`deleteTaskGroup`/`listGroupedTaskViewStructure`/
 * `applyGroupedTaskViewOrder` — zero byte-hits anywhere in the bundle, Part AO.L);
 * the plane moved to a dedicated **`task-groups`** channel (chunk-TCPD6WAE.js
 * `createTaskGroupService`): read() / save({id?,title?,color?}) / ungroup(groupId)
 * / move({node,sourceGroupId,groupId,anchor?}) / admit({tasks,placement?}) +
 * `state:task-groups` broadcasts. The phone's ENTIRE custom-group plane faulted
 * unknownMethod against the real desktop while working against the (legacy-
 * speaking) mock.
 *
 * The row's named pin: a transport-fake harness asserting the phone's group verbs
 * speak the task-groups shapes — arg-exact against the extracted schemas (the
 * census pins the shapes; the task ref's strict schema {hostId, workspacePath,
 * workspaceIdentity?, taskId} carries hostId = remoteHostIdFromWorkspaceIdentity
 * ?? "local", chunk-7SAPNXNZ bW). RED at the pre-fix commit is compile-shape (the
 * client spoke only the legacy verbs).
 */
class Pass0Phase3TaskGroupsWireTest {

    private class Call(val channel: String, val method: String, val arg: RpcValue?)

    /** A transport fake that records every channel call and serves canned responses. */
    private fun capturingTransport(respond: (channel: String, method: String) -> RpcValue): Pair<AppTransport, MutableList<Call>> {
        val calls = mutableListOf<Call>()
        val transport = Proxy.newProxyInstance(
            AppTransport::class.java.classLoader,
            arrayOf(AppTransport::class.java),
        ) { _, m, args ->
            when (m.name) {
                "channelCall" -> {
                    calls += Call(args[0] as String, args[1] as String, args[2] as? RpcValue)
                    respond(args[0] as String, args[1] as String)
                }
                "sendMobileSelfReport" -> null
                else -> throw UnsupportedOperationException("not used by this pin: ${m.name}")
            }
        } as AppTransport
        return transport to calls
    }

    private fun argObj(call: Call?): JsonObject? = ((call?.arg as? RpcValue.Arr)?.items?.firstOrNull() as? RpcValue.Obj)?.json as? JsonObject

    private fun snapshot(groups: JsonArray, members: JsonArray, order: JsonArray) = RpcValue.Obj(
        buildJsonObject {
            put("groups", groups)
            put("members", members)
            put("order", order)
        },
    )

    @Test
    fun `create speaks save without an id`() = runBlocking {
        val (transport, calls) = capturingTransport { c, m ->
            if (c == "task-groups" && m == "save") RpcValue.Obj(buildJsonObject {
                put("id", JsonPrimitive("task-group-1")); put("title", JsonPrimitive("Backend"))
                put("color", JsonPrimitive("blue")); put("createdAt", JsonPrimitive(1L)); put("updatedAt", JsonPrimitive(1L))
            }) else RpcValue.Undefined
        }
        val client = TaskServiceClient(transport)
        val saved = client.taskGroupsSave(id = null, title = "Backend", color = TaskGroupColor.BLUE.wire)
        assertEquals(1, calls.size)
        assertEquals("task-groups", calls[0].channel)
        assertEquals("save", calls[0].method)
        val body = argObj(calls[0])
        assertEquals("Backend", body?.get("title")?.let { (it as JsonPrimitive).content })
        assertEquals("blue", body?.get("color")?.let { (it as JsonPrimitive).content })
        assertNull("create carries NO id key", body?.get("id"))
        assertEquals("task-group-1", saved?.get("id")?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `rename and recolor speak save with the id and only the changed field`() = runBlocking {
        val (transport, calls) = capturingTransport { _, _ -> RpcValue.Obj(buildJsonObject { put("id", JsonPrimitive("g1")) }) }
        val client = TaskServiceClient(transport)
        client.taskGroupsSave(id = "g1", title = "Renamed", color = null)
        client.taskGroupsSave(id = "g1", title = null, color = TaskGroupColor.RED.wire)
        assertEquals(2, calls.size)
        val rename = argObj(calls[0])
        assertEquals("g1", rename?.get("id")?.let { (it as JsonPrimitive).content })
        assertEquals("Renamed", rename?.get("title")?.let { (it as JsonPrimitive).content })
        assertNull("rename keeps the stored color (no color key)", rename?.get("color"))
        val recolor = argObj(calls[1])
        assertEquals("g1", recolor?.get("id")?.let { (it as JsonPrimitive).content })
        assertEquals("red", recolor?.get("color")?.let { (it as JsonPrimitive).content })
        assertNull("recolor keeps the stored title (no title key)", recolor?.get("title"))
    }

    @Test
    fun `delete speaks ungroup`() = runBlocking {
        val (transport, calls) = capturingTransport { _, _ -> RpcValue.Undefined }
        val client = TaskServiceClient(transport)
        client.taskGroupsUngroup("g1")
        assertEquals(1, calls.size)
        assertEquals("task-groups", calls[0].channel)
        assertEquals("ungroup", calls[0].method)
        assertEquals("g1", ((calls[0].arg as? RpcValue.Arr)?.items?.firstOrNull() as? RpcValue.Str)?.value)
    }

    @Test
    fun `the structure read speaks the task-groups read`() = runBlocking {
        val (transport, calls) = capturingTransport { c, m ->
            if (c == "task-groups" && m == "read") snapshot(
                groups = JsonArray(emptyList()),
                members = JsonArray(emptyList()),
                order = JsonArray(emptyList()),
            ) else RpcValue.Undefined
        }
        val client = TaskServiceClient(transport)
        client.taskGroupsRead()
        assertEquals(1, calls.size)
        assertEquals("task-groups", calls[0].channel)
        assertEquals("read", calls[0].method)
        // read() takes NO args (Arr(emptyList()) — the ProxyChannel zero-arg shape).
        assertTrue((calls[0].arg as? RpcValue.Arr)?.items?.isEmpty() == true)
    }

    @Test
    fun `move speaks the task-groups move with the ref and the source group`() = runBlocking {
        val (transport, calls) = capturingTransport { _, _ -> RpcValue.Obj(JsonObject(emptyMap())) }
        val client = TaskServiceClient(transport)
        val ref = client.taskGroupRef("/synthetic/ws-one", "/synthetic/ws-one", "t1")
        client.taskGroupsMove(task = ref, sourceGroupId = "g1", groupId = "g2")
        assertEquals(1, calls.size)
        assertEquals("task-groups", calls[0].channel)
        assertEquals("move", calls[0].method)
        val body = argObj(calls[0])
        val node = body?.get("node") as? JsonObject
        assertEquals("task", node?.get("type")?.let { (it as JsonPrimitive).content })
        val task = node?.get("task") as? JsonObject
        assertEquals("t1", task?.get("taskId")?.let { (it as JsonPrimitive).content })
        assertEquals("local", task?.get("hostId")?.let { (it as JsonPrimitive).content })
        assertEquals("g1", body?.get("sourceGroupId")?.let { (it as JsonPrimitive).content })
        assertEquals("g2", body?.get("groupId")?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `move out of a group carries a null groupId`() = runBlocking {
        val (transport, calls) = capturingTransport { _, _ -> RpcValue.Obj(JsonObject(emptyMap())) }
        val client = TaskServiceClient(transport)
        val ref = client.taskGroupRef("/synthetic/ws-one", null, "t1")
        client.taskGroupsMove(task = ref, sourceGroupId = "g1", groupId = null)
        val body = argObj(calls[0])
        assertNull(body?.get("groupId"))
        // A NULL identity omits the optional key; hostId resolves to "local".
        val task = body?.get("node") as? JsonObject
        assertNull(task?.get("workspaceIdentity"))
        assertEquals("local", task?.get("hostId")?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `a remote-shaped identity carries its host prefix as hostId`() {
        val client = TaskServiceClient(capturingTransport { _, _ -> RpcValue.Undefined }.first)
        // remote:scheme:...:path (chunk-7SAPNXNZ parseRemoteWorkspaceIdentity) — the
        // host id is the identity minus ":<workspacePath>".
        val ref = client.taskGroupRef("/repo", "remote:ssh:u@h:2222:/repo", "t1")
        assertEquals("remote:ssh:u@h:2222", ref.get("hostId")?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `the read snapshot builds the grouped nodes the screen renders`() {
        val read = TaskServiceClient.TaskGroupsSnapshot(
            groups = listOf(
                TaskServiceClient.TaskGroupsGroup("g1", "Backend", "blue", 1, 1),
                TaskServiceClient.TaskGroupsGroup("g2", "Never ordered", "gray", 2, 2),
            ),
            members = listOf(
                TaskServiceClient.TaskGroupsMember("g1", "/ws", "t2", 1),
                TaskServiceClient.TaskGroupsMember("g1", "/ws", "t1", 0),
            ),
            order = listOf(
                TaskServiceClient.TaskGroupsOrderNode("task", "[\"/ws\",\"t3\"]", 0),
                TaskServiceClient.TaskGroupsOrderNode("group", "g1", 1),
            ),
        )
        val nodes = groupedNodesFromTaskGroups(read)
        // The order drives the layout: the loose task first, then g1 with its members
        // in sortOrder, then the un-ordered group appended.
        assertTrue(nodes[0] is GroupedTaskNode.Single)
        assertEquals("t3", (nodes[0] as GroupedTaskNode.Single).taskId)
        val group = nodes[1] as GroupedTaskNode.Group
        assertEquals("g1", group.group.id)
        assertEquals("Backend", group.group.title)
        assertEquals(TaskGroupColor.BLUE, group.group.color)
        assertEquals(listOf("t1", "t2"), group.taskIds)
        val appended = nodes[2] as GroupedTaskNode.Group
        assertEquals("g2", appended.group.id)
        assertEquals(emptyList<String>(), appended.taskIds)
    }
}
