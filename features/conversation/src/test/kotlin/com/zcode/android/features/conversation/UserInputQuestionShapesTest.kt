package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.features.conversation.ui.UserInputCard
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B2.4 (gaps-and-polish Phase 1C, P0): blocking multi-select questions rendered as
 * toggle-off RadioButtons (wrong affordance AND broken semantics — a multi-select
 * is a checkbox set), and the options-plane rows exposed only the ~20dp radio as
 * the touch target (labels dead). Fix shape: multi-select → checkboxes on ≥48dp
 * tappable rows; single-select → radios that select (never toggle off); options
 * plane rows fully tappable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class UserInputQuestionShapesTest {

    @get:Rule
    val rule = createComposeRule()

    private fun interaction(questions: List<PendingInteraction.UserInputPayload.Question> = emptyList(), options: List<PendingInteraction.UserInputPayload.Option> = emptyList()) =
        PendingInteraction(
            interactionId = "ui-shape-1",
            kind = PendingInteraction.Kind.USER_INPUT,
            anchorRowId = null,
            createdAtMs = 1L,
            permission = null,
            userInput = PendingInteraction.UserInputPayload(
                prompt = "Pick some",
                freeText = false,
                sensitive = false,
                options = options,
                questions = questions,
                toolName = null,
                raw = buildJsonObject {},
            ),
            raw = buildJsonObject {},
        )

    private fun setContent(interaction: PendingInteraction) {
        rule.setContent {
            ZCodeTheme {
                UserInputCard(interaction = interaction, busy = false, onAnswer = { _, _ -> })
            }
        }
        rule.waitForIdle()
    }

    private fun roleOf(tag: String): Role? =
        rule.onAllNodesWithTag(tag).fetchSemanticsNode("row $tag").config[SemanticsProperties.Role]

    @Test
    fun `multi-select questions render checkboxes - not toggle-off radios`() {
        val q = PendingInteraction.UserInputPayload.Question(
            question = "Which tables?",
            header = "DB",
            options = listOf(
                PendingInteraction.UserInputPayload.Option("users", "users"),
                PendingInteraction.UserInputPayload.Option("orders", "orders"),
            ),
            multiSelect = true,
        )
        setContent(interaction(questions = listOf(q)))

        assertEquals(Role.Checkbox, roleOf("userinput_q0_opt_users"), "multi-select options must carry the checkbox role")
    }

    @Test
    fun `single-select radios select and never toggle off on re-click`() {
        val q = PendingInteraction.UserInputPayload.Question(
            question = "Which one?",
            header = "Pick",
            options = listOf(
                PendingInteraction.UserInputPayload.Option("alpha", "Alpha"),
                PendingInteraction.UserInputPayload.Option("beta", "Beta"),
            ),
            multiSelect = false,
        )
        setContent(interaction(questions = listOf(q)))

        val radio = rule.onAllNodesWithTag("userinput_q0_opt_alpha").fetchSemanticsNode("row alpha")
        val radioSelected = radio.config[SemanticsProperties.Selected]

        rule.onAllNodesWithTag("userinput_q0_opt_alpha")[0].performClick()
        rule.waitForIdle()
        val afterFirst = rule.onAllNodesWithTag("userinput_q0_opt_alpha").fetchSemanticsNode("row alpha").config[SemanticsProperties.Selected]
        assertTrue("first click must select", afterFirst == true || radioSelected == null)

        rule.onAllNodesWithTag("userinput_q0_opt_alpha")[0].performClick()
        rule.waitForIdle()
        val afterSecond = rule.onAllNodesWithTag("userinput_q0_opt_alpha").fetchSemanticsNode("row alpha").config[SemanticsProperties.Selected]
        assertTrue(
            "a radio must never toggle OFF on re-click (found $afterSecond)",
            afterSecond == true,
        )
    }

    @Test
    fun `options-plane rows are fully tappable - not just the 20dp radio`() {
        setContent(
            interaction(
                options = listOf(
                    PendingInteraction.UserInputPayload.Option("opt-a", "Option A"),
                    PendingInteraction.UserInputPayload.Option("opt-b", "Option B"),
                ),
            ),
        )

        val row = rule.onAllNodesWithTag("userinput_option_opt-a").fetchSemanticsNode("row opt-a")
        assertNotNull(
            "the options-plane row must be clickable end-to-end",
            row.config[SemanticsProperties.OnClick],
        )
    }

    private fun assertEquals(message: String, expected: Role?, actual: Role?) {
        org.junit.Assert.assertEquals(message, expected, actual)
    }
}
