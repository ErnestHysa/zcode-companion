package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A11 (vc51 roadmap, scope narrowed per pass-12 AI2): the conversation screen's pinned
 * interaction card collapses to a ~53px sliver in landscape — the "Agent needs your input"
 * title glyphs HALF-CUT and no answer content reachable. Mechanism: the short-height
 * reserve (323dp) was calibrated pre-vc49 (a 135dp header); the vc49 one-row session bar
 * shrank the real chrome, so on a device-shaped landscape window (~326dp of content under
 * the top bar) the reserve eats essentially the whole window and the card falls to its
 * 24dp floor. h390dp models that device window (1080×2340 rotated, minus the top bar).
 *
 * Fix scope: ONLY the landscape pinned-stack height budget — the nav-inset and row-overlap
 * halves of the original finding are REFUTED (already correct in the app).
 */
@RunWith(AndroidJUnit4::class)
// w731dp-h390dp: the zcode-pixel landscape CONTENT window (1080px height at 2.625
// density ≈ 411dp, minus the top bar the Scaffold reserves ≈ the rig's ~326dp of
// content) — the geometry the recorded sliver lived in.
@Config(sdk = [30], qualifiers = "w731dp-h390dp")
class PinnedInteractionLandscapeShortWindowTest {

    @get:Rule
    val rule = createComposeRule()

    private fun question() = PendingInteraction(
        interactionId = "q-rig-1",
        kind = PendingInteraction.Kind.USER_INPUT,
        anchorRowId = null,
        createdAtMs = 1L,
        permission = null,
        userInput = PendingInteraction.UserInputPayload(
            prompt = "How should I ship this?",
            freeText = false,
            sensitive = false,
            options = emptyList(),
            questions = listOf(
                PendingInteraction.UserInputPayload.Question(
                    question = "Which channel?",
                    header = "Channel",
                    options = listOf(
                        PendingInteraction.UserInputPayload.Option("tag", "Tag the release"),
                        PendingInteraction.UserInputPayload.Option("branch", "Cut a branch"),
                    ),
                    multiSelect = false,
                ),
            ),
            toolName = null,
            raw = buildJsonObject {},
        ),
        raw = buildJsonObject {},
    )

    private fun blockedState() = ConversationUiState(
        rows = listOf(
            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {}),
        ),
        loading = false,
        taskState = com.zcode.android.domain.state.TaskStateMachine.State.WAITING_FOR_USER,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
        sessionState = SessionProjectionState(
            pendingInteractions = listOf(question()),
            config = SessionConfigState(
                provider = "zai", model = "glm-5.3", thought = "high", thoughtLevels = listOf("high"),
                followupMode = null, mode = "build", planEnabled = null,
                modelSelectionProviderId = null, modelSelectionModelId = null,
            ),
        ),
    )

    @Test
    fun `landscape pinned card keeps a usable height budget - title row fits whole`() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = blockedState(),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
        val density = rule.density.density
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val pinned = rule.onNodeWithTag("conv_pinned_budget").fetchSemanticsNode().boundsInRoot

        // The headline assertion: a usable band — title row + real answer content (the
        // recorded defect was a ~53px / 24dp sliver showing the card's tail with the title
        // glyphs half-cut). 80dp ≈ 3.5× the sliver: title + prompt + an option row visible,
        // Submit reachable through the card's own scroll.
        assertTrue(
            "pinned budget collapsed to " + (pinned.height / density) + "dp in the device-shaped landscape window",
            pinned.height / density >= 80f,
        )
        // The title renders WHOLE — never a half-cut glyph sliver.
        val title = rule.onNodeWithText("Agent needs your input").fetchSemanticsNode().boundsInRoot
        assertTrue("title clipped to " + (title.height / density) + "dp", title.height / density >= 14f)

        // The composer never leaves the window (the short-height contract the fix inherits).
        val composer = rule.onNodeWithTag("composer_field").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "composer pushed off-screen: bottom=" + composer.bottom + "px root=" + root.bottom + "px",
            composer.bottom <= root.bottom + 1f,
        )
        // Options + Submit reachable inside the card's own scroll.
        rule.onNodeWithTag("userinput_questions_submit").performScrollTo()
        rule.waitForIdle()
        val submit = rule.onNodeWithTag("userinput_questions_submit").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Submit unreachable: bottom=" + submit.bottom + "px root=" + root.bottom + "px",
            submit.bottom <= root.bottom + 1f && submit.top >= 0f,
        )
    }
}
