package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SessionUsageState
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.CodeBlock
import com.zcode.android.features.conversation.ui.ContextUsagePanel
import com.zcode.android.features.conversation.ui.SlashCommandMenu
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B3-a11y (gaps-and-polish Phase 2E, P1): sub-48dp targets measured live — the
 * composer/code copy button 24dp, slash items ~40dp, the context toggle ~28dp, the
 * turn chip ~22dp — plus bare `clickable {}` without roles. §30.2: interactive targets
 * ≥ 48dp; §30.1: interactive nodes carry a role.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class TouchTargetA11yTest {

    @get:Rule
    val rule = createComposeRule()

    private fun heightDp(n: SemanticsNode) = n.boundsInRoot.height / rule.density.density
    private fun widthDp(n: SemanticsNode) = n.boundsInRoot.width / rule.density.density

    @Test
    fun `code block copy button is a 48dp target`() {
        rule.setContent { ZCodeTheme { CodeBlock(code = "fun x() = 1", lang = "kotlin") } }
        val n = rule.onNodeWithTag("row_copy_code").fetchSemanticsNode()
        assertTrue("copy button ${widthDp(n)}x${heightDp(n)}dp (needs 48)", widthDp(n) >= 48f && heightDp(n) >= 48f)
    }

    @Test
    fun `slash menu items are 48dp tall and carry a role`() {
        val cmds = listOf(
            ConversationPort.SlashCommandView("compact", "Compact the context", null, "builtin"),
            ConversationPort.SlashCommandView("goal", "Set a goal", "text", "builtin"),
        )
        rule.setContent { ZCodeTheme { SlashCommandMenu(commands = cmds, query = "", onPick = {}) } }
        val items = rule.onAllNodesWithTag("slash_menu_item").fetchSemanticsNodes()
        assertTrue(items.size == 2)
        items.forEach { n ->
            assertTrue("slash item ${heightDp(n)}dp tall (needs 48)", heightDp(n) >= 48f)
            assertNotNull("slash item must carry a role", n.config.getOrNull(SemanticsProperties.Role))
        }
    }

    @Test
    fun `context usage toggle is a 48dp target with an expand state`() {
        val usage = SessionUsageState(
            contextWindow = SessionUsageState.ContextWindow(usedTokens = 50_000, maxTokens = 200_000, autoCompactThresholdTokens = null, breakdown = emptyList(), raw = JsonObject(emptyMap())),
            cumulative = null,
            raw = JsonObject(emptyMap()),
        )
        rule.setContent { ZCodeTheme { ContextUsagePanel(usage = usage) } }
        val clickable = rule.onRoot().fetchSemanticsNode().let { root ->
            fun find(n: SemanticsNode): SemanticsNode? =
                if (n.config.getOrNull(SemanticsActions.OnClick) != null) n else n.children.firstNotNullOfOrNull { find(it) }
            find(root)
        }
        assertNotNull("the context panel must expose its toggle", clickable)
        assertTrue("context toggle ${heightDp(clickable!!)}dp (needs 48)", heightDp(clickable) >= 48f)
        assertNotNull("toggle must announce its expand state", clickable.config.getOrNull(SemanticsProperties.StateDescription))
    }
}
