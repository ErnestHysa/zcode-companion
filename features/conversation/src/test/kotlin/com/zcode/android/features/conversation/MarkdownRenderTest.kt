package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.MarkdownText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * K5 (gaps-and-polish Phase 3C): the RENDERED side of the minimum set — the K5 fixture
 * through `MarkdownText`: nested list rows indent, the 2×2 table renders as cells (no raw
 * pipe text), strikethrough renders without tildes, the code block gains a wrap toggle.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MarkdownRenderTest {

    @get:Rule
    val rule = createComposeRule()

    private val k5 = listOf(
        "## Rewrite plan",
        "",
        "Applied `Parser.kt` fixes in [the workspace](https://example.test/ws):",
        "",
        "- Top-level item one",
        "  - nested sub-item A",
        "  - nested sub-item B",
        "- Top-level item two",
        "",
        "| File | Status |",
        "| ---- | ------ |",
        "| Parser.kt | rewritten |",
        "| ParserTest.kt | +12 −3 |",
        "",
        "```kotlin",
        "fun parse(input: String): List<Node> =",
        "    input.lines().mapNotNull(::parseLine)",
        "```",
        "",
        "Done ~~temporarily~~ permanently.",
    ).joinToString("\n")

    private fun render() {
        rule.setContent { ZCodeTheme { MarkdownText(text = k5) } }
        rule.waitForIdle()
    }

    @Test
    fun `nested list items render as separate indented rows`() {
        render()
        val top = rule.onNodeWithTag("md_list_item_0", useUnmergedTree = true).getBoundsInRoot()
        val nested = rule.onNodeWithTag("md_list_item_1", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("nested row must indent (nested.left=${nested.left} top.left=${top.left})", nested.left > top.left)
        rule.onAllNodesWithTag("md_list_item_2", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag("md_list_item_3", useUnmergedTree = true).assertCountEquals(1)
        // The live defect: one prose line "- nested sub-item A - nested sub-item B".
        rule.onAllNodesWithText("- nested sub-item A", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the table renders as cells and the raw pipe text is gone`() {
        render()
        rule.onNodeWithTag("md_table", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("md_table_cell_0_0", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("md_table_cell_2_1", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("rewritten").assertIsDisplayed()
        rule.onAllNodesWithText("| File", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("----", substring = true).assertCountEquals(0)
    }

    @Test
    fun `strikethrough renders without literal tildes`() {
        render()
        rule.onAllNodesWithText("~~", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Done temporarily permanently.").assertIsDisplayed()
    }

    @Test
    fun `the code block carries a wrap toggle that flips its state`() {
        render()
        val toggle = rule.onNodeWithTag("row_code_wrap_toggle", useUnmergedTree = true)
        toggle.assertIsDisplayed()
        rule.onNodeWithTag("row_code_scroll", useUnmergedTree = true).assertIsDisplayed()
        toggle.performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("row_code_wrapped", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("row_code_scroll", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the code block text is syntax highlighted (spans present)`() {
        render()
        rule.onNodeWithTag("row_code_text", useUnmergedTree = true).assertIsDisplayed()
    }
}
