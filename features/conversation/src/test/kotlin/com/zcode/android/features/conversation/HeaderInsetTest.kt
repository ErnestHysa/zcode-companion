package com.zcode.android.features.conversation

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * U2 (verification pass 2, 2026-10-21, live-found on the rig): the conversation header
 * floated ~53dp below the status bar on the Pixel 5 (cutout inset 145px) — every other
 * screen's title sat right under it. `ConversationScreen` hosted `ZCodeTopBar` INSIDE the
 * Scaffold content (`topBar = {}`) whose `padding(inner)` already carries the status-bar
 * inset, and the M3 TopAppBar applies its own — the inset landed twice. ZCodeTopBar's
 * contract: "every screen hosts it as a Scaffold topBar". Robolectric dispatches no
 * insets, so the pin injects a synthetic status-bar inset into the Compose host view and
 * asserts the back control sits within one icon-offset of it — not two insets down.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class HeaderInsetTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun statusBarInset_isAppliedOnce_aboveTheHeader() {
        val statusPx = 120
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = emptyList(),
                        loading = false,
                        taskState = TaskStateMachine.State.IDLE,
                        phase = ConnectionPhase.CONNECTED,
                        draft = "",
                    ),
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
        val host = composeHostView()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
        rule.waitForIdle()

        val back = rule.onNodeWithTag("conversation_back").fetchSemanticsNode().boundsInRoot
        // The header respects the inset (never drawn under the status bar)…
        assertTrue("back control drawn under the status bar: top=${back.top}px inset=${statusPx}px", back.top >= statusPx)
        // …and exactly ONCE: the icon's own in-bar offset is 8dp; a second inset lands it a
        // whole status bar lower.
        val oneInsetCeiling = statusPx + 32f * density
        assertTrue(
            "status-bar inset applied twice: back top=${back.top}px, one inset + icon offset would be ≤ ${oneInsetCeiling}px",
            back.top < oneInsetCeiling,
        )
    }

    /** The AndroidComposeView under android.R.id.content (the view whose insets Compose observes). */
    private fun composeHostView(): View {
        val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
        var view: View = content.getChildAt(0)
        while (view is ViewGroup && view.childCount > 0 && view.javaClass.simpleName != "AndroidComposeView") {
            view = view.getChildAt(0)
        }
        return view
    }
}
