package com.zcode.android.app.connect

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Phase 6.2 task-plane decoder (INV-8 unknown-tolerance): decodes BOTH observed
 * shapes — the production bootstrap-response (fields confirmed by the sanitized
 * live corpus fixtures/relay/live-subscribe-16.fixture.json) and the simplified
 * mock/test shape — and drops malformed entries with a count, never throws.
 */
class BootstrapParserTest {

    private val productionPayload: JsonObject = Json.parseToJsonElement(
        """
        {"zcode_type":"bootstrap-response","requestId":"r1","success":true,"result":{
          "windowControlSessionId":"synth-window-1",
          "desktopAppVersion":"3.14.3",
          "workspaces":[
            {"kind":"local","label":"Synthetic Workspace","workspacePath":"/synthetic/ws","workspacePurpose":"project"}
          ],
          "tasks":[
            {"createdAt":1790589971962,"displayStatus":"running","provider":"Synthetic Workspace",
             "taskId":"synth-c7dd76ba05d7","title":"Fix auth token refresh","updatedAt":1790600042787,
             "workspaceKind":"local","workspaceLabel":"android-native","workspacePath":"/synthetic/ws"},
            {"createdAt":1790592458750,"displayStatus":"completed",
             "taskId":"synth-6d583a9350a4","title":"Second task","unreadAt":1790597521316,
             "updatedAt":1790597525893,"workspaceKind":"local","workspaceLabel":"pragma",
             "workspacePath":"/synthetic/ws"}
          ],
          "initialViewState":{"activeTaskId":"synth-c7dd76ba05d7","activeWorkspaceKey":"k","updatedAt":1},
          "mobileViewState":{"activeTaskId":"synth-c7dd76ba05d7","activeWorkspaceKey":"k","updatedAt":2}
        }}
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `production bootstrap shape decodes with live-corpus fields`() {
        val result = BootstrapParser.parseBootstrap(productionPayload)
        assertEquals("3.14.3", result.desktopAppVersion)
        assertEquals(1, result.workspaces.size)
        assertEquals("/synthetic/ws", result.workspaces[0].identityKey) // §7.11: no identity → path
        assertEquals(2, result.tasks.size)
        val first = result.tasks[0]
        assertEquals("synth-c7dd76ba05d7", first.taskId)
        assertEquals("running", first.statusWire)
        assertEquals(1790589971962L, first.createdAtMs)
        assertEquals(1790600042787L, first.updatedAtMs)
        assertEquals("/synthetic/ws", first.workspaceIdentityKey)
        assertEquals("android-native", first.workspaceLabel)
        assertEquals(0, result.droppedEntries)
    }

    @Test
    fun `simplified legacy shape decodes (tolerant of id and status keys)`() {
        val payload = buildJsonObject {
            put("zcode_type", "bootstrap-response")
            putJsonObject("result") {
                putJsonArray("tasks") {
                    add(
                        buildJsonObject {
                            put("id", "synth-task-1")
                            put("title", "Synthetic task")
                            put("status", "idle")
                        },
                    )
                }
            }
        }
        val result = BootstrapParser.parseBootstrap(payload)
        assertEquals(1, result.tasks.size)
        assertEquals("synth-task-1", result.tasks[0].taskId)
        assertEquals("idle", result.tasks[0].statusWire)
        assertEquals(0L, result.tasks[0].createdAtMs) // missing → safe default
    }

    @Test
    fun `malformed entries are dropped and counted, never fatal (INV-8)`() {
        val payload = buildJsonObject {
            putJsonObject("result") {
                putJsonArray("tasks") {
                    add(buildJsonObject { put("title", "no id") }) // dropped: no id
                    add(buildJsonObject { put("taskId", "ok-1"); put("title", "ok"); put("displayStatus", "weird-status") })
                    add(kotlinx.serialization.json.JsonPrimitive(1)) // not an object
                }
                putJsonArray("workspaces") {
                    add(buildJsonObject { put("label", "no path or identity") }) // dropped
                }
            }
        }
        val result = BootstrapParser.parseBootstrap(payload)
        assertEquals(1, result.tasks.size)
        assertEquals("weird-status", result.tasks[0].statusWire) // unknown status → caller maps UNKNOWN
        assertEquals(3, result.droppedEntries)
    }

    @Test
    fun `workspace identity takes precedence over path (7-11 rule)`() {
        val payload = buildJsonObject {
            putJsonObject("result") {
                putJsonArray("workspaces") {
                    add(
                        buildJsonObject {
                            put("workspaceIdentity", "ident-1")
                            put("workspacePath", "/some/path")
                            put("label", "L")
                        },
                    )
                }
            }
        }
        val result = BootstrapParser.parseBootstrap(payload)
        assertEquals("ident-1", result.workspaces[0].identityKey)
    }

    @Test
    fun `null and garbage payloads are total (never throw)`() {
        assertTrue(BootstrapParser.parseBootstrap(null).tasks.isEmpty())
        val garbage = Json.parseToJsonElement("""{"result":{"tasks":"not-an-array"}}""").jsonObject
        assertTrue(BootstrapParser.parseBootstrap(garbage).tasks.isEmpty())
        assertTrue(BootstrapParser.parseWorkspaceList(null).workspaces.isEmpty())
    }

    @Test
    fun `workspace-list response decodes through the same tolerant path`() {
        val payload = buildJsonObject {
            put("zcode_type", "workspace-list-response")
            putJsonObject("result") {
                putJsonArray("workspaces") {
                    add(
                        buildJsonObject {
                            put("workspacePath", "/synthetic/ws")
                            put("label", "Synthetic One")
                            put("kind", "local")
                            put("workspaceKey", "synthetic-ws-one")
                        },
                    )
                }
            }
        }
        val result = BootstrapParser.parseWorkspaceList(payload)
        assertEquals(1, result.workspaces.size)
        assertEquals("Synthetic One", result.workspaces[0].label)
    }

    /**
     * A3.8 (Phase 5A): the bootstrap task row's `pinned` / `unreadAt` (windowHostController
     * task row schema) are decoded — the pinned section + unread dot are wire facts; absent
     * fields decode to the READ / unpinned defaults.
     */
    @Test
    fun `pinned and unreadAt decode from the task row and default when absent`() {
        val payload = Json.parseToJsonElement(
            """
            {"result":{"workspaces":[{"kind":"local","label":"demo","workspacePath":"/ws"}],
             "tasks":[
               {"taskId":"t1","title":"Pinned unread","workspacePath":"/ws","workspaceLabel":"demo","createdAt":1,"updatedAt":2,"pinned":true,"unreadAt":1700000000000},
               {"taskId":"t2","title":"Plain","workspacePath":"/ws","workspaceLabel":"demo","createdAt":3,"updatedAt":4}
             ]}}
            """,
        ).jsonObject
        val result = BootstrapParser.parseBootstrap(payload)
        val t1 = result.tasks.first { it.taskId == "t1" }
        val t2 = result.tasks.first { it.taskId == "t2" }
        assertTrue(t1.pinned)
        assertEquals(1700000000000L, t1.unreadAtMs)
        assertFalse(t2.pinned)
        assertNull(t2.unreadAtMs)
    }
}
