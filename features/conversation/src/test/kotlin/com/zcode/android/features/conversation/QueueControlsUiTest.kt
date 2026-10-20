package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.QueueState
import com.zcode.android.features.conversation.ui.QueueStrip
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * I1 (Phase 5A) UI pins over the queue strip: move-up is disabled on the first row and
 * move-down on the last; taps carry the row id + direction; the Auto-send switch reflects
 * the wire's `autoDrain` and fires the toggle; the follow-up chips mark the current mode
 * and fire ONLY on a change (re-tapping the selected chip sends nothing).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class QueueControlsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun queue(autoDrain: Boolean? = true) = QueueState(
        items = listOf("a", "b", "c").map { QueueState.QueueItem(it, "text $it", kind = null, dispatchState = "queued") },
        autoDrain = autoDrain,
        pauseReason = null,
    )

    private fun setContent(autoDrain: Boolean? = true, followup: String? = "queue") {
        calls.clear()
        rule.setContent {
            ZCodeTheme {
                QueueStrip(
                    queue = queue(autoDrain), busy = false, onSendNow = {}, onEdit = { _, _ -> },
                    onMove = { id, d -> calls += "move:$id:$d" },
                    onSetAutoDrain = { calls += "drain:$it" },
                    followupMode = followup,
                    onSetFollowupMode = { calls += "followup:$it" },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `move buttons are edge-disabled and carry id and direction`() {
        setContent()
        rule.onNodeWithTag("queue_move_up_a").assertIsNotEnabled()
        rule.onNodeWithTag("queue_move_down_c").assertIsNotEnabled()
        rule.onNodeWithTag("queue_move_down_a").assertIsEnabled().performClick()
        rule.onNodeWithTag("queue_move_up_c").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(listOf("move:a:1", "move:c:-1"), calls)
    }

    @Test
    fun `auto-send switch reflects the wire and fires the toggle`() {
        setContent(autoDrain = false)
        rule.onNodeWithTag("queue_auto_drain").assertIsOff().performClick(); rule.waitForIdle()
        assertEquals(listOf("drain:true"), calls)
    }

    @Test
    fun `auto-send switch is hidden when the wire carries no autoDrain`() {
        setContent(autoDrain = null)
        rule.onAllNodesWithTag("queue_auto_drain").assertCountEquals(0)
    }

    @Test
    fun `followup chips mark the mode and fire only on change`() {
        setContent(followup = "queue")
        rule.onNodeWithTag("queue_followup_queue").performClick(); rule.waitForIdle()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithTag("queue_followup_guide").performClick(); rule.waitForIdle()
        assertEquals(listOf("followup:guide"), calls)
    }
}
