package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * I2.5 + I2.3 (Phase 6A) pins: the turn navigator (desktop `chat.turnNavigator.*`)
 * lists the session's user queries and jumps to one; the session summary panel
 * (desktop `chat.summaryPanel.*`) renders duration / goal iteration / running
 * background tasks from the snapshot fields the phone already decodes, with a
 * collapsed capsule and an expanded card; nothing renders for an empty session.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SessionNavigatorAndSummaryUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun userRow(rowId: Long, text: String) = ConversationRow.Decoder.decode(
        Json.parseToJsonElement(
            """{"rowId":$rowId,"turnId":"t$rowId","kind":"userInput","entityId":"ent-$rowId",
                "createdAt":1700000000000,"createdAtSeq":$rowId,"text":"$text","origin":"typed",
                "guided":false,"attachments":[]}""",
        ).jsonObject,
    )

    private fun assistantRow(rowId: Long) = ConversationRow.Decoder.decode(
        Json.parseToJsonElement(
            """{"rowId":$rowId,"turnId":"t$rowId","kind":"assistantText","entityId":"ent-$rowId",
                "createdAt":1700000000000,"createdAtSeq":$rowId,"text":"answer $rowId","state":"complete"}""",
        ).jsonObject,
    )

    private fun turnRow(rowId: Long, startedAt: Double, endedAt: Double?) = ConversationRow.Decoder.decode(
        Json.parseToJsonElement(
            """{"rowId":$rowId,"turnId":"tturn-$rowId","kind":"turnHeader","entityId":"ent-turn-$rowId",
                "createdAt":$startedAt,"createdAtSeq":$rowId,"origin":"user","state":"completedSuccess",
                "startedAt":$startedAt${endedAt?.let { ""","endedAt":$it""" } ?: ""}}""",
        ).jsonObject,
    )

    /** goal iteration 3 + two running background works. */
    private val sessionState = SessionProjectionState.fromSnapshot(
        Json.parseToJsonElement(
            """{"goal":{"targetId":"g1","objective":"Ship it","status":"active","iteration":3},
                "backgroundWorks":[
                  {"workId":"w1","kind":"background","title":"Nightly sweep","status":"running","cancellable":true},
                  {"workId":"w2","kind":"background","title":"Index","status":"running","cancellable":true}]}""",
        ).jsonObject,
    )

    private fun setContent(
        rows: List<ConversationRow>,
        sessionState: SessionProjectionState? = null,
    ) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = rows,
                        loading = false,
                        taskState = TaskStateMachine.State.IDLE,
                        phase = ConnectionPhase.CONNECTED,
                        epoch = "e1",
                        sessionState = sessionState,
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
    fun `navigator appears for multi-query sessions and jumps to the picked query`() {
        setContent(
            listOf(
                turnRow(1, 1_000_000.0, 1_010_000.0),
                userRow(2, "first question about the parser"),
                assistantRow(3),
                turnRow(4, 1_020_000.0, null),
                userRow(5, "second question about the tests"),
                assistantRow(6),
                turnRow(7, 1_040_000.0, null),
                userRow(8, "third question about the release"),
                assistantRow(9),
            ),
        )
        rule.onNodeWithTag("turnnav_open").assertIsDisplayed()
        rule.onNodeWithTag("turnnav_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("turnnav_sheet").assertIsDisplayed()
        rule.onNodeWithText("Jump to query 1").assertIsDisplayed()
        rule.onNodeWithText("Jump to query 3").assertIsDisplayed()
        rule.onNodeWithTag("turnnav_item_1").performClick()
        rule.waitForIdle()
        // The jump landed on the FIRST query: its text is on screen again.
        org.junit.Assert.assertTrue(
            "the first query is visible after the jump",
            rule.onAllNodesWithText("first question about the parser").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `navigator stays hidden for single-query sessions`() {
        setContent(listOf(turnRow(1, 1_000_000.0, 1_010_000.0), userRow(2, "only one"), assistantRow(3)))
        rule.onAllNodesWithTag("turnnav_open").assertCountEquals(0)
    }

    @Test
    fun `navigator stays hidden for empty sessions`() {
        setContent(emptyList())
        rule.onAllNodesWithTag("turnnav_open").assertCountEquals(0)
    }

    @Test
    fun `summary panel renders duration, goal iteration, background tasks - capsule expands`() {
        setContent(
            listOf(
                turnRow(1, 1_000_000.0, 1_070_000.0), // 700s span → 11m 40s
                userRow(2, "question"),
                assistantRow(3),
            ),
            sessionState = sessionState,
        )
        rule.onNodeWithTag("summary_capsule").assertIsDisplayed()
        rule.onNodeWithTag("summary_capsule").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("summary_panel").assertIsDisplayed()
        rule.onNodeWithText("11m 40s").assertIsDisplayed()
        rule.onNodeWithText("Iteration 3").assertIsDisplayed()
        rule.onNodeWithText("2 background").assertIsDisplayed()
    }

    @Test
    fun `summary panel stays absent when the session has nothing to summarize`() {
        setContent(emptyList(), sessionState = null)
        rule.onAllNodesWithTag("summary_capsule").assertCountEquals(0)
        rule.onAllNodesWithTag("summary_panel").assertCountEquals(0)
    }
}
