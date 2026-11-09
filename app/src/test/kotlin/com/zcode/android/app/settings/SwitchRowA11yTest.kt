package com.zcode.android.app.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.AppContainer
import androidx.compose.ui.state.ToggleableState
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * N-BG-3 (pass 35): Settings' custom switches (Live connection / Auto-reconnect /
 * Terminal) exposed no checkable/checked a11y state and the labels were not tappable.
 * Pin: the SwitchRow exposes the checked state (toggleable role=Switch) and the label
 * tap toggles the preference (the terminal opt-in flips for real in the container).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class SwitchRowA11yTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `N-BG-3 - the terminal opt-in row exposes the checked state and the label toggles it`() {
        val activity = Robolectric.setupActivity(androidx.activity.ComponentActivity::class.java)
        val container = AppContainer(activity)
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
        rule.onNodeWithTag("settings_terminal_opt_in").performScrollTo()
        rule.waitForIdle()
        val before = rule.onNodeWithTag("settings_terminal_opt_in").fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState]
        assertEquals("precondition: the terminal opt-in defaults OFF", ToggleableState.Off, before)
        // THE BUG: the label was not tappable — only the switch on the right was.
        rule.onNodeWithTag("settings_terminal_opt_in").performClick()
        rule.waitForIdle()
        val after = rule.onNodeWithTag("settings_terminal_opt_in").fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState]
        assertEquals("the label tap must toggle the preference", ToggleableState.On, after)
    }
}
