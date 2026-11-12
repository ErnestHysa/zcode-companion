package com.zcode.android.app.connect

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BD-5 (vc52 Phase 13): the bootstrap task row's optional `provider` field —
 * the production shape ALREADY documents it (BootstrapParser's tolerance
 * contract lists `provider?` in the windowHostController task-row schema), but
 * the decoder never READ it (the audit's P4-smallest: "a task-row
 * provider/model badge would mirror the desktop's per-task model chip").
 * Pins: a provider-bearing row decodes the raw value; a provider-less row
 * (and the simplified test shape) stays null — INV-8 tolerant, never a guess.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class BootstrapParserProviderTest {

    private fun bootstrapTask(vararg extra: Pair<String, String>) = buildJsonObject {
        put("taskId", "t-1")
        put("title", "Fix the thing")
        put("displayStatus", "running")
        put("createdAt", 1_000.0)
        put("updatedAt", 2_000.0)
        put("workspacePath", "/ws/a")
        for ((k, v) in extra) put(k, v)
    }

    private fun parseTask(vararg extra: Pair<String, String>): BootstrapParser.ParsedTask {
        val result = buildJsonObject {
            put("workspaces", kotlinx.serialization.json.buildJsonArray {})
            put("tasks", kotlinx.serialization.json.buildJsonArray {
                add(bootstrapTask(*extra))
            })
        }
        val parsed = BootstrapParser.parseWorkspaceList(result)
        assertEquals(1, parsed.tasks.size)
        return parsed.tasks[0]
    }

    @Test
    fun `a provider-bearing row decodes the raw value`() {
        val t = parseTask("provider" to "zai-individual-coding-plan")
        assertEquals("zai-individual-coding-plan", t.provider)
    }

    @Test
    fun `a provider-less row stays null (the historical shape)`() {
        val t = parseTask()
        assertNull(t.provider)
    }

    @Test
    fun `the simplified test shape (id-title-status) decodes with provider null`() {
        val result = buildJsonObject {
            put("workspaces", kotlinx.serialization.json.buildJsonArray {})
            put("tasks", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("id", "t-2")
                    put("title", "mock row")
                    put("status", "running")
                })
            })
        }
        val parsed = BootstrapParser.parseWorkspaceList(result)
        assertEquals(1, parsed.tasks.size)
        assertEquals("mock row", parsed.tasks[0].title)
        assertNull(parsed.tasks[0].provider)
    }
}
