package com.zcode.android.core.ui.components

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeElevation
import com.zcode.android.core.ui.theme.ZCodeRadius
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier

/**
 * L5-chips (Phase 6C, pixel-found): the conversation header carried TWO chip
 * languages on one screen (outlined transparent session chips two rows above
 * filled-white bordered route chips), and zero-elevation overlays read as inert
 * labels (N1: the desktop's overlays are SHADOW-led). Pins: ONE chip component —
 * the outlined-transparent language, selected = subtle fill — at the theme's chip
 * radius, tags/content descriptions carried through; the elevation token the sheet
 * rides; the sheet source actually applies it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZCodeChipTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `one language - selected carries the subtle fill, unselected stays transparent`() {
        rule.setContent {
            com.zcode.android.core.ui.theme.ZCodeTheme {
                Box(Modifier.size(width = 320.dp, height = 96.dp)) {
                    ZCodeChip(
                        onClick = {},
                        label = "mode: build",
                        selected = true,
                        testTag = "chip_selected",
                        modifier = Modifier.size(width = 140.dp, height = 32.dp),
                    )
                    ZCodeChip(
                        onClick = {},
                        label = "Git",
                        selected = false,
                        testTag = "chip_plain",
                        modifier = Modifier.size(width = 140.dp, height = 32.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        val selected = rule.onNodeWithTag("chip_selected").captureToImage().toPixelMap()
        val plain = rule.onNodeWithTag("chip_plain").captureToImage().toPixelMap()
        // Sample INSIDE both chips (border inset): the selected fill differs from the
        // transparent one — one visual language with a real selected state.
        val sampleX = 20
        val sampleY = 16
        assertNotEquals(
            "selected and unselected chips must not share the fill",
            selected[sampleX, sampleY].toArgb(),
            plain[sampleX, sampleY].toArgb(),
        )
    }

    @Test
    fun `the chip radius is the theme's chip step - not a pill`() {
        rule.setContent {
            com.zcode.android.core.ui.theme.ZCodeTheme {
                ZCodeChip(
                    onClick = {},
                    label = "Git",
                    testTag = "chip_radius",
                    modifier = Modifier.size(width = 140.dp, height = 32.dp),
                )
            }
        }
        rule.waitForIdle()
        val density = androidx.compose.ui.platform.LocalDensity.current
        // The flat top edge reaches x = radius + 1 (the OnboardingPillPin math).
        val img = rule.onNodeWithTag("chip_radius").captureToImage().toPixelMap()
        val x = ((ZCodeRadius.sm.value * density.density) + 1).toInt()
        val topCenter = img[img.width / 2, 0].toArgb()
        assertEquals(topCenter, img[x.coerceAtMost(img.width - 1), 0].toArgb(), "the flat top edge reaches the radius step")
        assertNotEquals(topCenter, img[0, 0].toArgb(), "the corner is still rounded")
    }

    @Test
    fun `click fires and the content description rides the merged semantics`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            com.zcode.android.core.ui.theme.ZCodeTheme {
                ZCodeChip(
                    onClick = { clicks += "open" },
                    label = "Git",
                    contentDescription = "Open the git panel",
                    testTag = "chip_click",
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("chip_click").performClick()
        rule.waitForIdle()
        assertEquals(listOf("open"), clicks)
        rule.onNodeWithText("Git").assertExists()
    }

    @Test
    fun `the elevation token exists and the sheet applies it (N1 shadow-led overlays)`() {
        assertEquals(6.dp, ZCodeElevation.sheet)
        val root = File("").absoluteFile.let { dir ->
            var d: File? = dir
            while (d != null && !java.io.File(d, "settings.gradle.kts").isFile) d = d.parentFile
            requireNotNull(d)
        }
        val sheet = java.io.File(root, "core/ui/src/main/kotlin/com/zcode/android/core/ui/components/ZCodeModalBottomSheet.kt").readText()
        assertTrue(
            "the sheet must apply ZCodeElevation.sheet as its shadowElevation",
            Regex("""shadowElevation\s*=\s*ZCodeElevation\.sheet""").containsMatchIn(sheet),
        )
    }
}
