package com.zcode.android.features.conversation.markdown

/**
 * Bounded streaming-markdown parser (§19.2 "partial markdown … stabilized-prefix
 * strategy is our component"; ADR-015: compose-richtext's registry reality — latest
 * stable 0.17.0, alpha-only 1.0.x, single maintainer — did not justify the
 * dependency for a surface whose blocks are mostly FIRST-CLASS timeline components).
 *
 * Scope (bounded by INV-5 proportionality — the Phase 6 subset plus the Phase 3C
 * "minimum set" revisit, H1.3/H3.3/K5):
 *   blocks:  paragraphs, ATX headings (1–3), fenced code (``` with optional lang),
 *            bullets (-, *) and ordered lists (1.) WITH nesting levels (indent-driven,
 *            ≤ [MAX_LIST_DEPTH]), pipe tables (header + delimiter row required),
 *            blockquotes (consecutive `> ` lines merge), horizontal rules.
 *   inline:  `code`, **bold**, *italic*, ~~strikethrough~~, [text](url) — links render
 *            styled, never auto-opened (§26.2-T15).
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
        data class ListItem(val ordered: Boolean, val number: Int, val spans: List<Span>, val level: Int = 0) : Block()
        data class Quote(val spans: List<Span>) : Block()
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
                line.startsWith("####") || line.startsWith("###") || line.startsWith("##") || line.startsWith("#") -> {
                    flushParagraph()
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(3)
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
                    blocks.add(Block.ListItem(ordered = false, number = 0, spans = parseInline(trimmed.substring(2).trim()), level = indent))
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
                src.startsWith("[", i) && LINK.matchesAt(src, i) -> {
                    val match = LINK.find(src, i)!!
                    flush()
                    spans.add(Span(text = match.groupValues[1], linkUrl = match.groupValues[2]))
                    i = match.range.last + 1
                }
                src.startsWith("*", i) -> {
                    val end = src.indexOf('*', i + 1)
                    if (end > i) {
                        flush()
                        spans.add(Span(text = src.substring(i + 1, end), italic = true))
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

    private val LINK = Regex("\\[([^\\]]{1,256})]\\((https?://[^)\\s]{1,512})\\)")
}
