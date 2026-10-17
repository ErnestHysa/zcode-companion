package com.zcode.android.features.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.markdown.MarkdownParseCache
import com.zcode.android.features.conversation.markdown.MarkdownParser
import com.zcode.android.features.conversation.ui.MarkdownText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B2.15 (gaps-and-polish Phase 3C, D6-softened): the markdown parse ran per COALESCED
 * frame over the WHOLE message (~30 Hz × O(message) — N3's GPU-idle, UI-thread-bound
 * just-over-budget band). The parse is now memoized per blank-line segment (outside
 * fences): streaming a reply re-parses only the segment that changed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class MarkdownParseCacheTest {

    @get:Rule
    val rule = createComposeRule()

    private val a = "## Plan\n\nFirst paragraph that is done."
    private val b = "- item one\n  - nested\n- item two"
    private val fence = "```kotlin\nval x = 1\n\nval y = 2\n```"

    @Test
    fun `streaming frames re-parse only the changed tail segment`() {
        val cache = MarkdownParseCache()
        cache.parse("$a\n\n$b\n\nStreaming par")
        val afterFirst = cache.parseCalls
        // `a` itself holds a blank line → heading, paragraph, the list, the live tail = 4 segments.
        assertEquals(4, afterFirst)
        cache.parse("$a\n\n$b\n\nStreaming paragraph grows")
        assertEquals("only the tail re-parses", afterFirst + 1, cache.parseCalls)
        cache.parse("$a\n\n$b\n\nStreaming paragraph grows more")
        assertEquals(afterFirst + 2, cache.parseCalls)
        // An unchanged text is free.
        cache.parse("$a\n\n$b\n\nStreaming paragraph grows more")
        assertEquals(afterFirst + 2, cache.parseCalls)
    }

    @Test
    fun `the segmented parse equals the whole-text parse (fences with blank lines included)`() {
        val k5 = listOf(
            "## Rewrite plan", "", "Applied `Parser.kt` fixes in [the workspace](https://example.test/ws):", "",
            "- Top-level item one", "  - nested sub-item A", "  - nested sub-item B", "- Top-level item two", "",
            "| File | Status |", "| ---- | ------ |", "| Parser.kt | rewritten |", "| ParserTest.kt | +12 −3 |", "",
            "```kotlin", "fun parse(input: String): List<Node> =", "", "    input.lines().mapNotNull(::parseLine)", "```", "",
            "> q1", "> q2", "", "---", "", "Done ~~temporarily~~ permanently.",
        ).joinToString("\n")
        for (text in listOf(k5, "$a\n\n$fence\n\n$b", fence, "", "\n\n\n", "```\nopen fence\n\nstill open")) {
            assertEquals(MarkdownParser.parse(text), MarkdownParseCache().parse(text))
        }
    }

    @Test
    fun `an unclosed fence is one live segment - blank lines inside it never split`() {
        val segments = MarkdownParser.segments("intro\n\n```\nline\n\nmore")
        assertEquals(listOf("intro", "```\nline\n\nmore"), segments)
    }

    @Test
    fun `the cache keeps only the current text's segments (bounded memory)`() {
        val cache = MarkdownParseCache()
        cache.parse("one\n\ntwo\n\nthree")
        assertEquals(3, cache.size)
        cache.parse("four")
        assertEquals(1, cache.size)
    }

    @Test
    fun `MarkdownText reuses ONE cache across recompositions - a streamed delta costs one segment parse`() {
        val cache = MarkdownParseCache()
        var text by mutableStateOf("$a\n\n$b\n\nStreaming")
        rule.setContent { ZCodeTheme { MarkdownText(text = text, cache = cache) } }
        rule.waitForIdle()
        val first = cache.parseCalls
        assertEquals(4, first)
        text = "$a\n\n$b\n\nStreaming more"
        rule.waitForIdle()
        assertEquals(first + 1, cache.parseCalls)
        text = "$a\n\n$b\n\nStreaming more and more"
        rule.waitForIdle()
        assertEquals(first + 2, cache.parseCalls)
    }
}
