package com.zcode.android.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * J5 (gaps-and-polish Phase 2A, P1; pixel-proven on the onboarding seam, N4.2): the
 * M3 scheme left `surfaceContainer*` / `surfaceDim` / `surfaceBright` UNMAPPED, so every
 * AlertDialog, DropdownMenu and (future) ModalBottomSheet painted M3's neutral baseline
 * grey inside the designed-slate screens — two palettes in one flow. `outline` and
 * `outlineVariant` were the SAME token, so every divider rendered at strong weight.
 *
 * Contract pinned here: every surface-container role resolves to the designed pair (never
 * the M3 baseline), the divider variant is a SOFTER token than `outline`, and the role
 * reaches the pixels through [ZCodeTheme].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeSurfaceRolesTest {

    @get:Rule
    val rule = createComposeRule()

    private val m3LightBaseline: ColorScheme = lightColorScheme()
    private val m3DarkBaseline: ColorScheme = darkColorScheme()

    private fun surfaceRoles(s: ColorScheme) = mapOf(
        "surfaceDim" to s.surfaceDim,
        "surfaceBright" to s.surfaceBright,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
    )

    @Test
    fun `light surface-container roles are mapped - never the M3 baseline grey`() {
        val ours = surfaceRoles(LightScheme)
        val baseline = surfaceRoles(m3LightBaseline)
        for ((role, color) in ours) {
            if (color == baseline[role]) {
                // One designed coincidence: M3's lowest is also pure white. Every OTHER
                // overlap with the baseline family is the unmapped-palette bug.
                assertEquals(
                    "$role may only coincide with the baseline at the designed white",
                    Color(0xFFFFFFFF),
                    color,
                )
                assertEquals(
                    "$role must be the designed surface token itself",
                    ZCodeColorsLight.surface,
                    color,
                )
            }
        }
    }

    @Test
    fun `dark surface-container roles are mapped - never the M3 baseline grey`() {
        val ours = surfaceRoles(DarkScheme)
        val baseline = surfaceRoles(m3DarkBaseline)
        for ((role, color) in ours) {
            assertNotEquals(
                "$role must be a designed token, not M3's baseline ${baseline[role]}",
                baseline[role],
                color,
            )
        }
    }

    @Test
    fun `every surface role resolves to its designed token in both themes`() {
        fun check(scheme: ColorScheme, t: ZCodeColors, name: String) {
            assertEquals("$name surfaceDim", t.surfaceDim, scheme.surfaceDim)
            assertEquals("$name surfaceBright", t.surfaceBright, scheme.surfaceBright)
            assertEquals("$name surfaceContainerLowest", t.surfaceContainerLowest, scheme.surfaceContainerLowest)
            assertEquals("$name surfaceContainerLow", t.surfaceContainerLow, scheme.surfaceContainerLow)
            assertEquals("$name surfaceContainer", t.surfaceContainer, scheme.surfaceContainer)
            assertEquals("$name surfaceContainerHigh", t.surfaceContainerHigh, scheme.surfaceContainerHigh)
            assertEquals("$name surfaceContainerHighest", t.surfaceContainerHighest, scheme.surfaceContainerHighest)
            assertEquals("$name outlineVariant is the soft divider token", t.outlineSoft, scheme.outlineVariant)
            assertEquals("$name outline stays the control-border token", t.outline, scheme.outline)
        }
        check(LightScheme, ZCodeColorsLight, "light")
        check(DarkScheme, ZCodeColorsDark, "dark")
    }

    @Test
    fun `surface-container ladder is monotonic in both themes`() {
        // Light: lowest is the brightest, highest the most tinted (M3 semantics).
        val l = LightScheme
        assertTrue(lum(l.surfaceContainerLowest) >= lum(l.surfaceContainerLow))
        assertTrue(lum(l.surfaceContainerLow) >= lum(l.surfaceContainer))
        assertTrue(lum(l.surfaceContainer) >= lum(l.surfaceContainerHigh))
        assertTrue(lum(l.surfaceContainerHigh) >= lum(l.surfaceContainerHighest))
        assertTrue(lum(l.surfaceBright) > lum(l.surfaceDim))
        // Dark: lowest is the darkest, highest the lightest.
        val d = DarkScheme
        assertTrue(lum(d.surfaceContainerLowest) <= lum(d.surfaceContainerLow))
        assertTrue(lum(d.surfaceContainerLow) <= lum(d.surfaceContainer))
        assertTrue(lum(d.surfaceContainer) <= lum(d.surfaceContainerHigh))
        assertTrue(lum(d.surfaceContainerHigh) <= lum(d.surfaceContainerHighest))
        assertTrue(lum(d.surfaceBright) > lum(d.surfaceDim))
        // A dialog (surfaceContainerHigh) must visibly separate from the screen surface.
        assertNotEquals(l.surface, l.surfaceContainerHigh)
        assertNotEquals(d.surface, d.surfaceContainerHigh)
    }

    @Test
    fun `divider variant is a softer token than outline in both themes`() {
        assertNotEquals("light outlineVariant must not alias outline", LightScheme.outline, LightScheme.outlineVariant)
        assertNotEquals("dark outlineVariant must not alias outline", DarkScheme.outline, DarkScheme.outlineVariant)
        assertTrue(
            "light divider must be SOFTER (lower contrast vs surface) than outline",
            contrast(LightScheme.outlineVariant, LightScheme.surface) < contrast(LightScheme.outline, LightScheme.surface),
        )
        assertTrue(
            "dark divider must be SOFTER (lower contrast vs surface) than outline",
            contrast(DarkScheme.outlineVariant, DarkScheme.surface) < contrast(DarkScheme.outline, DarkScheme.surface),
        )
    }

    @Test
    fun `pixel pin - the dialog container role reaches the pixels through ZCodeTheme`() {
        var expected: Color? = null
        rule.setContent {
            ZCodeTheme {
                expected = MaterialTheme.colorScheme.surfaceContainerHigh
                Box(
                    Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .testTag("dialog-surface"),
                )
            }
        }
        val img = rule.onNodeWithTag("dialog-surface").captureToImage()
        val px = img.toPixelMap()[img.width / 2, img.height / 2]
        assertEquals(expected!!.toArgb(), px.toArgb())
        assertNotEquals(
            "the rendered dialog surface must not be M3's baseline grey",
            m3LightBaseline.surfaceContainerHigh.toArgb(),
            px.toArgb(),
        )
    }

    private fun lum(c: Color): Double {
        fun lin(v: Float): Double = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
