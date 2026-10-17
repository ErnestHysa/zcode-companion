package com.zcode.android.core.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * B2.15/J4 (gaps-and-polish Phase 3C): relative times never refreshed — `relativeTimeLabel`
 * read `System.currentTimeMillis()` once per recomposition and nothing ever recomposed on
 * the clock. One ticking `LocalRelativeNow` (minute cadence) provided at the app root;
 * every relative-time site reads it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RelativeNowTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the provided now advances on the minute cadence and recomposes readers`() {
        rule.mainClock.autoAdvance = false
        val seen = ArrayList<Long>()
        rule.setContent {
            ProvideRelativeNow(intervalMs = 60_000) {
                val now = LocalRelativeNow.current
                SideEffect { seen.add(now) }
                Text(relativeTimeLabelLive(now - 5_000) ?: "")
            }
        }
        rule.mainClock.advanceTimeByFrame()
        val first = seen.distinct().size
        assertTrue(first >= 1)
        rule.mainClock.advanceTimeBy(61_000)
        rule.mainClock.advanceTimeByFrame()
        assertTrue("now must tick after a minute: $seen", seen.distinct().size > first)
    }

    @Test
    fun `relativeTimeLabelLive reads the provided clock, not the wall clock`() {
        var label: String? = "unset"
        var expected: String? = "other"
        val then = 1_000_000L - 3 * 60_000L
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalRelativeNow provides 1_000_000L) {
                label = relativeTimeLabelLive(then)
                expected = relativeTimeLabel(then, 1_000_000L)
            }
        }
        rule.waitForIdle()
        assertEquals(expected, label)
        // The provided clock (not the wall clock) resolved it: ~55 years ago would be null.
        assertTrue(label != null)
    }

    @Test
    fun `every relative-time site rides the live clock and the app root provides it`() {
        val root = repoRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path && "/build/" !in it.path }
            .filter { "/features/" in it.path || "/app/src/main/kotlin/com/zcode/android/app/" in it.path }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, l ->
                    if (Regex("""\brelativeTimeLabel\(""").containsMatchIn(l) && "relativeTimeLabelLive" !in l) "${f.relativeTo(root)}:${i + 1}" else null
                }
            }.toList()
        assertTrue("relative-time sites must use relativeTimeLabelLive (ticking clock):\n" + offenders.joinToString("\n"), offenders.isEmpty())
        val app = File(root, "app/src/main/kotlin/com/zcode/android/app/nav/ZCodeApp.kt").readText()
        assertTrue("the app root must provide the clock", "ProvideRelativeNow" in app)
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir)
    }
}
