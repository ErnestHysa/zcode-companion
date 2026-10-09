package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * §35.3 UI suite for the conversation surface: row renderers (YOU/AGENT/tool/code/
 * reasoning/unknown fallback), the pinned state strips (attention/stale/history),
 * empty + loading, and the §30 a11y baseline (tool cards announce tool + state +
 * expand action; TalkBack-merge semantics).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConversationUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun userRow(text: String): ConversationRow.UserInput =
        ConversationRow.Decoder.decode(
            buildJsonObject {
                put("rowId", JsonPrimitive(1L))
                put("turnId", JsonPrimitive("t1"))
                put("kind", JsonPrimitive("userInput"))
                put("createdAt", JsonPrimitive(1.0))
                put("createdAtSeq", JsonPrimitive(1L))
                put("text", JsonPrimitive(text))
                put("origin", JsonPrimitive("user"))
                put("guided", JsonPrimitive(false))
            },
        ) as ConversationRow.UserInput

    private fun assistantRow(id: Long, text: String, state: String = "complete"): ConversationRow.AssistantText =
        ConversationRow.Decoder.decode(
            buildJsonObject {
                put("rowId", JsonPrimitive(id))
                put("turnId", JsonPrimitive("t1"))
                put("kind", JsonPrimitive("assistantText"))
                put("createdAt", JsonPrimitive(2.0))
                put("createdAtSeq", JsonPrimitive(id))
                put("assistantResponseId", JsonPrimitive("ar"))
                put("text", JsonPrimitive(text))
                put("state", JsonPrimitive(state))
            },
        ) as ConversationRow.AssistantText

    private fun toolRow(id: Long, name: String, status: String, input: String = "src/auth/session.kt"): ConversationRow.ToolCall =
        ConversationRow.Decoder.decode(
            buildJsonObject {
                put("rowId", JsonPrimitive(id))
                put("turnId", JsonPrimitive("t1"))
                put("kind", JsonPrimitive("toolCall"))
                put("createdAt", JsonPrimitive(3.0))
                put("createdAtSeq", JsonPrimitive(id))
                put("toolCallId", JsonPrimitive("call-$id"))
                put("toolName", JsonPrimitive(name))
                put("status", JsonPrimitive(status))
                put("inputText", JsonPrimitive(input))
            },
        ) as ConversationRow.ToolCall

    private fun unknownRow(): ConversationRow.Unknown =
        ConversationRow.Decoder.decode(
            buildJsonObject {
                put("rowId", JsonPrimitive(99L))
                put("turnId", JsonPrimitive("t1"))
                put("kind", JsonPrimitive("brandNewFutureKind"))
                put("createdAt", JsonPrimitive(4.0))
                put("createdAtSeq", JsonPrimitive(99L))
                put("mystery", JsonPrimitive(true))
            },
        ) as ConversationRow.Unknown

    private fun uiState(
        rows: List<ConversationRow>,
        taskState: TaskStateMachine.State = TaskStateMachine.State.RUNNING,
        streaming: Map<Long, String> = emptyMap(),
        stale: Boolean = false,
        loading: Boolean = false,
        hasMore: Boolean = false,
    ) = ConversationUiState(
        task = Task("s1", "Fix auth token refresh", TaskStatus.RUNNING, WorkspaceIdentityKey("/ws"), 1, 2),
        rows = rows,
        streaming = streaming,
        epoch = "e1",
        stale = stale,
        taskState = taskState,
        phase = ConnectionPhase.CONNECTED,
        loading = loading,
        hasMoreHistory = hasMore,
    )

    /**
     * FIELD-KILLER #10 (2026-09-30, vc14 field, repro'd on the emulator): RE-ENTRY with
     * cached rows laid the list out at index 0, `atBottom` read false, and the tail
     * anchor was SKIPPED — the conversation opened at the TOP of the window, the
     * prefetch fired spuriously ("loading earlier messages" flash), and "jump to
     * newest" showed. The first non-empty layout must anchor to the tail
     * unconditionally, and the prefetch must not fire before that anchor.
     */
    @Test
    fun reentry_withCachedRows_anchorsToTail_andDoesNotPrefetch() {
        var loadOlderCalls = 0
        val rows = (1L..60L).map { assistantRow(it, "message $it") }
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = uiState(rows, hasMore = true),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> loadOlderCalls++ },
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
        // Anchored at the TAIL: the newest row is composed and displayed; the oldest
        // rows are far above and NOT composed by the LazyColumn.
        rule.onNodeWithTag("conv-row-60").assertIsDisplayed()
        rule.onAllNodesWithTag("conv-row-1").assertCountEquals(0)
        // The prefetch must NOT have fired from the transient index-0 layout.
        rule.waitForIdle()
        kotlin.test.assertEquals(0, loadOlderCalls, "prefetch must not fire from the unanchored initial layout")
    }

    private fun setContent(state: ConversationUiState) {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = state,
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
    }

    @Test
    fun timeline_rendersYouAgentToolInTimelineGrammar() {
        setContent(
            uiState(
                listOf(
                    userRow("The refresh token path returns 401 after midnight UTC."),
                    assistantRow(2, "Inspecting session refresh flow and token clock-skew handling."),
                    toolRow(3, "read_file", "success"),
                    toolRow(4, "run_tests", "running", "session module unit tests"),
                    assistantRow(5, "Applying a 30s skew margin…", state = "streaming"),
                ),
            ),
        )
        // Tail-first render (§19.4): the viewport rests on the LAST row.
        rule.onNodeWithText("Applying a 30s skew margin…").assertIsDisplayed()
        rule.onNodeWithText("Generating").assertIsDisplayed() // §19.2 stream indicator
        rule.onNodeWithTag("conversation_list").assertIsDisplayed()
        // Earlier rows render on scroll-back (lazy composition — §19.2 render window).
        rule.onNodeWithTag("conversation_list").performScrollToNode(hasTestTag("row_tool_read_file"))
        rule.onNodeWithTag("row_tool_read_file").assertIsDisplayed()
        rule.onNodeWithText("Completed").assertIsDisplayed()
        rule.onNodeWithTag("conversation_list").performScrollToNode(hasText("The refresh token path returns 401 after midnight UTC."))
        rule.onNodeWithText("The refresh token path returns 401 after midnight UTC.").assertIsDisplayed()
    }

    @Test
    fun streamingText_leadsThePersistedRow_displayedAsBasePlusSuffix() {
        // §19.2 merge: the store row ("par") lags the coalesced buffer — the display
        // is the persisted base + the buffered suffix (store-flushed case covered by
        // the device E2E where row.text already ends with the buffered suffix).
        setContent(uiState(listOf(assistantRow(2, "par")), streaming = mapOf(2L to "tial text so far")))
        rule.onNodeWithText("partial text so far").assertIsDisplayed()
    }

    @Test
    fun markdownRenders_codeFences_asCodeBlocks() {
        setContent(uiState(listOf(assistantRow(2, "Look:\n```kotlin\nif (now > exp) {\n  return Unauthorized\n}\n```"))))
        // The fenced block renders as a CODE block (display-asserts on emulator
        // screenshots; Robolectric's small viewport clips it below the fold).
        // The code block sits INSIDE the merged AgentProse semantics → unmerged tree.
        // Children Text merges into the AgentProse node (merged tree search):
        // the fence rendered as a CODE block with language overline + code body.
        rule.onNodeWithText("CODE · kotlin").assertExists()
        rule.onAllNodes(hasText("if (now > exp) {", substring = true)).onFirst().assertExists()
    }

    @Test
    fun unknownToolAndUnknownKind_fallBack_generic_neverBlank() {
        setContent(
            uiState(
                listOf(
                    toolRow(3, "brand_new_tool", "unknown-status"),
                    unknownRow(),
                ),
            ),
        )
        rule.onNodeWithTag("row_tool_brand_new_tool").assertIsDisplayed() // generic card (§19.5)
        rule.onNodeWithTag("row_unknown").assertIsDisplayed()
    }

    @Test
    fun attentionStrip_whenWaiting_phase6ObserveOnly() {
        setContent(uiState(listOf(userRow("go")), taskState = TaskStateMachine.State.WAITING_FOR_PERMISSION))
        rule.onNodeWithTag("conversation_attention_strip").assertIsDisplayed()
        rule.onNodeWithText("Needs attention").assertIsDisplayed()
        // No approve/deny controls exist in Phase 6 (Phase 7 boundary, §43.2).
        rule.onAllNodesWithTag("approve_button").assertCountEquals(0)
    }

    @Test
    fun staleStrip_labeledCachedProjection() {
        setContent(uiState(listOf(userRow("go")), stale = true))
        rule.onNodeWithTag("conversation_stale_strip").assertIsDisplayed()
        rule.onNodeWithText("Showing cached projection · recovering").assertIsDisplayed()
    }

    @Test
    fun historyChangedStrip_onEpochMove() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = uiState(listOf(userRow("go"))).copy(historyChanged = true),
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
        rule.onNodeWithTag("conversation_history_strip").assertIsDisplayed()
    }

    @Test
    fun loading_showsSkeletons() {
        setContent(uiState(emptyList(), loading = true))
        rule.onAllNodesWithTag("conversation_skeleton").assertCountEquals(4)
    }

    @Test
    fun empty_showsGuidance() {
        setContent(uiState(emptyList(), taskState = TaskStateMachine.State.IDLE))
        rule.onNodeWithTag("conversation_empty").assertIsDisplayed()
    }

    @Test
    fun a11y_toolCardAnnouncesToolStateAndExpandAction() {
        setContent(uiState(listOf(toolRow(3, "read_file", "success"))))
        rule.onNodeWithTag("row_tool_read_file").assert(
            SemanticsMatcher("has content description") { node ->
                node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
            },
        )
    }
}
