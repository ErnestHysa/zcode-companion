package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
 * I2.4 (Phase 6A) UI pins: a `timelineMarker` row with `marker.type == "compact"` renders
 * the context-compaction lifecycle notice (desktop `chat.contextCompaction.*`) instead of
 * the generic quiet row; the failed phase offers Retry which fires the screen callback
 * once; non-compact markers keep the generic quiet row.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class CompactionNoticeUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun compactMarker(rowId: Long, status: String, origin: String, tokens: String = "") =
        ConversationRow.Decoder.decode(
            Json.parseToJsonElement(
                """{"rowId":$rowId,"turnId":"t1","kind":"timelineMarker","entityId":"ent-$rowId",
                    "createdAt":1700000000000,"createdAtSeq":$rowId,"lane":"turnTailBoundary",
                    "marker":{"type":"compact","status":"$status","origin":"$origin"$tokens}}""",
            ).jsonObject,
        )

    private fun modelChangeMarker() = ConversationRow.Decoder.decode(
        Json.parseToJsonElement(
            """{"rowId":99,"turnId":"t1","kind":"timelineMarker","entityId":"ent-99",
                "createdAt":1700000000000,"createdAtSeq":99,
                "marker":{"type":"modelChange","toProvider":"zai","toModel":"glm-5.1"}}""",
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
                    onRetryCompaction = { calls.add("compact-retry") },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `each lifecycle phase renders its desktop copy - started`() {
        setContent(compactMarker(41, "running", "auto"))
        rule.onNodeWithTag("row_compaction").assertIsDisplayed()
        rule.onNodeWithText("Compressing context").assertIsDisplayed()
    }

    @Test
    fun `auto terminal success renders completedAuto`() {
        setContent(compactMarker(42, "completed", "auto", ""","tokensBefore":180000,"tokensAfter":42000"""))
        rule.onNodeWithText("Context automatically compressed").assertIsDisplayed()
        rule.onNodeWithText("180K → 42K tokens").assertIsDisplayed()
    }

    @Test
    fun `failed renders the desktop failure copy with a Retry affordance that fires once`() {
        setContent(compactMarker(43, "failed", "manual"))
        rule.onNodeWithText("Context compression failed").assertIsDisplayed()
        rule.onNodeWithTag("compaction_retry").assertIsDisplayed()
        rule.onNodeWithTag("compaction_retry").performClick()
        rule.waitForIdle()
        assertEquals(listOf("compact-retry"), calls)
    }

    @Test
    fun `non-compact markers keep the generic quiet row`() {
        setContent(modelChangeMarker())
        rule.onAllNodesWithTag("row_compaction").assertCountEquals(0)
        rule.onNodeWithTag("row_other").assertIsDisplayed()
    }

    @Test
    fun `skipped and interrupted phases render their copy`() {
        setContent(compactMarker(44, "noop", "manual"), compactMarker(45, "cancelled", "manual"))
        rule.onNodeWithText("Context is up to date; no compression needed").assertIsDisplayed()
        rule.onNodeWithText("Context compression interrupted").assertIsDisplayed()
    }
}
