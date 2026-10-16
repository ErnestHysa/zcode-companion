package com.zcode.android.core.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B3-motion + K2 (gaps-and-polish Phase 2B, P1): the phone's motion census was ALL
 * ZERO — `AnimatedVisibility` 0, `animateContentSize` 0, `animate*AsState` 0,
 * `Crossfade` 0, `animateItem` 0, haptics 0 (K2) — and the NavHost had no transitions
 * (`ZCodeApp.kt`). The desktop's shipped vocabulary (K2) is the target: rows ARRIVE
 * (900ms cubic-bezier(0.16,1,0.3,1) with stagger), collapsibles animate (300ms
 * ease-in-out), shared-axis nav ≈300ms, haptic ticks on send/pick/copy — "fast and
 * low-drama… never springy" (DESIGN.md).
 *
 * Source-level gates (the census is a code fact, so the pin reads the code):
 * every K2 census item is non-zero in main sources, the NavHost declares transitions,
 * and no springy ease exists anywhere in main sources.
 */
class MotionCensusTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir) { "repo root not found" }
    }

    private fun mainSources(): Sequence<File> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { "/src/main/" in it.path && "/build/" !in it.path }
        .filter { p -> listOf("/app/", "/features/", "/core/ui/").any { it in p.path } }

    private fun count(regex: Regex): Int = mainSources().sumOf { f -> regex.findAll(f.readText()).count() }

    @Test
    fun `every K2 census item is non-zero in main sources`() {
        val census = mapOf(
            "AnimatedVisibility" to Regex("""\bAnimatedVisibility\("""),
            "animateContentSize" to Regex("""\.animateContentSize\("""),
            "animate*AsState" to Regex("""\banimate[A-Za-z]*AsState\("""),
            "Crossfade" to Regex("""\bCrossfade\("""),
            "animateItem" to Regex("""\.animateItem\("""),
            "haptics" to Regex("""\bperformHapticFeedback\("""),
        )
        val zero = census.filter { (_, re) -> count(re) == 0 }.keys
        assertTrue("K2 census items still at zero: $zero", zero.isEmpty())
    }

    @Test
    fun `the NavHost declares enter and exit transitions`() {
        val nav = File(root, "app/src/main/kotlin/com/zcode/android/app/nav/ZCodeApp.kt").readText()
        assertTrue("NavHost must declare enterTransition", "enterTransition" in nav)
        assertTrue("NavHost must declare exitTransition", "exitTransition" in nav)
        assertTrue("NavHost must declare popEnterTransition", "popEnterTransition" in nav)
        assertTrue("NavHost must declare popExitTransition", "popExitTransition" in nav)
    }

    @Test
    fun `no springy eases anywhere in main sources`() {
        val springy = Regex("""\bspring\(|\bSpring\.|\bSpringSpec\b|\bsnap\(\s*\)""")
        val offenders = mainSources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (springy.containsMatchIn(line)) "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertTrue("DESIGN.md: never springy in the workspace:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
