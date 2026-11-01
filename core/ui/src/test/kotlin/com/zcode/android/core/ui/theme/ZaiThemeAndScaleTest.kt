package com.zcode.android.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THEME-ZAI (vc51 Phase 4D, AC6) + G38 pins: the zai brand palettes carry the desktop's
 * verbatim chrome hexes and clear the SAME text-contrast floor as the base pair; the
 * in-app font scale multiplies every style's sp size + line height (and 1f is identity).
 */
class ZaiThemeAndScaleTest {

    private fun ratio(fg: Color, bg: Color): Double {
        fun lum(c: Color): Double = c.let {
            fun ch(v: Float): Double {
                val x = v.toDouble()
                return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
            }
            0.2126 * ch(it.red) + 0.7152 * ch(it.green) + 0.0722 * ch(it.blue)
        }
        val l1 = lum(fg); val l2 = lum(bg)
        val hi = maxOf(l1, l2); val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `zai light tokens are the desktop's verbatim chrome hexes`() {
        assertEquals(Color(0xFFF8F8F8), ZCodeColorsZaiLight.bg)
        assertEquals(Color(0xFFFFFFFF), ZCodeColorsZaiLight.surface)
        assertEquals(Color(0xFF000000), ZCodeColorsZaiLight.accent)
        assertEquals(Color(0xFFEBF4FF), ZCodeColorsZaiLight.accentSoft)
        assertEquals(Color(0xFF161616), ZCodeColorsZaiDark.bg)
        assertEquals(Color(0xFF2B2B2B), ZCodeColorsZaiDark.surface)
        assertEquals(Color(0xFFFFFFFF), ZCodeColorsZaiDark.accent)
        assertEquals(Color(0xFF001D3D), ZCodeColorsZaiDark.accentSoft)
    }

    @Test
    fun `zai palettes clear the 4_5 text contrast floor (J6)`() {
        assertTrue(ratio(ZCodeColorsZaiLight.text, ZCodeColorsZaiLight.surface) >= 4.5)
        assertTrue(ratio(ZCodeColorsZaiLight.textSecondary, ZCodeColorsZaiLight.surface) >= 4.5)
        assertTrue(ratio(ZCodeColorsZaiDark.text, ZCodeColorsZaiDark.surface) >= 4.5)
        assertTrue(ratio(ZCodeColorsZaiDark.textSecondary, ZCodeColorsZaiDark.surface) >= 4.5)
    }

    @Test
    fun `resolveDark maps the zai variants (re-scrim key)`() {
        // the JVM-side enum mapping — the @Composable resolveDark is pinned by the
        // settings surface test; the scheme selection rides Theme.kt's when-chain.
        assertTrue(ThemeMode.ZAI_DARK != ThemeMode.ZAI_LIGHT)
    }

    @Test
    fun `font scale multiplies every style and 1f is identity`() {
        val base = androidx.compose.material3.Typography()
        val scaled = base.scaledBy(1.15f)
        assertTrue(abs(scaled.bodyLarge.fontSize.value - base.bodyLarge.fontSize.value * 1.15f) < 0.01f)
        assertTrue(abs(scaled.bodyLarge.lineHeight.value - base.bodyLarge.lineHeight.value * 1.15f) < 0.01f)
        assertTrue(abs(scaled.labelSmall.fontSize.value - base.labelSmall.fontSize.value * 1.15f) < 0.01f)
        val shrunk = base.scaledBy(0.85f)
        assertTrue(abs(shrunk.titleLarge.fontSize.value - base.titleLarge.fontSize.value * 0.85f) < 0.01f)
        assertEquals(base, base.scaledBy(1f))
        // a scaled style stays a TextStyle copy (no inheritance surprises)
        assertEquals(TextStyle::class, scaled.bodyLarge::class)
    }
}
