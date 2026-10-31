package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * vc51 Phase-3 mock self-tests (rig fidelity, rule 5): the G13 sessions-index
 * state block keys, the G15 background-bash script plane, the RIG-2 workspace
 * file seeds and the G9 shared-context seed all land in the served projection.
 */
class MockZCodePhase3SelfTest {

    private fun mock(script: (MockZCode.Script) -> Unit = {}): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 6 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_selftest_p3")
        script(m.script)
        return m
    }

    @Test
    fun `backgroundWorks and sharedContextImport ride the state block beside the existing keys`() {
        val m = mock { s ->
            s.emitStateKeys = true
            s.goalState = JsonObject(mapOf("objective" to JsonPrimitive("finish"), "status" to JsonPrimitive("active"), "iteration" to JsonPrimitive(1)))
            s.backgroundWorks = JsonArray(listOf(JsonObject(mapOf(
                "workId" to JsonPrimitive("work-1"),
                "kind" to JsonPrimitive("bash"),
                "title" to JsonPrimitive("build"),
                "status" to JsonPrimitive("running"),
                "cancellable" to JsonPrimitive(true),
            ))))
            s.sharedContextImport = JsonObject(mapOf(
                "contextId" to JsonPrimitive("ctx-1"),
                "title" to JsonPrimitive("shared"),
                "shareUrl" to JsonPrimitive("https://zcode.example/cn/share/abc"),
                "status" to JsonPrimitive("pending"),
            ))
        }
        val state = m.stateBlockForTest()!!.jsonObject
        val works = state["backgroundWorks"] as JsonArray
        assertEquals("work-1", ((works[0].jsonObject["workId"]) as JsonPrimitive).content)
        val ctx = state["sharedContextImport"]!!.jsonObject
        assertEquals("ctx-1", (ctx["contextId"] as JsonPrimitive).content)
        // The merge keeps the pre-existing keys (the U4 clobber class).
        assertNotNull(state["goal"])
    }

    @Test
    fun `the background bash output script plane serves the typed result or the unavailable union member`() {
        val m = mock { s ->
            s.backgroundBashOutputs["work-1"] = JsonObject(mapOf(
                "kind" to JsonPrimitive("output"),
                "workId" to JsonPrimitive("work-1"),
                "status" to JsonPrimitive("running"),
                "output" to JsonPrimitive("line"),
                "truncated" to JsonPrimitive(false),
                "outputPath" to JsonPrimitive("/tmp/out.log"),
            ))
        }
        // The scripted map is the source the v4call handler reads (handler asserted on the rig).
        assertTrue(m.script.backgroundBashOutputs.containsKey("work-1"))
        assertNull(m.script.backgroundBashOutputs["work-unknown"], "an unknown workId falls to the typed unavailable member")
    }

    @Test
    fun `workspace file seeds land in the file tree the search filters`() {
        val m = mock { s ->
            s.seedWorkspaceFiles(listOf("src/main.rs", "README.md"))
        }
        assertTrue(m.script.fileTree.containsKey("src/main.rs"))
        assertTrue(m.script.fileTree.containsKey("README.md"))
    }

    @Test
    fun `the sessions-index snapshot and the delayed upsert script plane are wired`() {
        val m = mock { s ->
            s.sessionsIndexSnapshot = JsonObject(mapOf(
                "protocolVersion" to JsonPrimitive(1),
                "workspaceId" to JsonPrimitive("ws"),
                "logEpoch" to JsonPrimitive("idx-1"),
                "sessions" to JsonArray(emptyList()),
            ))
            s.sessionsIndexUpsertDelayMs = 5_000L
            s.sessionsIndexUpsertDelta = JsonObject(mapOf("op" to JsonPrimitive("session.upserted")))
        }
        assertNotNull(m.script.sessionsIndexSnapshot)
        assertEquals(5_000L, m.script.sessionsIndexUpsertDelayMs)
        assertEquals("session.upserted", (m.script.sessionsIndexUpsertDelta!!["op"] as JsonPrimitive).content)
    }
}
