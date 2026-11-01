package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SlashCatalogExtras
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G25 (vc51 Phase 4B) — the read-only SUBAGENTS viewer (Settings → Insights, the INV-9
 * viewer pattern). Pins: the seeded inventory renders rows with scope + enable badges
 * (both faces, including the DISABLED agent the slash-panel decode filters out), the
 * error and empty faces, and NO write affordance exists anywhere on the surface
 * (deeper subagent management is desktop-side, INV-9).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SubagentsViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fakePort(
        agents: List<SlashCatalogExtras.AgentEntryFull>? = null,
        error: String? = null,
    ) = object : SubagentsPort {
        override suspend fun subagents(): SubagentsPort.Result = SubagentsPort.Result(agents, error)
    }

    private val seed = listOf(
        SlashCatalogExtras.AgentEntryFull("Explore", "Read-only search agent", "built-in", enabled = true),
        SlashCatalogExtras.AgentEntryFull("Plan", "Implementation planning agent", "built-in", enabled = true),
        SlashCatalogExtras.AgentEntryFull("docs-writer", "Project documentation agent", "project", enabled = false),
    )

    @Test
    fun rendersInventory_withScopeAndStateBadges_bothFaces() {
        rule.setContent { ZCodeTheme { SubagentsViewerScreen(port = fakePort(seed)) } }
        rule.onNodeWithTag("subagents_list").assertIsDisplayed()
        rule.onNodeWithTag("subagent_row_Explore").assertIsDisplayed()
        rule.onNodeWithTag("subagent_row_docs-writer").assertIsDisplayed()
        // the DISABLED agent the slash panel filters out stays visible here
        rule.onNodeWithText("docs-writer").assertIsDisplayed()
        // scope badges
        rule.onNodeWithTag("subagent_scope_docs-writer").assertExists()
        // state badges: one per agent (assert the count, not the text — two "On" labels merge)
        rule.onAllNodesWithTag("subagent_state_badge_Explore", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag("subagent_state_badge_docs-writer", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun noWriteAffordance_anywhereOnTheSurface() {
        rule.setContent { ZCodeTheme { SubagentsViewerScreen(port = fakePort(seed)) } }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("subagents_list").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onAllNodesWithTag("subagent_switch", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun errorFace_isHonest() {
        rule.setContent { ZCodeTheme { SubagentsViewerScreen(port = fakePort(error = "fault.mock.unscripted:subagents.list")) } }
        rule.onNodeWithTag("subagents_error").assertIsDisplayed()
    }

    @Test
    fun emptyFace_isHonest() {
        rule.setContent { ZCodeTheme { SubagentsViewerScreen(port = fakePort(emptyList())) } }
        rule.onNodeWithTag("subagents_empty").assertIsDisplayed()
    }
}
