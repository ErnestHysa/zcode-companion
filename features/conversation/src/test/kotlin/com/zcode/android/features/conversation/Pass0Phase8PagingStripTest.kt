package com.zcode.android.features.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.Correlation
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.RecoveryAction
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P35-A (pass-0 Phase 8): the Load-earlier tap affordance is the PINNED strip above
 * the timeline, but the fault face (`older-error`) and the loading face
 * (`older-loading`) rendered as LAZY-LIST items at index 0/1 — hundreds of rows above
 * the tap point on a long timeline. A faulted page fetch gave the user at the tail
 * ZERO visible feedback (receipted live: gen2 fault arm → `port.pageFault` in logcat +
 * the screen UNCHANGED at the tap point; the error only rendered after ~60 gap-drags).
 *
 * The fix: the pinned strip carries ALL three faces — the tap affordance, the loading
 * face, and the fault face with its Retry (the AL.E retry-success face preserved:
 * clearing the fault restores the tappable affordance).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase8PagingStripTest {

    @get:Rule
    val rule = createComposeRule()

    private fun assistantRow(id: Long, text: String): ConversationRow.AssistantText =
        ConversationRow.Decoder.decode(
            buildJsonObject {
                put("rowId", JsonPrimitive(id))
                put("turnId", JsonPrimitive("t1"))
                put("kind", JsonPrimitive("assistantText"))
                put("createdAt", JsonPrimitive(2.0))
                put("createdAtSeq", JsonPrimitive(id))
                put("assistantResponseId", JsonPrimitive("ar"))
                put("text", JsonPrimitive(text))
                put("state", JsonPrimitive("complete"))
            },
        ) as ConversationRow.AssistantText

    private fun faultError() = AppError(
        ErrorCategory.TIMEOUT, "Taking too long", "diag",
        AppError.Retryable.AUTO, recoverable = true,
        autoRecovery = RecoveryAction.NONE, correlation = Correlation(),
    )

    private fun uiState(
        rows: List<ConversationRow>,
        hasMore: Boolean = true,
        loadingOlder: Boolean = false,
        error: AppError? = null,
        totalRows: Long? = 400L,
    ) = ConversationUiState(
        task = Task("s1", "Fix auth token refresh", TaskStatus.RUNNING, WorkspaceIdentityKey("/ws"), 1, 2),
        rows = rows,
        epoch = "e1",
        taskState = TaskStateMachine.State.RUNNING,
        phase = ConnectionPhase.CONNECTED,
        hasMoreHistory = hasMore,
        totalRows = totalRows,
        loadingOlder = loadingOlder,
        loadOlderError = error,
    )

    private var retryCalls = mutableListOf<Long>()

    private fun setContent(state: ConversationUiState) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = state,
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = { retryCalls.add(it) },
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
    }

    @Test
    fun `F-P35-A - the faulted page's error face renders in the PINNED strip - visible without scrolling`() {
        // 60 rows: the timeline anchors at the tail — the lazy-list index-0/1 error
        // item (the pre-fix geometry) is hundreds of rows above the tap point.
        val rows = (1L..60L).map { assistantRow(it, "message $it") }
        setContent(uiState(rows, error = faultError()))
        // The pinned strip carries the fault face (the tag the fix introduces).
        rule.onAllNodesWithTag("conversation_older_error_strip").assertCountEquals(1)
        rule.onNodeWithTag("conversation_older_error_strip").assertIsDisplayed()
        // The in-list lazy item is GONE — the pinned strip is the single fault face.
        rule.onAllNodesWithTag("conversation_older_error").assertCountEquals(0)
    }

    @Test
    fun `F-P35-A - the retry from the pinned strip slot re-fires onRetryOlder with the window head`() {
        val rows = (1L..60L).map { assistantRow(it, "message $it") }
        setContent(uiState(rows, error = faultError()))
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(listOf(1L), retryCalls)
    }

    @Test
    fun `F-P35-A - the AL-E retry-success face - clearing the fault restores the Load-earlier affordance`() {
        val rows = (1L..60L).map { assistantRow(it, "message $it") }
        var error by androidx.compose.runtime.mutableStateOf<AppError?>(faultError())
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = uiState(rows, error = error),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = { retryCalls.add(it) },
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
        rule.onNodeWithTag("conversation_older_error_strip").assertIsDisplayed()
        // The retry succeeds: the VM clears the fault (Part AL.E's receipted face) and
        // the tappable affordance returns in the same pinned slot.
        rule.runOnUiThread { error = null }
        rule.waitForIdle()
        rule.onAllNodesWithTag("conversation_older_error_strip").assertCountEquals(0)
        rule.onAllNodesWithTag("conversation_load_older_strip").assertCountEquals(1)
        rule.onNodeWithTag("conversation_load_older_strip").assertIsDisplayed()
    }

    @Test
    fun `F-P35-A - the loading face rides the pinned strip - visible while paging`() {
        val rows = (1L..60L).map { assistantRow(it, "message $it") }
        setContent(uiState(rows, loadingOlder = true))
        rule.onAllNodesWithTag("conversation_older_loading_strip").assertCountEquals(1)
        rule.onNodeWithTag("conversation_older_loading_strip").assertIsDisplayed()
        // The in-list loading item is GONE — one face, one geometry.
        rule.onAllNodesWithTag("conversation_older_loading").assertCountEquals(0)
    }
}
