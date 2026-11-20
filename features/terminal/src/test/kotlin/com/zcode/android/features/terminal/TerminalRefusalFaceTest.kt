package com.zcode.android.features.terminal

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.terminal.port.TerminalPort
import com.zcode.android.features.terminal.ui.TerminalScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-CC-1 (pass-0 Phase 9) pins: the terminal REFUSAL face's Retry button rendered
 * BLANK — `StateCard(text, retry = {…})` without `retryLabel` produced an
 * unlabeled 160×132 pill that worked but showed (and spoke, for TalkBack) NOTHING.
 * The Idle branch always passed the label; only Refused forgot it; the
 * CapabilityOff branch offered no recovery affordance at all. The fix passes
 * `retryLabel = terminal_attach` at Refused AND gives CapabilityOff the same
 * labeled re-probe (attach() re-runs the capability gate — a recovered transport
 * may now declare localTerminal).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TerminalRefusalFaceTest {

    @get:Rule
    val rule = createComposeRule()

    private fun port(state: TerminalPort.TerminalState, onAttach: () -> Unit = {}) = object : TerminalPort {
        var attachCalls = 0
        override val optIn = MutableStateFlow(true)
        override suspend fun setOptIn(enabled: Boolean) {}
        override val state = MutableStateFlow<TerminalPort.TerminalState>(state)
        override val snapshot = MutableStateFlow(TerminalPort.TerminalSnapshot(emptyList(), emptyList(), 0, 0))
        override suspend fun attach(): TerminalPort.TerminalState {
            attachCalls++
            onAttach()
            return state.value
        }
        override fun detach() {}
        override fun search(query: String): List<Int> = emptyList()
        override val inputOptIn = MutableStateFlow(false)
        override val armed = MutableStateFlow(false)
        override suspend fun setInputOptIn(enabled: Boolean, granted: Boolean) {}
        override suspend fun arm() {}
        override fun disarm() {}
        override suspend fun write(data: String) {}
        override suspend fun resize(cols: Int, rows: Int) {}
    }

    @Test
    fun `N-CC-1 - the refusal face renders a LABELED Attach retry (the blank pill is gone)`() {
        rule.setContent { ZCodeTheme { TerminalScreen(port(TerminalPort.TerminalState.Refused("fault.terminal.denied"))) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_retry").assertExists("the refusal face must offer the recovery affordance")
        rule.onNodeWithText("Attach").assertExists("the Retry pill must carry its visible/a11y label (TalkBack)")
    }

    @Test
    fun `N-CC-1 - tapping the refusal retry re-fires the attach`() {
        val port = port(TerminalPort.TerminalState.Refused("fault.terminal.denied"))
        rule.setContent { ZCodeTheme { TerminalScreen(port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_retry").performClick()
        rule.waitForIdle()
        assertEquals(1, port.attachCalls, "the labeled pill still WORKS (the pass-47 receipt)")
    }

    @Test
    fun `N-CC-1 - the CapabilityOff face offers a labeled re-probe too`() {
        rule.setContent { ZCodeTheme { TerminalScreen(port(TerminalPort.TerminalState.CapabilityOff)) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_retry").assertExists("CapabilityOff must offer recovery (the hello may declare the bit after a reconnect)")
        rule.onNodeWithText("Attach").assertExists("the re-probe is labeled")
    }

    @Test
    fun `the Idle face keeps its labeled Attach (the branch that was always right)`() {
        rule.setContent { ZCodeTheme { TerminalScreen(port(TerminalPort.TerminalState.Idle)) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_retry").assertExists()
        rule.onNodeWithText("Attach").assertExists()
        assertTrue(true)
    }
}
