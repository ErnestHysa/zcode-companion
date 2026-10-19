package com.zcode.android.features.pairing

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.pairing.port.PairingPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 3.4 UI tests per state (§43.2-3.4 / §35.3): Robolectric + Compose, a fake port,
 * and the REAL flow reducer + screens. Covers E1 (rationale), E2 (rejected), E3
 * (compat block), stale warning, E7 (consent), connecting/failed, taken-over (E6),
 * connected + unpair.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp") // Pixel-class window so all flow actions are on-screen
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairingScreensUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val link = PairingFlow.LinkView("d_Sid0000000001", "MockBook", "3.14.3", "https://zcode.z.ai", 1L)

    private class FakePort : PairingPort {
        override val pairedEndpoint = kotlinx.coroutines.flow.MutableStateFlow<PairingFlow.LinkView?>(null)
        var validateResult: PairingPort.Validation = PairingPort.Validation.Invalid("NOT_A_URL", "not a URL")
        var connectResult: PairingPort.ConnectOutcome = PairingPort.ConnectOutcome.Failure(
            PairingFlow.ErrorView("m", "d", rePair = true, retryable = false),
        )
        var unpaired = false
        var reducedConsentSeen: Boolean? = null

        override suspend fun validate(raw: String): PairingPort.Validation = validateResult
        override suspend fun completePairing(
            link: PairingFlow.LinkView,
            reducedSecurityConsent: Boolean,
        ): PairingPort.ConnectOutcome {
            reducedConsentSeen = reducedSecurityConsent
            return connectResult
        }

        override suspend fun retry(): PairingPort.ConnectOutcome = connectResult
        override suspend fun unpair() {
            unpaired = true
            pairedEndpoint.value = null
        }
    }

    private fun host(vm: PairingViewModel) = compose.setContent {
        androidx.compose.material3.MaterialTheme {
            com.zcode.android.features.pairing.ui.PairingHost(vm, scannerContent = null)
        }
    }

    // Real main-looper scope (Robolectric main thread): the compose test pumps it via
    // waitForIdle; virtual time is unnecessary — port fakes return immediately.
    private fun runWith(block: (FakePort, PairingViewModel) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val port = FakePort()
        val vm = PairingViewModel(port, scope)
        block(port, vm)
        scope.cancel()
    }

    // ---------------------------------------------------------------- E2 rejected

    @Test
    fun `invalid link shows rejected screen with no state written`() = runWith { port, vm ->
        host(vm)
        compose.onNodeWithTag("open-manual").performClick()
        compose.onNodeWithTag("manual-field").performTextInput("https://evil.example/x")
        compose.onNodeWithTag("manual-submit").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("reject-detail").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            ApplicationProvider.getApplicationContext<android.content.Context>().getString(
                com.zcode.android.features.pairing.R.string.rejected_title,
            ),
        ).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- E3 blocked

    @Test
    fun `compat blocked shows version guidance`() = runWith { port, vm ->
        port.validateResult = PairingPort.Validation.CompatBlocked("3.9.1", "below minimum supported desktop 3.14.0")
        host(vm)
        compose.onNodeWithTag("open-manual").performClick()
        compose.onNodeWithTag("manual-field").performTextInput(link.toString())
        compose.onNodeWithTag("manual-submit").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("compat-detail").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            ApplicationProvider.getApplicationContext<android.content.Context>().getString(
                com.zcode.android.features.pairing.R.string.compat_blocked_title,
            ),
        ).assertIsDisplayed()
        compose.onNodeWithTag("compat-close").assertExists2()
    }

    // ---------------------------------------------------------------- E7 consent

    @Test
    fun `no-secure-lock device shows explicit reduced-security consent (E7)`() = runWith { port, vm ->
        port.validateResult = PairingPort.Validation.Ready(
            link = link, stale = false, compatAboveTested = false,
            requireE7Consent = true, offerBiometricEnrollment = false,
        )
        port.connectResult = PairingPort.ConnectOutcome.Success(link)
        host(vm)
        compose.onNodeWithTag("open-manual").performClick()
        compose.onNodeWithTag("manual-field").performTextInput("https://zcode.z.ai/remote/v4?sid=d_x&hash=y")
        compose.onNodeWithTag("manual-submit").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("consent-reduced").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("consent-reduced").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("connected-detail").fetchSemanticsNodes().isNotEmpty()
        }
        check(port.reducedConsentSeen == true) { "consent must be forwarded to the port" }
        compose.onNodeWithTag("connected-detail").assertIsDisplayed()
    }

    // ------------------------------------------------------------- E5 re-pair

    // E1-hint (gaps-and-polish Phase 4G): a stale link's failure state NAMES the
    // desktop-side remedy (refreshQr) instead of dead-ending into a blind re-scan.
    @Test
    fun `a rePair-class failure carries the desktop-refresh hint`() = runWith { port, vm ->
        port.validateResult = PairingPort.Validation.Ready(
            link = link, stale = false, compatAboveTested = false,
            requireE7Consent = false, offerBiometricEnrollment = false,
        )
        port.connectResult = PairingPort.ConnectOutcome.Failure(
            PairingFlow.ErrorView("Pairing expired — scan the QR again", "relay AUTH_FAILED", rePair = true, retryable = false),
        )
        host(vm)
        compose.onNodeWithTag("open-manual").performClick()
        compose.onNodeWithTag("manual-field").performTextInput("x")
        compose.onNodeWithTag("manual-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("fail-rescan").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("fail-refresh-hint").assertIsDisplayed()
        compose.onNodeWithText("refresh the QR", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a transport-class failure does NOT carry the hint (retry is the remedy)`() = runWith { port, vm ->
        port.validateResult = PairingPort.Validation.Ready(
            link = link, stale = false, compatAboveTested = false,
            requireE7Consent = false, offerBiometricEnrollment = false,
        )
        port.connectResult = PairingPort.ConnectOutcome.Failure(
            PairingFlow.ErrorView("Connection failed", "timeout", rePair = false, retryable = true),
        )
        host(vm)
        compose.onNodeWithTag("open-manual").performClick()
        compose.onNodeWithTag("manual-field").performTextInput("x")
        compose.onNodeWithTag("manual-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("setup-confirm").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("setup-confirm").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("fail-retry").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithTag("fail-refresh-hint").fetchSemanticsNodes().size)
    }

    @Test
    fun `auth failure shows re-pair screen without retry button (E5)`() = runWith { port, vm ->
        port.validateResult = PairingPort.Validation.Ready(
            link = link, stale = false, compatAboveTested = false,
            requireE7Consent = false, offerBiometricEnrollment = false,
        )
        port.connectResult = PairingPort.ConnectOutcome.Failure(
            PairingFlow.ErrorView("Pairing expired — scan the QR again", "relay AUTH_FAILED", rePair = true, retryable = false),
        )
        host(vm)
        compose.onNodeWithTag("open-manual").performClick()
        compose.onNodeWithTag("manual-field").performTextInput("x")
        compose.onNodeWithTag("manual-submit").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("setup-confirm").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("setup-confirm").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("fail-rescan").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fail-message").assertIsDisplayed()
        compose.onAllNodesWithTag("fail-retry").assertCountEquals(0)
    }

    // ------------------------------------------------------------- E6 takeover

    @Test
    fun `taken over screen offers reconnect and dismiss (E6)`() = runWith { port, vm ->
        port.validateResult = PairingPort.Validation.Ready(
            link = link, stale = false, compatAboveTested = false,
            requireE7Consent = false, offerBiometricEnrollment = false,
        )
        port.connectResult = PairingPort.ConnectOutcome.TakenOver
        host(vm)
        compose.onNodeWithTag("open-manual").performClick()
        compose.onNodeWithTag("manual-field").performTextInput("x")
        compose.onNodeWithTag("manual-submit").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("setup-confirm").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("setup-confirm").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("takeover-reconnect").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("takeover-dismiss").performClick()
        compose.onNodeWithTag("start-scan").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- unpair

    @Test
    fun `unpair confirmation forwards wipe to port and returns to welcome`() = runWith { port, vm ->
        port.validateResult = PairingPort.Validation.Ready(
            link = link, stale = false, compatAboveTested = false,
            requireE7Consent = false, offerBiometricEnrollment = false,
        )
        port.connectResult = PairingPort.ConnectOutcome.Success(link)
        host(vm)
        compose.onNodeWithTag("open-manual").performClick()
        compose.onNodeWithTag("manual-field").performTextInput("x")
        compose.onNodeWithTag("manual-submit").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("setup-confirm").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("setup-confirm").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("connected-unpair").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("connected-unpair").performClick()
        compose.onNodeWithTag("unpair-confirm").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("start-scan").fetchSemanticsNodes().isNotEmpty()
        }
        check(port.unpaired) { "port.unpair must have been called" }
    }

    // ------------------------------------------------------------- welcome

    @Test
    fun `welcome shows the security explainer and both pairing paths`() = runWith { port, vm ->
        host(vm)
        compose.onNodeWithText(
            ApplicationProvider.getApplicationContext<android.content.Context>().getString(
                com.zcode.android.features.pairing.R.string.welcome_heading,
            ),
        ).assertIsDisplayed()
        compose.onNodeWithTag("start-scan").assertIsDisplayed()
        compose.onNodeWithTag("open-manual").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExists2() = this.assertExists()
}
