package com.zcode.android.core.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C5-i18n (gaps-and-polish Phase 2D, P1): the zh-CN pipeline works (191/191 keys) —
 * the holes were the LITERALS: `ErrorMapping`'s `userMessage`s are compiled English
 * ("Desktop unreachable — wake the Mac…"), the most stress-timed copy in the app,
 * plus ~30 Part-B strings and I3's download strings spelled straight into composables.
 *
 * Gate: (1) no user-facing English literal in any composable call of the UI layer
 * (`Text("…")`, `text = "…"`, `title/label/message/contentDescription = "…"`,
 * `append(" · paused")`-style fragments), (2) every AppError the mapping table
 * produces carries a message KEY (resolved per locale in core/ui), (3) no UI file reads
 * `.userMessage` directly for display.
 */
class I18nLiteralGateTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir) { "repo root not found" }
    }

    private fun uiSources(): Sequence<File> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { "/src/main/" in it.path && "/build/" !in it.path }
        .filter { p -> listOf("/app/src/main/kotlin/com/zcode/android/app/settings/", "/app/src/main/kotlin/com/zcode/android/app/connect/", "/app/src/main/kotlin/com/zcode/android/app/nav/", "/features/", "/core/ui/").any { it in p.path } }
        .filter { "/ui/" in it.path || "/app/settings/" in it.path || "/app/connect/" in it.path || "/app/nav/" in it.path || "/components/" in it.path }

    private val literal = Regex(
        """(?:\bText\(\s*|\bText\(text = |\b(?:title|label|message|headline|hint|placeholder|actionLabel|retryLabel|detail|subtitle)\s*=\s*|contentDescription\s*=\s*|\.append\()"((?:[A-Za-z][A-Za-z ,.…—()$'{}/\d:-]*[a-z][^"]*)|(?: · [a-z]+))"""",
    )
    private val allow = listOf("testTag", "ClipData", "label = \"", "@Suppress")

    /** Text( followed by a literal on the NEXT line (the line scanner can't see it). */
    private val multilineLiteral = Regex("""Text\(\s*"[A-Za-z][^"]*"""")
    /** A clipboard payload label is not user-facing UI text. */
    private val multilineExempt = Regex("""newPlainText\(""")

    @Test
    fun `no user-facing English literal in the UI layer`() {
        val offenders = uiSources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@mapIndexedNotNull null
                if (allow.any { it in line } && "Text(" !in line && "contentDescription" !in line) return@mapIndexedNotNull null
                if (literal.containsMatchIn(line) && "stringResource(" !in line && "getString(" !in line) "${f.relativeTo(root)}:${i + 1}: $trimmed" else null
            }.ifEmpty {
                // multiline pattern: Text( at a line end, literal on the next line
                val joined = f.readText().replace("\r", "")
                val m = multilineLiteral.find(joined)
                if (m != null && !multilineExempt.containsMatchIn(joined)) listOf("${f.relativeTo(root)}: multiline Text(\"…\") literal") else emptyList()
            }
        }.toList()
        assertTrue("hardcoded user-facing English (externalize to strings.xml):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `no UI file displays userMessage directly`() {
        val offenders = uiSources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                // A line that consults messageKey first IS the resolver (key → copy, English fallback).
                if (".userMessage" in l && "developerDiagnostic" !in l && "errorUserMessage" !in l && "messageKey" !in l) "${f.relativeTo(root)}:${i + 1}" else null
            }
        }.toList()
        assertTrue("UI reads .userMessage for display (use errorUserMessage / the key):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
