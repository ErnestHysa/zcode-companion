package com.zcode.android.features.pairing

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.components.ZMark
import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.pairing.port.PairingPort
import com.zcode.android.features.pairing.ui.PairingHost
import com.zcode.android.core.ui.theme.ZCodeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M1.2 (Phase 6D): the README's "small Z mark on pairing/connection" had ZERO code
 * (grep: none; pairing pixels: none). The mark exists now — on the pairing Welcome
 * screen — and the pins prove it renders as a raised monogram tile (fill ≠ background,
 * glyph ink inside) on the screen a new user sees first.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZMarkPinTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the mark is a filled tile with the glyph - not blank`() {
        rule.setContent {
            ZCodeTheme {
                ZMark(modifier = Modifier.testTag("z_mark"))
            }
        }
        rule.waitForIdle()
        val pm = rule.onNodeWithTag("z_mark").captureToImage().toPixelMap()
        // The tile fill (near the corner, inside the 8dp radius) differs from the
        // glyph-check area and from pure transparency; the glyph ink sits at center.
        val corner = pm[10, 10].toArgb()
        val center = pm[pm.width / 2, pm.height / 2].toArgb()
        val outside = pm[0, 0].toArgb()
        assertNotEquals("the tile carries the container fill", corner, outside)
        assertNotEquals("the glyph renders ink at the center", center, corner)
    }

    @Test
    fun `the mark renders on the pairing welcome screen`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vm = PairingViewModel(FakePort(), scope)
        rule.setContent { ZCodeTheme { PairingHost(vm = vm, scannerContent = null) } }
        rule.waitForIdle()
        rule.onNodeWithTag("z_mark").assertIsDisplayed()
        scope.cancel()
    }

    private class FakePort : PairingPort {
        override val pairedEndpoint = kotlinx.coroutines.flow.MutableStateFlow<PairingFlow.LinkView?>(null)
        override suspend fun validate(raw: String): PairingPort.Validation = PairingPort.Validation.Invalid("NOT_A_URL", "not a URL")
        override suspend fun completePairing(link: PairingFlow.LinkView, e7Required: Boolean, reducedSecurityConsent: Boolean): PairingPort.ConnectOutcome =
            PairingPort.ConnectOutcome.Failure(PairingFlow.ErrorView("m", "d", rePair = true, retryable = false))
        override suspend fun retry(): PairingPort.ConnectOutcome =
            PairingPort.ConnectOutcome.Failure(PairingFlow.ErrorView("m", "d", rePair = true, retryable = false))
        override suspend fun unpair() { pairedEndpoint.value = null }
    }
}
