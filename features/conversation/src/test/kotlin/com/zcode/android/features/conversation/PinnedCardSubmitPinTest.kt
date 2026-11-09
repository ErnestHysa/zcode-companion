package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BG-1 (pass 35; N-BF-3 worsened, D4 carried): the three-group question card clipped
 * after the first group — "Send answers" sat four inner scrolls deep, the first swipes
 * over the card scrolled the pinned STACK instead of the card (3 of 4 attempts wrong),
 * and with the keyboard up not one pixel of Submit was reachable or hinted. The hook
 * card's Trust needed a magic swipe too (D4).
 *
 * Fix: the pinned plane is a bounded lazy column; the question/hook cards cap at the
 * budget and scroll THEIR OWN body with the submit row PINNED at the card's bottom
 * edge. Pins (395dp portrait, the full armed stack):
 * 1. Submit + Decline are VISIBLE without any inner scroll (pinned at the card bottom).
 * 2. A swipe STARTING on the card scrolls the CARD's body (the first group's options
 *    move), never the stack (there is exactly one card — the stack cannot cycle).
 * 3. The hook card's Trust button is visible without a magic swipe.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w395dp-h700dp")
class PinnedCardSubmitPinTest {

    @get:Rule
    val rule = createComposeRule()

    private fun threeGroupQuestion() = PendingInteraction(
        interactionId = "q-3group",
        kind = PendingInteraction.Kind.USER_INPUT,
        anchorRowId = null,
        createdAtMs = 1L,
        permission = null,
        userInput = PendingInteraction.UserInputPayload(
            prompt = "Before I proceed, decide the following",
            freeText = false,
            sensitive = false,
            options = emptyList(),
            questions = listOf(
                PendingInteraction.UserInputPayload.Question(
                    question = "Which channel should receive the summary?",
                    header = "Channel",
                    options = listOf(
                        PendingInteraction.UserInputPayload.Option("email", "Email"),
                        PendingInteraction.UserInputPayload.Option("slack", "Slack"),
                    ),
                    multiSelect = null,
                ),
                PendingInteraction.UserInputPayload.Question(
                    question = "Which extras should run with it?",
                    header = "Extras",
                    options = listOf(
                        PendingInteraction.UserInputPayload.Option("lint", "Lint"),
                        PendingInteraction.UserInputPayload.Option("tests", "Tests"),
                        PendingInteraction.UserInputPayload.Option("bench", "Benchmarks"),
                    ),
                    multiSelect = true,
                ),
                PendingInteraction.UserInputPayload.Question(
                    question = "Any final notes for the report?",
                    header = "Notes",
                    options = emptyList(),
                    multiSelect = null,
                ),
            ),
            toolName = null,
            raw = buildJsonObject {},
        ),
        raw = buildJsonObject {},
    )

    private fun armedState(interaction: PendingInteraction) = ConversationUiState(
        rows = listOf(
            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {}),
        ),
        loading = false,
        taskState = TaskStateMachine.State.WAITING_FOR_USER,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
        sessionState = SessionProjectionState(
            pendingInteractions = listOf(interaction),
            queue = QueueState(
                items = listOf(
                    QueueState.QueueItem("q-1", "queued follow-up one that wraps a little", null, "queued"),
                    QueueState.QueueItem("q-2", "queued follow-up two", null, "queued"),
                ),
                autoDrain = false,
                pauseReason = null,
            ),
        ),
    )

    private fun setContent(interaction: PendingInteraction) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = armedState(interaction),
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
                    onAnswerQuestions = { _, _ -> },
                    onDeclineQuestions = { _ -> },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `N-BG-1 - the three-group card shows Submit and Decline WITHOUT scrolling`() {
        setContent(threeGroupQuestion())
        rule.onNodeWithTag("conv_pinned_budget").assertExists()
        // THE BUG: pre-fix Submit sat four inner scrolls deep (never painted here).
        rule.onNodeWithTag("userinput_questions_submit").assertIsDisplayed()
        rule.onNodeWithTag("userinput_decline").assertIsDisplayed()
    }

    @Test
    fun `N-BG-1 - the card body scrolls internally, the stack never cycles and Submit stays pinned`() {
        setContent(threeGroupQuestion())
        val cardBefore = rule.onNodeWithTag("userinput_card_q-3group").fetchSemanticsNode().boundsInRoot
        // The card exposes its OWN scrollable answer body (the pinned plane no longer
        // scrolls as a stack — with one card there is nothing to cycle).
        val body = rule.onAllNodesWithTag("userinput_body").fetchSemanticsNodes()
        assertTrue("the card must expose its own scrollable body", body.isNotEmpty())
        val axis = body[0].config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(
            "the body must be scrollable (the deepest group is reachable through it)",
            axis != null && axis.maxValue.invoke() > 0f,
        )
        // A swipe over the card must NEVER move the card (the old failure: the swipe
        // scrolled the pinned STACK, hiding the card and its controls).
        rule.onNodeWithTag("userinput_card_q-3group").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals("the card moved — the swipe cycled the stack (D4)", cardBefore, rule.onNodeWithTag("userinput_card_q-3group").fetchSemanticsNode().boundsInRoot)
        // The pinned row STAYED pinned through it.
        rule.onNodeWithTag("userinput_questions_submit").assertIsDisplayed()
    }

    @Test
    fun `D4 - the hook card's Trust button is visible without a magic swipe`() {
        val hook = com.zcode.android.domain.model.WorkspaceHookReview(
            reviewFlowId = "flow-1", generation = 1, interactionId = "hook-1",
            sessionId = "s1", taskId = "t1", runId = "r1",
            workspaceIdentity = "/repo", workspaceLabel = "repo",
            bundleDigest = "d", createdAtMs = 1L, deadlineAtMs = 2L,
            workspacePath = "/repo", remoteSessionId = null,
            summary = com.zcode.android.domain.model.WorkspaceHookReview.Summary(eventCount = 4, hookCount = 3, pendingCount = 3),
            items = (1..6).map { i ->
                com.zcode.android.domain.model.WorkspaceHookReview.Item(
                    reviewItemId = "h-$i", event = "PreToolUse", matcher = null, type = "command",
                    displayName = "Hook script $i with a fairly long descriptive name",
                    displayCommand = "bash scripts/hook-$i.sh --flag", sourcePath = "/repo/.zcode/hooks.json",
                    executionMode = "foreground", configuredEnabled = true, editable = false,
                    trustState = "pending_trust",
                )
            },
        )
        setContent(
            PendingInteraction(
                interactionId = "hook-1", kind = PendingInteraction.Kind.WORKSPACE_HOOK_REVIEW,
                anchorRowId = null, createdAtMs = 1L, permission = null, userInput = null,
                hookReview = hook, raw = buildJsonObject {},
            ),
        )
        rule.onNodeWithTag("conv_pinned_budget").assertExists()
        // THE BUG (D4): Trust sat below six item rows inside the stack scroll.
        rule.onNodeWithTag("hook_review_trust_selected").assertIsDisplayed()
        // The item list is scrollable INSIDE the card (the first item can leave the viewport).
        rule.onAllNodesWithTag("hook_review_item_h-1")[0].performScrollTo()
        rule.waitForIdle()
        rule.onNodeWithTag("hook_review_trust_selected").assertIsDisplayed()
    }
}
