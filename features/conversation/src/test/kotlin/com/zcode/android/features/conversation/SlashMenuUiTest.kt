package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.SlashCommandMenu
import com.zcode.android.features.conversation.port.ConversationPort
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-12 — the composer "/" catalog menu. The catalog is the WIRE catalog
 * (workspace-config topic, §11.2 — the desktop has NO builtin-only fallback;
 * useSlashCommands.ts). The menu filters by the token being typed; picking a
 * command with an args hint inserts `/name ` (args typed by the user); picking
 * an argument-less command inserts `/name` ready to send.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SlashMenuUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val catalog = listOf(
        ConversationPort.SlashCommandView("compact", "Compact the conversation", "[instructions]", "builtin"),
        ConversationPort.SlashCommandView("goal", "Show or set the goal", "[objective]", "builtin"),
        ConversationPort.SlashCommandView("deploy", "Run the deploy script", "<env>", "custom"),
        ConversationPort.SlashCommandView("review-pr", "Review the open PR", null, "custom"),
    )

    @Test
    fun menuListsBuiltinAndCustomCommands() {
        rule.setContent { ZCodeTheme { SlashCommandMenu(commands = catalog, query = "", onPick = {}) } }
        val items = rule.onAllNodesWithTag("slash_menu_item").fetchSemanticsNodes()
        assertEquals(4, items.size, "builtin + custom entries from the live workspace catalog")
    }

    @Test
    fun menuFiltersByTypedToken() {
        rule.setContent { ZCodeTheme { SlashCommandMenu(commands = catalog, query = "de", onPick = {}) } }
        val items = rule.onAllNodesWithTag("slash_menu_item").fetchSemanticsNodes()
        assertEquals(1, items.size, "prefix filter on the token after /")
    }

    @Test
    fun pickInsertsCommandWithArgsHintTrailingSpace() {
        var picked: String? = null
        rule.setContent {
            ZCodeTheme {
                SlashCommandMenu(commands = catalog, query = "", onPick = { picked = it })
            }
        }
        // "deploy" carries inputHint "<env>" → insert "/deploy " (user types args).
        rule.onAllNodesWithTag("slash_menu_item")[2].performClick()
        rule.waitForIdle()
        assertEquals("/deploy ", picked)
        // "review-pr" has NO inputHint → insert "/review-pr" (ready to send).
        rule.onAllNodesWithTag("slash_menu_item")[3].performClick()
        rule.waitForIdle()
        assertEquals("/review-pr", picked)
    }

    @Test
    fun emptyCatalog_rendersNothing() {
        rule.setContent { ZCodeTheme { SlashCommandMenu(commands = emptyList(), query = "", onPick = {}) } }
        rule.onAllNodesWithTag("slash_menu_item").assertCountEquals(0)
    }

    @Test
    fun noMatch_rendersNothing() {
        rule.setContent { ZCodeTheme { SlashCommandMenu(commands = catalog, query = "zzz", onPick = {}) } }
        rule.onAllNodesWithTag("slash_menu_item").assertCountEquals(0)
    }
}
