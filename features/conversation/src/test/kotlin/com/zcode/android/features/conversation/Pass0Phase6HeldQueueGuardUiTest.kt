package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.InputRouting
import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ComposerBar
import com.zcode.android.features.conversation.ui.SessionContextSheet
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pass-0 Phase 6 — the UI half of the held-queue guard family.
 *
 * F-P20-A (composer, pass-22 Y.E receipt): the G50 dialog fired for /compact
 * sends and the answered disposition was silently DISCARDED (the host's compact
 * handler consults the routing mode, never a trio — the desktop never asks for
 * compact). Post-fix the compact dispatch SKIPS the ask (desktop parity): the
 * send rides nulls and self-queues host-side; no answer can be discarded because
 * none is requested. Plain-text sends under choice keep the ask (control).
 *
 * F-P19-A (composer re-open): the ViewModel's re-confirmation epoch re-arms the
 * disposition dialog — the desktop's confirmationRequired face with ids re-read
 * from the live projection at answer time (the silent-retry loop parity).
 *
 * F-P20-A (sheet, pass-20 W.D receipt): the context sheet's Set-goal bypassed
 * send() with NO choice gate — under choice the host structurally refused every
 * sheet goal (heldQueueDispositionRequired) and the generic ERROR was all the
 * user got. Post-fix the sheet runs the SAME gate: under choice the disposition
 * dialog fires first and the answer rides the wire; without a held queue the
 * legacy shape stays.
 */
@RunWith(AndroidJUnit4::class)
// NO w*dp qualifier: a width-qualified Robolectric config never idles the M3
// dialog-over-sheet stack (the phase-5 rig learning — GoalAffordancesUiTest's
// precedent); the default device profile renders both dialogs and idles.
@Config(sdk = [30])
class Pass0Phase6HeldQueueGuardUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun choiceQueueState(draft: String, heldQueueReconfirmation: Int = 0) = ConversationUiState(
        rows = emptyList(),
        loading = false,
        taskState = TaskStateMachine.State.IDLE,
        phase = ConnectionPhase.CONNECTED,
        draft = draft,
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
        heldQueueReconfirmation = heldQueueReconfirmation,
    )

    private fun startNowState(draft: String) = ConversationUiState(
        rows = emptyList(),
        loading = false,
        taskState = TaskStateMachine.State.IDLE,
        phase = ConnectionPhase.CONNECTED,
        draft = draft,
    )

    // ------------------------------------------------------------------ F-P20-A composer

    @Test
    fun `F-P20-A - a COMPACT dispatch under choice skips the disposition ask and sends nulls (the answer is never asked, never discarded)`() {
        val sends = mutableListOf<Triple<String?, String?, List<String>?>>()
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = choiceQueueState(draft = "/compact"),
                    onDraftChange = {},
                    onSend = { d, v, ids -> sends.add(Triple(d, v, ids)) },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.onNodeWithTag("composer_send").performClick()
        rule.waitForIdle()
        // Compact never asks the disposition question (desktop parity — vMc has no trio path).
        rule.onAllNodesWithTag("composer_disposition_clear").assertCountEquals(0)
        assertEquals(1, sends.size, "the compact went straight out")
        assertEquals(null, sends[0].first, "no trio — the host self-queues the compact under choice either way")
        assertEquals(null, sends[0].third)
    }

    @Test
    fun `F-P20-A - a compress alias dispatch skips the ask too (the same compact word)`() {
        val sends = mutableListOf<Triple<String?, String?, List<String>?>>()
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = choiceQueueState(draft = "/compress"),
                    onDraftChange = {},
                    onSend = { d, v, ids -> sends.add(Triple(d, v, ids)) },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.onNodeWithTag("composer_send").performClick()
        rule.onAllNodesWithTag("composer_disposition_keep").assertCountEquals(0)
        assertEquals(listOf<Triple<String?, String?, List<String>?>>(Triple(null, null, null)), sends)
    }

    @Test
    fun `F-P20-A - a GOAL slash dispatch under choice KEEPS the ask (its admission consumes the trio)`() {
        val sends = mutableListOf<Triple<String?, String?, List<String>?>>()
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = choiceQueueState(draft = "/goal ship it"),
                    onDraftChange = {},
                    onSend = { d, v, ids -> sends.add(Triple(d, v, ids)) },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.onNodeWithTag("composer_send").performClick()
        rule.onNodeWithTag("composer_disposition_keep").performClick()
        assertEquals(1, sends.size)
        assertEquals("keepQueueAndSend", sends[0].first, "the goal dialog answer must reach the VM")
        assertEquals(listOf("q-1", "q-2"), sends[0].third)
    }

    @Test
    fun `F-P20-A - plain text under choice keeps the ask (the existing G50 contract, control)`() {
        val sends = mutableListOf<Triple<String?, String?, List<String>?>>()
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = choiceQueueState(draft = "hello there"),
                    onDraftChange = {},
                    onSend = { d, v, ids -> sends.add(Triple(d, v, ids)) },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.onNodeWithTag("composer_send").performClick()
        rule.onNodeWithTag("composer_disposition_clear").performClick()
        assertEquals(1, sends.size)
        assertEquals("clearQueueAndSend", sends[0].first)
    }

    @Test
    fun `F-P20-A - compact outside choice never asked anything anyway (control)`() {
        val sends = mutableListOf<Triple<String?, String?, List<String>?>>()
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = startNowState(draft = "/compact"),
                    onDraftChange = {},
                    onSend = { d, v, ids -> sends.add(Triple(d, v, ids)) },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.onNodeWithTag("composer_send").performClick()
        assertEquals(listOf<Triple<String?, String?, List<String>?>>(Triple(null, null, null)), sends)
    }

    // ------------------------------------------------------------------ F-P19-A composer re-open

    @Test
    fun `F-P19-A - the re-confirmation epoch re-arms the disposition dialog (confirmationRequired parity)`() {
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = choiceQueueState(draft = "p19cas", heldQueueReconfirmation = 1),
                    onDraftChange = {},
                    onSend = { _, _, _ -> },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("composer_disposition_keep")
            .assertExists("the dialog re-armed itself from the epoch bump")
    }

    @Test
    fun `F-P19-A - no epoch means no self-armed dialog (control)`() {
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = choiceQueueState(draft = "p19cas"),
                    onDraftChange = {},
                    onSend = { _, _, _ -> },
                    onStop = {},
                    onRetrySend = {},
                    onDismissNotice = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("composer_disposition_keep").assertCountEquals(0)
    }

    // ------------------------------------------------------------------ F-P20-A sheet gate

    @Test
    fun `F-P20-A - the sheet Set-goal under choice gates on the disposition dialog and hands the trio to the VM`() {
        val sets = mutableListOf<Triple<String, String?, List<String>?>>()
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(
                        inputRouting = InputRouting(InputRouting.Mode.CHOICE, "held"),
                        queue = QueueState(
                            items = listOf(QueueState.QueueItem("q-1", "first", null, "queued")),
                            autoDrain = false,
                            pauseReason = null,
                        ),
                    ),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                    onSetGoal = { objective, disposition, ids -> sets.add(Triple(objective, disposition, ids)) },
                )
            }
        }
        rule.onNodeWithTag("goal_set_row").performClick()
        rule.onNodeWithTag("goal_set_field").performTextInput("finish the ledger")
        rule.onNodeWithTag("goal_set_confirm").performClick()
        rule.waitForIdle()
        // The choice gate fires BEFORE any wire: the disposition dialog asks first.
        rule.onNodeWithTag("composer_disposition_keep").performClick()
        rule.waitForIdle()
        assertEquals(
            listOf(Triple<String, String?, List<String>?>("finish the ledger", "keepQueueAndSend", listOf("q-1"))),
            sets,
            "the sheet's answered trio rides the goal send — the pass-20 structural refusal is gated",
        )
    }

    @Test
    fun `F-P20-A - the sheet Set-goal clear answer carries clearQueueAndSend`() {
        val sets = mutableListOf<Triple<String, String?, List<String>?>>()
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(
                        inputRouting = InputRouting(InputRouting.Mode.CHOICE, "held"),
                        queue = QueueState(
                            items = listOf(QueueState.QueueItem("q-1", "first", null, "queued")),
                            autoDrain = false,
                            pauseReason = null,
                        ),
                    ),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                    onSetGoal = { objective, disposition, ids -> sets.add(Triple(objective, disposition, ids)) },
                )
            }
        }
        rule.onNodeWithTag("goal_set_row").performClick()
        rule.onNodeWithTag("goal_set_field").performTextInput("goal two")
        rule.onNodeWithTag("goal_set_confirm").performClick()
        rule.onNodeWithTag("composer_disposition_clear").performClick()
        assertEquals(
            listOf(Triple<String, String?, List<String>?>("goal two", "clearQueueAndSend", listOf("q-1"))),
            sets,
        )
    }

    @Test
    fun `F-P20-A - the sheet Set-goal without a held queue keeps the legacy shape (no dialog, no trio, control)`() {
        val sets = mutableListOf<Triple<String, String?, List<String>?>>()
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                SessionContextSheet(
                    sessionState = SessionProjectionState(),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = null,
                    sessionStatusProvider = { null },
                    onDismiss = {},
                    onSetGoal = { objective, disposition, ids -> sets.add(Triple(objective, disposition, ids)) },
                )
            }
        }
        rule.onNodeWithTag("goal_set_row").performClick()
        rule.onNodeWithTag("goal_set_field").performTextInput("simple goal")
        rule.onNodeWithTag("goal_set_confirm").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("composer_disposition_keep").assertCountEquals(0)
        assertEquals(listOf(Triple<String, String?, List<String>?>("simple goal", null, null)), sets)
    }
}
