package com.zcode.android.features.terminal

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * J7 (gaps-and-polish Phase 2F, P1): the terminal was read-only AND copy-less — zero
 * copy/selection affordance (no `SelectionContainer`, no clipboard). Copy-OUT is
 * read-side (paste stays ADR-gated): the visible buffer is selectable and one action
 * puts the visible output on the clipboard.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TerminalCopyTest {

    @get:Rule
    val rule = createComposeRule()

    private val port = object : TerminalPort {
        override val optIn = MutableStateFlow(true)
        override suspend fun setOptIn(enabled: Boolean) {}
        override val state = MutableStateFlow<TerminalPort.TerminalState>(TerminalPort.TerminalState.Live("zsh"))
        override val snapshot = MutableStateFlow(
            TerminalPort.TerminalSnapshot(
                lines = listOf(listOf(AnsiParser.Span(text = "build succeeded in 12s"))),
                plainText = listOf("build succeeded in 12s"),
                ringBytes = 24, droppedBytes = 0,
            ),
        )
        override suspend fun attach(): TerminalPort.TerminalState = TerminalPort.TerminalState.Live("zsh")
        override fun detach() {}
        override fun search(query: String): List<Int> = emptyList()
        override val inputOptIn = MutableStateFlow(false)
        override val armed = MutableStateFlow(false)
        override suspend fun setInputOptIn(enabled: Boolean, granted: Boolean) {}
        override suspend fun arm() {}
        override fun disarm() {}
    }

    private fun clipboard(): ClipboardManager =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test
    fun `copy visible output puts the buffer on the clipboard`() {
        rule.setContent { ZCodeTheme { TerminalScreen(port = port) } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_copy_output").performClick()
        val clip = clipboard().primaryClip?.getItemAt(0)?.text ?: ""
        assertTrue("clipboard must carry the visible output: '$clip'", clip.contains("build succeeded in 12s"))
    }

    /**
     * Selection is a pointer-gesture layer, not a semantics action (Robolectric exposes
     * no SetSelection on Text inside a SelectionContainer) — the observable contract is
     * the SOURCE: the buffer's LazyColumn is wrapped in a SelectionContainer.
     */
    @Test
    fun `the visible buffer is wrapped in a SelectionContainer`() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        val src = java.io.File(dir, "features/terminal/src/main/kotlin/com/zcode/android/features/terminal/ui/TerminalScreen.kt").readText()
        val container = src.indexOf("SelectionContainer(modifier")
        val buffer = src.indexOf("LazyColumn(state = listState")
        assertTrue("the buffer LazyColumn must sit inside a SelectionContainer", container in 1 until buffer)
    }
}
