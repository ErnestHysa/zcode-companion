package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.features.conversation.ui.UserInputCard
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
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
 * the touch target (labels dead). Fix shape: multi-select → checkbox-role rows
 * ≥48dp; single-select → radios that select (never toggle off); options-plane
 * rows fully tappable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class UserInputQuestionShapesTest {

    @get:Rule
    val rule = createComposeRule()

    private fun questionsInteraction(id: String, questions: List<PendingInteraction.UserInputPayload.Question>) =
        PendingInteraction(
            interactionId = id,
            kind = PendingInteraction.Kind.USER_INPUT,
            anchorRowId = null,
            createdAtMs = 1L,
            permission = null,
            userInput = PendingInteraction.UserInputPayload(
                prompt = "Pick some",
                freeText = false,
                sensitive = false,
                options = emptyList(),
                questions = questions,
                toolName = null,
                raw = buildJsonObject {},
            ),
            raw = buildJsonObject {},
        )

    private fun optionsInteraction(id: String) = PendingInteraction(
        interactionId = id,
        kind = PendingInteraction.Kind.USER_INPUT,
        anchorRowId = null,
        createdAtMs = 1L,
        permission = null,
        userInput = PendingInteraction.UserInputPayload(
            prompt = null,
            freeText = false,
            sensitive = false,
            options = listOf(PermissionOption("opt-a"), PermissionOption("opt-b")),
            questions = emptyList(),
            toolName = null,
            raw = buildJsonObject {},
        ),
        raw = buildJsonObject {},
    )

    private fun setContent(interaction: PendingInteraction) {
        rule.setContent {
            ZCodeTheme { UserInputCard(interaction = interaction, busy = false, onAnswer = { _, _ -> }) }
        }
        rule.waitForIdle()
    }

    private fun node(tag: String) = rule.onAllNodesWithTag(tag)[0].fetchSemanticsNode()

    @Test
    fun `multi-select questions render checkbox-role rows at least 48dp`() {
        val q = PendingInteraction.UserInputPayload.Question(
            question = "Which tables?",
            header = "DB",
            options = listOf(
                PendingInteraction.UserInputPayload.Option("users", "users"),
                PendingInteraction.UserInputPayload.Option("orders", "orders"),
            ),
            multiSelect = true,
        )
        setContent(questionsInteraction("shape-multi", listOf(q)))

        val row = node("userinput_q0_opt_users")
        assertEquals(
            "multi-select options must carry the checkbox role (found ${row.config[SemanticsProperties.Role]})",
            Role.Checkbox,
            row.config[SemanticsProperties.Role],
        )
        assertTrue(
            "the row must be a ≥48dp tappable target (height=${row.size.height})",
            row.size.height >= 48f * rule.density.density - 0.5f,
        )
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
        setContent(questionsInteraction("shape-single", listOf(q)))

        rule.onAllNodesWithTag("userinput_q0_opt_alpha")[0].performClick()
        rule.waitForIdle()
        assertEquals(
            "first click must select",
            true,
            node("userinput_q0_opt_alpha").config[SemanticsProperties.Selected],
        )

        rule.onAllNodesWithTag("userinput_q0_opt_alpha")[0].performClick()
        rule.waitForIdle()
        assertEquals(
            "a radio must never toggle OFF on re-click",
            true,
            node("userinput_q0_opt_alpha").config[SemanticsProperties.Selected],
        )
    }

    @Test
    fun `options-plane rows are fully tappable - not just the 20dp radio`() {
        setContent(optionsInteraction("shape-opts"))

        val row = node("userinput_option_opt-a")
        assertNotNull(
            "the options-plane row must be clickable end-to-end",
            row.config[SemanticsProperties.OnClick],
        )
        assertTrue(
            "the options-plane row must be a ≥48dp target (height=${row.size.height})",
            row.size.height >= 48f * rule.density.density - 0.5f,
        )
    }
}
