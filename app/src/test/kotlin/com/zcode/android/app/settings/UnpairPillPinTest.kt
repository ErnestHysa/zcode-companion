package com.zcode.android.app.settings

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.AppContainer
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K1.2 + N10-radius (Phase 2A, P1; pixel-found N1): the red "Unpair this device" button
 * rendered as a FULL PILL (M3's 50% Button default). Pixel pin: with the desktop `lg`
 * step (8dp) the flat top edge reaches x = 8dp+1; a full pill leaves that pixel empty.
 * Hosted on the real SettingsScreen over a real AppContainer (Robolectric-instantiable).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnpairPillPinTest {

    class HostActivity : FragmentActivity()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `Unpair this device is a desktop-radius control - not a full pill`() {
        val activity = Robolectric.setupActivity(HostActivity::class.java)
        val container = AppContainer(activity)
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            ZCodeTheme {
                SettingsScreen(
                    container = container,
                    endpointName = "MockBook",
                    desktopVersion = "3.14.5",
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("settings_unpair").performScrollTo()
        rule.waitForIdle()
        val img = rule.onNodeWithTag("settings_unpair").captureToImage()
        val pm = img.toPixelMap()
        val x = (8f * density + 1).toInt()
        val topCenter = pm[img.width / 2, 0].toArgb()
        assertEquals("the flat top edge must reach x=${x}px (a full pill leaves it empty)", topCenter, pm[x, 0].toArgb())
        assertNotEquals("the corner must still be rounded", topCenter, pm[0, 0].toArgb())
    }
}
