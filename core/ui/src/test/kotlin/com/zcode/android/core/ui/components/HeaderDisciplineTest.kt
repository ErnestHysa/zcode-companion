package com.zcode.android.core.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B3-chrome (gaps-and-polish Phase 2C, P1): four hand-rolled header variants (a Row
 * with an `ArrowBack` IconButton + a title Text), manual `statusBarsPadding` ×12,
 * duplicated stacked titles on the Files/Git/Terminal routes, no scroll elevation.
 *
 * Source gate: every screen header is the ONE `ZCodeTopBar` — no screen file may
 * hand-roll an `Icons.AutoMirrored.Filled.ArrowBack` header or pad the status bar by
 * hand (the Scaffold/TopAppBar insets own it). Pairing's `TopAppBar` is the M3
 * primitive the shared bar wraps and is exempt only inside core/ui.
 */
class HeaderDisciplineTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir) { "repo root not found" }
    }

    private fun screenSources(): Sequence<File> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { "/src/main/" in it.path && "/build/" !in it.path }
        .filter { p -> listOf("/app/", "/features/").any { it in p.path } }

    @Test
    fun `no screen hand-rolls an ArrowBack header - the shared ZCodeTopBar owns it`() {
        val offenders = screenSources().filter { f ->
            val s = f.readText()
            "Icons.AutoMirrored.Filled.ArrowBack" in s || "Icons.AutoMirrored.Default.ArrowBack" in s
        }.map { it.relativeTo(root).path }.toList()
        assertTrue("hand-rolled back headers (use ZCodeTopBar):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `no screen pads the status bar by hand`() {
        val offenders = screenSources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if ("statusBarsPadding()" in line) "${f.relativeTo(root)}:${i + 1}" else null
            }
        }.toList()
        assertTrue("manual statusBarsPadding (the Scaffold/TopAppBar insets own it):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `the shared header exists in core ui`() {
        assertTrue(File(root, "core/ui/src/main/kotlin/com/zcode/android/core/ui/components/ZCodeTopBar.kt").isFile)
    }
}
