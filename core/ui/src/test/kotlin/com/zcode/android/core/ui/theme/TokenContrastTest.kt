package com.zcode.android.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import com.zcode.android.core.ui.components.StatusTone
import com.zcode.android.core.ui.components.toneColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * J6 (gaps-and-polish Phase 2A, P1): the computed 39-pair contrast table had three
 * failures worth owning — `textMuted` on surface/bg/surface2 (3.32–3.76 light,
 * 3.67–4.06 dark) and the light-mode `statusWaiting` text role (3.43:1 on surface,
 * 2.88:1 on its own 16%-tint AttentionStrip) — all below WCAG AA small text (4.5:1).
 *
 * This pin IS the recomputed table: every TEXT-role token must clear 4.5:1 on all
 * three designed surfaces in both themes, and the WAITING tone must also clear it on
 * the strip tint it is composed over. Numbers are WCAG 2.x relative luminance.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TokenContrastTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val AA_SMALL_TEXT = 4.5
    }

    private fun textRoles(t: ZCodeColors) = mapOf(
        "text" to t.text,
        "textSecondary" to t.textSecondary,
        "textMuted" to t.textMuted,
        "statusRunning" to t.statusRunning,
        "statusWaiting" to t.statusWaiting,
        "statusSuccess" to t.statusSuccess,
        "statusError" to t.statusError,
        "statusInfo" to t.statusInfo,
        "accent" to t.accent,
    )

    private fun surfaces(t: ZCodeColors) = mapOf(
        "surface" to t.surface,
        "bg" to t.bg,
        "surface2" to t.surface2,
    )

    private fun assertTable(name: String, t: ZCodeColors) {
        val failures = mutableListOf<String>()
        for ((role, fg) in textRoles(t)) {
            for ((bgName, bg) in surfaces(t)) {
                val c = contrast(fg, bg)
                if (c < AA_SMALL_TEXT) failures += "$name.$role on $bgName = ${"%.2f".format(c)}"
            }
        }
        assertTrue("text roles below AA ($AA_SMALL_TEXT:1):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `light text roles clear AA on every designed surface`() = assertTable("light", ZCodeColorsLight)

    @Test
    fun `dark text roles clear AA on every designed surface`() = assertTable("dark", ZCodeColorsDark)

    @Test
    fun `light WAITING tone clears AA on its own 16 percent strip tint`() {
        val t = ZCodeColorsLight
        // AttentionStrip(tone = WAITING): container = statusWaiting @ 16% over the surface,
        // content = statusWaiting — the chip-label path J6 measured at 11sp.
        val strip = composite(t.statusWaiting, 0.16f, t.surface)
        val c = contrast(t.statusWaiting, strip)
        assertTrue("WAITING text on its strip tint = ${"%.2f".format(c)} (needs $AA_SMALL_TEXT)", c >= AA_SMALL_TEXT)
    }

    @Test
    fun `pixel pin - light WAITING tone text path resolves to an AA token`() {
        var surface: Color? = null
        rule.setContent {
            ZCodeTheme {
                surface = MaterialTheme.colorScheme.surface
                Box(
                    Modifier
                        .size(24.dp)
                        .background(toneColor(StatusTone.WAITING))
                        .testTag("waiting-tone"),
                )
            }
        }
        val img = rule.onNodeWithTag("waiting-tone").captureToImage()
        val px = img.toPixelMap()[img.width / 2, img.height / 2]
        assertEquals(ZCodeColorsLight.statusWaiting.toArgb(), px.toArgb())
        val c = contrast(px, surface!!)
        assertTrue("rendered WAITING ink vs surface = ${"%.2f".format(c)}", c >= AA_SMALL_TEXT)
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

    /** Opaque result of [fg] at [alpha] over opaque [bg] (sRGB space, as Compose composites). */
    private fun composite(fg: Color, alpha: Float, bg: Color): Color = Color(
        red = alpha * fg.red + (1 - alpha) * bg.red,
        green = alpha * fg.green + (1 - alpha) * bg.green,
        blue = alpha * fg.blue + (1 - alpha) * bg.blue,
    )
}
