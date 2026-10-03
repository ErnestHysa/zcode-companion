package com.zcode.android.core.common.ansi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §21 ANSI SGR vectors — the view-only terminal's styling contract. */
class AnsiParserTest {

    @Test
    fun `plain text passes through as one span`() {
        val d = AnsiParser.decode("hello world")
        assertEquals(1, d.spans.size)
        assertEquals("hello world", d.spans[0].text)
        assertEquals(null, d.spans[0].fg)
    }

    @Test
    fun `sgr foreground and bold split spans`() {
        val d = AnsiParser.decode("\u001B[31mred-then\u001B[0m-plain")
        assertEquals(2, d.spans.size)
        assertEquals("red-then", d.spans[0].text)
        assertEquals(1, d.spans[0].fg) // red = palette index 1
        assertEquals("-plain", d.spans[1].text)
        assertEquals(null, d.spans[1].fg)
        assertEquals(false, d.spans[1].bold)
    }

    @Test
    fun `bright foreground palette`() {
        val d = AnsiParser.decode("\u001B[92mbright-green")
        assertEquals(10, d.spans[0].fg) // 92-90+8 = 10 (bright green)
    }

    @Test
    fun `256 color packs to rgb`() {
        val d = AnsiParser.decode("\u001B[38;5;196mred256")
        val fg = d.spans[0].fg!!
        // 196 = 6×6×6 cube red corner (5,0,0) → packed 0xFF_FF_00_00 (signed negative).
        assertEquals(0xFF, fg ushr 24)
        assertEquals(255, (fg shr 16) and 0xFF)
        assertEquals(0, (fg shr 8) and 0xFF)
        assertEquals(0, fg and 0xFF)
    }

    @Test
    fun `truecolor passes through`() {
        val d = AnsiParser.decode("\u001B[38;2;12;34;56mtc")
        val fg = d.spans[0].fg!!
        assertEquals(12, (fg shr 16) and 0xFF)
        assertEquals(34, (fg shr 8) and 0xFF)
        assertEquals(56, fg and 0xFF)
    }

    @Test
    fun `cursor motion sequences are consumed`() {
        val d = AnsiParser.decode("a\u001B[2K\u001B[10;20Hb\u001B[?25lc")
        assertEquals(1, d.spans.size)
        assertEquals("abc", d.spans[0].text)
    }

    @Test
    fun `osc sequences are consumed`() {
        val d = AnsiParser.decode("a\u001B]0;window title\u0007b")
        assertEquals("ab", d.spans.joinToString("") { it.text })
        assertTrue(d.spans.none { it.text.contains("title") })
    }

    @Test
    fun `osc st terminated`() {
        // OSC-8 hyperlink: the TEXT between open/close is real content; the escapes are not.
        val d = AnsiParser.decode("a\u001B]8;;http://x\u001B\\link\u001B]8;;\u001B\\b")
        assertEquals("alinkb", d.spans.joinToString("") { it.text })
    }

    @Test
    fun `state carries across decodeLines`() {
        val (lines, endState) = AnsiParser.decodeLines("\u001B[1;32mgreen\nstill green\u001B[0m\nplain")
        assertEquals(3, lines.size)
        assertEquals(1, lines[0].size)
        assertTrue(lines[0][0].bold)
        assertTrue(lines[1][0].bold, "SGR state carries across lines")
        assertEquals(false, lines[2][0].bold, "reset on line 2 applies to line 3")
        assertEquals(false, endState.bold)
    }

    @Test
    fun `truncated escape at chunk end is dropped`() {
        val d = AnsiParser.decode("text\u001B[3")
        assertEquals("text", d.spans.joinToString("") { it.text })
    }

    @Test
    fun `stateAfter computes post-chunk sgr`() {
        val s = AnsiParser.stateAfter("\u001B[41mbackground-only", SgrState())
        assertEquals(1, s.bg)
        assertEquals(null, s.fg)
    }

    @Test
    fun `reverse underline strike dim italic`() {
        val d = AnsiParser.decode("\u001B[1;2;3;4;7;9mx")
        val s = d.spans[0]
        assertTrue(s.bold && s.dim && s.italic && s.underline && s.reverse && s.strike)
    }

    @Test
    fun `empty sgr resets`() {
        val d = AnsiParser.decode("\u001B[31mred\u001B[mplain")
        assertEquals(null, d.spans[1].fg)
    }

    @Test
    fun `bad 256 color index ignored`() {
        val d = AnsiParser.decode("\u001B[38;5;999mstill-default")
        assertEquals(null, d.spans[0].fg)
    }
}
