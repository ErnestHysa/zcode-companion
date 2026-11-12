package com.zcode.android.features.conversation

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-33-D (pass 33, live-measured twice): the screen applied `.padding(inner)` (the M3
 * Scaffold's content insets include the nav bar) and then `.imePadding()` WITHOUT
 * `consumeWindowInsets(inner)` — with the keyboard up the nav-bar inset was applied
 * TWICE: the composer sat 145px (3-button) / 60px (gesture) above the keyboard versus
 * the correct 16px, and under the armed pinned stack the dead band pushed the action
 * row out of paint (two organic repros: attach + Send not painted, taps at Send's a11y
 * centre sent nothing). GAP-A16's closure (`ComposerSqueezeBudgetTest`) verified the
 * FIELD's bounds only — the Send button was never checked in paint. This pin injects
 * SYNTHETIC nav + IME insets (the HeaderInsetTest recipe — Robolectric dispatches none)
 * into a full phone-shaped window and asserts the SEND button's painted bounds sit
 * within the composer's own padding of the keyboard top — never a nav-bar-inset's worth
 * above it, and never inside the keyboard.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class ComposerImeInsetTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun permissionInteraction() = PendingInteraction(
        interactionId = "perm-f33d",
        kind = PendingInteraction.Kind.PERMISSION,
        anchorRowId = null,
        createdAtMs = 1L,
        permission = PendingInteraction.PermissionPayload(
            toolCallId = null,
            toolName = "Bash",
            summary = "run a command",
            detail = null,
            freeText = null,
            options = listOf(
                com.zcode.android.domain.model.PermissionOption("allowOnce"),
                com.zcode.android.domain.model.PermissionOption("allowAlways"),
                com.zcode.android.domain.model.PermissionOption("deny"),
            ),
            raw = buildJsonObject {},
        ),
        userInput = null,
        raw = buildJsonObject {},
    )

    private fun armedState() = ConversationUiState(
        rows = listOf(
            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {}),
        ),
        loading = false,
        taskState = TaskStateMachine.State.WAITING_FOR_PERMISSION,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
        sessionState = SessionProjectionState(
            pendingInteractions = listOf(permissionInteraction()),
            queue = QueueState(
                items = (1..3).map { i ->
                    QueueState.QueueItem(
                        queueItemId = "q-$i",
                        text = "queued follow-up number $i that wraps onto the next line of the strip",
                        kind = null,
                        dispatchState = "queued",
                    )
                },
                autoDrain = false,
                pauseReason = null,
            ),
        ),
    )

    @Test
    fun `F33-D - the Send button paints within one composer padding of the IME top`() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = armedState(),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = { _, _, _ -> },
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
        // Rig-proportioned synthetic insets (the pass-33 device math: the 3-BUTTON nav
        // bar is ~132px ≈ 50dp — the 145px dead band case — and the keyboard ≈ 300dp on
        // the 891dp phone): this window is density-1, so the injected values ARE dp.
        val navDp = 56
        val imeDp = 300
        val host = composeHostView()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navDp))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeDp))
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
        rule.waitForIdle()

        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val imeTop = root.bottom - imeDp
        // Send is painted under the armed stack with the IME up (the organic repro face).
        rule.onNodeWithTag("composer_send").assertIsDisplayed()
        val send = rule.onNodeWithTag("composer_send").fetchSemanticsNode().boundsInRoot
        // CORRECT: the Send bottom sits within the composer shell's own bottom padding
        // (≈16px) of the keyboard top — never a whole nav-bar inset above it.
        assertTrue(
            "double nav inset: Send bottom=${send.bottom}px, IME top=${imeTop}px " +
                "(gap ${(imeTop - send.bottom).toInt()}px > the 40px composer-padding ceiling)",
            send.bottom >= imeTop - 40f,
        )
        // And never INSIDE the keyboard area.
        assertTrue(
            "composer drawn under the IME: Send bottom=${send.bottom}px IME top=$imeTop",
            send.bottom <= imeTop + 1f,
        )
    }

    /** The AndroidComposeView under android.R.id.content (the view whose insets Compose observes). */
    private fun composeHostView(): android.view.View {
        val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
        var view: android.view.View = content.getChildAt(0)
        while (view is ViewGroup && view.childCount > 0 && view.javaClass.simpleName != "AndroidComposeView") {
            view = view.getChildAt(0)
        }
        return view
    }
}
