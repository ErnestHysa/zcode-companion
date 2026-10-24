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
 * T3 (verification pass 4, 2026-10-23, live-found on the rig): the U1/U8 budgets
 * assume portrait heights. In landscape (a 731×411dp phone window) with a pinned
 * question card: the timeline collapsed to ZERO height, the card's viewport was
 * a ~200px sliver, and the composer overflowed the window — the send button
 * clipped to a 50px edge sliver (at font 1.3 the composer buttons additionally
 * crushed to ~15px). The M2/N6 landscape sweeps predate the pass-2 budgets and
 * passes 2/3 never rotated. The short-height contract: the composer keeps its
 * intrinsic size and NEVER leaves the window (any edge), the timeline keeps a
 * real short-height floor, and the card yields its minimum FIRST — present and
 * scrollable, its answer controls reachable inside its own scroll.
 */
@RunWith(AndroidJUnit4::class)
// w731dp-h411dp: a phone's landscape CONTENT window (Pixel 5 rotated) — the T3 geometry.
@Config(sdk = [30], qualifiers = "w731dp-h411dp")
class PinnedInteractionLandscapeBudgetTest {

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

    private fun blockedState() = ConversationUiState(
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
        ),
    )

    @Test
    fun blockingQuestion_landscape_composerWithinWindow_timelineKeepsShortFloor_cardScrolls() {
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

        // The composer never leaves the window — ANY edge (T3's clipped send button).
        val composer = rule.onNodeWithTag("composer_field").fetchSemanticsNode().boundsInRoot
        assertTrue("composer pushed off-screen vertically: bottom=${composer.bottom}px root=${root.bottom}px", composer.bottom <= root.bottom + 1f)
        assertTrue("composer clipped horizontally: left=${composer.left}px right=${composer.right}px root width=${root.width}px", composer.left >= 0f && composer.right <= root.right + 1f)

        // The timeline keeps the short-height floor (it collapsed to ZERO on the rig).
        // 48dp IS the landscape floor by arithmetic: the measured fixed chrome is
        // 275dp (header 135 + composer 140) in a ~347dp content window, and the card
        // keeps a 24dp scrollable sliver — 347 − 275 − 24 = 48. The portrait floor
        // (120dp) cannot hold beside the fixed chrome; the composer and this floor
        // are what short-height mode protects, in that order.
        val list = rule.onNodeWithTag("conversation_list").fetchSemanticsNode().boundsInRoot
        assertTrue("timeline squeezed to ${list.height / density}dp in landscape", list.height / density >= 48f)

        // The card is present and its answer controls are reachable — inside its own scroll.
        val pinned = rule.onNodeWithTag("conv_pinned_budget").fetchSemanticsNode().boundsInRoot
        assertTrue("pinned stack vanished: height=${pinned.height / density}dp", pinned.height / density > 0f)
        rule.onNodeWithTag("userinput_questions_submit").performScrollTo()
        rule.waitForIdle()
        val submit = rule.onNodeWithTag("userinput_questions_submit").fetchSemanticsNode().boundsInRoot
        assertTrue("Submit unreachable: bottom=${submit.bottom}px root=${root.bottom}px", submit.bottom <= root.bottom + 1f && submit.top >= 0f)
    }
}
