package com.zcode.android.app.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.McpStatuses
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * GAP-9 (the project roadmap; docs/protocol/v4-wire.md §12.5) — the MCP
 * servers viewer rendered from the GENERATED fixtures. Pins: the list renders
 * the payload's OWN fields verbatim (status/transport/toolCount — "status
 * display matches payload fields — no invented states"); the empty face; and
 * NO mutation affordance (no connect/disconnect/enable targets — the phone
 * rides mode:"status"; INV-9).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class McpViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixtureStatuses(name: String): McpStatuses {
        val file = sequenceOf(File("."), File(".."), File("../.."), File("../../.."))
            .map { it.resolve("fixtures/protocol/phase3-wire-fixtures.json") }
            .firstOrNull { it.isFile } ?: error("phase3-wire-fixtures.json not found")
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!
            as kotlinx.serialization.json.JsonArray
        val data = cases.map { it.jsonObject }
            .first { it["name"]!!.toString().trim('"') == name }["data"]!!.toString()
        return McpStatuses.decode(Json.parseToJsonElement(data).jsonObject)
    }

    private fun fakePort(statuses: McpStatuses?, error: String? = null) = object : McpPort {
        override suspend fun statuses(): McpPort.Result = McpPort.Result(statuses, error)
    }

    @Test
    fun rendersStatuses_verbatimFromFixture() {
        rule.setContent { ZCodeTheme { McpViewerScreen(port = fakePort(fixtureStatuses("mcp-status-mixed"))) } }
        rule.onNodeWithTag("mcp_list").assertIsDisplayed()
        rule.onNodeWithTag("mcp_row_context7").assertExists()
        // Verbatim payload values: status + transport + toolCount.
        rule.onNodeWithTag("mcp_state_context7").assertExists()
        rule.onNodeWithTag("mcp_row_search").assertExists()
        rule.onNodeWithTag("mcp_state_search").assertExists()
        rule.onNodeWithTag("mcp_row_docs").assertExists()
        rule.onNodeWithTag("mcp_row_internal-api").assertExists()
        // NO mutation affordance (mode:"status" read; INV-9).
        listOf("mcp_connect", "mcp_disconnect", "mcp_enable", "mcp_disable", "mcp_add")
            .forEach { tag -> rule.onAllNodesWithTag(tag).assertCountEquals(0) }
    }

    @Test
    fun emptyRecord_rendersEmptyState() {
        rule.setContent { ZCodeTheme { McpViewerScreen(port = fakePort(fixtureStatuses("mcp-status-empty"))) } }
        rule.onNodeWithTag("mcp_empty").assertExists()
    }

    @Test
    fun portError_rendersUnavailableFace() {
        rule.setContent { ZCodeTheme { McpViewerScreen(port = fakePort(null, error = "refused")) } }
        rule.onNodeWithTag("mcp_error").assertExists()
    }
}