package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.markdown.CodeHighlighter
import com.zcode.android.features.conversation.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1.3+H3.3+K5 (gaps-and-polish Phase 3C, P2): the markdown minimum set, pinned on
 * K5's EXACT live fixture (`script.demoAssistantMarkdown`). Live dump facts the pins
 * reproduce: nested sub-items rendered as ONE prose line with literal markers
 * ("- nested sub-item A - nested sub-item B"), the 2×2 pipe table rendered as a raw
 * text block, `~~temporarily~~` rendered with literal tildes.
 */
class MarkdownMinimumSetTest {

    /** `MockRelayMain.script.demoAssistantMarkdown`, verbatim. */
    private val k5 = listOf(
        "## Rewrite plan",
        "",
        "Applied `Parser.kt` fixes in [the workspace](https://example.test/ws):",
        "",
        "- Top-level item one",
        "  - nested sub-item A",
        "  - nested sub-item B",
        "- Top-level item two",
        "",
        "| File | Status |",
        "| ---- | ------ |",
        "| Parser.kt | rewritten |",
        "| ParserTest.kt | +12 −3 |",
        "",
        "```kotlin",
        "fun parse(input: String): List<Node> =",
        "    input.lines().mapNotNull(::parseLine)",
        "```",
        "",
        "Done ~~temporarily~~ permanently.",
    ).joinToString("\n")

    private fun text(spans: List<MarkdownParser.Span>) = spans.joinToString("") { it.text }

    // ------------------------------------------------------------------ nested lists (H3.3)

    @Test
    fun `K5 - nested sub-items are list items with a nesting level, never prose`() {
        val blocks = MarkdownParser.parse(k5)
        val items = blocks.filterIsInstance<MarkdownParser.Block.ListItem>()
        assertEquals(listOf("Top-level item one", "nested sub-item A", "nested sub-item B", "Top-level item two"), items.map { text(it.spans) })
        assertEquals(listOf(0, 1, 1, 0), items.map { it.level })
        // The live defect: a paragraph carrying the literal markers.
        val prose = blocks.filterIsInstance<MarkdownParser.Block.Paragraph>().map { text(it.spans) }
        assertTrue("no paragraph may carry list markers: $prose", prose.none { "- nested" in it })
    }

    @Test
    fun `ordered lists nest too and keep their numbers`() {
        val blocks = MarkdownParser.parse("1. first\n   1. inner one\n   2. inner two\n2. second")
        val items = blocks.filterIsInstance<MarkdownParser.Block.ListItem>()
        assertEquals(4, items.size)
        assertEquals(listOf(0, 1, 1, 0), items.map { it.level })
        assertEquals(listOf(1, 1, 2, 2), items.map { it.number })
        assertTrue(items.all { it.ordered })
    }

    @Test
    fun `deep nesting is bounded and tab indentation counts`() {
        val blocks = MarkdownParser.parse("- a\n\t- b\n\t\t- c\n\t\t\t\t\t\t\t\t- deep")
        val items = blocks.filterIsInstance<MarkdownParser.Block.ListItem>()
        assertEquals(listOf(0, 1, 2, MarkdownParser.MAX_LIST_DEPTH), items.map { it.level })
    }

    // ------------------------------------------------------------------ tables (H1.3/K5)

    @Test
    fun `K5 - the 2x2 pipe table parses into header and rows`() {
        val table = MarkdownParser.parse(k5).filterIsInstance<MarkdownParser.Block.Table>().single()
        assertEquals(listOf("File", "Status"), table.header.map { text(it) })
        assertEquals(listOf(listOf("Parser.kt", "rewritten"), listOf("ParserTest.kt", "+12 −3")), table.rows.map { r -> r.map { text(it) } })
        // No paragraph carries raw pipes.
        val prose = MarkdownParser.parse(k5).filterIsInstance<MarkdownParser.Block.Paragraph>().map { text(it.spans) }
        assertTrue("raw pipe text leaked into prose: $prose", prose.none { it.contains("| File") })
    }

    @Test
    fun `table alignment row is honored and ragged rows are padded`() {
        val src = "| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 |\n| x | y | z | extra |"
        val table = MarkdownParser.parse(src).filterIsInstance<MarkdownParser.Block.Table>().single()
        assertEquals(listOf(MarkdownParser.Align.LEFT, MarkdownParser.Align.CENTER, MarkdownParser.Align.RIGHT), table.align)
        assertEquals(3, table.rows[0].size)
        assertEquals("", text(table.rows[0][2]))
        assertEquals(3, table.rows[1].size)
    }

    @Test
    fun `a pipe line without a delimiter row stays prose (no false tables)`() {
        val blocks = MarkdownParser.parse("a | b is not a table\nstill prose")
        assertTrue(blocks.single() is MarkdownParser.Block.Paragraph)
    }

    @Test
    fun `table cells keep inline formatting`() {
        val table = MarkdownParser.parse("| k | v |\n|---|---|\n| `code` | **bold** |").filterIsInstance<MarkdownParser.Block.Table>().single()
        assertTrue(table.rows[0][0].single().code)
        assertTrue(table.rows[0][1].single().bold)
    }

    // ------------------------------------------------------------------ strikethrough + hr + quotes

    @Test
    fun `K5 - strikethrough is a span, not literal tildes`() {
        val last = MarkdownParser.parse(k5).last() as MarkdownParser.Block.Paragraph
        val struck = last.spans.single { it.strikethrough }
        assertEquals("temporarily", struck.text)
        assertFalse(text(last.spans).contains("~~"))
        assertEquals("Done temporarily permanently.", text(last.spans))
    }

    @Test
    fun `unterminated strikethrough renders literally (stable prefix)`() {
        val spans = MarkdownParser.parseInline("streaming ~~partial")
        assertEquals("streaming ~~partial", text(spans))
        assertTrue(spans.none { it.strikethrough })
    }

    @Test
    fun `horizontal rules parse and consecutive quote lines merge into one block`() {
        val blocks = MarkdownParser.parse("> line one\n> line two\n\n---\n\nafter")
        assertTrue(blocks[0] is MarkdownParser.Block.Quote)
        assertEquals("line one line two", text((blocks[0] as MarkdownParser.Block.Quote).spans))
        assertTrue(blocks[1] is MarkdownParser.Block.Rule)
        assertTrue(blocks[2] is MarkdownParser.Block.Paragraph)
    }

    // ------------------------------------------------------------------ highlighting

    @Test
    fun `kotlin fence tokenizes keywords strings comments and numbers`() {
        val tokens = CodeHighlighter.tokenize("kotlin", "fun parse(input: String): Int { // note\n  val s = \"x\" + 42\n  return 1 }")
        fun kinds(text: String) = tokens.filter { it.text == text }.map { it.kind }
        assertEquals(listOf(CodeHighlighter.Kind.KEYWORD), kinds("fun"))
        assertEquals(listOf(CodeHighlighter.Kind.KEYWORD), kinds("val"))
        assertEquals(listOf(CodeHighlighter.Kind.STRING), kinds("\"x\""))
        assertEquals(listOf(CodeHighlighter.Kind.NUMBER), kinds("42"))
        assertTrue(tokens.any { it.kind == CodeHighlighter.Kind.COMMENT && it.text.startsWith("// note") })
        // Round-trips the source exactly — the tokens cover every character once.
        assertEquals("fun parse(input: String): Int { // note\n  val s = \"x\" + 42\n  return 1 }", tokens.joinToString("") { it.text })
    }

    @Test
    fun `python bash and json pick their comment and keyword vocabularies`() {
        val py = CodeHighlighter.tokenize("python", "def f():\n  # c\n  return None")
        assertTrue(py.any { it.text == "def" && it.kind == CodeHighlighter.Kind.KEYWORD })
        assertTrue(py.any { it.kind == CodeHighlighter.Kind.COMMENT && it.text.startsWith("# c") })
        val sh = CodeHighlighter.tokenize("bash", "if [ -f x ]; then echo ok; fi # done")
        assertTrue(sh.any { it.text == "then" && it.kind == CodeHighlighter.Kind.KEYWORD })
        val json = CodeHighlighter.tokenize("json", "{\"a\": true, \"n\": 3}")
        assertTrue(json.any { it.text == "true" && it.kind == CodeHighlighter.Kind.KEYWORD })
        assertTrue(json.any { it.text == "\"a\"" && it.kind == CodeHighlighter.Kind.STRING })
    }

    @Test
    fun `unknown or absent language yields plain tokens only and the highlighter is total`() {
        val plain = CodeHighlighter.tokenize(null, "fun x = \"y\" // z")
        assertTrue(plain.all { it.kind == CodeHighlighter.Kind.PLAIN })
        val weird = CodeHighlighter.tokenize("brainf", "+++[>+<-]")
        assertTrue(weird.all { it.kind == CodeHighlighter.Kind.PLAIN })
        for (h in listOf("", "\"", "\"\"\"", "/*", "//", "'''", "\u0000\uFFFF", "a".repeat(20_000))) {
            CodeHighlighter.tokenize("kotlin", h)
            CodeHighlighter.tokenize("python", h)
        }
    }

    @Test
    fun `parser stays TOTAL on hostile table and list input`() {
        for (h in listOf("|", "||", "|---|", "| a |\n|", "- \n  - \n    - ", "~~", "~~~~", "|:|:|\n|-|-|")) {
            MarkdownParser.parse(h)
        }
    }
}
