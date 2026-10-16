package com.zcode.android.core.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B3-states (gaps-and-polish Phase 2C, P1): five loading patterns (bare "Loading…"
 * text, static skeleton + redundant text, mini spinner, state-swapped row text, the
 * pairing spinner), text-only empty states, Toast ×5 instead of Snackbar, errors
 * without retry. One `EmptyState` / `ErrorState` / `LoadingSkeleton` / `SectionHeader`
 * family in core/ui, adopted on every screen (lint-able by this gate), Toasts replaced
 * by the Snackbar-backed `LocalNotifier`.
 */
class StatesDisciplineTest {

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
    fun `no Toast anywhere in screens - notices go through the Snackbar notifier`() {
        val offenders = screenSources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if ("Toast.makeText" in l) "${f.relativeTo(root)}:${i + 1}" else null }
        }.toList()
        assertTrue("Toast.makeText sites (use LocalNotifier.current.show):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `the read-only viewers adopt the shared state components`() {
        val viewers = listOf(
            "app/src/main/kotlin/com/zcode/android/app/settings/PluginsViewerScreen.kt",
            "app/src/main/kotlin/com/zcode/android/app/settings/SkillsViewerScreen.kt",
            "app/src/main/kotlin/com/zcode/android/app/settings/McpViewerScreen.kt",
            "app/src/main/kotlin/com/zcode/android/app/settings/UsageScreen.kt",
        )
        val missing = viewers.flatMap { v ->
            val s = File(root, v).readText()
            listOf("LoadingSkeleton(", "EmptyState(", "ErrorState(").filter { it !in s }.map { "$v lacks $it" }
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun `one SectionHeader - Settings no longer hand-rolls SectionLabel`() {
        val settings = File(root, "app/src/main/kotlin/com/zcode/android/app/settings/SettingsScreen.kt").readText()
        assertTrue("Settings must use the shared SectionHeader", "SectionHeader(" in settings)
        assertTrue("the private SectionLabel must be gone", "fun SectionLabel(" !in settings)
    }

    @Test
    fun `the state family exists in core ui`() {
        assertTrue(File(root, "core/ui/src/main/kotlin/com/zcode/android/core/ui/components/States.kt").isFile)
        assertTrue(File(root, "core/ui/src/main/kotlin/com/zcode/android/core/ui/components/Notifier.kt").isFile)
    }
}
