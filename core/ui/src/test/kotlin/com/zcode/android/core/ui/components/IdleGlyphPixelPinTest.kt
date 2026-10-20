package com.zcode.android.core.ui.components

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L5-idle (Phase 6C, pixel-found): the Idle status glyph was a MINUS BAR
 * (`Icons.Filled.Remove`) — the app's most common state rendered as a stray "—"
 * before its label on every fresh Tasks list. The swap maps IDLE/UNKNOWN to a CLOCK
 * (idle = waiting for its window); the pixel pin proves the glyph is no longer a
 * centered hairline: the icon box must carry ink OUTSIDE the vertical midline band
 * (a clock's rim), and the mapping unit-pin names the vector.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdleGlyphPixelPinTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the mapping swaps the minus bar for the clock`() {
        assertEquals(Icons.Outlined.Schedule, taskStatusGlyph(TaskStatus.IDLE))
        assertEquals(Icons.Outlined.Schedule, taskStatusGlyph(TaskStatus.UNKNOWN))
        assertNotEquals(Icons.Filled.Remove, taskStatusGlyph(TaskStatus.IDLE))
    }

    @Test
    fun `the rendered glyph is not a centered hairline - ink exists off the midline`() {
        rule.setContent {
            ZCodeTheme {
                Icon(
                    imageVector = taskStatusGlyph(TaskStatus.IDLE),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("idle_glyph").size(24.dp),
                )
            }
        }
        rule.waitForIdle()
        val img = rule.onNodeWithTag("idle_glyph").captureToImage()
        val pm = img.toPixelMap()
        val midY = img.height / 2
        // A minus bar carries ink ONLY near midY; a clock has rim ink near the top too.
        var inkNearMidline = 0
        var inkNearTop = 0
        for (x in 0 until img.width) {
            if (pm[x, midY].toArgb() != 0) inkNearMidline++
            if (pm[x, (img.height * 0.25f).toInt()].toArgb() != 0) inkNearTop++
        }
        assertTrue("the glyph draws at all", inkNearMidline > 0)
        assertTrue(
            "the idle glyph must carry ink off the vertical midline (a clock rim) — a minus bar has none",
            inkNearTop > 0,
        )
    }
}
