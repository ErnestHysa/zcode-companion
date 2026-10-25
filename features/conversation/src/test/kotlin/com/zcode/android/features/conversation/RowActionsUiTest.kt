package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
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
 * E3.2+I1+N5 (gaps-and-polish Phase 4A): the shipped UI offered ONLY Copy+Edit on
 * user rows and NOTHING on assistant rows (N5 live absence proof) while the wire
 * decodes `canFork`/`canRetry`/`canRewindFiles` + `feedback` and the protocol layer
 * models the commands. Pins: assistant rows render an action row GATED on the
 * row's own flags (desktop: `latestAssistantTextRow.actions?.canRetry === true`,
 * ConversationTurnGroup.tsx:867); Like/Dislike reflect the row's feedback echo;
 * the turn card offers "Undo files" only when `canRewindFiles` + files > 0 +
 * not reverted + not running (editWorkspaceRewindAvailability, :1227-1235).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RowActionsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun assistantRow(rowId: Long, canFork: Boolean, canRetry: Boolean, feedback: String? = null, state: String = "complete") =
        ConversationRow.Decoder.decode(
            Json.parseToJsonElement(
                """{"rowId":$rowId,"turnId":"t1","kind":"assistantText","entityId":"ent-$rowId",
                    "createdAt":1700000000000,"createdAtSeq":$rowId,"text":"the answer","state":"$state",
                    "actions":{"canFork":$canFork,"canRetry":$canRetry}
                    ${feedback?.let { ""","feedback":"$it"""" } ?: ""}}""",
            ).jsonObject,
        )

    private fun turnRow(rowId: Long, canRewind: Boolean, files: Int, fileState: String = "active", turnState: String = "completedSuccess") =
        ConversationRow.Decoder.decode(
            Json.parseToJsonElement(
                """{"rowId":$rowId,"turnId":"t1","kind":"turnHeader","entityId":"ent-turn-$rowId",
                    "createdAt":1700000000000,"createdAtSeq":$rowId,"origin":"userInput","state":"$turnState",
                    "startedAt":1700000000000,
                    "fileChanges":{"additions":3,"deletions":1,"files":$files,"state":"$fileState"},
                    "actions":{"canRewindFiles":$canRewind}}""",
            ).jsonObject,
        )

    private val calls = mutableListOf<String>()

    private fun setContent(vararg rows: ConversationRow) {
        calls.clear()
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = rows.toList(),
                        loading = false,
                        taskState = TaskStateMachine.State.IDLE,
                        phase = ConnectionPhase.CONNECTED,
                        epoch = "e1",
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
                    onRetryTurn = { r, e -> calls.add("retry:$r:$e") },
                    onForkAssistant = { r, e -> calls.add("fork:$r:$e") },
                    onApplyFileRewind = { r, e -> calls.add("rewind:$r:$e") },
                    onAssistantFeedback = { r, e, f -> calls.add("feedback:$r:$e:$f") },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `assistant row with both flags renders Fork and Retry and sends the row target`() {
        setContent(assistantRow(10, canFork = true, canRetry = true))
        assertEquals(1, count("row_fork"))
        assertEquals(1, count("row_retry"))
        rule.onNodeWithTag("row_fork").performClick()
        rule.onNodeWithTag("row_retry").performClick()
        rule.waitForIdle()
        assertEquals(listOf("fork:10:ent-10", "retry:10:ent-10"), calls)
    }

    @Test
    fun `flags absent - no Fork no Retry (the wire decides, never the phone)`() {
        setContent(assistantRow(11, canFork = false, canRetry = false))
        assertEquals(0, count("row_fork"))
        assertEquals(0, count("row_retry"))
    }

    @Test
    fun `only canRetry - Retry offered, Fork withheld`() {
        setContent(assistantRow(12, canFork = false, canRetry = true))
        assertEquals(0, count("row_fork"))
        assertEquals(1, count("row_retry"))
    }

    @Test
    fun `like and dislike always render on a completed assistant row and toggle through the callback`() {
        setContent(assistantRow(13, canFork = false, canRetry = false))
        rule.onNodeWithTag("row_like").performClick()
        rule.onNodeWithTag("row_dislike").performClick()
        rule.waitForIdle()
        assertEquals(listOf("feedback:13:ent-13:like", "feedback:13:ent-13:dislike"), calls)
    }

    @Test
    fun `the feedback echo renders Liked and tapping again clears it`() {
        setContent(assistantRow(14, canFork = false, canRetry = false, feedback = "like"))
        rule.onNodeWithContentDescription("Liked").assertIsDisplayed()
        rule.onNodeWithTag("row_like").performClick()
        rule.waitForIdle()
        assertEquals(listOf("feedback:14:ent-14:null"), calls)
    }

    @Test
    fun `a streaming assistant row offers no actions yet`() {
        setContent(assistantRow(15, canFork = true, canRetry = true, state = "streaming"))
        assertEquals(0, count("row_fork"))
        assertEquals(0, count("row_like"))
    }

    @Test
    fun `turn card offers Undo files only when canRewindFiles and confirms before sending`() {
        setContent(turnRow(20, canRewind = true, files = 2))
        assertEquals(1, count("row_turn_rewind"))
        rule.onNodeWithTag("row_turn_rewind").performClick()
        rule.waitForIdle()
        // Desktop: chat.changeSummary.rewindDialog — confirm "Undo files" before the
        // command (a bottom sheet — B3-sheets).
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("rewind_confirm").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithTag("rewind_confirm").performClick()
        rule.waitForIdle()
        assertEquals(listOf("rewind:20:ent-turn-20"), calls)
    }

    @Test
    fun `no canRewindFiles - no Undo files even with changes`() {
        setContent(turnRow(21, canRewind = false, files = 2))
        assertEquals("the change chip still renders", 1, count("row_turn_changes"))
        assertEquals(0, count("row_turn_rewind"))
    }

    @Test
    fun `reverted turn withholds Undo files`() {
        setContent(turnRow(22, canRewind = true, files = 2, fileState = "reverted"))
        assertEquals(0, count("row_turn_rewind"))
    }

    @Test
    fun `running turn withholds Undo files`() {
        setContent(turnRow(23, canRewind = true, files = 2, turnState = "running"))
        assertEquals(0, count("row_turn_rewind"))
    }
}
