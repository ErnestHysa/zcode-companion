package com.zcode.android.features.pairing

import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.pairing.port.PairingPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-P2-B (pass-0 Phase 8; the pass-3 code pin, Part F.A2.7): after UnpairConfirmed the
 * ViewModel used to reduce from a FABRICATED source state
 * `Connected(pendingLink ?: fakeLink())` — a manufactured link with zero relationship to
 * any real pairing (the fabricated input to the reduce; the pass-3 claim's
 * "resume card claims Paired · Connect" face). The fix reduces from the GENUINE current
 * state; these pins hold the contract that makes the hazard class observable:
 *
 *  - after unpair the state is the honest unpaired `Welcome(null)` — never a Connected
 *    state, never a fabricated link (the resume card cannot claim "Paired · Connect");
 *  - the port's unpair() ran (the credential wipe precedes the state reduction);
 *  - the reduction is honest even when NO real link was ever pending (the exact input
 *    shape the fakeLink used to paper over: pendingLink == null).
 */
class Pass0Phase8UnpairStateTest {

    private class FakePort : PairingPort {
        var unpairCalls = 0
        override val pairedEndpoint = MutableStateFlow<PairingFlow.LinkView?>(null)
        override suspend fun validate(raw: String): PairingPort.Validation =
            PairingPort.Validation.Invalid("not-faked", "pin")
        override suspend fun completePairing(
            link: PairingFlow.LinkView,
            e7Required: Boolean,
            reducedSecurityConsent: Boolean,
        ): PairingPort.ConnectOutcome =
            PairingPort.ConnectOutcome.Failure(
                PairingFlow.ErrorView("not faked", "not faked", rePair = false, retryable = false),
            )
        override suspend fun retry(): PairingPort.ConnectOutcome =
            PairingPort.ConnectOutcome.Failure(
                PairingFlow.ErrorView("not faked", "not faked", rePair = false, retryable = false),
            )
        override suspend fun unpair() {
            unpairCalls++
            pairedEndpoint.value = null
        }
    }

    private fun link(sid: String = "sid-1") = PairingFlow.LinkView(
        deviceSid = sid, desktopName = "Desk", desktopAppVersion = "3.18.1",
        origin = "https://relay.example", issuedAtMs = 1L,
    )

    @Test
    fun `F-P2-B - after unpair the state is the honest unpaired Welcome(null)`() = runTest {
        val port = FakePort()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = PairingViewModel(port, scope)
        try {
            // Reach Connected through the real reduction (a genuine link, the resume card's source).
            vm.dispatch(PairingFlow.Event.ConnectSucceeded(link()))
            assertEquals(PairingFlow.State.Connected(link()), vm.state.value)
            vm.dispatch(PairingFlow.Event.UnpairRequested)
            assertEquals(
                "the confirm face carries the REAL link (no fabrication)",
                PairingFlow.State.UnpairConfirm(link()),
                vm.state.value,
            )
            vm.dispatch(PairingFlow.Event.UnpairConfirmed)
            assertTrue("unpair() ran before the state reduction", port.unpairCalls >= 1)
            assertEquals(
                "the honest unpaired state — the resume card cannot claim Paired · Connect",
                PairingFlow.State.Welcome(null),
                vm.state.value,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `F-P2-B - the reduction is honest with NO pending link (the fakeLink input shape)`() = runTest {
        val port = FakePort()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = PairingViewModel(port, scope)
        try {
            // Connected via ConnectSucceeded but the VM's pendingLink was never set
            // (a fresh process resuming a sealed credential) — the exact input shape
            // the old fakeLink() existed for.
            vm.dispatch(PairingFlow.Event.ConnectSucceeded(link()))
            vm.dispatch(PairingFlow.Event.UnpairRequested)
            vm.dispatch(PairingFlow.Event.UnpairConfirmed)
            assertEquals(
                "no fabricated link may leak into the post-unpair state",
                PairingFlow.State.Welcome(null),
                vm.state.value,
            )
        } finally {
            scope.cancel()
        }
    }
}
