package com.zcode.android.app.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.AppContainer
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * N-BH-4 (pass 36): the presentation-mode dialog's options were clickable Rows
 * wrapping `RadioButton(onClick = null)` WITHOUT `Modifier.selectable(role =
 * RadioButton)` — neither row exposed a checked/selected state, so TalkBack could not
 * announce the current mode. Pin: every picker dialog row (the shared
 * [PickerRadioRow] path AND the inline presentation-mode rows) exposes the selected
 * state, and exactly the CURRENT option carries it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class PickerDialogSelectableTest {

    @get:Rule
    val rule = createComposeRule()

    private fun selectedStates(vararg tags: String): Map<String, Boolean?> {
        rule.waitForIdle()
        return tags.associateWith { tag ->
            rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Selected]
        }
    }

    private fun setContent() {
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
    }

    @Test
    fun `N-BH-4 - the presentation-mode dialog rows expose the selected state`() {
        setContent()
        rule.onNodeWithTag("settings_interface_mode").performScrollTo().performClick()
        rule.waitForIdle()
        val states = selectedStates(
            "settings_interface_mode_OFFICE",
            "settings_interface_mode_CODING",
        )
        // The default preference is CODING (the desktop's interfaceMode) — exactly one
        // row carries Selected, and it is the current one.
        assertEquals(
            "exactly one row must expose Selected (pre-fix: none did)",
            1,
            states.values.count { it == true },
        )
        assertEquals(true, states["settings_interface_mode_CODING"])
        assertEquals(false, states["settings_interface_mode_OFFICE"])
    }

    @Test
    fun `N-BH-4 - the shared picker rows (theme) expose the selected state`() {
        setContent()
        rule.onNodeWithTag("settings_theme").performScrollTo().performClick()
        rule.waitForIdle()
        val states = selectedStates(
            "settings_theme_LIGHT",
            "settings_theme_DARK",
            "settings_theme_SYSTEM",
        )
        assertTrue(
            "the shared PickerRadioRow must expose Selected on every row (the map keys must all exist): $states",
            states.values.all { it != null },
        )
        assertEquals(1, states.values.count { it == true })
    }
}
