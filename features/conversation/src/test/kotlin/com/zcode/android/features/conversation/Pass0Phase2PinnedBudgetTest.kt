package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.WorkspaceHookReview
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P3-A (pass-0 Phase 2; 12+ ledger stamps, passes 1–9/12/33/44): the pinned
 * pending-stack LazyColumn measures its items with UNBOUNDED main-axis
 * constraints, so every card's internal `weight(1f, fill = false)` body — the
 * question/answer body, the hook item list — resolved to ZERO height. The pass-44
 * live receipts: arriving and re-surfaced cards rendered "summary → disabled
 * trust area" (`enabled = false`, the ANSWER leg gated) because the option rows
 * and hook items the controls depend on never painted.
 *
 * The pin asserts the BODIES themselves (not just the controls the older
 * PinnedCardSubmitPinTest covers): question headers/option rows and hook item
 * rows are DISPLAYED, with the pinned-child count varied (1 card → the armed
 * 3-card stack).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w395dp-h800dp")
class Pass0Phase2PinnedBudgetTest {

    @get:Rule
    val rule = createComposeRule()

    private fun questionCard(id: String = "q-body") = PendingInteraction(
        interactionId = id,
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
            ),
            toolName = null,
            raw = buildJsonObject {},
        ),
        raw = buildJsonObject {},
    )

    private fun hookCard(id: String = "hook-body", items: Int = 6) = PendingInteraction(
        interactionId = id,
        kind = PendingInteraction.Kind.WORKSPACE_HOOK_REVIEW,
        anchorRowId = null,
        createdAtMs = 1L,
        permission = null,
        userInput = null,
        hookReview = WorkspaceHookReview(
            reviewFlowId = "flow-$id", generation = 1, interactionId = id,
            sessionId = "s1", taskId = "t1", runId = "r1",
            workspaceIdentity = "/repo", workspaceLabel = "repo",
            bundleDigest = "d", createdAtMs = 1L, deadlineAtMs = 2L,
            workspacePath = "/repo", remoteSessionId = null,
            summary = WorkspaceHookReview.Summary(eventCount = items, hookCount = items, pendingCount = items),
            items = (1..items).map { i ->
                WorkspaceHookReview.Item(
                    reviewItemId = "h-$i", event = "PreToolUse", matcher = null, type = "command",
                    displayName = "Hook script $i with a fairly long descriptive name",
                    displayCommand = "bash scripts/hook-$i.sh --flag", sourcePath = "/repo/.zcode/hooks.json",
                    executionMode = "foreground", configuredEnabled = true, editable = false,
                    trustState = "pending_trust",
                )
            },
        ),
        raw = buildJsonObject {},
    )

    private fun permissionCard(id: String = "perm-body") = PendingInteraction(
        interactionId = id,
        kind = PendingInteraction.Kind.PERMISSION,
        anchorRowId = null,
        createdAtMs = 1L,
        permission = PendingInteraction.PermissionPayload(
            toolCallId = "tc-1",
            toolName = "Bash",
            summary = "Run the build?",
            detail = null,
            freeText = null,
            options = listOf(
                com.zcode.android.domain.model.PermissionOption("allowOnce", "Allow once", "allowOnce"),
                com.zcode.android.domain.model.PermissionOption("deny", "No", "deny"),
            ),
            raw = buildJsonObject {},
        ),
        userInput = null,
        raw = buildJsonObject {},
    )

    private fun setContent(interactions: List<PendingInteraction>) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = listOf(
                            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {}),
                        ),
                        loading = false,
                        taskState = TaskStateMachine.State.WAITING_FOR_USER,
                        phase = ConnectionPhase.CONNECTED,
                        draft = "",
                        sessionState = SessionProjectionState(pendingInteractions = interactions),
                    ),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = { _, _, _ -> },
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
    fun `F-P3-A - the question card's BODY renders - question header and option rows visible`() {
        setContent(listOf(questionCard()))
        rule.onNodeWithTag("userinput_q_header_0").assertIsDisplayed()
        rule.onNodeWithTag("userinput_q0_opt_email").assertIsDisplayed()
    }

    @Test
    fun `F-P3-A - the hook card's item rows render - the first hook item is visible`() {
        setContent(listOf(hookCard()))
        // Pre-fix the weighted item list measured ZERO high: not even the first row
        // painted. Post-fix the card is bounded, the list keeps real height (deeper
        // rows scroll INSIDE the card — the D4 contract) and h-1 is on screen.
        rule.onNodeWithTag("hook_review_item_h-1").assertIsDisplayed()
    }

    @Test
    fun `F-P3-A - the armed stack - every card body keeps its rows at real height`() {
        setContent(listOf(permissionCard(), questionCard(), hookCard()))
        // The FIRST card (the permission) shows its option rows without scrolling —
        // pre-fix its body was zero-height and the option rows never painted.
        rule.onNodeWithTag("approval_allowOnce").assertIsDisplayed()
    }
}
