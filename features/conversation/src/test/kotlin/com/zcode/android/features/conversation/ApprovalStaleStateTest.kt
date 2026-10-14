package com.zcode.android.features.conversation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.features.conversation.ui.ApprovalCard
import com.zcode.android.features.conversation.ui.EditUserQueryDialog
import com.zcode.android.features.conversation.ui.UserInputCard
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B2.13 (gaps-and-polish Phase 1C, P0): the approval/user-input card states are
 * NOT keyed on the interaction — `confirmOption`/`refineText`/`selected` survived
 * an interaction swap INSIDE the same composition slot, showing the PREVIOUS
 * request's confirm dialog and answers on a NEW request (stale-state class). The
 * edit dialog's initial text was unkeyed the same way. The pins swap the
 * interaction through a state holder so the card recomposes IN PLACE — the exact
 * field scenario (a second approval lands while the first card is showing).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ApprovalStaleStateTest {

    @get:Rule
    val rule = createComposeRule()

    private fun permissionInteraction(id: String) = PendingInteraction(
        interactionId = id,
        kind = PendingInteraction.Kind.PERMISSION,
        anchorRowId = null,
        createdAtMs = 1L,
        permission = PendingInteraction.PermissionPayload(
            toolCallId = null,
            toolName = "Bash",
            summary = "run a command",
            detail = null,
            freeText = null,
            options = listOf(
                PermissionOption("allowOnce"),
                PermissionOption("allowAlways"),
                PermissionOption("deny"),
            ),
            raw = buildJsonObject {},
        ),
        userInput = null,
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
            options = listOf(
                PendingInteraction.UserInputPayload.Option("opt-a", "A"),
                PendingInteraction.UserInputPayload.Option("opt-b", "B"),
            ),
            questions = emptyList(),
            toolName = null,
            raw = buildJsonObject {},
        ),
        raw = buildJsonObject {},
    )

    @Test
    fun `confirm step does not leak across interactions`() {
        val current = mutableStateOf(permissionInteraction("stale-a"))
        rule.setContent {
            ZCodeTheme { ApprovalCard(interaction = current.value, busy = false, onAnswer = { _, _ -> }) }
        }
        rule.onNodeWithTag("approval_allowAlways").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("approval_confirm_yes").assertExists()

        rule.runOnIdle { current.value = permissionInteraction("stale-b") }
        rule.waitForIdle()

        rule.onAllNodesWithTag("approval_confirm_yes").assertCountEquals(0)
    }

    @Test
    fun `refine text does not leak across interactions`() {
        val current = mutableStateOf(permissionInteraction("refine-a"))
        rule.setContent {
            ZCodeTheme { ApprovalCard(interaction = current.value, busy = false, onAnswer = { _, _ -> }) }
        }
        rule.onNodeWithTag("approval_refine_text").performTextInput("refine it please")
        rule.waitForIdle()

        rule.runOnIdle { current.value = permissionInteraction("refine-b") }
        rule.waitForIdle()

        val text = rule.onNodeWithTag("approval_refine_text").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
        assertEquals("typed refine text must not leak into the next interaction", "", text)
    }

    @Test
    fun `user-input selection does not leak across interactions`() {
        val current = mutableStateOf(optionsInteraction("sel-a"))
        rule.setContent {
            ZCodeTheme { UserInputCard(interaction = current.value, busy = false, onAnswer = { _, _ -> }) }
        }
        rule.onNodeWithTag("userinput_option_opt-a").performClick()
        rule.waitForIdle()

        rule.runOnIdle { current.value = optionsInteraction("sel-b") }
        rule.waitForIdle()

        val selected = rule.onNodeWithTag("userinput_option_opt-a").fetchSemanticsNode()
            .config[SemanticsProperties.Selected]
        assertEquals("a selection must not survive into the next interaction", null, selected)
    }

    @Test
    fun `edit dialog re-seeds its field from the new initial text`() {
        val initial = mutableStateOf("old message")
        rule.setContent {
            ZCodeTheme { EditUserQueryDialog(initialText = initial.value, onConfirm = {}, onDismiss = {}) }
        }
        rule.waitForIdle()

        rule.runOnIdle { initial.value = "new message" }
        rule.waitForIdle()

        val text = rule.onNodeWithTag("edit_text_field").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
        assertEquals("the field must re-seed from the row's current text", "new message", text)
    }
}
