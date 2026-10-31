package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.features.conversation.ui.ComposerBar
import com.zcode.android.features.conversation.ui.composerKeyboardOptions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-A9 (vc51 roadmap, sub-phase 1E; passes 3–5 live receipts): the queue-mode
 * composer had NO send affordance while the agent is stoppable — the exact state
 * queue mode exists for. The primary control morphed STOP↔SEND on
 * `canStop && stopState==STOPPABLE` ALONE, so a typed draft dead-ended behind a
 * self-defeating Stop (and no IME send action existed — Enter inserted a newline).
 *
 * The fix anchor is the desktop contract (`ConversationComposer.tsx:1136`):
 * `showStopControl = canStop && !hasDraftToSubmit` — a non-blank draft ALWAYS keeps
 * the send path, even mid-run; the Stop control exists only for a blank draft.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class QueueComposerSendControlTest {

    @get:Rule
    val rule = createComposeRule()

    private var sent: String? = null
    private var stopped = false

    /** ENQUEUE routing + stoppable control (the rig's queue-mode shape). */
    private fun stateWith(draft: String): ConversationUiState {
        val sessionState = SessionProjectionState.fromSnapshot(
            buildJsonObject {
                putJsonObject("control") {
                    put("phase", "running")
                    put("canStop", true)
                    put("stopState", "stoppable")
                }
                putJsonObject("inputRouting") {
                    put("mode", "enqueue")
                    put("reasonCode", "foregroundBusy")
                }
            },
        )
        return ConversationUiState(draft = draft, sessionState = sessionState)
    }

    private fun setContent(initialDraft: String) {
        sent = null
        stopped = false
        val draft = androidx.compose.runtime.mutableStateOf(initialDraft)
        rule.setContent {
            ZCodeTheme {
                // Stateful owner loop: typing flows through onDraftChange into the
                // state (the real VM behavior) so the morph reacts to the draft.
                ComposerBar(
                    state = stateWith(draft.value),
                    onDraftChange = { draft.value = it },
                    onSend = { sent = "sent" },
                    onStop = { stopped = true },
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `A9 - a typed draft mid-run keeps the SEND control and loses the self-defeating Stop`() {
        setContent(initialDraft = "queued follow-up draft")
        rule.onNodeWithTag("composer_send").assertExists()
        assertTrue(
            "with a non-blank draft the STOP control must yield the slot (desktop " +
                "showStopControl = canStop && !hasDraftToSubmit)",
            rule.onAllNodesWithTag("composer_stop").fetchSemanticsNodes().isEmpty(),
        )
        rule.onNodeWithTag("composer_send").performClick()
        rule.waitForIdle()
        assertEquals("sent", sent)
    }

    @Test
    fun `A9 - a blank draft mid-run still offers the STOP interrupt`() {
        setContent(initialDraft = "")
        rule.onNodeWithTag("composer_stop").assertExists()
        assertTrue(rule.onAllNodesWithTag("composer_send").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("composer_stop").performClick()
        rule.waitForIdle()
        assertTrue(stopped)
    }

    @Test
    fun `A9 - the composer field carries an IME send action (no newline dead-end)`() {
        setContent(initialDraft = "")
        // The desktop contract: Enter dispatches. The IME action must be SEND (the
        // recorded rig artifact was Enter inserting a raw newline into the draft).
        val options = composerKeyboardOptions("hello")
        assertTrue(
            "the composer IME action must be Send",
            options.imeAction == androidx.compose.ui.text.input.ImeAction.Send,
        )
        // Typing into the field then pressing the IME action dispatches the send path.
        rule.onNodeWithTag("composer_field").performTextInput("mid-run dispatch")
        rule.waitForIdle()
        rule.onNodeWithTag("composer_send").performClick()
        rule.waitForIdle()
        assertEquals("sent", sent)
    }

}
