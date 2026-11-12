package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-A16 (pass 13, 2026-11-02, live-found on the rig twice): with a tall PINNED
 * approval card + a QUEUE strip above the timeline, opening the IME squeezed the
 * weighted timeline to zero and pushed the composer (draft + buttons) BEHIND the
 * keyboard — typing worked blind. The strip stack was the one UNBOUNDED plane left
 * (the pinned stack has had a budget since U8): a multi-row queue consumed the
 * window and the last-pinned composer clipped below the fold. Pinned: with a
 * permission card + a 3-row queue in a short (IME-open-shaped) window, the composer
 * stays on screen.
 */
@RunWith(AndroidJUnit4::class)
// h520dp ≈ the CONTENT height once the IME is up on the rig's phone (h731dp minus a
// ~300dp keyboard, with headroom for the composer intrinsic) — the geometry the rig
// reproduced the squeeze at, without needing a real IME.
@Config(sdk = [30], qualifiers = "w411dp-h520dp")
class ComposerSqueezeBudgetTest {

    @get:Rule
    val rule = createComposeRule()

    private fun permissionInteraction() = PendingInteraction(
        interactionId = "perm-rig-1",
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
                com.zcode.android.domain.model.PermissionOption("allowOnce"),
                com.zcode.android.domain.model.PermissionOption("allowAlways"),
                com.zcode.android.domain.model.PermissionOption("deny"),
            ),
            raw = buildJsonObject {},
        ),
        userInput = null,
        raw = buildJsonObject {},
    )

    private fun squeezedState() = ConversationUiState(
        rows = listOf(
            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {}),
        ),
        loading = false,
        taskState = TaskStateMachine.State.WAITING_FOR_PERMISSION,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
        sessionState = SessionProjectionState(
            pendingInteractions = listOf(permissionInteraction()),
            queue = QueueState(
                items = (1..3).map { i ->
                    QueueState.QueueItem(
                        queueItemId = "q-$i",
                        text = "queued follow-up number $i that wraps onto the next line of the strip",
                        kind = null,
                        dispatchState = "queued",
                    )
                },
                autoDrain = false,
                pauseReason = null,
            ),
        ),
    )

    @Test
    fun `A16 - the composer stays on screen with a tall pinned card and a queue strip`() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = squeezedState(),
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
                )
            }
        }
        rule.waitForIdle()
        val density = rule.density.density
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot

        // The pinned card is present (the plane is armed).
        rule.onNodeWithTag("conv_pinned_budget").assertExists()

        // THE BUG: pre-fix the unbounded queue strip starved the weighted timeline and
        // pushed the composer below the fold (the rig's "typing works blind").
        rule.onNodeWithTag("composer_send").assertIsDisplayed()
        val composer = rule.onNodeWithTag("composer_field").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "composer pushed off-screen: bottom=${composer.bottom}px root=${root.bottom}px",
            composer.bottom <= root.bottom + 1f,
        )
    }
}
