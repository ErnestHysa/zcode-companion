package com.zcode.android.core.common.ansi

/**
 * Minimal ANSI stream decoding for the §21 view-only terminal: SGR colors and
 * attributes become styled spans; every other escape sequence (cursor motion,
 * OSC, private modes) is CONSUMED — TUI-class output renders as a plain stream
 * (§21 MVP: "no full vt100 emulation in MVP"). Pure Kotlin; the parser is a
 * stateless function over a text chunk, composed by the caller across appends.
 *
 * Sequences handled:
 *  - CSI … m (SGR): 0 reset, 1 bold, 2 dim, 3 italic, 4 underline, 7 reverse,
 *    9 strike, 22/23/24/29 off, 30–37/90–97 fg, 39 default fg, 40–47/100–107 bg,
 *    49 default bg, 38;5;N / 48;5;N (256-color), 38;2;r;g;b / 48;2;r;g;b (truecolor).
 *  - CSI <final> where final != 'm': consumed (cursor movement, erase, modes).
 *  - OSC … (BEL or ST terminated): consumed (title/link spans).
 *  - C0 controls: \r (carriage return — plain-stream: keep, renderer treats it
 *    as a line break candidate), \n, \t, \b consumed as text pass-through chars.
 */
object AnsiParser {

    /** A run of text sharing one SGR state. */
    data class Span(
        val text: String,
        val fg: Int? = null,
        val bg: Int? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val reverse: Boolean = false,
        val strike: Boolean = false,
    )

    /** One decoded line: spans + whether the source line ended inside an escape. */
    data class Decoded(val spans: List<Span>)

    private const val ESC = '\u001B'
    private const val BEL = '\u0007'

    /**
     * Decode [text] (already split into lines by the caller — see [decodeLines])
     * OR decode a raw chunk that may contain multi-line sequences. Returns spans
     * covering the whole input with SGR state carried across the chunk. Unknown
     * escapes are dropped; text content is preserved verbatim.
     */
    fun decode(text: String, initial: SgrState = SgrState()): Decoded {
        if (!text.contains(ESC)) {
            return Decoded(if (text.isEmpty()) emptyList() else listOf(Span(text, initial.fg, initial.bg, initial.bold, initial.dim, initial.italic, initial.reverse, initial.strike)))
        }
        val spans = ArrayList<Span>()
        val sb = StringBuilder()
        var state = initial

        fun flush() {
            if (sb.isNotEmpty()) {
                spans.add(Span(sb.toString(), state.fg, state.bg, state.bold, state.dim, state.italic, state.underline, state.reverse, state.strike))
                sb.clear()
            }
        }

        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c != ESC) {
                sb.append(c)
                i++
                continue
            }
            // Escape sequence start.
            if (i + 1 >= n) break // truncated escape at chunk end: drop the bare ESC
            when (val next = text[i + 1]) {
                '[' -> {
                    // CSI: parameter bytes 0x30–0x3F, intermediates 0x20–0x2F, final 0x40–0x7E.
                    var j = i + 2
                    while (j < n && text[j] in '\u0020'..'\u003F') j++
                    if (j >= n) break // truncated CSI: drop the remainder
                    val final = text[j]
                    if (final in '\u0040'..'\u007E') {
                        if (final == 'm') {
                            flush()
                            state = state.afterSgr(text.substring(i + 2, j))
                        } // else: cursor motion / erase / mode — consumed (plain stream, §21)
                        i = j + 1
                    } else {
                        i = j + 1 // malformed — skip what we scanned
                    }
                }
                ']' -> {
                    // OSC: terminated by BEL or ESC \ (ST).
                    var j = i + 2
                    while (j < n) {
                        if (text[j] == BEL) { i = j + 1; break }
                        if (text[j] == ESC && j + 1 < n && text[j + 1] == '\\') { i = j + 2; break }
                        j++
                    }
                    if (j >= n) break // unterminated OSC: drop the remainder
                    flush()
                }
                '(', ')' -> {
                    // Charset selection: ESC ( X — consume one more char.
                    if (i + 2 < n) i = i + 3 else break
                }
                else -> {
                    // Two-char escape (e.g. ESC 7, ESC 8, ESC =): consumed.
                    i += 2
                }
            }
        }
        flush()
        return Decoded(spans)
    }

    /** Splits [text] into lines, decoding each while carrying SGR state across lines. */
    fun decodeLines(text: String, initial: SgrState = SgrState()): Pair<List<List<Span>>, SgrState> {
        var state = initial
        val out = ArrayList<List<Span>>()
        val lines = text.split('\n')
        for ((index, line) in lines.withIndex()) {
            val chunk = if (index < lines.lastIndex) line + "\n" else line
            out.add(decode(chunk, state).spans)
            // The state AFTER this chunk — SGR-only walk (decode itself is chunk-stateless).
            state = stateAfter(chunk, state)
        }
        return out to state
    }

    /** Walks only SGR sequences to compute the resulting state. */
    fun stateAfter(text: String, initial: SgrState): SgrState {
        var state = initial
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i] != ESC) { i++; continue }
            if (i + 1 >= n) break
            if (text[i + 1] == '[') {
                var j = i + 2
                while (j < n && text[j] in '\u0020'..'\u003F') j++
                if (j >= n) break
                if (text[j] == 'm') state = state.afterSgr(text.substring(i + 2, j))
                i = j + 1
            } else if (text[i + 1] == ']') {
                var j = i + 2
                while (j < n) {
                    if (text[j] == BEL) { i = j + 1; break }
                    if (text[j] == ESC && j + 1 < n && text[j + 1] == '\\') { i = j + 2; break }
                    j++
                }
                if (j >= n) break
            } else if (text[i + 1] == '(' || text[i + 1] == ')') {
                if (i + 2 < n) i += 3 else break
            } else {
                i += 2
            }
        }
        return state
    }
}

/** Mutable-by-construction SGR attribute state carried across spans/chunks. */
data class SgrState(
    val fg: Int? = null,
    val bg: Int? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
    val strike: Boolean = false,
) {
    /** Standard 16-color palette values 0..15; 256-color and truecolor pass through as packed ints. */
    fun afterSgr(params: String): SgrState {
        if (params.isEmpty()) return SgrState() // CSI m == reset
        var s = this
        var idx = 0
        val parts = params.split(';', ':')
        while (idx < parts.size) {
            val code = parts[idx].toIntOrNull() ?: 0
            when (code) {
                0 -> return SgrState()
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(reverse = true)
                9 -> s = s.copy(strike = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                29 -> s = s.copy(strike = false)
                30, 31, 32, 33, 34, 35, 36, 37 -> s = s.copy(fg = code - 30)
                39 -> s = s.copy(fg = null)
                40, 41, 42, 43, 44, 45, 46, 47 -> s = s.copy(bg = code - 40)
                49 -> s = s.copy(bg = null)
                90, 91, 92, 93, 94, 95, 96, 97 -> s = s.copy(fg = code - 90 + 8)
                100, 101, 102, 103, 104, 105, 106, 107 -> s = s.copy(bg = code - 100 + 8)
                38, 48 -> {
                    val isFg = code == 38
                    val color = readExtendedColor(parts, idx) ?: break
                    idx = color.second
                    s = if (isFg) s.copy(fg = color.first) else s.copy(bg = color.first)
                }
                else -> Unit // unknown/secret SGR codes ignored
            }
            idx++
        }
        return s
    }

    /** 38;5;N (256) / 38;2;r;g;b (truecolor) → packed argb-ish int (0xFFrrggbb); returns code-end index. */
    private fun readExtendedColor(parts: List<String>, startIdx: Int): Pair<Int, Int>? {
        val mode = parts.getOrNull(startIdx + 1)?.toIntOrNull() ?: return null
        return when (mode) {
            5 -> {
                val n = parts.getOrNull(startIdx + 2)?.toIntOrNull() ?: return null
                if (n !in 0..255) return null
                packed256(n) to (startIdx + 2)
            }
            2 -> {
                val r = parts.getOrNull(startIdx + 2)?.toIntOrNull() ?: return null
                val g = parts.getOrNull(startIdx + 3)?.toIntOrNull() ?: return null
                val b = parts.getOrNull(startIdx + 4)?.toIntOrNull() ?: return null
                if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
                ((0xFF shl 24) or (r shl 16) or (g shl 8) or b) to (startIdx + 4)
            }
            else -> null
        }
    }

    /** 256-color → packed 0xFFrrggbb (16 palette + 6×6×6 cube + 24 greys). Values ≥ 0x1000000 mark "extended". */
    private fun packed256(n: Int): Int {
        if (n < 16) return n // palette index — renderer resolves via theme
        if (n < 232) {
            val i = n - 16
            val r = i / 36
            val g = (i % 36) / 6
            val b = i % 6
            fun ch(v: Int) = if (v == 0) 0 else (55 + v * 40)
            return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
        val grey = (n - 232) * 10 + 8
        return (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
    }
}
