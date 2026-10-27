package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FIELD regression (2026-10-04, vc32 Samsung S24 — "an overlay blocks the chat"):
 * `SessionModeChips` chained `.fillMaxWidth()` UNDER the caller's
 * `weight(1f, fill = false)`, capping it at the weighted share; the chips wrapped
 * one character per column to hundreds of dp, the header row swallowed the screen,
 * the Files/Git/Terminal chips landed mid-screen (CenterVertically) and the
 * weight(1f) timeline collapsed to zero. Invisible to the existing suites because
 * every test passed `config = null` (the chips never composed). This test renders
 * the screen WITH a config-bearing session state and asserts the geometry:
 * single-line chips, header-row coherence, and a live timeline.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SessionChipsLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private fun stateWithConfig() = ConversationUiState(
        rows = listOf(
            // One placeholder row so TimelineBody (and its LazyColumn) composes.
            com.zcode.android.domain.model.ConversationRow.Unknown(
                rowId = 1,
                turnId = "t1",
                unknownKind = "test-row",
                raw = kotlinx.serialization.json.JsonObject(emptyMap()),
            ),
        ),
        loading = false,
        taskState = TaskStateMachine.State.RUNNING,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
        sessionState = SessionProjectionState(
            config = SessionConfigState(
                provider = "zai",
                model = "glm-5.3",
                thought = "high",
                thoughtLevels = listOf("high"),
                followupMode = null,
                mode = "yolo",
                planEnabled = null,
                modelSelectionProviderId = null,
                modelSelectionModelId = null,
            ),
        ),
    )

    @Test
    fun configChips_singleLine_inHeaderRow_timelineAlive() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = stateWithConfig(),
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
        // F-CHAT-FOCUS v2 (2026-10-27): the chips live BEHIND the bar's config chip —
        // expand it, pin the ORIGINAL geometry contract, then collapse it back (a true
        // two-way toggle).
        rule.onNodeWithTag("conv_session_config").assertIsDisplayed()
        rule.onAllNodesWithTag("conv_mode_chip").assertCountEquals(0)
        rule.onNodeWithTag("conv_session_config").performClick()
        rule.waitForIdle()
        val density = rule.density.density
        val modeChip = rule.onNodeWithTag("conv_mode_chip").fetchSemanticsNode().boundsInRoot
        val modelChip = rule.onNodeWithTag("conv_model_chip").fetchSemanticsNode().boundsInRoot
        val list = rule.onNodeWithTag("conversation_list").fetchSemanticsNode().boundsInRoot

        // §30.2-class geometry: chips stay single-line (never explode vertically).
        assertTrue("mode chip crushed/wrapped: $modeChip", modeChip.height / density <= 48f)
        assertTrue("model chip crushed/wrapped: $modelChip", modelChip.height / density <= 48f)
        // Material AssistChip floor (40dp): readable, never a one-character column.
        assertTrue("mode chip invisible-crushed: $modeChip", modeChip.width / density >= 40f)

        // Mode and model chips must share ONE row (the vc32 crushed-chip class).
        val modeCenter = modeChip.center.y / density
        assertTrue(
            "mode and model chips must share the row (Δ=${kotlin.math.abs(modelChip.center.y / density - modeCenter)}dp)\n" +
                "mode=$modeChip\nmodel=$modelChip\nlist=$list density=$density",
            kotlin.math.abs(modelChip.center.y / density - modeCenter) <= 48f,
        )

        // The timeline survives with real height (the field bug collapsed it to 0).
        assertTrue("timeline collapsed: $list", list.height / density >= 120f)

        // §10.3: the mode picker renders ALL FOUR verified modes (build/edit/plan/
        // yolo — command.ts:216). FIELD (vc34): the user reported seeing three — the
        // clipped-DropdownMenu class; the dialog lists every option un-clipped.
        // GAP-17: rows render the DESKTOP titles (mode.label.glm.*) — pinned here.
        rule.onNodeWithTag("conv_mode_chip").performClick()
        rule.waitForIdle()
        for (title in listOf("Ask before changes", "Edit automatically", "Plan mode", "Full access")) {
            rule.onAllNodesWithText(title).onFirst().assertExists()
        }
    }

    /**
     * F-CHAT-FOCUS (field 2026-10-27): with a session config present, the header
     * defaults to the ONE compact summary line (mode · provider/model · effort) and
     * the interactive chip row is ABSENT until the summary is tapped — the collapsed
     * header must never swallow the chat viewport (the ~28 %-timeline field report).
     */
    @Test
    fun configSummary_collapsedByDefault_expandsToChipRow() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = stateWithConfig(),
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
        // Collapsed: the bar's config chip carries the echo; no chips.
        rule.onNodeWithTag("conv_session_bar").assertIsDisplayed()
        rule.onNodeWithTag("conv_session_config").assertIsDisplayed()
        rule.onNodeWithText("yolo · zai/glm-5.3 · high").assertIsDisplayed()
        rule.onAllNodesWithTag("conv_mode_chip").assertCountEquals(0)
        rule.onAllNodesWithTag("conv_effort_chip").assertCountEquals(0)
        rule.onAllNodesWithTag("conv_model_chip").assertCountEquals(0)
        // The timeline keeps real height even with goal + todos + plans armed.
        val list = rule.onNodeWithTag("conversation_list").fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        assertTrue("timeline collapsed under the header: $list", list.height / density >= 120f)
        // Expand → the interactive chip row appears; collapse → gone again (two-way).
        rule.onNodeWithTag("conv_session_config").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("conv_mode_chip").assertIsDisplayed()
        rule.onNodeWithTag("conv_model_chip").assertIsDisplayed()
        rule.onNodeWithTag("conv_session_config").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("conv_mode_chip").assertCountEquals(0)
        // The Context affordance opens the session context sheet (where Files/Git/
        // Terminal/Model calls/Share now live).
        rule.onNodeWithTag("conv_session_context").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("session_context_sheet").assertIsDisplayed()
    }
}
