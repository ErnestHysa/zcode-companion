package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G5 (vc51): find-in-conversation on the real screen — the header's find affordance opens
 * the bar, a query renders the "n of m" match count over the timeline rows, next/previous
 * walks (and wraps) the matches, and close hides the bar again. The index itself is pinned
 * in [ConversationFindIndexTest]; the query-map-style jump rides the same scroll contract.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConversationFindUiTest {

    @get:Rule
    val rule = createComposeRule()

    private var seq = 0L
    private fun base() = ConversationRow.Base(createdAt = 1.0, createdAtSeq = ++seq)
    private fun user(id: Long, text: String) = ConversationRow.UserInput(
        rowId = id, turnId = "t$id", base = base(), text = text, epilogueStart = null,
        origin = "user", originMeta = null, guided = false, sourceCommandId = null,
        attachments = emptyList(), raw = buildJsonObject {},
    )
    private fun agent(id: Long, text: String) = ConversationRow.AssistantText(
        rowId = id, turnId = "t$id", base = base(), assistantResponseId = null,
        text = text, state = ConversationRow.TextState.COMPLETE, model = null, raw = buildJsonObject {},
    )

    private fun setContent() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = listOf(
                            user(1, "ship the release today"),
                            agent(2, "The release is prepared"),
                            user(3, "who reviewed the release"),
                        ),
                        loading = false,
                        taskState = TaskStateMachine.State.RUNNING,
                        phase = ConnectionPhase.CONNECTED,
                        draft = "",
                    ),
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
    }

    @Test
    fun `find opens counts matches walks and wraps then closes`() {
        setContent()
        rule.onNodeWithTag("conv_find_open").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("conv_find_bar").assertIsDisplayed()
        rule.onNodeWithTag("conv_find_query").performTextInput("release")
        rule.waitForIdle()
        // three matches over user + assistant rows (the tool-free timeline order)
        rule.onNodeWithText("1 of 3").assertIsDisplayed()
        rule.onNodeWithTag("conv_find_next").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2 of 3").assertIsDisplayed()
        rule.onNodeWithTag("conv_find_prev").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("1 of 3").assertIsDisplayed()
        // wrap backwards from the first to the last
        rule.onNodeWithTag("conv_find_prev").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("3 of 3").assertIsDisplayed()
        rule.onNodeWithTag("conv_find_close").performClick()
        rule.waitForIdle()
        val bars = rule.onAllNodesWithTag("conv_find_bar").fetchSemanticsNodes()
        assertEquals(0, bars.size)
    }

    @Test
    fun `a query with no hits renders the honest none face`() {
        setContent()
        rule.onNodeWithTag("conv_find_open").performClick()
        rule.onNodeWithTag("conv_find_query").performTextInput("zzz")
        rule.waitForIdle()
        rule.onNodeWithText("No matches").assertIsDisplayed()
    }
}
