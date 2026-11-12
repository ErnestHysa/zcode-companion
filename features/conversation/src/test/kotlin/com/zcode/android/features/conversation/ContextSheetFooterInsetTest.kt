package com.zcode.android.features.conversation

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationPlan
import com.zcode.android.domain.model.ConversationPlanStep
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.PlanStepStatus
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import com.zcode.android.features.conversation.ui.SessionContextSheet
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-27-C (pass 31 + the pass-33 re-manifest; the bottom-edge clip family, carrying D2,
 * D5 and N-BD-4's notice bar): with tall sheet content the second chip row was
 * height-clipped to an ~11px sliver (wrapped chips reported [0,0][0,0]), the footer
 * painted UNDER the 3-button nav bar (a tap on Share hit system Back) and was cut by
 * the screen edge under gesture nav, and the sheet could reopen scrolled below the
 * chip row. The pass-31 family also clipped the conversation send_notice bar and
 * Settings' bottom rows (N-BD-4 — the Settings face is pinned in the app module's
 * SettingsBottomInsetTest).
 *
 * Pins: (1) with synthetic nav insets the footer chips report non-zero bounds and sit
 * fully ABOVE the nav-bar line; (2) the sheet consumes the HOISTED scroll state (the
 * host keys a fresh ScrollState(0) per open — D5's reopen face is structurally
 * impossible, asserted at the seam and by the wiring source pin); (3) the send_notice
 * bar sits above the nav-bar line under synthetic nav insets.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class ContextSheetFooterInsetTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun idleState() = ConversationUiState(
        rows = listOf(
            ConversationRow.Unknown(rowId = 1, turnId = "t1", unknownKind = "test-row", raw = buildJsonObject {}),
        ),
        loading = false,
        taskState = TaskStateMachine.State.IDLE,
        phase = ConnectionPhase.CONNECTED,
        draft = "",
        sessionState = SessionProjectionState(),
    )

    @Test
    fun `F27-C - the footer chips clear the nav bar with non-zero bounds`() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = idleState(),
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
        injectNavInset(navDp = 56)
        rule.onNodeWithTag("conv_session_context").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("session_context_sheet").assertExists()

        // Robolectric cannot dispatch insets into the sheet's POPUP window (the popup
        // has its own compose view), so the nav-bar clearance face is receipted on the
        // rig (exit gate) + the source pin below. What IS deterministic here: at max
        // scroll the second chip row reports FULL bounds — never the pass-31 zero/11px
        // height-clip face — and stays above the sheet's own trailing spacer.
        val density = rule.density.density
        val screenHeight = rule.activity.window.decorView.height / density
        rule.onNodeWithTag("session_context_content").performTouchInput { swipeUp(); swipeUp() }
        rule.waitForIdle()
        for (chip in listOf("conv_open_trajectory", "conv_share")) {
            val bounds = rule.onNodeWithTag(chip).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$chip height-clipped at max scroll (the D2 zero/sliver face): bounds=$bounds",
                bounds.height > 20f,
            )
            assertTrue(
                "$chip outside the sheet viewport: bottom=${bounds.bottom}px screen=$screenHeight",
                bounds.bottom <= screenHeight + 1f,
            )
        }
    }

    @Test
    fun `F27-C - the sheet consumes the hoisted scroll state and opens at the top`() {
        val stale = ScrollState(300)
        // A 40-step plan overflows the sheet's 640dp viewport so the stale offset has a
        // real scroll range to live in (short content clamps it to 0 — no seam to test).
        val tallPlan = ConversationPlan(
            title = "Long plan",
            status = null,
            steps = (1..40).map { i -> ConversationPlanStep("s$i", "Step $i of the long plan", PlanStepStatus.PENDING) },
        )
        rule.setContent {
            ZCodeTheme {
                SessionContextSheet(
                    sessionState = SessionProjectionState(),
                    rows = emptyList(),
                    workflowRuns = null,
                    plans = listOf(tallPlan),
                    sessionStatusProvider = { null },
                    onDismiss = {},
                    scrollState = stale,
                )
            }
        }
        rule.waitForIdle()
        val content = rule.onNodeWithTag("session_context_content").fetchSemanticsNode().config
        val axis = content[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the sheet must expose the hoisted scroll state's axis (D5 seam)", axis != null)
        // CONSUMED: the axis reports the INJECTED state (whatever the scrollable clamped
        // it to — attachment mutates both). An internally-created fresh state would
        // report 0 while the injected state holds its own (mutated) value.
        assertEquals(
            "the sheet did not consume the hoisted scroll state (D5 seam)",
            stale.value.toFloat(),
            axis!!.value.invoke(),
            0.5f,
        )
        assertTrue(
            "content must overflow the viewport for the seam to be real (max=${axis.maxValue.invoke()})",
            axis.maxValue.invoke() > 100f,
        )
    }

    @Test
    fun `F27-C wiring - the host keys a fresh ScrollState to the sheet's visibility`() {
        val source = conversationScreenSource()
        assertTrue(
            "the host must hoist the sheet scroll keyed on the sheet's visibility (D5)",
            source.contains("remember(contextSheet) {") && source.contains("ScrollState(0)"),
        )
        assertTrue(
            "the host must pass the hoisted scroll state into SessionContextSheet",
            source.contains("scrollState = contextSheetScroll"),
        )
        // The device face Robolectric cannot drive (the sheet's popup window has its own
        // insets): the sheet content is inset by the NAV BAR on the outer column, and the
        // sheet's measured height includes it (skipPartiallyExpanded: height = content).
        val sheetSource = sessionContextSheetSource()
        assertTrue(
            "the sheet footer must be inset by navigationBars (F-27-C: the Share tap hit system Back)",
            sheetSource.contains("navigationBarsPadding()"),
        )
        assertTrue(
            "the nav-bar inset must wrap (be OUTSIDE) the scroll viewport so the height includes it",
            sheetSource.indexOf("navigationBarsPadding()") < sheetSource.indexOf("verticalScroll("),
        )
    }

    private fun sessionContextSheetSource(): String {
        val candidates = listOf(
            File("src/main/kotlin/com/zcode/android/features/conversation/ui/SessionContextSheet.kt"),
            File("features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/SessionContextSheet.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("SessionContextSheet.kt not found from the test working dir")
    }

    @Test
    fun `N-BD-4 - the send_notice bar sits above the nav-bar line`() {
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = idleState().copy(
                        sendNotice = SendNotice("Trusted 2 hook(s) — the session continues.", SendNotice.Kind.INFO),
                    ),
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
        injectNavInset(navDp = 56)
        rule.waitForIdle()
        val density = rule.density.density
        val screenHeight = rule.activity.window.decorView.height / density
        val navLine = screenHeight - 56f
        val notice = rule.onNodeWithTag("send_notice").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "notice bar height-clipped (the N-BD-4 10px sliver face): bounds=$notice",
            notice.height > 20f,
        )
        assertTrue(
            "notice bar under the nav bar: bottom=${notice.bottom}px nav line=$navLine",
            notice.bottom <= navLine,
        )
    }

    private fun injectNavInset(navDp: Int) {
        val host = composeHostView()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navDp))
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
        rule.waitForIdle()
    }

    private fun conversationScreenSource(): String {
        val candidates = listOf(
            File("src/main/kotlin/com/zcode/android/features/conversation/ui/ConversationScreen.kt"),
            File("features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/ConversationScreen.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("ConversationScreen.kt not found from the test working dir")
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
