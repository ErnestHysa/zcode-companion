package com.zcode.android.features.pairing

import com.zcode.android.features.pairing.flow.PairingFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 3.4 UI-flow state machine tests — every §2.6 branch E1–E7 plus takeover
 * (E6/§15.3) and unpair. Pure reducer: no fakes needed.
 */
class PairingFlowTest {

    private val link = PairingFlow.LinkView("d_Sid0000000001", "MacBook", "3.14.3", "https://zcode.z.ai", 1L)
    private val errRetry = PairingFlow.ErrorView("m", "d", rePair = false, retryable = true)
    private val errRepair = PairingFlow.ErrorView("m", "d", rePair = true, retryable = false)

    @Test
    fun `E1 camera denial routes to rationale with manual-entry escape`() {
        var s: PairingFlow.State = PairingFlow.reduce(PairingFlow.initial(null), PairingFlow.Event.StartScan)
        assertIs<PairingFlow.State.Scanning>(s)
        s = PairingFlow.reduce(s, PairingFlow.Event.CameraPermissionDenied)
        assertIs<PairingFlow.State.CameraPermissionNeeded>(s)
        // rationale offers BOTH the retry and the manual escape
        s = PairingFlow.reduce(s, PairingFlow.Event.OpenManualEntry)
        assertIs<PairingFlow.State.ManualEntry>(s)
    }

    @Test
    fun `E2 invalid link lands on Rejected with no downstream state`() {
        var s: PairingFlow.State = PairingFlow.reduce(PairingFlow.initial(null), PairingFlow.Event.OpenManualEntry)
        s = PairingFlow.reduce(s, PairingFlow.Event.DraftChanged("garbage"))
        s = PairingFlow.reduce(s, PairingFlow.Event.QrDecoded("garbage"))
        assertIs<PairingFlow.State.Validating>(s)
        s = PairingFlow.reduce(s, PairingFlow.Event.ValidationRejected("NOT_A_URL", "detail"))
        val rejected = assertIs<PairingFlow.State.Rejected>(s)
        assertEquals("NOT_A_URL", rejected.reason)
        // From Rejected the user can re-scan or edit — never a sealed state.
        assertIs<PairingFlow.State.Scanning>(PairingFlow.reduce(s, PairingFlow.Event.StartScan))
        assertIs<PairingFlow.State.ManualEntry>(PairingFlow.reduce(s, PairingFlow.Event.OpenManualEntry))
    }

    @Test
    fun `E3 compat blocked stops pairing before any credential write`() {
        val s = PairingFlow.reduce(PairingFlow.initial(null), PairingFlow.Event.ValidationCompatBlocked("3.9.1", "below minimum"))
        val blocked = assertIs<PairingFlow.State.CompatBlocked>(s)
        assertEquals("3.9.1", blocked.version)
        // Only cancel is reachable — no continue path exists (hard block).
        assertIs<PairingFlow.State.Welcome>(PairingFlow.reduce(s, PairingFlow.Event.CancelScan))
    }

    @Test
    fun `stale warning and above-tested gate to warn-continue then setup`() {
        var s: PairingFlow.State = PairingFlow.reduce(
            PairingFlow.initial(null),
            PairingFlow.Event.ValidationReady(link, stale = true, compatAboveTested = false, requireE7Consent = false, offerBiometricEnrollment = true),
        )
        val stale = assertIs<PairingFlow.State.StaleWarning>(s)
        s = PairingFlow.reduce(stale, PairingFlow.Event.StaleAccepted)
        val setup = assertIs<PairingFlow.State.SecuritySetup>(s)
        assertEquals(link, setup.link)

        var s2: PairingFlow.State = PairingFlow.reduce(
            PairingFlow.initial(null),
            PairingFlow.Event.ValidationReady(link, stale = false, compatAboveTested = true, requireE7Consent = false, offerBiometricEnrollment = false),
        )
        s2 = PairingFlow.reduce(assertIs<PairingFlow.State.CompatAboveTested>(s2), PairingFlow.Event.CompatAccepted)
        assertIs<PairingFlow.State.SecuritySetup>(s2)
    }

    @Test
    fun `E7 no-secure-lock marks setup and explicit consent is recorded before connecting`() {
        var s: PairingFlow.State = PairingFlow.reduce(
            PairingFlow.initial(null),
            PairingFlow.Event.ValidationReady(link, stale = false, compatAboveTested = false, requireE7Consent = true, offerBiometricEnrollment = false),
        )
        val setup = assertIs<PairingFlow.State.SecuritySetup>(s)
        assertTrue(setup.requireE7Consent, "E7 flag must reach the screen (never silent)")
        s = PairingFlow.reduce(setup, PairingFlow.Event.SecurityConfirmed(reducedSecurityConsent = true))
        assertTrue((s as PairingFlow.State.SecuritySetup).reducedSecurityConsent)
    }

    @Test
    fun `E4 retryable failure offers retry E5 repair failure offers only rescan`() {
        var s: PairingFlow.State = PairingFlow.reduce(PairingFlow.initial(null), PairingFlow.Event.ConnectFailed(errRetry, link))
        val f1 = assertIs<PairingFlow.State.ConnectFailed>(s)
        assertTrue(f1.error.retryable && !f1.error.rePair)
        assertIs<PairingFlow.State.Connecting>(PairingFlow.reduce(s, PairingFlow.Event.RetryRequested))

        s = PairingFlow.reduce(PairingFlow.initial(null), PairingFlow.Event.ConnectFailed(errRepair, link))
        val f2 = assertIs<PairingFlow.State.ConnectFailed>(s)
        assertTrue(f2.error.rePair)
    }

    @Test
    fun `E6 taken over explains and reconnect requires explicit takeover`() {
        var s: PairingFlow.State = PairingFlow.reduce(PairingFlow.initial(null), PairingFlow.Event.TerminalTakenOver)
        assertIs<PairingFlow.State.TakenOver>(s)
        s = PairingFlow.reduce(s, PairingFlow.Event.TakeOverRequested)
        assertIs<PairingFlow.State.Connecting>(s)
        s = PairingFlow.reduce(PairingFlow.initial(null), PairingFlow.Event.TerminalTakenOver)
        assertIs<PairingFlow.State.Welcome>(PairingFlow.reduce(s, PairingFlow.Event.DismissTakenOver))
    }

    @Test
    fun `happy path ends in Connected and unpair confirms before wiping`() {
        var s: PairingFlow.State = PairingFlow.reduce(
            PairingFlow.initial(null),
            PairingFlow.Event.ValidationReady(link, stale = false, compatAboveTested = false, requireE7Consent = false, offerBiometricEnrollment = false),
        )
        s = PairingFlow.reduce(assertIs<PairingFlow.State.SecuritySetup>(s), PairingFlow.Event.SecurityConfirmed(reducedSecurityConsent = false))
        s = PairingFlow.reduce(s, PairingFlow.Event.ConnectSucceeded(link))
        val connected = assertIs<PairingFlow.State.Connected>(s)
        s = PairingFlow.reduce(connected, PairingFlow.Event.UnpairRequested)
        assertIs<PairingFlow.State.UnpairConfirm>(s)
        s = PairingFlow.reduce(s, PairingFlow.Event.UnpairConfirmed)
        assertIs<PairingFlow.State.Welcome>(s)
    }

    @Test
    fun `draft edits only apply within ManualEntry`() {
        val s = PairingFlow.reduce(PairingFlow.initial(null), PairingFlow.Event.DraftChanged("x"))
        assertIs<PairingFlow.State.Welcome>(s)
    }
}
