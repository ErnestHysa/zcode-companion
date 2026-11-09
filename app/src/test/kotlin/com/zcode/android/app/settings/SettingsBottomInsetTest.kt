package com.zcode.android.app.settings

import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.AppContainer
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * N-BD-4 (pass 31, the F-27-C bottom-edge clip family): Settings' bottom rows rendered
 * in the nav-bar zone — `settings_locale` sat at y=2065-2072 with its text invisible
 * (a 7px tappable band that "worked blind"). Guard pin: with synthetic navigationBars
 * insets injected, the LAST settings row must report full bounds sitting ABOVE the
 * nav-bar line (the screen's Scaffold content insets carry the nav bar — this pin
 * keeps the family closed; the recorded face was captured on the pass-31 build).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class SettingsBottomInsetTest {

    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun `N-BD-4 - the locale row sits fully above the nav-bar line with visible bounds`() {
        val container = AppContainer(rule.activity)
        rule.setContent {
            ZCodeTheme {
                SettingsScreen(
                    container = container,
                    endpointName = "MockBook",
                    desktopVersion = "3.14.5",
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        val host = composeHostView()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 56))
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
        rule.waitForIdle()

        rule.onNodeWithTag("settings_locale").performScrollTo()
        rule.waitForIdle()
        val density = rule.density.density
        val screenHeight = rule.activity.window.decorView.height / density
        val navLine = screenHeight - 56f
        val row = rule.onNodeWithTag("settings_locale").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "locale row height-clipped (the N-BD-4 invisible-text face): bounds=$row",
            row.height > 20f,
        )
        assertTrue(
            "locale row in the nav-bar zone: bottom=${row.bottom}px nav line=$navLine",
            row.bottom <= navLine,
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
