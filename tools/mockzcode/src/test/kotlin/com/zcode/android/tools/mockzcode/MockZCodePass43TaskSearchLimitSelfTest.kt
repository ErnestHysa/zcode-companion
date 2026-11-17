package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * p43 (pass-43) mock self-tests — the listTaskList LIMIT/hasMore face (debt #29):
 *
 * The desktop's queryTaskList honors the caller's `limit` (LIMIT ? over the ordered
 * filter) and answers `{items: ≤limit, total: COUNT over the filter, hasMore:
 * total > items.length}` (host chunk-TCPD6WAE.js). The phone's search sends limit:20
 * (TaskServiceClient.searchTasks). The mock served ALL hits with hasMore:false
 * hardcoded, so the pagination face was structurally undrivable — pass 42's coverage
 * list named it (the "hasMore/limit path of listTaskList" leftover).
 *
 * The arm: MOCK_TASKS_MANY=1 seeds synth-task-4..27 (27 total "synthetic" hits).
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass43TaskSearchLimitSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 13 })
        return MockZCode(passHash = passHash, expectedSid = "d_p43s")
    }

    private fun seedTasks(m: MockZCode, count: Int, titlePrefix: String = "Synthetic task") {
        m.script.directTasks = (1..count).map { n ->
            buildJsonObject {
                put("taskId", JsonPrimitive("t$n"))
                put("title", JsonPrimitive("$titlePrefix $n"))
                put("workspacePath", JsonPrimitive("/synthetic/ws-one"))
            }
        }
    }

    private fun lastPayload(m: MockZCode): JsonObject? =
        m.promiseResponsesForTest().lastOrNull()
            ?.let { (it.third as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json as? JsonObject }

    private fun itemsOf(m: MockZCode): List<JsonObject> {
        val payload = lastPayload(m)
        val items = payload?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { it as? JsonObject }
    }

    @Test
    fun `limit 20 over 27 hits serves 20 items + total 27 + hasMore true (desktop queryTaskList shape)`() {
        val m = mock()
        seedTasks(m, 27)
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject {
            put("search", JsonPrimitive("synthetic"))
            put("limit", JsonPrimitive(20))
        })
        val payload = lastPayload(m)
        assertEquals(20, itemsOf(m).size, "the desktop honors the caller's limit (LIMIT ? over the filter)")
        assertEquals(27, (payload?.get("total") as? JsonPrimitive)?.content?.toIntOrNull(),
            "total is the COUNT over the filter, NOT the served size")
        assertEquals(true, (payload?.get("hasMore") as? JsonPrimitive)?.content?.toBoolean(),
            "hasMore = total > items.length (the desktop-faithful pagination face)")
    }

    @Test
    fun `limit larger than the hit count serves all + hasMore false`() {
        val m = mock()
        seedTasks(m, 5)
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject {
            put("search", JsonPrimitive("synthetic"))
            put("limit", JsonPrimitive(20))
        })
        val payload = lastPayload(m)
        assertEquals(5, itemsOf(m).size)
        assertEquals(false, (payload?.get("hasMore") as? JsonPrimitive)?.content?.toBoolean())
    }

    @Test
    fun `no limit serves ALL hits + hasMore false (the shape pass-38-39 decoded, unchanged)`() {
        val m = mock()
        seedTasks(m, 27)
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject {
            put("search", JsonPrimitive("synthetic"))
        })
        val payload = lastPayload(m)
        assertEquals(27, itemsOf(m).size, "no limit = the unbounded desktop query (LIMIT clause absent)")
        assertEquals(false, (payload?.get("hasMore") as? JsonPrimitive)?.content?.toBoolean())
    }

    @Test
    fun `the served slice keeps the p42 snippet fields (the post-slice hits still render snippets)`() {
        val m = mock()
        seedTasks(m, 27)
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject {
            put("search", JsonPrimitive("synthetic"))
            put("limit", JsonPrimitive(20))
        })
        val first = itemsOf(m).first()
        val snippets = first["searchSnippets"] as? JsonArray
        assertTrue(snippets != null && snippets.isNotEmpty(), "the sliced hits must keep searchSnippets (debt #27)")
        val snippet = (snippets?.single() as? JsonPrimitive)?.content.orEmpty()
        assertTrue(snippet.lowercase().contains("synthetic"), "the snippet window must contain the query")
    }

    @Test
    fun `a bad limit value arms nothing (limit absent behavior)`() {
        val m = mock()
        seedTasks(m, 5)
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject {
            put("search", JsonPrimitive("synthetic"))
            put("limit", JsonPrimitive("bogus"))
        })
        assertEquals(5, itemsOf(m).size, "a non-numeric limit must not slice anything")
    }

    @Test
    fun `MOCK_TASKS_MANY arming shape — 27 synthetic hits, the search surface crosses limit 20`() {
        val m = mock()
        armTaskManagementRig(m)
        armTasksManyRig(m)
        assertEquals(27, m.script.directTasks.size, "3 seeded + 24 extra")
        m.taskPromiseForTest(1, "listTaskList", buildJsonObject {
            put("search", JsonPrimitive("synthetic"))
            put("limit", JsonPrimitive(20))
        })
        val payload = lastPayload(m)
        assertEquals(20, itemsOf(m).size)
        assertEquals(27, (payload?.get("total") as? JsonPrimitive)?.content?.toIntOrNull())
        assertEquals(true, (payload?.get("hasMore") as? JsonPrimitive)?.content?.toBoolean())
    }
}
