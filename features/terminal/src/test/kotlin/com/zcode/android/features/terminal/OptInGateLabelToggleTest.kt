package com.zcode.android.features.terminal

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.terminal.port.TerminalPort
import com.zcode.android.features.terminal.ui.TerminalScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BG-3 (pass 35): the terminal consent gate's Switch was the only hit target — two
 * taps on the "Enable terminal (view-only)" label produced zero events and looked
 * like a dead screen. Pin: the whole row (label included) is the toggle target.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class OptInGateLabelToggleTest {

    @get:Rule
    val rule = createComposeRule()

    private fun port(onSet: (Boolean) -> Unit) = object : TerminalPort {
        override val optIn = MutableStateFlow(false)
        override suspend fun setOptIn(enabled: Boolean) = onSet(enabled)
        override val state = MutableStateFlow<TerminalPort.TerminalState>(TerminalPort.TerminalState.Idle)
        override val snapshot = MutableStateFlow(
            TerminalPort.TerminalSnapshot(
                lines = emptyList(),
                plainText = emptyList(),
                ringBytes = 0, droppedBytes = 0,
            ),
        )
        override suspend fun attach(): TerminalPort.TerminalState = TerminalPort.TerminalState.Idle
        override fun detach() {}
        override fun search(query: String): List<Int> = emptyList()
        override val inputOptIn = MutableStateFlow(false)
        override val armed = MutableStateFlow(false)
        override suspend fun setInputOptIn(enabled: Boolean, granted: Boolean) {}
        override suspend fun arm() {}
        override fun disarm() {}
    }

    @Test
    fun `N-BG-3 - the consent row (label included) toggles the gate`() {
        var toggles = 0
        rule.setContent {
            ZCodeTheme {
                TerminalScreen(port = port { toggles++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_optin_row").performClick()
        rule.waitForIdle()
        assertEquals("the row (label) tap must toggle the opt-in", 1, toggles)
    }
}
