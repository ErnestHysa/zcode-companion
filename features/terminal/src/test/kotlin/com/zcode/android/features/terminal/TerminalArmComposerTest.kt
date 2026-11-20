package com.zcode.android.features.terminal

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.common.ansi.AnsiParser
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.terminal.port.TerminalPort
import com.zcode.android.features.terminal.ui.TerminalScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G104 (pass-0 Phase 9) compose pins: the ADR-022 armed input tier's UI. The
 * port members, the transport methods and the mock's write-echo handlers shipped
 * in the gaps-and-polish era but ZERO UI surface existed (terminal.write/
 * terminal.resize were UI-UNREACHABLE; the shipped copy claimed an absolute
 * view-only posture the ADR had already superseded). The fix ships the tier:
 * the Arm affordance on Live (input opt-in held), the armed banner + badge,
 * the single-line composer (Enter → \r, dedicated Ctrl-C → \u0003).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TerminalArmComposerTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakePort : TerminalPort {
        val optInF = MutableStateFlow(true)
        val stateF = MutableStateFlow<TerminalPort.TerminalState>(TerminalPort.TerminalState.Live("/bin/zsh"))
        val inputOptInF = MutableStateFlow(false)
        val armedF = MutableStateFlow(false)
        val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
        var armCalls = 0
        override val optIn get() = optInF
        override suspend fun setOptIn(enabled: Boolean) {}
        override val state get() = stateF
        override val snapshot = MutableStateFlow(
            TerminalPort.TerminalSnapshot(
                lines = listOf(listOf(AnsiParser.Span(text = "ready"))),
                plainText = listOf("ready"),
                ringBytes = 6, droppedBytes = 0,
            ),
        )
        override suspend fun attach(): TerminalPort.TerminalState = stateF.value
        override fun detach() {}
        override fun search(query: String): List<Int> = emptyList()
        override val inputOptIn get() = inputOptInF
        override val armed get() = armedF
        override suspend fun setInputOptIn(enabled: Boolean, granted: Boolean) { inputOptInF.value = enabled }
        override suspend fun arm() { armCalls++; armedF.value = true }
        override fun disarm() { armedF.value = false }
        override suspend fun write(data: String) { writes.add(data) }
        override suspend fun resize(cols: Int, rows: Int) {}
    }

    @Test
    fun `G104 - the Arm affordance renders on Live with the input opt-in - the composer stays hidden while disarmed`() {
        val port = FakePort().apply { inputOptInF.value = true }
        rule.setContent { ZCodeTheme { TerminalScreen(port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_arm_input").assertExists("the Arm affordance renders on Live (the ADR's gate-4 UI)")
        rule.onNodeWithTag("terminal_composer").assertDoesNotExist()
        rule.onNodeWithTag("terminal_input_armed_banner").assertDoesNotExist()
    }

    @Test
    fun `G104 - with the input opt-in OFF the tier is invisible (no Arm, no composer)`() {
        val port = FakePort().apply { inputOptInF.value = false }
        rule.setContent { ZCodeTheme { TerminalScreen(port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_arm_input").assertDoesNotExist()
        rule.onNodeWithTag("terminal_composer").assertDoesNotExist()
    }

    @Test
    fun `G104 - Arm reveals the armed banner and composer - the send rides the arm with CR`() {
        val port = FakePort().apply { inputOptInF.value = true }
        rule.setContent { ZCodeTheme { TerminalScreen(port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_arm_input").performClick()
        rule.waitUntil(5_000) { port.armedF.value }
        rule.onNodeWithTag("terminal_input_armed_banner").assertExists("the armed banner stays visible while armed")
        rule.onNodeWithText("INPUT ARMED").assertExists("the header badge swaps to the armed posture")
        rule.onNodeWithTag("terminal_composer").performTextInput("ls")
        rule.onNodeWithTag("terminal_composer").performImeAction(ImeAction.Send)
        rule.waitUntil(5_000) { port.writes.isNotEmpty() }
        assertEquals(listOf("ls\r"), port.writes.toList())
        assertTrue(port.armCalls >= 1)
    }

    @Test
    fun `G104 - the dedicated Ctrl-C writes the control byte`() {
        val port = FakePort().apply { inputOptInF.value = true; armedF.value = true }
        rule.setContent { ZCodeTheme { TerminalScreen(port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_ctrl_c").performClick()
        rule.waitUntil(5_000) { port.writes.isNotEmpty() }
        assertEquals(listOf("\u0003"), port.writes.toList())
    }

    @Test
    fun `G104 - disarm clears the composer plane (the banner and field hide)`() {
        val port = FakePort().apply { inputOptInF.value = true; armedF.value = true }
        rule.setContent { ZCodeTheme { TerminalScreen(port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_composer").assertExists()
        port.disarm()
        rule.waitUntil(5_000) { !port.armedF.value }
        rule.onNodeWithTag("terminal_composer").assertDoesNotExist()
        rule.onNodeWithTag("terminal_input_armed_banner").assertDoesNotExist()
        rule.onNodeWithText("VIEW-ONLY").assertExists("the badge returns to the read posture")
        assertFalse(port.armedF.value)
    }
}
