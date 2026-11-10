package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.markdown.MarkdownParser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * N-BI-1 / N-BI-2 (vc52 Phase 10) — the markdown conformance pins.
 *
 * N-BI-1: ordered-list markers used a FIXED 18 dp column ("10." wrapped its period
 * onto its own line) and heading levels were clamped to 3 (H2–H6 all rendered at one
 * size). N-BI-2: the recorded pass-37 gap list vs the desktop's streamdown 2.5.0 +
 * remark-gfm 4.0.1 stack — each ADOPTED construct pinned at the parser level (the
 * renderer's faces are pinned in [MarkdownConformanceRenderTest]); the NOT-adopted
 * constructs are pinned as LITERAL so a future change is a deliberate act.
 *
 * RED evidence: every adopted pin failed at the pre-fix commit (the constructs did
 * not exist — `_underscore_` stayed literal, `[x]` stayed in the text, `![alt]`
 * parsed as a LINK with the "!" leaked, `\*` kept the backslash AND styled the
 * char, `[^1]` was literal, H6 clamped to 3).
 */
class MarkdownConformanceTest {

    private fun text(spans: List<MarkdownParser.Span>) = spans.joinToString("") { it.text }

    private fun paragraphs(src: String) = MarkdownParser.parse(src).filterIsInstance<MarkdownParser.Block.Paragraph>()

    private fun items(src: String) = MarkdownParser.parse(src).filterIsInstance<MarkdownParser.Block.ListItem>()

    private fun headings(src: String) = MarkdownParser.parse(src).filterIsInstance<MarkdownParser.Block.Heading>()

    // ------------------------------------------------------------------ N-BI-1

    @Test
    fun `N-BI-1 - H1 to H6 keep their TRUE levels (no 3-clamp)`() {
        val hs = headings("# one\n## two\n### three\n#### four\n##### five\n###### six")
        assertEquals(listOf(1, 2, 3, 4, 5, 6), hs.map { it.level })
    }

    @Test
    fun `N-BI-1 - heading text still strips the hashes`() {
        assertEquals("deep", text(headings("###### deep").single().spans))
    }

    // ------------------------------------------------------------------ N-BI-2: task lists

    @Test
    fun `N-BI-2 - GFM task lists parse to task items, unchecked and checked`() {
        val items = items("- [ ] ship the fix\n- [x] write the pin")
        assertEquals(2, items.size)
        assertEquals(false, items[0].task)
        assertEquals(true, items[1].task)
        assertEquals("ship the fix", text(items[0].spans))
        assertEquals("write the pin", text(items[1].spans))
    }

    @Test
    fun `N-BI-2 - uppercase X checks too, and a non-task bracket is NOT a task`() {
        assertEquals(true, items("- [X] loud check").single().task)
        val notTask = items("- [ ]nospace").single()
        assertNull(notTask.task)
        assertEquals("[ ]nospace", text(notTask.spans))
    }

    // ------------------------------------------------------------------ N-BI-2: underscore emphasis

    @Test
    fun `N-BI-2 - underscore emphasis italicizes (the recorded literal face)`() {
        val spans = paragraphs("this is _underscore emphasis_ text").single().spans
        val italics = spans.filter { it.italic }
        assertEquals(1, italics.size)
        assertEquals("underscore emphasis", italics.single().text)
    }

    @Test
    fun `N-BI-2 - intraword underscores NEVER emphasize (CommonMark flanking)`() {
        val spans = paragraphs("the var some_var_name stays plain").single().spans
        assertTrue(spans.none { it.italic }, "snake_case must stay plain: $spans")
        assertEquals("the var some_var_name stays plain", text(spans))
    }

    // ------------------------------------------------------------------ N-BI-2: images

    @Test
    fun `N-BI-2 - an image parses to an IMAGE span, not a link with a leaked bang`() {
        val spans = paragraphs("look ![alt text](https://example.test/img.png) done").single().spans
        val img = spans.singleOrNull { it.imageUrl != null }
        assertTrue(img != null, "the image span must exist: $spans")
        assertEquals("alt text", img!!.text)
        assertEquals("https://example.test/img.png", img.imageUrl)
        assertNull(img.linkUrl, "an image is NOT a link span")
        assertFalse(text(spans).contains("!"), "the ! must not leak")
    }

    // ------------------------------------------------------------------ N-BI-2: autolinks

    @Test
    fun `N-BI-2 - an angle autolink strips the brackets and links`() {
        val spans = paragraphs("see <https://example.test/docs> now").single().spans
        val link = spans.singleOrNull { it.linkUrl != null }
        assertTrue(link != null, "the autolink span must exist")
        assertEquals("https://example.test/docs", link!!.linkUrl)
        assertEquals("https://example.test/docs", link.text, "the brackets must not leak")
    }

    // ------------------------------------------------------------------ N-BI-2: footnotes

    @Test
    fun `N-BI-2 - a footnote reference and its definition pair up`() {
        val blocks = MarkdownParser.parse("text with a ref[^1] here.\n\n[^1]: the footnote body")
        val p = blocks.filterIsInstance<MarkdownParser.Block.Paragraph>().single()
        val ref = p.spans.singleOrNull { it.footnoteRef != null }
        assertTrue(ref != null, "the footnote ref must exist: ${p.spans}")
        assertEquals("1", ref!!.footnoteRef)
        val def = blocks.filterIsInstance<MarkdownParser.Block.FootnoteDefinition>().single()
        assertEquals("1", def.label)
        assertEquals("the footnote body", text(def.spans))
    }

    // ------------------------------------------------------------------ N-BI-2: escapes

    @Test
    fun `N-BI-2 - a backslash escape yields the literal char, never styled`() {
        val spans = paragraphs("escaped \\*not italic\\* and a literal \\`tick\\`").single().spans
        assertTrue(spans.all { !it.italic && !it.code && it.linkUrl == null }, "no styling may leak through escapes: $spans")
        assertEquals("escaped *not italic* and a literal `tick`", text(spans), "backslashes are consumed, chars literal")
    }

    @Test
    fun `N-BI-2 - a backslash before a non-escapable char stays literal`() {
        assertEquals("5 \\ 3", text(paragraphs("5 \\ 3").single().spans))
    }

    @Test
    fun `N-BI-2 - escape inside emphasis still emphasizes the rest`() {
        val spans = paragraphs("*a \\* b*").single().spans
        val italic = spans.singleOrNull { it.italic }
        assertTrue(italic != null, "the emphasis span must survive the escape")
        assertEquals("a * b", italic!!.text)
    }

    // ------------------------------------------------------------------ NOT adopted (recorded desktop-only)

    @Test
    fun `NOT adopted - triple asterisk stays literal (recorded desktop-only)`() {
        val spans = paragraphs("***both***").single().spans
        assertTrue(spans.none { it.bold && it.italic })
        assertEquals("***both***", text(spans))
    }

    @Test
    fun `NOT adopted - a bare URL stays plain (recorded desktop-only)`() {
        val spans = paragraphs("visit https://example.test/plain now").single().spans
        assertTrue(spans.none { it.linkUrl != null })
        assertEquals("visit https://example.test/plain now", text(spans))
    }

    @Test
    fun `NOT adopted - a backslash-newline is not a hard break (recorded desktop-only)`() {
        val spans = paragraphs("line one\\\nline two").single().spans
        assertEquals("line one\\ line two", text(spans), "the backslash stays (only ESCAPABLE chars consume it)")
    }

    @Test
    fun `NOT adopted - nested blockquotes stay flattened (recorded desktop-only)`() {
        val blocks = MarkdownParser.parse("> outer\n> > inner")
        assertEquals(1, blocks.filterIsInstance<MarkdownParser.Block.Quote>().size)
    }

    @Test
    fun `NOT adopted - inline HTML stays literal (recorded desktop-only)`() {
        assertEquals("<kbd>Ctrl</kbd>", text(paragraphs("<kbd>Ctrl</kbd>").single().spans))
    }

    @Test
    fun `NOT adopted - math stays literal (recorded desktop-only)`() {
        assertEquals("\$E=mc^2\$", text(paragraphs("\$E=mc^2\$").single().spans))
    }
}
