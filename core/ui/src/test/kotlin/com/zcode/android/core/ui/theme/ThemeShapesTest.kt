package com.zcode.android.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * K1.2 + N10-radius (gaps-and-polish Phase 2A, P1): the theme defined NO `Shapes(...)`
 * (M3 defaults 4/8/12/16/28dp applied) next to 9 literal `RoundedCornerShape` call sites
 * (3dp ×4, 4dp, 6dp ×2, 8dp, 10dp) and full-pill 999dp buttons on the first screen a
 * user sees. The desktop's shipped scale is a complete 5-step 2/4/6/8/12 (N1:
 * `--radius-xs…xl`, max 12px).
 *
 * Pinned: ONE Shapes source whose five roles are that scale (strictly increasing, max
 * 12dp, no role left on an M3 default), and a repo-wide gate that no main source
 * outside the theme spells a dp literal into `RoundedCornerShape(...)`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ThemeShapesTest {

    @get:Rule
    val rule = createComposeRule()

    private val m3Default = Shapes()

    /** Corner radius in px for a square 100×100 reference box at density 1 (== dp). */
    private fun radiusDp(shape: Shape): Float {
        val rcs = shape as RoundedCornerShape
        return rcs.topStart.toPx(Size(100f, 100f), Density(1f))
    }

    @Test
    fun `ZCodeTheme installs the desktop radius scale - not M3 defaults`() {
        var shapes: Shapes? = null
        rule.setContent { ZCodeTheme { shapes = MaterialTheme.shapes } }
        val s = shapes!!
        val roles = listOf(
            "extraSmall" to s.extraSmall,
            "small" to s.small,
            "medium" to s.medium,
            "large" to s.large,
            "extraLarge" to s.extraLarge,
        )
        val defaults = listOf(m3Default.extraSmall, m3Default.small, m3Default.medium, m3Default.large, m3Default.extraLarge)
        roles.forEachIndexed { i, (name, shape) ->
            assertNotEquals("$name must not be left on the M3 default", defaults[i], shape)
        }
        val radii = roles.map { radiusDp(it.second) }
        assertEquals("the five-step desktop scale (N1)", listOf(2f, 4f, 6f, 8f, 12f), radii)
        assertTrue("max desktop radius is 12px", radii.all { it <= 12f })
        for (i in 1 until radii.size) assertTrue("scale is strictly increasing", radii[i] > radii[i - 1])
    }

    @Test
    fun `no main source outside the theme spells a dp literal into RoundedCornerShape`() {
        val root = repoRoot()
        val offenders = mutableListOf<String>()
        val literal = Regex("""RoundedCornerShape\(\s*\d+(\.\d+)?\s*\.dp""")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val p = f.path
                "/src/main/" in p && "/build/" !in p && !p.endsWith("/theme/Shape.kt")
            }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    if (literal.containsMatchIn(line)) offenders += "${f.relativeTo(root)}:${i + 1}: ${line.trim()}"
                }
            }
        assertTrue(
            "literal RoundedCornerShape(<n>.dp) sites must come from the Shapes source:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "settings.gradle.kts not found above ${File("").absolutePath}" }
    }
}
