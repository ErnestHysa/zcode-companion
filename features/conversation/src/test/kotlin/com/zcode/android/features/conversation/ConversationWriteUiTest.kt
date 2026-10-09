package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SessionControl
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Phase 7 write-surface UI suite (§35.3 + §20.2/§20.4 at the UI level): the approval
 * card (fail-closed options, confirm steps), the composer (draft + send + stop),
 * the queue strip, and the §30 a11y semantics of approval actions.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConversationWriteUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun state(
        sessionState: SessionProjectionState? = null,
        draft: String = "",
    ): ConversationUiState = ConversationUiState(
        rows = emptyList(),
        loading = false,
        taskState = TaskStateMachine.State.RUNNING,
        phase = ConnectionPhase.CONNECTED,
        draft = draft,
        sessionState = sessionState,
    )

    private fun permissionInteraction(): com.zcode.android.domain.model.PendingInteraction =
        com.zcode.android.domain.model.PendingInteraction.decode(
            Json.parseToJsonElement(
                """
                {"interactionId":"i-ui-1","kind":"permission","anchorRowId":null,"createdAt":1700000000000.0,
                 "payload":{"kind":"permission","toolCallId":"tc","toolName":"Bash","summary":"Run the build?",
                 "detail":{"command":"./gradlew build"},
                 "options":[{"optionId":"allowOnce","label":"Allow once","kind":"allowOnce"},
                            {"optionId":"deny","label":"Deny","kind":"deny"},
                            {"optionId":"fullAccess","label":"Full access","kind":"custom"}]}}
                """.trimIndent(),
            ) as JsonObject,
        )!!

    private fun setContent(
        uiState: ConversationUiState,
        onAnswer: (String, String, String?, Boolean) -> Unit = { _, _, _, _ -> },
        onSend: (String?) -> Unit = {},
    ) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = uiState,
                    phase = uiState.phase,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = onSend,
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = onAnswer,
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
    }

    // ---------------------------------------------------------------- approval card

    @Test
    fun approvalCard_rendersToolAndOptions_withDecisionContext() {
        val interaction = permissionInteraction()
        setContent(state(sessionState = SessionProjectionState(pendingInteractions = listOf(interaction))))
        rule.onNodeWithTag("approval_card_i-ui-1").assertIsDisplayed()
        rule.onNodeWithText("Run the build?").assertIsDisplayed()
        rule.onNodeWithText("Allow once — this request only").assertIsDisplayed()
        rule.onNodeWithText("Deny — the agent is told no").assertIsDisplayed()
    }

    @Test
    fun approvalCard_allowOnce_answersImmediately() {
        var answered: Pair<String, String>? = null
        val interaction = permissionInteraction()
        setContent(
            state(sessionState = SessionProjectionState(pendingInteractions = listOf(interaction))),
            onAnswer = { iid, optionId, _, _ -> answered = iid to optionId },
        )
        rule.onNodeWithTag("approval_allowOnce").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("i-ui-1" to "allowOnce", answered)
    }

    @Test
    fun approvalCard_denyRequiresConfirm_thenAnswers() {
        var answered: Pair<String, String>? = null
        val interaction = permissionInteraction()
        setContent(
            state(sessionState = SessionProjectionState(pendingInteractions = listOf(interaction))),
            onAnswer = { iid, optionId, _, _ -> answered = iid to optionId },
        )
        rule.onNodeWithTag("approval_deny").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertNull(answered) // confirm step first (§20.2)
        rule.onNodeWithTag("approval_confirm_yes").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("i-ui-1" to "deny", answered)
    }

    @Test
    fun approvalCard_unknownOption_isInert_neverAnswerable() {
        var answered = false
        val interaction = permissionInteraction()
        setContent(
            state(sessionState = SessionProjectionState(pendingInteractions = listOf(interaction))),
            onAnswer = { _, _, _, _ -> answered = true },
        )
        // §20.4 row 2: unsupported ids render INERT — disabled, no answer path.
        rule.onNodeWithTag("approval_unsupported_fullAccess").assertIsNotEnabled()
        rule.onNodeWithTag("approval_unsupported_fullAccess").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertFalse(answered)
    }

    // ---------------------------------------------------------------- composer

    @Test
    fun composer_sendEnabledWithDraft_andFires() {
        var sent: String? = null
        setContent(state(draft = "hello agent"), onSend = { sent = "fired" })
        rule.onNodeWithTag("composer_send").assertIsEnabled()
        rule.onNodeWithTag("composer_send").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("fired", sent)
    }

    @Test
    fun composer_stopAbsentWithoutAuthoritativeControl() {
        // No control block → no stop button (never a fabricated control).
        setContent(state())
        org.junit.Assert.assertEquals(
            0,
            rule.onAllNodesWithTag("composer_stop").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun composer_stopVisibleWhenControlSaysStoppable() {
        val control = SessionControl("running", false, true, SessionControl.StopState.STOPPABLE, emptyList())
        setContent(state(sessionState = SessionProjectionState(control = control)))
        rule.onNodeWithTag("composer_stop").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- queue strip

    @Test
    fun queueStrip_rendersItems_andSendNowFires() {
        var sentNow: String? = null
        val queue = QueueState(
            items = listOf(QueueState.QueueItem("q-9", "queued prompt", "sendText", "queued")),
            autoDrain = false,
            pauseReason = "manual",
        )
        var answered = false
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = state(sessionState = SessionProjectionState(queue = queue)),
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
                    onAnswer = { _, _, _, _ -> answered = true },
                    onSendQueuedNow = { sentNow = it },
                    onEditQueueItem = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("queue_strip").assertIsDisplayed()
        rule.onNodeWithText("Queued (1) · paused · manual").assertIsDisplayed()
        rule.onNodeWithTag("queue_send_now_q-9").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("q-9", sentNow)
    }
}
