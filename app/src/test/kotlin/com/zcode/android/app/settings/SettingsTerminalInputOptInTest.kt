package com.zcode.android.app.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.AppContainer
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * G104 (pass-0 Phase 9) pins: ADR-022 gate 3 — the Settings "Allow typing into
 * the terminal" row. Default OFF; flipping ON requires the device-credential
 * confirm (the injected seam; the production impl is the BiometricPrompt with
 * the DEVICE_CREDENTIAL fallback). A refused/absent confirm FAILS CLOSED
 * (INV-14: never silently ungated); turning OFF never prompts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class SettingsTerminalInputOptInTest {

    @get:Rule
    val rule = createComposeRule()

    private fun state(container: AppContainer): ToggleableState =
        rule.onNodeWithTag("settings_terminal_input_opt_in").fetchSemanticsNode()
            .config[SemanticsProperties.ToggleableState]

    @Test
    fun `G104 - the input opt-in row renders default OFF and fails CLOSED without a confirm`() {
        val activity = Robolectric.setupActivity(androidx.activity.ComponentActivity::class.java)
        val container = AppContainer(activity)
        rule.setContent {
            ZCodeTheme {
                SettingsScreen(container = container, endpointName = "MockBook", desktopVersion = "3.18.1", onBack = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("settings_terminal_input_opt_in").performScrollTo()
        rule.waitForIdle()
        assertEquals("the second opt-in defaults OFF", ToggleableState.Off, state(container))
        // The Robolectric harness hosts a ComponentActivity (no FragmentActivity) —
        // the production confirm cannot run → granted=false → the flip must FAIL
        // CLOSED, never silently arm.
        rule.onNodeWithTag("settings_terminal_input_opt_in").performClick()
        rule.waitForIdle()
        rule.waitForIdle()
        assertEquals("INV-14: no confirm → still OFF", ToggleableState.Off, state(container))
    }

    @Test
    fun `G104 - an injected granted confirm flips ON and OFF never prompts`() {
        val activity = Robolectric.setupActivity(androidx.activity.ComponentActivity::class.java)
        val container = AppContainer(activity)
        var prompts = 0
        container.terminalInputCredentialConfirm = { prompts++; true }
        rule.setContent {
            ZCodeTheme {
                SettingsScreen(container = container, endpointName = "MockBook", desktopVersion = "3.18.1", onBack = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("settings_terminal_input_opt_in").performScrollTo()
        rule.waitForIdle()
        rule.onNodeWithTag("settings_terminal_input_opt_in").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("settings_terminal_input_opt_in").fetchSemanticsNodes()
                .firstOrNull()?.config?.get(SemanticsProperties.ToggleableState) == ToggleableState.On
        }
        assertEquals(1, prompts, "the ON flip prompted exactly once")
        rule.onNodeWithTag("settings_terminal_input_opt_in").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("settings_terminal_input_opt_in").fetchSemanticsNodes()
                .firstOrNull()?.config?.get(SemanticsProperties.ToggleableState) == ToggleableState.Off
        }
        assertEquals("turning OFF never prompts (the ADR)", 1, prompts)
    }

    @Test
    fun `G104 - a refused confirm leaves the row OFF (the cancelled prompt path)`() {
        val activity = Robolectric.setupActivity(androidx.activity.ComponentActivity::class.java)
        val container = AppContainer(activity)
        container.terminalInputCredentialConfirm = { false }
        rule.setContent {
            ZCodeTheme {
                SettingsScreen(container = container, endpointName = "MockBook", desktopVersion = "3.18.1", onBack = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("settings_terminal_input_opt_in").performScrollTo()
        rule.waitForIdle()
        rule.onNodeWithTag("settings_terminal_input_opt_in").performClick()
        rule.waitForIdle()
        rule.waitForIdle()
        assertEquals("a refused credential leaves the gate OFF", ToggleableState.Off, state(container))
    }
}
