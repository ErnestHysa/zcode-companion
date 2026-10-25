package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithTag as onNodeWithTagUnmerged
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.SlashCommandMenu
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C2 (gaps-and-polish Phase 4C): the desktop's slash panel has THREE sections
 * (slashCommandPanelSections.tsx — commands incl. the user's own custom commands,
 * skills, subagents); the phone rendered one. Pins: three section headers; a custom
 * command from the desktop appears; picking a skill inserts the `$skill` form and a
 * subagent the `@agent` form (the desktop's mention vocabulary); per-section
 * loading / error / empty states are honest; the prefix filter spans sections; a
 * CLI catalog entry wins over a same-named custom command (SlashCommandPlugin.tsx).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SlashMenuSectionsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val catalog = listOf(
        ConversationPort.SlashCommandView("compact", "Compact the context", null, "builtin"),
        ConversationPort.SlashCommandView("review", "Review the diff", "[file]", "builtin"),
    )

    private val extras = ConversationPort.SlashExtras(
        userCommands = listOf(
            ConversationPort.SlashCommandView("deploy", "Deploy to staging", "[env]", "user"),
            ConversationPort.SlashCommandView("review", "my own review (shadowed)", null, "user"),
        ),
        skills = listOf(ConversationPort.SlashSkillView("pdf", "Work with PDFs", "plugin")),
        skillsError = null,
        agents = listOf(ConversationPort.SlashAgentView("Explore", "Read-only search agent", "built-in")),
        agentsError = null,
        loading = false,
    )

    private val picks = mutableListOf<String>()

    private fun setContent(query: String, extras: ConversationPort.SlashExtras? = this.extras) {
        picks.clear()
        rule.setContent { ZCodeTheme { SlashCommandMenu(commands = catalog, query = query, onPick = { picks += it }, extras = extras) } }
        rule.waitForIdle()
    }

    @Test
    fun `three sections render with their headers`() {
        setContent("/")
        rule.onNodeWithTag("slash_section_commands").assertIsDisplayed()
        rule.onNodeWithTag("slash_section_skills").assertIsDisplayed()
        rule.onNodeWithTag("slash_section_agents").assertIsDisplayed()
    }

    @Test
    fun `a custom command from the desktop appears beside the builtins`() {
        setContent("/")
        rule.onNodeWithText("/deploy").assertIsDisplayed()
        rule.onNodeWithText("/compact").assertIsDisplayed()
    }

    @Test
    fun `a CLI catalog entry wins over a same-named custom command`() {
        setContent("/rev")
        assertEquals(1, rule.onAllNodesWithTag("slash_menu_item").fetchSemanticsNodes().size)
        rule.onNodeWithText("Review the diff [file]").assertIsDisplayed()
    }

    @Test
    fun `picking a custom command with an argument hint inserts the open form`() {
        setContent("/dep")
        rule.onNodeWithText("/deploy").performClick(); rule.waitForIdle()
        assertEquals(listOf("/deploy "), picks)
    }

    @Test
    fun `picking a skill inserts the skill mention form`() {
        setContent("/pd")
        rule.onNodeWithTag("slash_skill_pdf", useUnmergedTree = true).performClick(); rule.waitForIdle()
        assertEquals(listOf("\$pdf "), picks)
    }

    @Test
    fun `picking an agent inserts the agent mention form`() {
        setContent("/exp")
        rule.onNodeWithTag("slash_agent_Explore", useUnmergedTree = true).performClick(); rule.waitForIdle()
        assertEquals(listOf("@Explore "), picks)
    }

    @Test
    fun `the prefix filter spans sections and hides empty ones`() {
        setContent("/pd")
        assertEquals(0, rule.onAllNodesWithTag("slash_section_commands").fetchSemanticsNodes().size)
        rule.onNodeWithTag("slash_section_skills").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("slash_section_agents").fetchSemanticsNodes().size)
    }

    @Test
    fun `loading and error states are honest per section`() {
        setContent("/", extras = ConversationPort.SlashExtras(emptyList(), null, "fault.skills.unavailable", null, null, loading = false))
        rule.onNodeWithTag("slash_skills_error").assertIsDisplayed()
        rule.onNodeWithTag("slash_agents_empty").assertIsDisplayed()
    }

    @Test
    fun `while extras load the sections show loading`() {
        setContent("/", extras = ConversationPort.SlashExtras(emptyList(), null, null, null, null, loading = true))
        rule.onNodeWithTag("slash_skills_loading").assertIsDisplayed()
    }

    @Test
    fun `no extras at all keeps the legacy single-section menu`() {
        setContent("/", extras = null)
        rule.onNodeWithText("/compact").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("slash_section_skills").fetchSemanticsNodes().size)
    }
}
