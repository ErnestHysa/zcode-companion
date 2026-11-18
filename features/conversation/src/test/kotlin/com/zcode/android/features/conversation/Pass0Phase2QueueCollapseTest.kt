package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.QueueState
import com.zcode.android.features.conversation.ui.QueueStrip
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P8-D (pass-0 Phase 2; stamps ×4, pass 18 s08/s09): the queue strip rendered
 * ALL items always-expanded — no collapsed state existed (the desktop's
 * ConversationQueuePanel HAS a collapse), so several queued items consumed the
 * window above the timeline (the F-P6-B geometry) and the strip's
 * `Queued (2) · paused` header clipped under the strip band above it.
 *
 * The pin: the header is a two-way toggle — collapsed leaves ONLY the header
 * (label + chevron; items, the Auto-send toggle and the follow-up chips leave the
 * composition), expanded restores them. Expanded by default (the pre-fix
 * behavior preserved).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase2QueueCollapseTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent() {
        rule.setContent {
            ZCodeTheme {
                QueueStrip(
                    queue = QueueState(
                        items = listOf("a", "b", "c").map { QueueState.QueueItem(it, "text $it", kind = null, dispatchState = "queued") },
                        autoDrain = false,
                        pauseReason = "rate limit",
                    ),
                    busy = false,
                    onSendNow = {},
                    onEdit = { _, _ -> },
                    onMove = { _, _ -> },
                    onSetAutoDrain = {},
                    followupMode = "queue",
                    onSetFollowupMode = {},
                    onDelete = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `F-P8-D - expanded by default - items, Auto-send and follow-up chips render`() {
        setContent()
        rule.onNodeWithTag("queue_item_a").assertIsDisplayed()
        rule.onNodeWithTag("queue_auto_drain_row").assertIsDisplayed()
        rule.onNodeWithTag("queue_followup_queue").assertIsDisplayed()
    }

    @Test
    fun `F-P8-D - the header toggle collapses the body - only the header remains`() {
        setContent()
        rule.onNodeWithTag("queue_strip_toggle").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("queue_item_a").assertCountEquals(0)
        rule.onAllNodesWithTag("queue_item_c").assertCountEquals(0)
        rule.onAllNodesWithTag("queue_auto_drain_row").assertCountEquals(0)
        rule.onAllNodesWithTag("queue_followup_queue").assertCountEquals(0)
        // The header (label + toggle) stays — the collapsed strip is one row.
        rule.onNodeWithTag("queue_strip_toggle").assertIsDisplayed()
    }

    @Test
    fun `F-P8-D - the toggle is two-way - expanding restores the body`() {
        setContent()
        rule.onNodeWithTag("queue_strip_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("queue_strip_toggle").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("queue_item_a").assertIsDisplayed()
        rule.onNodeWithTag("queue_auto_drain_row").assertIsDisplayed()
    }
}
