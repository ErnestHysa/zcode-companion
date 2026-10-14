package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C6-BackHandler (gaps-and-polish Phase 1D, P0; the C9 bug-class): leaving a
 * conversation with an unsent draft and a running task gave ZERO confirmation —
 * the promised Phase-7 draft guard never landed (predictive back is declared; the
 * guard is the missing half). Accept: back with a non-empty draft raises the
 * confirm; empty draft passes through; pin per branch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class DraftBackGuardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(draft: String, onBack: () -> Unit) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = emptyList(),
                        loading = false,
                        taskState = com.zcode.android.domain.state.TaskStateMachine.State.RUNNING,
                        phase = ConnectionPhase.CONNECTED,
                        draft = draft,
                    ),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = onBack,
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

    private fun pressBack() {
        rule.onNodeWithTag("conversation_back").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `back with a non-empty draft raises the confirm - not an instant leave`() {
        var left = false
        setContent(draft = "an unsent draft", onBack = { left = true })
        pressBack()

        assertTrue(
            "back with a draft must raise the confirm dialog (found none)",
            rule.onAllNodesWithTag("conversation_leave_confirm").fetchSemanticsNodes().isNotEmpty(),
        )
        assertEquals("the confirm must gate the leave", false, left)

        // Keep editing → still on the screen.
        rule.onNodeWithTag("conversation_leave_keep").performClick()
        rule.waitForIdle()
        assertEquals(false, left)
        assertTrue(
            "the dialog must close on keep",
            rule.onAllNodesWithTag("conversation_leave_confirm").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `confirming the leave leaves`() {
        var left = false
        setContent(draft = "an unsent draft", onBack = { left = true })
        pressBack()
        rule.onNodeWithTag("conversation_leave_discard").performClick()
        rule.waitForIdle()
        assertEquals("confirming must leave", true, left)
    }

    @Test
    fun `back with an empty draft passes through - no confirm`() {
        var left = false
        setContent(draft = "", onBack = { left = true })
        pressBack()
        assertEquals("an empty draft must not gate the back", true, left)
        assertEquals(
            0,
            rule.onAllNodesWithTag("conversation_leave_confirm").fetchSemanticsNodes().size,
        )
    }
}
