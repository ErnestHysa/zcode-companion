package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.ShareMode
import com.zcode.android.features.conversation.ui.ShareModeSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G56 (vc52 Phase 13): print to PDF — the desktop exports pages to PDF
 * (printPageToPdf); the phone's share shipped text + Markdown only. The share
 * sheet gains a PRINT mode: the transcript paginates (pure [TranscriptPrinter])
 * and the handoff rides the Android Printing framework (the system sheet opens,
 * the adapter writes the pages to a PDF).
 *
 * Pins: the pagination is LOSSLESS (the pages join back to the exact transcript,
 * line-for-line); no page exceeds the cap; a short transcript is one page; the
 * sheet renders the PRINT row and dispatches ShareMode.PRINT.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TranscriptPrintTest {

    @get:Rule
    val rule = createComposeRule()

    // ------------------------------------------------------- 1. the pure pagination

    @Test
    fun `pagination is lossless - the pages join back to the exact transcript`() {
        val text = (1..250).joinToString("\n") { "line $it — the agent did something worth printing" }
        val pages = TranscriptPrinter.paginate(text)
        assertTrue("more than one page for 250 lines", pages.size > 1)
        assertEquals(text, pages.joinToString("\n"))
    }

    @Test
    fun `no page exceeds the cap and pages carry only whole lines`() {
        val text = (1..200).joinToString("\n") { "row $it" }
        val pages = TranscriptPrinter.paginate(text, linesPerPage = 40)
        for (page in pages) {
            assertTrue("page cap respected (${page.lines().size})", page.lines().size <= 40)
        }
    }

    @Test
    fun `a short transcript is exactly one page`() {
        val pages = TranscriptPrinter.paginate("hello\nworld")
        assertEquals(1, pages.size)
        assertEquals("hello\nworld", pages[0])
    }

    @Test
    fun `an empty transcript is one blank page (never a crash)`() {
        val pages = TranscriptPrinter.paginate("")
        assertEquals(1, pages.size)
    }

    @Test
    fun `blank interior lines survive (the transcript keeps its shape)`() {
        val text = "a\n\n\nb"
        val pages = TranscriptPrinter.paginate(text)
        assertEquals(text, pages.joinToString("\n"))
    }

    // ------------------------------------------------------------ 2. the sheet row

    @Test
    fun `the sheet renders the PRINT row and dispatches ShareMode PRINT`() {
        val picked = mutableListOf<ShareMode>()
        rule.setContent { ZCodeTheme { ShareModeSheet(onMode = { picked += it }, onDismiss = {}) } }
        rule.onNodeWithTag("share_mode_print").assertIsDisplayed()
        rule.onNodeWithTag("share_mode_print").performClick()
        assertEquals(listOf(ShareMode.PRINT), picked)
    }
}
