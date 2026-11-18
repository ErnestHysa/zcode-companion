package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.BackgroundWork
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P6-B (pass-0 Phase 2; stamps ×3, pass 12 s05/s09): with the armed stack the
 * timeline rendered ZERO rows — the pinned cards and the expanded queue strip ate
 * the window. The pre-fix budget arithmetic violated BOTH of its own documented
 * caps: `pinnedBudget` took maxOf(30% share, window−reserve) — the LARGER of two
 * caps, i.e. neither — and the strip cap never reserved an explicit timeline
 * floor (PINNED_RESERVE's own comment budgets a 120dp one).
 *
 * The harness arms EVERYTHING the exit gate names: the 3-card stack (approval +
 * question + hook), the expanded queue, the pending-command recovery banner and
 * the background-work indicator, at a compact phone window — and asserts the
 * timeline still renders rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w395dp-h700dp")
class Pass0Phase2ArmedStackTimelineTest {

    @get:Rule
    val rule = createComposeRule()

    private fun card(id: String, kind: PendingInteraction.Kind): PendingInteraction = when (kind) {
        PendingInteraction.Kind.PERMISSION -> PendingInteraction(
            interactionId = id, kind = kind, anchorRowId = null, createdAtMs = 1L,
            permission = PendingInteraction.PermissionPayload(
                toolCallId = "tc", toolName = "Bash", summary = "Run the build?", detail = null, freeText = null,
                options = listOf(
                    com.zcode.android.domain.model.PermissionOption("allowOnce", "Allow once", "allowOnce"),
                    com.zcode.android.domain.model.PermissionOption("deny", "No", "deny"),
                ),
                raw = buildJsonObject {},
            ),
            userInput = null, raw = buildJsonObject {},
        )
        PendingInteraction.Kind.USER_INPUT -> PendingInteraction(
            interactionId = id, kind = kind, anchorRowId = null, createdAtMs = 1L,
            permission = null,
            userInput = PendingInteraction.UserInputPayload(
                prompt = "Decide", freeText = false, sensitive = false, options = emptyList(),
                questions = listOf(
                    PendingInteraction.UserInputPayload.Question(
                        "Which channel?", "Channel",
                        listOf(PendingInteraction.UserInputPayload.Option("email", "Email")),
                        multiSelect = null,
                    ),
                ),
                toolName = null, raw = buildJsonObject {},
            ),
            raw = buildJsonObject {},
        )
        PendingInteraction.Kind.WORKSPACE_HOOK_REVIEW -> PendingInteraction(
            interactionId = id, kind = kind, anchorRowId = null, createdAtMs = 1L,
            permission = null, userInput = null,
            hookReview = com.zcode.android.domain.model.WorkspaceHookReview(
                reviewFlowId = "flow", generation = 1, interactionId = id,
                sessionId = "s1", taskId = "t1", runId = "r1",
                workspaceIdentity = "/repo", workspaceLabel = "repo",
                bundleDigest = "d", createdAtMs = 1L, deadlineAtMs = 2L,
                workspacePath = "/repo", remoteSessionId = null,
                summary = com.zcode.android.domain.model.WorkspaceHookReview.Summary(4, 3, 3),
                items = (1..4).map { i ->
                    com.zcode.android.domain.model.WorkspaceHookReview.Item(
                        reviewItemId = "h-$i", event = "PreToolUse", matcher = null, type = "command",
                        displayName = "Hook script $i", displayCommand = "bash h$i.sh",
                        sourcePath = "/repo/.zcode/hooks.json", executionMode = "foreground",
                        configuredEnabled = true, editable = false, trustState = "pending_trust",
                    )
                },
            ),
            raw = buildJsonObject {},
        )
        else -> error("unused")
    }

    private fun setContent() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = listOf(
                            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "a", raw = buildJsonObject {}),
                            ConversationRow.Unknown(rowId = 2, turnId = "t1", unknownKind = "b", raw = buildJsonObject {}),
                            ConversationRow.Unknown(rowId = 3, turnId = "t2", unknownKind = "c", raw = buildJsonObject {}),
                        ),
                        loading = false,
                        taskState = TaskStateMachine.State.WAITING_FOR_USER,
                        phase = ConnectionPhase.CONNECTED,
                        draft = "",
                        sessionState = SessionProjectionState(
                            pendingInteractions = listOf(
                                card("p1", PendingInteraction.Kind.PERMISSION),
                                card("q1", PendingInteraction.Kind.USER_INPUT),
                                card("h1", PendingInteraction.Kind.WORKSPACE_HOOK_REVIEW),
                            ),
                            queue = QueueState(
                                items = (1..5).map { QueueState.QueueItem("q$it", "queued follow-up $it", null, "queued") },
                                autoDrain = false,
                                pauseReason = null,
                            ),
                            backgroundWorks = listOf(
                                BackgroundWork("w1", "bash", "lint-all", "running", cancellable = true),
                            ),
                        ),
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
                    backgroundOutputProvider = { null },
                    pendingRecovery = listOf(
                        ConversationPort.RecoverableCommand("cmd-a", "sendText", "first draft", 1L),
                    ),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `F-P6-B - the fully armed stack still renders timeline rows`() {
        setContent()
        // The stack IS armed: the pinned plane, the expanded queue and the banners
        // are all on screen — and the timeline must still render rows. The timeline
        // is lazy: only the visible window composes, so the assertion is "at least
        // one row composed AND displayed", never the full count.
        rule.onNodeWithTag("conv_pinned_budget").assertExists()
        rule.onNodeWithTag("queue_strip_toggle").assertExists()
        // A lazy list keeps the semantics of a not-yet-placed prefetch item at a
        // zero rect — judge only PLACED rows (non-zero bounds), and require one.
        val placed = rule.onAllNodesWithTag("row_unknown", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0f }
        assertTrue(
            "the timeline must render at least one row under the fully armed stack (placed=${placed.size})",
            placed.isNotEmpty(),
        )
        // The placed row is on screen (within the root window, non-clipped away).
        rule.onAllNodesWithTag("row_unknown", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first { it.boundsInRoot.height > 0f }
            .let { placed -> assertTrue(placed.boundsInRoot.width > 0f) }
    }
}
