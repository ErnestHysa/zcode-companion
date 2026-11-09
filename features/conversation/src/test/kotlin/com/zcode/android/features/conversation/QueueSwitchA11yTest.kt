package com.zcode.android.features.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.QueueState
import com.zcode.android.features.conversation.ui.QueueStrip
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BC-1 + N-BC-3 (pass 27 a11y audit + hit-target count): the queue strip's Auto-send
 * Switch had no label association (an unlabeled clickable — TalkBack read nothing),
 * and the Move-up/Move-down buttons were 32dp wide (under the 48dp floor at every
 * scale). Pins: the label+switch share ONE toggleable row (the label tap toggles and
 * the row exposes the checked state), and the reorder buttons measure ≥48dp.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class QueueSwitchA11yTest {

    @get:Rule
    val rule = createComposeRule()

    private fun queue() = QueueState(
        items = listOf(QueueState.QueueItem("q-1", "queued text", null, "queued")),
        autoDrain = false,
        pauseReason = null,
    )

    @Test
    fun `N-BC-1 - the Auto-send label toggles and the row exposes the checked state`() {
        // STATEFUL fixture: the wire echo flips the row's toggleable value (the app
        // contract — the switch is display-only, the echo is the truth).
        var autoDrain by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            ZCodeTheme {
                QueueStrip(
                    queue = queue().copy(autoDrain = autoDrain),
                    busy = false,
                    onSendNow = {},
                    onEdit = { _, _ -> },
                    onMove = null,
                    onSetAutoDrain = { autoDrain = it },
                )
            }
        }
        rule.waitForIdle()
        // The row exposes the toggleable/checked state (pre-fix: an unlabeled clickable).
        val row = rule.onNodeWithTag("queue_auto_drain_row").fetchSemanticsNode().config
        assertEquals(
            "the row must expose the switch's checked state",
            androidx.compose.ui.state.ToggleableState.Off,
            row[SemanticsProperties.ToggleableState],
        )
        // THE BUG: the label tap produced zero events — only the switch was the target.
        rule.onNodeWithText("Auto-send").performClick()
        rule.waitForIdle()
        assertEquals("the label tap must toggle auto-drain", true, autoDrain)
        // The row itself stays wired through the same toggle (display-only inner switch).
        rule.onNodeWithTag("queue_auto_drain_row").performClick()
        rule.waitForIdle()
        assertEquals("a second tap must toggle it back off", false, autoDrain)
    }

    @Test
    fun `N-BC-3 - the queue reorder buttons meet the 48dp width floor`() {
        rule.setContent {
            ZCodeTheme {
                QueueStrip(
                    queue = queue(),
                    busy = false,
                    onSendNow = {},
                    onEdit = { _, _ -> },
                    onMove = { _, _ -> },
                    onSetAutoDrain = {},
                )
            }
        }
        rule.waitForIdle()
        val density = rule.density.density
        for (tag in listOf("queue_move_up_q-1", "queue_move_down_q-1")) {
            val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$tag width ${b.width / density}dp is under the 48dp floor",
                b.width >= 48f * density - 0.5f,
            )
        }
    }
}
