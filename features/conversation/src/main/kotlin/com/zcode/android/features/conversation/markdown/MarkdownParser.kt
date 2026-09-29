package com.zcode.android.features.conversation.markdown

/**
 * Bounded streaming-markdown parser (§19.2 "partial markdown … stabilized-prefix
 * strategy is our component"; ADR-015: compose-richtext's registry reality — latest
 * stable 0.17.0, alpha-only 1.0.x, single maintainer — did not justify the
 * dependency for a surface whose blocks are mostly FIRST-CLASS timeline components).
 *
 * Scope (bounded by INV-5 proportionality — this is a Phase 6 subset, revisit at
 * Phase 11 if full CommonMark fidelity is ever required):
 *   blocks:  paragraphs, ATX headings (1–3), fenced code (``` with optional lang),
 *            bullets (-, *), ordered lists (1.), blockquotes (>).
 *   inline:  `code`, **bold**, *italic*, [text](url) — links render styled, never
 *            auto-opened (§26.2-T15).
 * Partial streams: an UNCLOSED fence renders as a literal pending-code block
 * (complete=false) — incomplete constructs never flicker as prose (§19.2).
 *
 * The parser is pure and total: ANY input yields blocks (never throws) — assistant
 * output is untrusted content (T15).
 */
object MarkdownParser {

    sealed class Block {
        data class Paragraph(val spans: List<Span>) : Block()
        data class Heading(val level: Int, val spans: List<Span>) : Block()
        data class CodeFence(val lang: String?, val code: String, val complete: Boolean) : Block()
        data class ListItem(val ordered: Boolean, val number: Int, val spans: List<Span>) : Block()
        data class Quote(val spans: List<Span>) : Block()
    }

    data class Span(
        val text: String,
        val code: Boolean = false,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val linkUrl: String? = null,
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
                line.startsWith("####") || line.startsWith("###") || line.startsWith("##") || line.startsWith("#") -> {
                    flushParagraph()
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(3)
                    blocks.add(Block.Heading(level, parseInline(line.dropWhile { it == '#' }.trim())))
                    i++
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flushParagraph()
                    blocks.add(Block.ListItem(ordered = false, number = 0, spans = parseInline(line.substring(2).trim())))
                    i++
                }
                ORDERED.matches(line) -> {
                    flushParagraph()
                    val match = ORDERED.find(line)!!
                    blocks.add(
                        Block.ListItem(
                            ordered = true,
                            number = match.groupValues[1].toIntOrNull() ?: 1,
                            spans = parseInline(match.groupValues[2].trim()),
                        ),
                    )
                    i++
                }
                line.startsWith("> ") || line == ">" -> {
                    flushParagraph()
                    blocks.add(Block.Quote(parseInline(line.removePrefix("> ").trim())))
                    i++
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
            val rest = src.substring(i)
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
