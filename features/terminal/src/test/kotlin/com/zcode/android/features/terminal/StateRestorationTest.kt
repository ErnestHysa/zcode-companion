package com.zcode.android.features.terminal

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.common.ansi.AnsiParser
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.terminal.port.TerminalPort
import com.zcode.android.features.terminal.ui.TerminalScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C6-state (gaps-and-polish Phase 2F, P1): terminal pause/search state died on
 * process death (plain `remember`). Pinned: the paused state survives the restore
 * (the chip reads "Resume" after).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class StateRestorationTest {

    @get:Rule
    val rule = createComposeRule()

    private val restorer = StateRestorationTester(rule)

    private val port = object : TerminalPort {
        override val optIn = MutableStateFlow(true)
        override suspend fun setOptIn(enabled: Boolean) {}
        override val state = MutableStateFlow<TerminalPort.TerminalState>(TerminalPort.TerminalState.Live("zsh"))
        override val snapshot = MutableStateFlow(
            TerminalPort.TerminalSnapshot(
                lines = listOf(listOf(AnsiParser.Span(text = "line one"))),
                plainText = listOf("line one"),
                ringBytes = 8, droppedBytes = 0,
            ),
        )
        override suspend fun attach(): TerminalPort.TerminalState = TerminalPort.TerminalState.Live("zsh")
        override fun detach() {}
        override fun search(query: String): List<Int> = emptyList()
    }

    @Test
    fun `the paused state survives process death`() {
        restorer.setContent { ZCodeTheme { TerminalScreen(port = port) } }
        rule.waitForIdle()
        rule.onNodeWithText("Pause").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Resume").fetchSemanticsNodes().isNotEmpty()

        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertTrue(
            "the paused state must survive the restore (chip reads Resume)",
            rule.onAllNodesWithText("Resume").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
