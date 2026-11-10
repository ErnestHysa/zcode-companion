package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.MarkdownText
import com.zcode.android.features.conversation.ui.headingTextStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * N-BI-1 / N-BI-2 (vc52 Phase 10) RENDERER faces over [MarkdownText]:
 * - N-BI-1: the ordered marker column GROWS with the digits (the fixed 18 dp column
 *   wrapped "10."'s period onto its own line) and H1–H6 map to DISTINCT type (the old
 *   2-level map flattened H2–H6).
 * - N-BI-2: task glyphs render (☑/☐), an image renders its alt as a link-styled
 *   fallback (the deterministic Robolectric face — no network), a footnote definition
 *   renders labeled, and an autolink's brackets do not leak into the painted text.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MarkdownConformanceRenderTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(md: String) {
        rule.setContent { ZCodeTheme { MarkdownText(text = md) } }
        rule.waitForIdle()
    }

    private fun nodeWidthDp(text: String): Float {
        val node = rule.onAllNodesWithText(text).fetchSemanticsNodes().first()
        return node.layoutInfo.width / rule.density.density
    }

    @Test
    fun `N-BI-1 - the marker column grows with digits, 2-digit wider than 1-digit`() {
        setContent("9. nine\n10. ten")
        val w9 = nodeWidthDp("9.")
        val w10 = nodeWidthDp("10.")
        assertTrue(
            w10 > w9,
            "the 2-digit marker column ($w10 dp) must exceed the 1-digit one ($w9 dp) — the fixed 18 dp column wrapped the period",
        )
        assertTrue(w9 >= 18f, "the 1-digit marker keeps the 18 dp floor ($w9)")
    }

    @Test
    fun `N-BI-1 - the 3-digit marker never wraps its period (30dp budget)`() {
        setContent("100. hundred")
        val w = nodeWidthDp("100.")
        assertTrue(w >= 30f, "3 digits need $w dp >= the 9dp*3+8dp=35dp-epsilon budget")
    }

    @Test
    fun `N-BI-1 - H1 to H6 map to SIX DISTINCT styles (the old map had two buckets)`() {
        // Robolectric's fallback font metrics clamp single-line rendered heights to
        // near-equal boxes (~35px for every level here) — painted pixels are an
        // environment artifact. The honest pin is the style ASSIGNMENT.
        val t = com.zcode.android.core.ui.theme.ZCodeTypographyLight.m3
        val styles = (1..6).map { headingTextStyle(t, it) }
        assertTrue(
            styles.toSet().size == 6,
            "all six heading levels must resolve to distinct styles — the old 2-level map flattened H2–H6: ${styles.map { it.fontSize }}",
        )
        assertTrue(styles[0] == t.headlineSmall)
        assertTrue(styles[1] == t.titleLarge)
        assertTrue(styles[2] == t.titleMedium)
        assertTrue(styles[3] == t.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        assertTrue(styles[4] == t.titleSmall)
        assertTrue(styles[5] == t.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }

    @Test
    fun `N-BI-1 - the levels are ORDERED by size (H1 largest, H6 smallest)`() {
        val t = com.zcode.android.core.ui.theme.ZCodeTypographyLight.m3
        val sizes: List<Float> = (1..6).map { headingTextStyle(t, it).fontSize.value }
        assertTrue(sizes == sizes.sortedDescending(), "heading sizes must descend: $sizes")
    }

    @Test
    fun `N-BI-1 - all six heading levels render on screen`() {
        setContent("# one\n## two\n### three\n#### four\n##### five\n###### six")
        for (label in listOf("one", "two", "three", "four", "five", "six")) {
            rule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `N-BI-2 - task lists render the check glyphs with their tags`() {
        setContent("- [x] done work\n- [ ] open work")
        rule.onNodeWithTag("md_task_checked").assertIsDisplayed()
        rule.onNodeWithTag("md_task_unchecked").assertIsDisplayed()
        // The bracket markers themselves never paint as text.
        rule.onAllNodesWithText("[x]", substring = true).assertCountEquals(0)
    }

    @Test
    fun `N-BI-2 - an image renders the alt fallback (no network in Robolectric) and no leaked bang`() {
        setContent("look ![the alt](https://example.test/img.png)")
        rule.onNodeWithTag("md_image_unavailable").assertIsDisplayed()
        rule.onNodeWithText("Image: the alt").assertIsDisplayed()
        rule.onAllNodesWithText("!the alt").assertCountEquals(0)
    }

    @Test
    fun `N-BI-2 - a footnote definition renders its label and body`() {
        setContent("body text[^1]\n\n[^1]: the footnote body")
        rule.onNodeWithTag("md_footnote_def").assertIsDisplayed()
        rule.onNodeWithText("[1]").assertIsDisplayed()
        rule.onNodeWithText("the footnote body").assertIsDisplayed()
    }

    @Test
    fun `N-BI-2 - an autolink paints WITHOUT the angle brackets`() {
        setContent("see <https://example.test/docs> now")
        // The whole painted paragraph (exact match), and no bracket glyph anywhere.
        rule.onNodeWithText("see https://example.test/docs now").assertIsDisplayed()
        rule.onAllNodesWithText("<", substring = true).assertCountEquals(0)
    }
}
