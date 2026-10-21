package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.GoalState
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.PlanState
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * U8 (verification pass 2, 2026-10-21, live-found on the rig once U4's mock merge let the
 * question reach the conversation): the pinned interaction cards stack UNBOUNDED above
 * the timeline. A two-question AskUserQuestion card (radio + free text + multi-select) is
 * taller than half a phone; with the goal/todos panels above it the card was clipped at
 * its second question and its own Submit/Decline, the timeline and the composer were
 * pushed off-screen — the blocking question could not be answered at all. The squeeze
 * class (B2.12 → U1), on the one surface that must never lose its answer controls.
 * Pinned: with every panel populated AND a multi-question card open, Submit stays
 * reachable, the composer stays on screen and the timeline keeps real height.
 */
@RunWith(AndroidJUnit4::class)
// h731dp: a phone's CONTENT height (Pixel 5 minus system bars) — the geometry the rig showed.
@Config(sdk = [30], qualifiers = "w411dp-h731dp")
class PinnedInteractionBudgetTest {

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
                PendingInteraction.UserInputPayload.Question(
                    question = "Which extras?",
                    header = "Extras",
                    options = listOf(
                        PendingInteraction.UserInputPayload.Option("notes", "Release notes"),
                        PendingInteraction.UserInputPayload.Option("changelog", "Changelog"),
                    ),
                    multiSelect = true,
                ),
            ),
            toolName = null,
            raw = buildJsonObject {},
        ),
        raw = buildJsonObject {},
    )

    private fun blockedWithEveryPanel() = ConversationUiState(
        rows = listOf(
            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {}),
        ),
        loading = false,
        taskState = TaskStateMachine.State.WAITING_FOR_USER,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
        sessionState = SessionProjectionState(
            pendingInteractions = listOf(question()),
            config = SessionConfigState(
                provider = "zai", model = "glm-5.3", thought = "high", thoughtLevels = listOf("high"),
                followupMode = null, mode = "build", planEnabled = null,
                modelSelectionProviderId = null, modelSelectionModelId = null,
            ),
            goal = GoalState(
                targetId = "g1", objective = "Ship it", summaryTitle = "Ship it", timeUsedSeconds = 299,
                activeRunStartedAtMs = null, status = "verifying", iteration = 3,
                verifications = (1L..3L).map { GoalState.Verification(it, "notSatisfied", it, null, "$it unmet", null) },
            ),
            plan = PlanState(
                items = (1..6).map { PlanState.Item("todo-$it", "Todo item number $it", "pending") },
                updatedAtMs = 1L,
            ),
        ),
    )

    @Test
    fun blockingQuestion_staysAnswerable_withEveryPanelPopulated() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = blockedWithEveryPanel(),
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

        // The timeline keeps the vc32 floor even under a blocking card (it collapsed to 0).
        val list = rule.onNodeWithTag("conversation_list").fetchSemanticsNode().boundsInRoot
        assertTrue("timeline squeezed to ${list.height / density}dp under the pinned card", list.height / density >= 120f)

        // The composer never leaves the screen.
        val composer = rule.onNodeWithTag("composer_field").fetchSemanticsNode().boundsInRoot
        assertTrue("composer pushed off-screen: bottom=${composer.bottom}px root=${root.bottom}px", composer.bottom <= root.bottom + 1f)

        // The answer controls are reachable — inside the pinned stack's own scroll.
        rule.onNodeWithTag("userinput_questions_submit").performScrollTo()
        rule.waitForIdle()
        val submit = rule.onNodeWithTag("userinput_questions_submit").fetchSemanticsNode().boundsInRoot
        assertTrue("Submit unreachable: bottom=${submit.bottom}px root=${root.bottom}px", submit.bottom <= root.bottom + 1f && submit.top >= 0f)
    }
}
