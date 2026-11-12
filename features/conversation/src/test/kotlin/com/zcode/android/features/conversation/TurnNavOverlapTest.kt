package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
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
 * F-36-A (pass 36): both floating buttons anchored BottomEnd in the same Box;
 * jump-to-latest composed LATER and covered the turn navigator whenever the timeline
 * was scrolled up — the tree showed `turnnav_open` as a 13px sliver under
 * `conversation_jump_latest`, and four taps at the navigator's spot all went to
 * jump-to-latest (the list snapped to the bottom; no navigator sheet). The I2.5
 * "ALWAYS visible" contract failed exactly when navigating history.
 *
 * Pin: with ≥2 user inputs (the navigator earns its affordance) and the timeline
 * scrolled up (jump-to-latest visible), the two buttons' bounds DO NOT overlap —
 * the navigator sits fully above the jump button and opens at any scroll position.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class TurnNavOverlapTest {

    @get:Rule
    val rule = createComposeRule()

    private fun rows(): List<ConversationRow> = buildList {
        for (i in 1..12) {
            add(
                ConversationRow.UserInput(
                    rowId = i.toLong() * 10, turnId = "t$i",
                    base = ConversationRow.Base(createdAt = i.toDouble(), createdAtSeq = i.toLong()),
                    text = "user query $i with enough text to wrap into a couple of lines on this window",
                    epilogueStart = null, origin = "user", originMeta = null, guided = false, sourceCommandId = null,
                    attachments = emptyList(), raw = buildJsonObject {},
                ),
            )
            add(
                ConversationRow.AssistantText(
                    rowId = i.toLong() * 10 + 1, turnId = "t$i",
                    base = ConversationRow.Base(createdAt = i + 0.5, createdAtSeq = i.toLong() * 10 + 1),
                    assistantResponseId = "a$i", text = "answer $i " + "with padding prose ".repeat(8),
                    state = ConversationRow.TextState.COMPLETE, model = null, raw = buildJsonObject {},
                ),
            )
        }
    }

    private fun setContent() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = rows(),
                        loading = false,
                        taskState = TaskStateMachine.State.IDLE,
                        phase = ConnectionPhase.CONNECTED,
                        draft = "",
                        sessionState = SessionProjectionState(),
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
                )
            }
        }
        rule.waitForIdle()
        // Scroll INTO HISTORY (swipe down reveals earlier content): the recorded face
        // is the timeline "scrolled up" — jump-to-latest appears there.
        rule.onNodeWithTag("conversation_list").performTouchInput { swipeDown(); swipeDown(); swipeDown(); swipeDown() }
        rule.waitForIdle()
    }

    @Test
    fun `F36-A - the navigator and jump-to-latest never overlap when both are visible`() {
        setContent()
        rule.onNodeWithTag("turnnav_open").assertIsDisplayed()
        rule.onNodeWithTag("conversation_jump_latest").assertIsDisplayed()
        val nav = rule.onNodeWithTag("turnnav_open").fetchSemanticsNode().boundsInRoot
        val jump = rule.onNodeWithTag("conversation_jump_latest").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "buttons overlap: nav=$nav jump=$jump (the F-36-A 13px-sliver face)",
            nav.bottom <= jump.top || jump.bottom <= nav.top || nav.right <= jump.left || jump.right <= nav.left,
        )
        // The recorded geometry: the navigator sits ABOVE jump-to-latest.
        assertTrue(
            "navigator not stacked above jump: nav bottom=${nav.bottom} jump top=${jump.top}",
            nav.bottom <= jump.top,
        )
    }

    @Test
    fun `F36-A - the navigator opens at a scrolled-up position (the buried state)`() {
        setContent()
        rule.onNodeWithTag("turnnav_open").assertIsDisplayed()
        rule.onNodeWithTag("turnnav_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("turnnav_sheet").assertIsDisplayed()
    }
}
