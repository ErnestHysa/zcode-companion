package com.zcode.android.features.conversation

import com.zcode.android.domain.model.TrajectoryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * N-CB-1 (pass-0 Phase 8, G100's named residual): the trajectory sheet's FAULT face
 * was an ETERNAL SPINNER — the caller derived loading from
 * `trajectory == null && showTrajectory`, which is true EXACTLY when trajectory is
 * null, so every fetch fault (offline / unscripted desktop / decode failure — the
 * typed fault receipted spinning through a full relay swap) rendered an infinite
 * loading bar and `trajectory_unavailable` was structurally unreachable.
 *
 * The fix: an EXPLICIT in-flight flag on the ViewModel. These pins hold the flag's
 * contract: true only mid-fetch, settled false after BOTH outcomes — the fault (null)
 * then renders the unavailable face, the success renders the records, and a reopen
 * re-fires (the retry affordance).
 *
 * New-API pin class: `trajectoryLoading` did not exist pre-fix (the compile-absence
 * stash demonstration is the RED record — see the fix-round note).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Pass0Phase8TrajectoryFaultTest {

    private class TrajectoryPort : Phase7FakePort() {
        var trajectoryCalls = 0
        var result: TrajectoryResult? = null
        var gate = CompletableDeferred<Unit>()
        override suspend fun fetchTrajectory(taskId: String): TrajectoryResult? {
            trajectoryCalls++
            gate.await()
            return result
        }
    }

    @Test
    fun `N-CB-1 - a faulting fetch settles the flag - the bar cannot spin forever`() = runTest {
        val port = TrajectoryPort().apply { result = null } // null = the fault class
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.loadTrajectory()
        runCurrent()
        assertTrue(
            vm.trajectoryLoading.value,
            "the flag is the honest in-flight face while the fetch runs",
        )
        port.gate.complete(Unit)
        runCurrent()
        assertFalse(
            vm.trajectoryLoading.value,
            "N-CB-1: the flag SETTLES on a fault — trajectory==null now renders the unavailable face, never an eternal bar",
        )
        assertEquals(null, vm.trajectory.value)
    }

    @Test
    fun `N-CB-1 - a success settles the flag with records and a reopen re-fires and can render the fault face`() = runTest {
        val port = TrajectoryPort().apply {
            result = TrajectoryResult(records = emptyList(), sourceFiles = emptyList(), truncated = false)
        }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.loadTrajectory()
        port.gate.complete(Unit)
        runCurrent()
        assertFalse(vm.trajectoryLoading.value)
        assertNotNull(vm.trajectory.value)
        // Reopen after a later fault: the flag runs mid-flight again and settles —
        // the sheet shows the bar, then the unavailable face (the retry affordance).
        port.gate = CompletableDeferred()
        port.result = null
        vm.loadTrajectory()
        runCurrent()
        assertTrue(vm.trajectoryLoading.value, "the reopen re-fires with the honest in-flight face")
        port.gate.complete(Unit)
        runCurrent()
        assertFalse(vm.trajectoryLoading.value, "settled again — the fault face renders")
        assertEquals(null, vm.trajectory.value)
    }
}
