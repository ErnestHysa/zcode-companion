package com.zcode.android.features.conversation.markdown

/**
 * Bounded streaming-markdown parser (§19.2 "partial markdown … stabilized-prefix
 * strategy is our component"; ADR-015: compose-richtext's registry reality — latest
 * stable 0.17.0, alpha-only 1.0.x, single maintainer — did not justify the
 * dependency for a surface whose blocks are mostly FIRST-CLASS timeline components).
 *
 * Scope (bounded by INV-5 proportionality — the Phase 6 subset plus the Phase 3C
 * "minimum set" revisit, H1.3/H3.3/K5, plus the N-BI-2 vc52 adopts):
 *   blocks:  paragraphs, ATX headings (1–6 — N-BI-1: the old clamp rendered H2–H6 at
 *            one size), fenced code (``` with optional lang), bullets (-, *) and
 *            ordered lists (1.) WITH nesting levels (indent-driven, ≤
 *            [MAX_LIST_DEPTH]), GFM task lists ([ ] / [x] — read-only glyphs),
 *            footnote definitions ([^label]: text), pipe tables (header + delimiter
 *            row required), blockquotes (consecutive `> ` lines merge), horizontal
 *            rules.
 *   inline:  `code`, **bold**, *italic*, _underscore italic_ (CommonMark flanking —
 *            intraword snake_case never emphasizes), ~~strikethrough~~,
 *            [text](url), ![alt](url) (thumbnail), autolinks <https://…>,
 *            footnote refs [^label], backslash escapes of ASCII punctuation —
 *            links render styled, never auto-opened (§26.2-T15).
 *   Explicitly NOT adopted (recorded desktop-only, streamdown 2.5.0 + remark-gfm
 *   4.0.1): `***both***`, bare-URL linking, hard line breaks (two-space and
 *   backslash-newline), nested blockquotes, indented code blocks, `$…$`/`$$…$$`
 *   math, inline HTML, mermaid.
 * Partial streams: an UNCLOSED fence renders as a literal pending-code block
 * (complete=false) — incomplete constructs never flicker as prose (§19.2).
 *
 * The parser is pure and total: ANY input yields blocks (never throws) — assistant
 * output is untrusted content (T15).
 */
object MarkdownParser {

    /** Nesting depth cap: deeper indents clamp here (hostile input stays bounded). */
    const val MAX_LIST_DEPTH = 6

    enum class Align { LEFT, CENTER, RIGHT }

    sealed class Block {
        data class Paragraph(val spans: List<Span>) : Block()
        data class Heading(val level: Int, val spans: List<Span>) : Block()
        data class CodeFence(val lang: String?, val code: String, val complete: Boolean) : Block()
        data class ListItem(
            val ordered: Boolean,
            val number: Int,
            val spans: List<Span>,
            val level: Int = 0,
            /** N-BI-2: GFM task list — null = not a task, false = [ ], true = [x]. */
            val task: Boolean? = null,
        ) : Block()
        data class Quote(val spans: List<Span>) : Block()
        /** N-BI-2: a `[^label]: body` footnote definition (rendered small, labeled). */
        data class FootnoteDefinition(val label: String, val spans: List<Span>) : Block()
        /** H1.3/K5: a pipe table — header cells, per-column alignment, body rows (padded to the header width). */
        data class Table(val header: List<List<Span>>, val align: List<Align>, val rows: List<List<List<Span>>>) : Block()
        /** `---` / `***` / `___` thematic break. */
        data object Rule : Block()
    }

    data class Span(
        val text: String,
        val code: Boolean = false,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val linkUrl: String? = null,
        val strikethrough: Boolean = false,
        /** N-BI-2: `![alt](url)` — [text] is the alt; the renderer paints a thumbnail. */
        val imageUrl: String? = null,
        /** N-BI-2: a `[^label]` footnote reference (superscript style). */
        val footnoteRef: String? = null,
    )

    fun parse(src: String): List<Block> {
        val blocks = ArrayList<Block>()
        val lines = src.lines()
        var i = 0
        val paragraph = StringBuilder()
        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks.add(Block.Paragraph(parseInline(paragraph.toString().trim())))
            }
            paragraph.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val indent = indentLevel(line)
            when {
                line.startsWith("```") -> {
                    flushParagraph()
                    val lang = line.removePrefix("```").trim().takeIf { it.isNotBlank() }
                    val code = StringBuilder()
                    i++
                    var closed = false
                    while (i < lines.size) {
                        if (lines[i].startsWith("```")) {
                            closed = true
                            i++
                            break
                        }
                        code.appendLine(lines[i])
                        i++
                    }
                    val text = code.toString().removeSuffix("\n")
                    blocks.add(Block.CodeFence(lang, text, complete = closed))
                }
                line.isBlank() -> {
                    flushParagraph()
                    i++
                }
                RULE.matches(trimmed) -> {
                    flushParagraph()
                    blocks.add(Block.Rule)
                    i++
                }
                line.startsWith("#") -> {
                    flushParagraph()
                    // N-BI-1: H1–H6 all map to distinct type — the old coerceAtMost(3)
                    // flattened H2–H6 into one size.
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    blocks.add(Block.Heading(level, parseInline(line.dropWhile { it == '#' }.trim())))
                    i++
                }
                isTableStart(lines, i) -> {
                    flushParagraph()
                    val header = splitCells(lines[i])
                    val align = parseAlignRow(lines[i + 1], header.size)
                    i += 2
                    val rows = ArrayList<List<List<Span>>>()
                    while (i < lines.size && looksLikeTableRow(lines[i])) {
                        val cells = splitCells(lines[i]).map { parseInline(it) }
                        rows.add(padRow(cells, header.size))
                        i++
                    }
                    blocks.add(Block.Table(header.map { parseInline(it) }, align, rows))
                }
                // H3.3: `"  - sub".startsWith("- ")` was false — indented sub-bullets fell
                // to the paragraph branch and rendered as prose with literal markers.
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flushParagraph()
                    val body = trimmed.substring(2).trim()
                    // GFM: the checkbox needs its trailing space (or the bare "[ ]"
                    // body) — "[ ]nospace" is plain text, not a task.
                    val task = when {
                        body == "[ ]" || body.startsWith("[ ] ") -> false
                        body == "[x]" || body.startsWith("[x] ") || body == "[X]" || body.startsWith("[X] ") -> true
                        else -> null
                    }
                    val spans = parseInline(if (task != null) body.substring(3).trim() else body)
                    blocks.add(Block.ListItem(ordered = false, number = 0, spans = spans, level = indent, task = task))
                    i++
                }
                FOOTNOTE_DEF.matches(trimmed) -> {
                    flushParagraph()
                    val m = FOOTNOTE_DEF.find(trimmed)!!
                    blocks.add(Block.FootnoteDefinition(m.groupValues[1], parseInline(m.groupValues[2].trim())))
                    i++
                }
                ORDERED.matches(trimmed) -> {
                    flushParagraph()
                    val match = ORDERED.find(trimmed)!!
                    blocks.add(
                        Block.ListItem(
                            ordered = true,
                            number = match.groupValues[1].toIntOrNull() ?: 1,
                            spans = parseInline(match.groupValues[2].trim()),
                            level = indent,
                        ),
                    )
                    i++
                }
                line.startsWith("> ") || line == ">" -> {
                    flushParagraph()
                    // Consecutive quote lines are ONE block (they used to be one block per line).
                    val quote = StringBuilder()
                    while (i < lines.size && (lines[i].startsWith("> ") || lines[i] == ">")) {
                        val part = lines[i].removePrefix(">").trim()
                        if (part.isNotEmpty()) {
                            if (quote.isNotEmpty()) quote.append(' ')
                            quote.append(part)
                        }
                        i++
                    }
                    blocks.add(Block.Quote(parseInline(quote.toString())))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                    i++
                }
            }
        }
        flushParagraph()
        return blocks
    }

    private val ORDERED = Regex("^(\\d{1,3})\\. (.*)$")
    private val RULE = Regex("^(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    /** N-BI-2: `[^label]: body` (GFM footnote definition). */
    private val FOOTNOTE_DEF = Regex("^\\[\\^([^\\]]{1,64})\\]: (.*)$")
    private val ALIGN_CELL = Regex("^:?-+:?$")

    /**
     * B2.15: blank-line segments, never split inside a fence. No block spans a blank line
     * outside a fence, so `parse(text) == segments(text).flatMap(parse)` — the memo key
     * for [MarkdownParseCache]. An unclosed fence runs to the end as one live segment.
     */
    fun segments(src: String): List<String> {
        val out = ArrayList<String>()
        val cur = ArrayList<String>()
        var inFence = false
        for (line in src.lines()) {
            if (line.startsWith("```")) {
                inFence = !inFence
                cur.add(line)
                continue
            }
            if (!inFence && line.isBlank()) {
                if (cur.isNotEmpty()) {
                    out.add(cur.joinToString("\n"))
                    cur.clear()
                }
                continue
            }
            cur.add(line)
        }
        if (cur.isNotEmpty()) out.add(cur.joinToString("\n"))
        return out
    }

    /** Indentation → nesting level: two spaces or one tab per level, clamped. */
    private fun indentLevel(line: String): Int {
        var spaces = 0
        var tabs = 0
        for (ch in line) {
            when (ch) {
                ' ' -> spaces++
                '\t' -> tabs++
                else -> break
            }
        }
        return (spaces / 2 + tabs).coerceIn(0, MAX_LIST_DEPTH)
    }

    // ------------------------------------------------------------------ tables

    /** A table starts with a pipe row FOLLOWED by a delimiter row (`| --- | :-: |`). */
    private fun isTableStart(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        if (!looksLikeTableRow(lines[i])) return false
        val header = splitCells(lines[i])
        if (header.isEmpty()) return false
        val delimiter = splitCells(lines[i + 1])
        return delimiter.isNotEmpty() && delimiter.all { ALIGN_CELL.matches(it.trim()) }
    }

    private fun looksLikeTableRow(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.length > 1
    }

    /** Split on unescaped pipes; outer pipes are delimiters, not empty cells. */
    private fun splitCells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length - 1)
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < t.length) {
            val ch = t[k]
            if (ch == '\\' && k + 1 < t.length && t[k + 1] == '|') {
                cur.append('|')
                k += 2
                continue
            }
            if (ch == '|') {
                cells.add(cur.toString().trim())
                cur.clear()
            } else {
                cur.append(ch)
            }
            k++
        }
        cells.add(cur.toString().trim())
        return cells
    }

    private fun parseAlignRow(line: String, width: Int): List<Align> {
        val cells = splitCells(line)
        return List(width) { c ->
            val cell = cells.getOrNull(c)?.trim() ?: "---"
            when {
                cell.startsWith(":") && cell.endsWith(":") -> Align.CENTER
                cell.endsWith(":") -> Align.RIGHT
                else -> Align.LEFT
            }
        }
    }

    private fun padRow(cells: List<List<Span>>, width: Int): List<List<Span>> =
        List(width) { c -> cells.getOrNull(c) ?: emptyList() }

    /**
     * Inline scanner — single pass, no regex backtracking on untrusted input.
     * Unterminated markers render literally (stable prefix: partial ** renders as
     * literal "**" until closed — no layout jumps).
     */
    fun parseInline(src: String): List<Span> {
        val spans = ArrayList<Span>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                spans.add(Span(plain.toString()))
                plain.clear()
            }
        }
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("`", i) -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        spans.add(Span(text = src.substring(i + 1, end), code = true))
                        i = end + 1
                    } else {
                        plain.append('`')
                        i++
                    }
                }
                // N-BI-2: backslash escape — a backslash before ASCII punctuation
                // yields the literal character (never styled). Not adopted as a hard
                // line break (backslash-newline stays literal, recorded desktop-only).
                src.startsWith("\\", i) && i + 1 < src.length && src[i + 1] in ESCAPABLE -> {
                    plain.append(src[i + 1])
                    i += 2
                }
                // N-BI-2 (NOT adopted): `***both***` stays literal — a bold close
                // that would swallow a third asterisk renders the run verbatim.
                src.startsWith("***", i) -> {
                    plain.append("***")
                    i += 3
                }
                src.startsWith("**", i) -> {
                    val end = src.indexOf("**", i + 2)
                    if (end > i + 1) {
                        flush()
                        spans.add(Span(text = src.substring(i + 2, end), bold = true))
                        i = end + 2
                    } else {
                        plain.append("**")
                        i += 2
                    }
                }
                // K5: `~~temporarily~~` rendered with literal tildes.
                src.startsWith("~~", i) -> {
                    val end = src.indexOf("~~", i + 2)
                    if (end > i + 1) {
                        flush()
                        spans.add(Span(text = src.substring(i + 2, end), strikethrough = true))
                        i = end + 2
                    } else {
                        plain.append("~~")
                        i += 2
                    }
                }
                // N-BI-2: `![alt](url)` BEFORE the link matcher (the old path matched
                // the inner [alt](url) and leaked the "!" literally).
                src.startsWith("![", i) && IMAGE.matchesAt(src, i) -> {
                    val match = IMAGE.find(src, i)!!
                    flush()
                    spans.add(Span(text = match.groupValues[1], imageUrl = match.groupValues[2]))
                    i = match.range.last + 1
                }
                // N-BI-2: GFM autolink literal `<https://…>` (brackets stripped).
                src.startsWith("<", i) && AUTOLINK.matchesAt(src, i) -> {
                    val match = AUTOLINK.find(src, i)!!
                    flush()
                    spans.add(Span(text = match.groupValues[1], linkUrl = match.groupValues[1]))
                    i = match.range.last + 1
                }
                // N-BI-2: `[^label]` footnote reference (superscript), matched before
                // the generic link matcher (which would read it as a link with an
                // invalid url).
                src.startsWith("[^", i) && FOOTNOTE_REF.matchesAt(src, i) -> {
                    val match = FOOTNOTE_REF.find(src, i)!!
                    flush()
                    spans.add(Span(text = match.groupValues[1], footnoteRef = match.groupValues[1]))
                    i = match.range.last + 1
                }
                src.startsWith("[", i) && LINK.matchesAt(src, i) -> {
                    val match = LINK.find(src, i)!!
                    flush()
                    spans.add(Span(text = match.groupValues[1], linkUrl = match.groupValues[2]))
                    i = match.range.last + 1
                }
                // N-BI-2: `_underscore emphasis_` — CommonMark flanking: the opening
                // `_` must not follow an alphanumeric (intraword snake_case never
                // emphasizes) and the closing `_` must not precede one.
                src.startsWith("_", i) && (i == 0 || !src[i - 1].isLetterOrDigit()) -> {
                    val end = findUnderscoreClose(src, i + 1)
                    if (end != null) {
                        flush()
                        spans.add(Span(text = src.substring(i + 1, end), italic = true))
                        i = end + 1
                    } else {
                        plain.append('_')
                        i++
                    }
                }
                src.startsWith("*", i) -> {
                    // N-BI-2: the close scan skips backslash-escaped stars — `*a \* b*`
                    // emphasizes the WHOLE body with the escaped star literal inside.
                    var end = src.indexOf('*', i + 1)
                    while (end > 0 && end > i && src[end - 1] == '\\' &&
                        !(end - 2 >= i && src[end - 2] == '\\')
                    ) {
                        end = src.indexOf('*', end + 1)
                    }
                    if (end > i) {
                        flush()
                        spans.add(Span(text = src.substring(i + 1, end).replace("\\*", "*"), italic = true))
                        i = end + 1
                    } else {
                        plain.append('*')
                        i++
                    }
                }
                else -> {
                    plain.append(src[i])
                    i++
                }
            }
        }
        flush()
        return spans
    }

    /** CommonMark's escapable ASCII punctuation set. */
    private const val ESCAPABLE = "!\"\\#$%&'()*+,-./:;<=>?@[]^_`{|}~"

    /** The closing `_` of an emphasis run: an underscore whose NEXT char is not
     *  alphanumeric (CommonMark right-flanking). */
    private fun findUnderscoreClose(src: String, from: Int): Int? {
        for (j in from until src.length) {
            if (src[j] == '_' && (j + 1 >= src.length || !src[j + 1].isLetterOrDigit())) return j
        }
        return null
    }

    private val LINK = Regex("\\[([^\\]]{1,256})]\\((https?://[^)\\s]{1,512})\\)")
    private val IMAGE = Regex("!\\[([^\\]]{1,256})]\\((https?://[^)\\s]{1,512})\\)")
    private val AUTOLINK = Regex("<((?:https?://|mailto:)[^>\\s]{1,512})>")
    private val FOOTNOTE_REF = Regex("\\[\\^([^\\]]{1,64})\\]")
}
