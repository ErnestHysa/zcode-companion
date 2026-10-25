package com.zcode.android.features.pairing

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.pairing.port.PairingPort
import com.zcode.android.features.pairing.ui.PairingHost
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
 * K1.2 + N10-radius (Phase 2A, P1; pixel-found N1/N4.2): the onboarding CTAs — "Scan QR
 * code" (filled) and "Paste pairing link" (outlined) — rendered as FULL PILLS (M3's 50%
 * Button default), a radius the desktop language never uses (max 12px), on the very
 * first screen a user sees.
 *
 * Pixel pin: with the desktop `lg` step (8dp) the flat top edge of the button reaches
 * x = 8dp+1 — a full pill (radius = half the 40dp height) leaves that pixel empty.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingPillPinTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakePort : PairingPort {
        override val pairedEndpoint = kotlinx.coroutines.flow.MutableStateFlow<PairingFlow.LinkView?>(null)
        override suspend fun validate(raw: String): PairingPort.Validation = PairingPort.Validation.Invalid("NOT_A_URL", "not a URL")
        override suspend fun completePairing(link: PairingFlow.LinkView, reducedSecurityConsent: Boolean): PairingPort.ConnectOutcome =
            PairingPort.ConnectOutcome.Failure(PairingFlow.ErrorView("m", "d", rePair = true, retryable = false))
        override suspend fun retry(): PairingPort.ConnectOutcome = completePairing(
            PairingFlow.LinkView("d", "n", "v", "https://x", 1L), false,
        )
        override suspend fun unpair() { pairedEndpoint.value = null }
    }

    @Test
    fun `onboarding CTAs are desktop-radius controls - not full pills`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vm = PairingViewModel(FakePort(), scope)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            ZCodeTheme { PairingHost(vm, scannerContent = null) }
        }
        compose.waitForIdle()
        assertFlatTopEdgeReaches(tag = "start-scan", radiusDp = 8f, density = density)
        assertFlatTopEdgeReaches(tag = "open-manual", radiusDp = 8f, density = density)
        scope.cancel()
    }

    private fun assertFlatTopEdgeReaches(tag: String, radiusDp: Float, density: Float) {
        val img = compose.onNodeWithTag(tag).captureToImage()
        val pm = img.toPixelMap()
        val x = (radiusDp * density + 1).toInt()
        val topCenter = pm[img.width / 2, 0].toArgb()
        assertEquals(
            "$tag: the flat top edge must reach x=${x}px (a full pill leaves it empty)",
            topCenter,
            pm[x, 0].toArgb(),
        )
        assertNotEquals("$tag: the corner must still be rounded", topCenter, pm[0, 0].toArgb())
    }
}
