package com.zcode.android.features.pairing

import com.zcode.android.features.pairing.flow.PairingFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * B2.10 (gaps-and-polish Phase 1E, P0): the compat screens rendered TWO HARDCODED
 * desktop versions ("3.14.0", "3.14.3") — the boundaries drifted from the
 * CompatibilityGate the moment the gate moved. The boundaries now thread
 * port → event → state → UI. This pin locks the thread-through: the state ALWAYS
 * carries what the port supplied (the app layer fills it from the gate's
 * constants — asserted there), and an unset boundary degrades to the copy's own
 * version (never a blank).
 */
class CompatBoundaryThreadTest {

    @Test
    fun `compat-blocked state carries the port's min-supported boundary`() {
        val state = PairingFlow.reduce(
            PairingFlow.State.Scanning,
            PairingFlow.Event.ValidationCompatBlocked("3.13.0", "below minimum supported desktop 3.14.0", minSupported = "3.14.0"),
        ) as PairingFlow.State.CompatBlocked

        assertEquals("3.14.0", state.minSupported)
        assertEquals("3.13.0", state.version)
    }

    @Test
    fun `compat-above state carries the port's max-tested boundary`() {
        val link = PairingFlow.LinkView(
            deviceSid = "d", desktopName = "Desk", desktopAppVersion = "3.15.0",
            origin = "", issuedAtMs = 1L,
        )
        val state = PairingFlow.reduce(
            PairingFlow.State.Scanning,
            PairingFlow.Event.ValidationReady(
                link = link, stale = false, compatAboveTested = true,
                requireE7Consent = false, offerBiometricEnrollment = false,
                maxTestedDesktop = "3.14.4",
            ),
        ) as PairingFlow.State.CompatAboveTested

        assertEquals("3.14.4", state.maxTestedDesktop)
        assertTrue(state.link === link)
    }

    @Test
    fun `an unset boundary stays null (UI falls back - never blank copy)`() {
        val state = PairingFlow.reduce(
            PairingFlow.State.Scanning,
            PairingFlow.Event.ValidationCompatBlocked("3.13.0", "detail"),
        ) as PairingFlow.State.CompatBlocked

        assertNull(state.minSupported)
    }
}
