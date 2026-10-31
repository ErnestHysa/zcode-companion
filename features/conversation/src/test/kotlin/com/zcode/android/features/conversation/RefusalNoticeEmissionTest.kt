package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.port.ConversationPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * GAP-A13 (vc51 roadmap, sub-phase 1B; pass-11 live receipts): slash/goal REFUSAL
 * feedback is invisible at refusal time and stale when it finally paints.
 * `_sendNotice` is a `.value` READ inside the uiState combine — a notice-only change
 * cannot re-emit state, so the refused verb looks like a dead button until some
 * UNRELATED source happens to fire (on the rig: minutes later, showing stale copy).
 *
 * The pin proves the notice-ONLY case: after the setup settles, the refusal touches
 * nothing but the notice — uiState must still RE-EMIT and carry the refusal notice.
 * (Existing refusal tests pass incidentally: their flows touch other sources.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefusalNoticeEmissionTest {

    /** A port whose slash verbs refuse (the rig's MOCK_REFUSE_COMMANDS shape). */
    private class RefusingPort : Phase7FakePort() {
        override suspend fun pauseGoal(taskId: String): ConversationPort.SendOutcome =
            ConversationPort.SendOutcome.FAILED

        override suspend fun compact(taskId: String): ConversationPort.SendOutcome =
            ConversationPort.SendOutcome.FAILED
    }

    @Test
    fun `A13 - a refused goal pause re-emits uiState with fresh copy, draft preserved`() = runTest {
        val vm = ConversationViewModel(RefusingPort(), backgroundScope, "s-a13")
        runCurrent()
        var emissions = 0
        val job = launch { vm.state.collect { emissions++ } }
        runCurrent() // the initial emission lands
        val baseline = emissions
        assertTrue(baseline >= 1, "precondition: uiState emitted at least once before the refusal")

        vm.onDraftChange("/goal pause")
        runCurrent()
        val afterDraft = emissions
        vm.send()
        runCurrent() // give the combine every chance to react to the notice-only change

        assertTrue(
            emissions > afterDraft,
            "a notice-only change MUST re-emit uiState (GAP-A13): emissions $afterDraft -> $emissions",
        )
        val notice = vm.state.value.sendNotice
        assertEquals(
            SendNotice.Key.GOAL_PAUSE_REFUSED,
            notice?.key,
            "the refusal banner must paint with the FRESH pause copy at refusal time",
        )
        assertEquals("/goal pause", vm.state.value.draft, "refusal semantics preserve the draft")
        job.cancel()
    }

    @Test
    fun `A13 - a refused compact re-emits uiState with the refusal notice`() = runTest {
        val vm = ConversationViewModel(RefusingPort(), backgroundScope, "s-a13b")
        runCurrent()
        var emissions = 0
        val job = launch { vm.state.collect { emissions++ } }
        runCurrent()
        vm.onDraftChange("/compact")
        runCurrent()
        val afterDraft = emissions
        vm.send()
        runCurrent()

        assertTrue(
            emissions > afterDraft,
            "a notice-only change MUST re-emit uiState (GAP-A13): emissions $afterDraft -> $emissions",
        )
        assertTrue(vm.state.value.sendNotice != null, "the compact refusal must be visible")
        job.cancel()
    }
}
