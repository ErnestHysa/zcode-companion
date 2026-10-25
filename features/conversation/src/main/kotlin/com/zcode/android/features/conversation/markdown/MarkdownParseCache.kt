package com.zcode.android.features.conversation.markdown

/**
 * B2.15 (gaps-and-polish Phase 3C, D6-softened): the markdown parse ran per COALESCED
 * frame over the WHOLE message (~30 Hz × O(message); N3 measured the UI-thread band).
 * This memo keys the parse on blank-line SEGMENTS (never splitting inside a fence —
 * [MarkdownParser.segments]): a streamed delta re-parses only the segment it touched,
 * every earlier segment is a map hit. The memo keeps exactly the current text's
 * segments, so memory is bounded by the message, not by its history.
 *
 * One instance lives per `MarkdownText` (`remember`), i.e. per row.
 */
class MarkdownParseCache {

    private var memo: HashMap<String, List<MarkdownParser.Block>> = HashMap()

    /** Test probe: how many segment parses ran (a frame that changes one segment costs one). */
    var parseCalls: Int = 0
        private set

    /** Test probe: segments currently memoized. */
    val size: Int get() = memo.size

    fun parse(text: String): List<MarkdownParser.Block> {
        val segments = MarkdownParser.segments(text)
        val next = HashMap<String, List<MarkdownParser.Block>>(segments.size * 2)
        val out = ArrayList<MarkdownParser.Block>()
        for (seg in segments) {
            val blocks = memo[seg] ?: next[seg] ?: run {
                parseCalls++
                MarkdownParser.parse(seg)
            }
            next[seg] = blocks
            out.addAll(blocks)
        }
        memo = next
        return out
    }
}
