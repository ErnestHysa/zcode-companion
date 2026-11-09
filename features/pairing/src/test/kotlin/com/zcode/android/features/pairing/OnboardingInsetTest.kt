package com.zcode.android.features.pairing

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.pairing.ui.OnboardingPager
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * vc52 Phase 8 exit gate (rig-caught, unlocked by the F-34-E start-destination fix): the
 * onboarding pass had NEVER shown on a real pairing before, so nobody saw that its header
 * row ignored the status-bar inset in the edge-to-edge app — on zcode-a16 the Skip control
 * spanned y=66–198 under a 136 px status bar and every tap at its reported bounds hit the
 * status bar: Skip / Get started were dead, onboardingDone never persisted, and every
 * relaunch re-ran onboarding. Synthetic insets (the HeaderInsetTest recipe) — Robolectric
 * dispatches none on its own.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class OnboardingInsetTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the onboarding header clears the status bar - Skip is reachable`() {
        val statusPx = 136 // zcode-a16's measured status-bar frame
        rule.setContent { ZCodeTheme { OnboardingPager(onFinish = {}) } }
        rule.waitForIdle()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusPx, 0, 0))
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(composeHostView(), insets) }
        rule.waitForIdle()
        val skip = rule.onNodeWithTag("onboarding_skip").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the Skip control is drawn under the status bar: top=${skip.top}px inset=${statusPx}px (dead on device)",
            skip.top >= statusPx,
        )
    }

    private fun composeHostView(): View {
        val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
        var view: View = content.getChildAt(0)
        while (view is ViewGroup && view.childCount > 0 && view.javaClass.simpleName != "AndroidComposeView") {
            view = view.getChildAt(0)
        }
        return view
    }
}
