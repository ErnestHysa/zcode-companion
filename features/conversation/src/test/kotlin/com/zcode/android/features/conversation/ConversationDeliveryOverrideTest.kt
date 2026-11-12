package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.InputRouting
import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import com.zcode.android.features.conversation.ui.ComposerBar
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G49+G50 (vc52 Phase 13): the composer's per-send delivery choice.
 *
 * G49: the desktop's sendText carries a per-message `requestedDelivery`
 * ("startNow"|"queue"|"guide") override; the phone's CommandCoordinator
 * supported the key but ZERO UI sender existed (routing was purely the
 * session's inputRouting mode). The composer grows a delivery menu — a ONE-SHOT
 * override that rides the next send and clears after it.
 *
 * G50: the held-queue disposition dialog (clear vs keep — already shipped)
 * never sent the desktop's CAS set; the disposition choice now carries
 * `expectedHeldQueueItemIds` (the queue items the user saw when choosing).
 *
 * Pins: (1) the menu renders the three desktop values and fires a one-shot
 * override through onSend; (2) the CHOICE-mode disposition dialog hands the
 * queued ids beside the disposition word; (3) a null override sends nulls
 * (routing decides, byte-identical to today).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class ConversationDeliveryOverrideTest {

    @get:Rule
    val rule = createComposeRule()

    private fun state(routingMode: String = "startNow", queueItems: List<Pair<String, String>> = emptyList()) =
        ConversationUiState(
            rows = listOf(
                ConversationRow.UserInput(
                    rowId = 1, turnId = "t1",
                    base = ConversationRow.Base(createdAt = 1.0, createdAtSeq = 1),
                    text = "hi", epilogueStart = null, origin = "user", originMeta = null,
                    guided = false, sourceCommandId = null, attachments = emptyList(),
                    raw = JsonObject(emptyMap()),
                ),
            ),
            loading = false,
            taskState = TaskStateMachine.State.IDLE,
            phase = ConnectionPhase.CONNECTED,
            draft = "hello",
        )

    @Test
    fun `the delivery menu renders the three desktop values and fires a one-shot override`() {
        var sends = mutableListOf<Pair<String?, String?>>() // heldQueueDisposition to requestedDelivery
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = state(),
                    onDraftChange = {},
                    onSend = { held, delivery, _ -> sends.add(held to delivery) },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        // The user picks "queue" from the delivery menu, then sends.
        rule.onNodeWithTag("composer_delivery_menu").performClick()
        rule.onNodeWithTag("composer_delivery_queue").performClick()
        rule.onNodeWithTag("composer_send").performClick()
        assertEquals(1, sends.size)
        assertEquals("queue", sends[0].second)
        // The override is ONE-SHOT: the next send rides the routing mode again.
        rule.onNodeWithTag("composer_send").performClick()
        assertEquals(2, sends.size)
        assertEquals(null, sends[1].second)
    }

    @Test
    fun `a send without an override carries nulls (routing decides, byte-identical)`() {
        val sends = mutableListOf<Pair<String?, String?>>()
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = state(),
                    onDraftChange = {},
                    onSend = { held, delivery, _ -> sends.add(held to delivery) },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.onNodeWithTag("composer_send").performClick()
        assertEquals(1, sends.size)
        assertEquals(null, sends[0].first)
        assertEquals(null, sends[0].second)
    }

    @Test
    fun `CHOICE routing - the disposition dialog hands the queued ids beside the word`() {
        val sends = mutableListOf<Triple<String?, String?, List<String>?>>()
        val held = ConversationUiState(
            rows = emptyList(),
            loading = false,
            taskState = TaskStateMachine.State.IDLE,
            phase = ConnectionPhase.CONNECTED,
            draft = "hello",
            sessionState = SessionProjectionState(
                inputRouting = InputRouting(InputRouting.Mode.CHOICE, "held"),
                queue = QueueState(
                    items = listOf(
                        QueueState.QueueItem("q-1", "first queued", null, "queued"),
                        QueueState.QueueItem("q-2", "second queued", null, "queued"),
                    ),
                    autoDrain = false,
                    pauseReason = null,
                ),
            ),
        )
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = held,
                    onDraftChange = {},
                    onSend = { heldDisposition, delivery, ids -> sends.add(Triple(heldDisposition, delivery, ids)) },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.onNodeWithTag("composer_send").performClick()
        rule.onNodeWithTag("composer_disposition_keep").performClick()
        assertEquals(1, sends.size)
        assertEquals("keepQueueAndSend", sends[0].first)
        org.junit.Assert.assertEquals("the CAS ids ride the disposition choice", listOf("q-1", "q-2"), sends[0].third)
    }
}
