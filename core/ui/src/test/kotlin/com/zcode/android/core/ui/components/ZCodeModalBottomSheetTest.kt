package com.zcode.android.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B3-sheets (Phase 2C): pixel pin for the sheet chrome — the sheet paints the J5
 * `surfaceContainerLow` role and its content area reaches the pixels inside the
 * sheet window (night, where the designed pair is pixel-legible).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp-night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZCodeModalBottomSheetTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `pixel pin - sheet content is composed over the designed sheet role`() {
        var sheetRole = 0
        rule.setContent {
            ZCodeTheme {
                sheetRole = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
                ZCodeModalBottomSheet(onDismissRequest = {}) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .testTag("sheet_body"),
                    )
                }
            }
        }
        rule.waitForIdle()
        val img = rule.onNodeWithTag("sheet_body").captureToImage()
        val px = img.toPixelMap()[img.width / 2, img.height / 2].toArgb()
        assertEquals("the sheet body shows the surfaceContainerLow role (J5), not M3 baseline", sheetRole, px)
        assertNotEquals("and it is not the screen background", ZCodeThemeBackgroundArgb(), px)
    }

    @Suppress("TestFunctionName")
    private fun ZCodeThemeBackgroundArgb(): Int = com.zcode.android.core.ui.theme.ZCodeColorsDark.bg.toArgb()
}
