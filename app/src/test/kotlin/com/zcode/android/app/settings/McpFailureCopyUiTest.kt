package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.McpStatuses
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G70 (vc52 Phase 12) compose leg: the MCP viewer phrases the desktop's failure
 * vocabulary — every one of the 19 recorded failureKind codes maps to the shipped
 * copy + suggested steps (the settings.mcp.failure.* / failureAdvice.* tables of
 * asar 4a6080ed…); unknown codes render RAW (INV-8); the raw code + error stay on
 * the card for support; the authoring boundary note renders under the list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class McpFailureCopyUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun statuses(vararg servers: Pair<String, McpStatuses.McpServerStatus>) = McpStatuses(
        statuses = servers.toMap(),
    )

    private fun server(
        status: String = "failed",
        failureKind: String? = "connection_timeout",
        error: String? = "ECONNREFUSED 127.0.0.1:9222",
        protocolEra: String? = "modern",
    ) = McpStatuses.McpServerStatus(
        status = status,
        transport = "sse",
        toolCount = 0,
        updatedAt = "2026-10-14T09:31:00Z",
        error = error,
        failureKind = failureKind,
        protocolEra = protocolEra,
    )

    private fun fakePort(result: McpStatuses?) = object : McpPort {
        override suspend fun statuses(): McpPort.Result = McpPort.Result(result, null)
    }

    private fun setContent(result: McpStatuses?) {
        rule.setContent { ZCodeTheme { McpViewerScreen(port = fakePort(result)) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("mcp_list").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a failed server renders the mapped copy + advice + raw kind and error`() {
        setContent(statuses("playwright" to server()))
        rule.onNodeWithTag("mcp_failure_playwright", useUnmergedTree = true).assertIsDisplayed() // G103: the row is clickable — child tags merge away
        rule.onNodeWithText("The MCP server connection timed out. Try again later.").assertExists()
        rule.onNodeWithText("Suggested steps — Confirm the server is running, check the network and proxy, and review server logs. If startup is slow, check the configured connection timeout.", substring = true).assertExists()
        rule.onNodeWithText("connection_timeout").assertExists()
        rule.onNodeWithText("ECONNREFUSED 127.0.0.1:9222").assertExists()
    }

    @Test
    fun `every one of the 19 recorded failure kinds maps to DISTINCT copy`() {
        val kinds = listOf(
            "coding_plan_required", "config_invalid", "connection_failed", "connection_timeout",
            "network_unreachable", "not_authenticated", "oauth_authorization_failed",
            "official_origin_untrusted", "process_start_failed", "protocol_error",
            "protocol_negotiation_failed", "rate_limited", "runtime_unavailable",
            "server_internal_error", "server_not_found", "server_unavailable",
            "status_unavailable", "tool_list_failed", "unexpected_disconnect",
        )
        // ONE composition, 19 rows (one per kind) — the table-pin shape the Accept
        // asks for: every code resolves to NON-NULL mapped copy that differs from
        // the raw code, and no two kinds share copy.
        val servers = kinds.mapIndexed { i, kind -> "srv$i" to server(failureKind = kind, error = null) }.toTypedArray()
        setContent(statuses(*servers))
        val seen = HashSet<String>()
        for (i in kinds.indices) {
            // LazyColumn: below-the-fold rows are DISPOSED until scrolled to
            // (the recorded Phase-9 blind spot).
            // G103 (pass-0 Phase 10): the row is CLICKABLE — scroll to the ROW tag
            // (the merged tree carries it) and read the child copy unmerged.
            rule.onNodeWithTag("mcp_list").performScrollToNode(hasTestTag("mcp_row_srv$i"))
            rule.waitForIdle()
            val text = rule.onAllNodesWithTag("mcp_failure_copy_srv$i", useUnmergedTree = true).fetchSemanticsNodes()
                .first().config.get(androidx.compose.ui.semantics.SemanticsProperties.Text)
                .single().text
            assertTrue("kind ${kinds[i]} copy is not the raw code", text != kinds[i])
            seen.add(text)
        }
        assertTrue("each kind's copy is distinct (${seen.size}/${kinds.size})", seen.size == kinds.size)
    }

    @Test
    fun `an unknown failure kind renders raw with no invented copy`() {
        setContent(statuses("x" to server(failureKind = "connectionRefused")))
        rule.onNodeWithTag("mcp_failure_x", useUnmergedTree = true).assertIsDisplayed() // G103 merge
        rule.onNodeWithText("connectionRefused").assertExists()
        rule.onAllNodesWithTag("mcp_failure_copy_x").assertCountEquals(0)
        rule.onAllNodesWithTag("mcp_advice_x").assertCountEquals(0)
    }

    @Test
    fun `a connected server renders no failure card`() {
        setContent(statuses("github" to server(status = "connected", failureKind = null, error = null)))
        rule.onAllNodesWithTag("mcp_failure_github").assertCountEquals(0)
    }

    @Test
    fun `the status detail line carries type + protocol + tools and the boundary note renders`() {
        setContent(statuses("docs-search" to server(status = "disabled", failureKind = null, error = null, protocolEra = "legacy")))
        rule.onNodeWithText("sse", substring = true).assertExists()
        rule.onNodeWithText("legacy", substring = true).assertExists()
        rule.onNodeWithTag("mcp_boundary_note").assertIsDisplayed()
        rule.onNodeWithText("Read-only status view. MCP server authoring (add / edit / connect) lives on the desktop.", substring = true).assertExists()
    }
}
