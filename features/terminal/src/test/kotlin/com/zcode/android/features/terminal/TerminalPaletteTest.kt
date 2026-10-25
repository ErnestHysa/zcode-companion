package com.zcode.android.features.terminal

import androidx.compose.ui.graphics.Color
import com.zcode.android.core.ui.theme.DarkScheme
import com.zcode.android.core.ui.theme.LightScheme
import com.zcode.android.features.terminal.ui.terminalColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B2.8 (gaps-and-polish Phase 1E, P0; pixel-found): the terminal ANSI palette was
 * a literal 16-color ARGB table whose WHITE entries (7/15) painted
 * `Color(0xFFFFFFFF)` on the light theme's `surfaceVariant` — invisible. The
 * white entries must resolve to the surface's own ink ROLE in BOTH themes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TerminalPaletteTest {

    @Test
    fun `ansi white is the surface ink - not literal white`() {
        assertEquals(
            "ANSI 15 (bright white) must be the light theme's ink",
            LightScheme.onSurface,
            terminalColor(15, LightScheme),
        )
        assertEquals(
            "ANSI 15 (bright white) must be the dark theme's ink",
            DarkScheme.onSurface,
            terminalColor(15, DarkScheme),
        )
        assertEquals(
            "ANSI 7 (white) must be the light theme's ink",
            LightScheme.onSurface,
            terminalColor(7, LightScheme),
        )
        assertNotEquals(
            "the literal white-on-light bug must stay dead",
            Color(0xFFFFFFFF),
            terminalColor(15, LightScheme),
        )
    }

    @Test
    fun `packed RGB passes through and colored hues stay stable`() {
        assertEquals(Color(0x11223344), terminalColor(0x11223344, LightScheme))
        assertEquals(
            "hue hues are mid-tones legible on both surfaces",
            Color(0xFFCD3131),
            terminalColor(1, LightScheme),
        )
        assertEquals(Color(0xFFCD3131), terminalColor(1, DarkScheme))
    }
}
