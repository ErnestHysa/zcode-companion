package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.components.messageTimeLabel
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-13 (android-native-gaps.md): user + assistant rows render a compact time label
 * DERIVED READ-ONLY from that row's `base.createdAt` (desktop:
 * ConversationRowView.tsx:1469-1472 — subtle label, no renderer state; formatter
 * decision table pinned in MessageTimeLabelTest). Rows with no valid createdAt render
 * NO label — never a fabricated "0:00".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RowTimestampUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun userRow(id: Long, text: String, createdAtMs: Double): ConversationRow.UserInput =
        ConversationRow.Decoder.decode(
            buildJsonObject {
                put("rowId", JsonPrimitive(id))
                put("turnId", JsonPrimitive("t1"))
                put("kind", JsonPrimitive("userInput"))
                put("createdAt", JsonPrimitive(createdAtMs))
                put("createdAtSeq", JsonPrimitive(id))
                put("text", JsonPrimitive(text))
                put("origin", JsonPrimitive("user"))
                put("guided", JsonPrimitive(false))
            },
        ) as ConversationRow.UserInput

    private fun assistantRow(id: Long, text: String, createdAtMs: Double): ConversationRow.AssistantText =
        ConversationRow.Decoder.decode(
            buildJsonObject {
                put("rowId", JsonPrimitive(id))
                put("turnId", JsonPrimitive("t1"))
                put("kind", JsonPrimitive("assistantText"))
                put("createdAt", JsonPrimitive(createdAtMs))
                put("createdAtSeq", JsonPrimitive(id))
                put("assistantResponseId", JsonPrimitive("ar-$id"))
                put("text", JsonPrimitive(text))
                put("state", JsonPrimitive("complete"))
            },
        ) as ConversationRow.AssistantText

    private fun uiState(rows: List<ConversationRow>) = ConversationUiState(
        task = Task("s1", "Fix auth token refresh", TaskStatus.RUNNING, WorkspaceIdentityKey("/ws"), 1, 2),
        rows = rows,
        epoch = "e1",
        taskState = TaskStateMachine.State.RUNNING,
        phase = ConnectionPhase.CONNECTED,
    )

    private fun setContent(rows: List<ConversationRow>) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = uiState(rows),
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

    /**
     * The label must derive from the row's own createdAt. The renderer reads the
     * wall clock internally, so the assertion accepts the same-day label OR the
     * yesterday label (a midnight crossing between fixture build and render) —
     * both are computed with the SAME pure formatter the renderer uses.
     */
    private fun expectedLabels(createdAtMs: Double, nowMs: Long): Set<String> = setOf(
        messageTimeLabel(createdAtMs, nowMs, yesterdayWord = "Yesterday"),
        messageTimeLabel(createdAtMs, nowMs + 120_000, yesterdayWord = "Yesterday"),
    ).filterNotNull().toSet()

    @Test
    fun userAndAssistantRows_renderTimeLabelDerivedFromCreatedAt() {
        val now = System.currentTimeMillis()
        val userTs = now - 60_000.0
        val agentTs = now - 30_000.0
        setContent(
            listOf(
                userRow(1, "please fix the login bug", userTs),
                assistantRow(2, "On it — checking the auth flow now.", agentTs),
            ),
        )
        // One label per row kind, each matching the pure formatter for ITS row's ts.
        val userLabels = expectedLabels(userTs, now)
        val agentLabels = expectedLabels(agentTs, now)
        val userNodes = rule.onAllNodesWithTag("row_time_user").onFirst()
        val agentNodes = rule.onAllNodesWithTag("row_time_agent").onFirst()
        // fetchSemanticsNode forces existence; the text check pins derivation.
        val renderedUser = userNodes.fetchSemanticsNode().let {
            it.config.get(androidx.compose.ui.semantics.SemanticsProperties.Text).first().text
        }
        val renderedAgent = agentNodes.fetchSemanticsNode().let {
            it.config.get(androidx.compose.ui.semantics.SemanticsProperties.Text).first().text
        }
        assertTrue(
            "user label '$renderedUser' must be derived from createdAt ($userLabels)",
            renderedUser in userLabels,
        )
        assertTrue(
            "agent label '$renderedAgent' must be derived from createdAt ($agentLabels)",
            renderedAgent in agentLabels,
        )
    }

    @Test
    fun rowWithoutValidTimestamp_rendersNoLabel() {
        setContent(listOf(userRow(1, "ancient row", 0.0)))
        rule.onAllNodesWithTag("row_time_user").assertCountEquals(0)
        rule.onNodeWithText("please fix the login bug").assertExists()
    }
}
