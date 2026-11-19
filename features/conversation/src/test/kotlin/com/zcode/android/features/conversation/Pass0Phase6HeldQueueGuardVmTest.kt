package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass-0 Phase 6 — the ViewModel half of the held-queue guard family
 * (F-P19-A + F-P20-A) and the honest stale face's reachability (N-BQ-1 pins at
 * the transport layer; this file guards the consuming branch).
 *
 * F-P19-A (pass 19): on `guard.heldQueueConfirmationStale` the desktop re-opens
 * the held-queue confirmation with fresh ids (confirmationRequired → the
 * confirmation state re-enters; NO fail settle is recorded). The phone rendered
 * the generic refusal ERROR instead. Now the ViewModel raises a re-confirmation
 * epoch (the composer re-arms its disposition dialog; the user answers with ids
 * re-read from the live projection at answer time), the draft survives, and no
 * refusal notice renders — the desktop's silent-retry loop parity.
 *
 * F-P20-A (passes 20-22): the goal surfaces carry the answered trio — the
 * composer-slash /goal branch forwards it, the sheet's Set-goal gate sends it,
 * and a G50 answer is never silently discarded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Pass0Phase6HeldQueueGuardVmTest {

    /** Fake capturing the goal trio + scripting send outcomes. */
    private class GuardPort : Phase7FakePort() {
        var promptOutcome = ConversationPort.SendOutcome.ACCEPTED
        var goalOutcome = ConversationPort.SendOutcome.ACCEPTED
        val promptCalls = mutableListOf<String>()
        val goalObjectives = mutableListOf<String>()
        val goalDispositions = mutableListOf<String?>()
        val goalIds = mutableListOf<List<String>?>()
        val goalDisplayTexts = mutableListOf<String>()

        override suspend fun sendPrompt(
            taskId: String, text: String, heldQueueDisposition: String?,
            requestedDelivery: String?, expectedHeldQueueItemIds: List<String>?,
        ): ConversationPort.SendOutcome {
            promptCalls += text
            return promptOutcome
        }

        override suspend fun sendGoal(
            taskId: String, objective: String, displayText: String,
            heldQueueDisposition: String?, expectedHeldQueueItemIds: List<String>?,
        ): ConversationPort.SendOutcome {
            goalObjectives += objective
            goalDisplayTexts += displayText
            goalDispositions += heldQueueDisposition
            goalIds += expectedHeldQueueItemIds
            return goalOutcome
        }
    }

    // ------------------------------------------------------------------ F-P19-A

    @Test
    fun `F-P19-A - a confirmation-required send re-arms the dialog request, keeps the draft, shows NO refusal`() = runTest {
        val port = GuardPort().apply { promptOutcome = ConversationPort.SendOutcome.CONFIRMATION_REQUIRED }
        val vm = ConversationViewModel(port, backgroundScope, "s-p19")
        vm.onDraftChange("p19casB")
        vm.send()
        runCurrent()
        assertEquals(1, vm.state.value.heldQueueReconfirmation, "the re-confirmation epoch bumped (the dialog re-arms)")
        assertNull(vm.state.value.sendNotice, "the desktop settles NO error on the guard code (silent retry loop)")
        assertEquals("p19casB", vm.state.value.draft, "the draft survives — the answer re-sends it")
    }

    @Test
    fun `F-P19-A - repeated mismatches keep bumping the epoch (the loop stays alive)`() = runTest {
        val port = GuardPort().apply { promptOutcome = ConversationPort.SendOutcome.CONFIRMATION_REQUIRED }
        val vm = ConversationViewModel(port, backgroundScope, "s-p19")
        vm.onDraftChange("p19cas")
        vm.send()
        runCurrent()
        vm.send() // the re-opened dialog answered again (ids still stale on the rig)
        runCurrent()
        assertEquals(2, vm.state.value.heldQueueReconfirmation)
        assertNull(vm.state.value.sendNotice)
    }

    @Test
    fun `F-P19-A - the epoch never bumps on an ordinary refusal (control)`() = runTest {
        val port = GuardPort().apply { promptOutcome = ConversationPort.SendOutcome.FAILED }
        val vm = ConversationViewModel(port, backgroundScope, "s-p19")
        vm.onDraftChange("plain refusal")
        vm.send()
        runCurrent()
        assertEquals(0, vm.state.value.heldQueueReconfirmation)
        assertNotNull(vm.state.value.sendNotice, "the generic refusal notice still renders for non-guard failures")
    }

    @Test
    fun `F-P19-A - the slash-goal confirmation-required answer re-arms too (desktop re-opens on the slash path)`() = runTest {
        val port = GuardPort().apply { goalOutcome = ConversationPort.SendOutcome.CONFIRMATION_REQUIRED }
        val vm = ConversationViewModel(port, backgroundScope, "s-p19")
        vm.onDraftChange("/goal rig goal")
        vm.send()
        runCurrent()
        // Composer-slash sends carry the trio only when the dialog answered — here
        // the mismatch is injected by the fake to verify the re-arm + no-notice face.
        assertEquals(1, vm.state.value.heldQueueReconfirmation)
        assertNull(vm.state.value.sendNotice, "no generic goal-refusal banner on the guard code")
        assertEquals("/goal rig goal", vm.state.value.draft, "the slash draft survives — the answer re-parses it")
    }

    // ------------------------------------------------------------------ F-P20-A

    @Test
    fun `F-P20-A - a dialog-answered composer-slash goal send carries the trio to the wire`() = runTest {
        val port = GuardPort()
        val vm = ConversationViewModel(port, backgroundScope, "s-p20")
        vm.onDraftChange("/goal ship the release")
        vm.send(heldQueueDisposition = "keepQueueAndSend", expectedHeldQueueItemIds = listOf("q-1", "q-2"))
        runCurrent()
        assertEquals(listOf("ship the release"), port.goalObjectives)
        assertEquals("/goal ship the release", port.goalDisplayTexts.single())
        assertEquals("keepQueueAndSend", port.goalDispositions.single(), "the G50 answer must NOT be discarded")
        assertEquals(listOf("q-1", "q-2"), port.goalIds.single())
        assertEquals("", vm.state.value.draft, "accepted goal dispatch clears the draft")
    }

    @Test
    fun `F-P20-A - a dialog-answered plain send still forwards the trio (existing contract, control)`() = runTest {
        val port = GuardPort()
        val vm = ConversationViewModel(port, backgroundScope, "s-p20")
        vm.onDraftChange("plain text")
        vm.send(heldQueueDisposition = "clearQueueAndSend", expectedHeldQueueItemIds = listOf("q-1"))
        runCurrent()
        assertEquals(listOf("plain text"), port.promptCalls)
        assertEquals("", vm.state.value.draft)
    }

    @Test
    fun `F-P20-A - the sheet's Set-goal gate sends the trio (the sheet bypass of send() is gated now)`() = runTest {
        val port = GuardPort()
        val vm = ConversationViewModel(port, backgroundScope, "s-p20")
        vm.setGoal("finish the ledger", heldQueueDisposition = "clearQueueAndSend", expectedHeldQueueItemIds = listOf("q-1"))
        runCurrent()
        assertEquals(listOf("finish the ledger"), port.goalObjectives)
        assertEquals("/goal finish the ledger", port.goalDisplayTexts.single())
        assertEquals("clearQueueAndSend", port.goalDispositions.single())
        assertEquals(listOf("q-1"), port.goalIds.single())
    }

    @Test
    fun `F-P20-A - the sheet's ungated Set-goal keeps the legacy trio-less shape`() = runTest {
        val port = GuardPort()
        val vm = ConversationViewModel(port, backgroundScope, "s-p20")
        vm.setGoal("no queue standing")
        runCurrent()
        assertEquals(listOf("no queue standing"), port.goalObjectives)
        assertNull(port.goalDispositions.single())
        assertNull(port.goalIds.single())
    }

    @Test
    fun `F-P20-A - a pending sheet goal routes the re-opened dialog answer to the goal, not the draft`() = runTest {
        val port = GuardPort()
        val vm = ConversationViewModel(port, backgroundScope, "s-p20")
        // The sheet gate stashed a pending goal (routing=choice) and re-armed the
        // dialog; the user's answer must send THE GOAL with the trio — never the
        // unrelated composer draft.
        vm.stageGoalForChoice("sheet goal objective")
        vm.onDraftChange("unrelated draft text")
        vm.send(heldQueueDisposition = "keepQueueAndSend", expectedHeldQueueItemIds = listOf("q-1"))
        runCurrent()
        assertEquals(listOf("sheet goal objective"), port.goalObjectives, "the stashed goal rode the answer")
        assertEquals("keepQueueAndSend", port.goalDispositions.single())
        assertTrue(port.promptCalls.isEmpty(), "the composer draft never went out as a text send")
        assertEquals("unrelated draft text", vm.state.value.draft, "the draft is untouched by the goal answer")
        assertNull(vm.state.value.sendNotice)
    }

    @Test
    fun `F-P20-A - dismissing the re-opened dialog drops the pending goal (nothing sends)`() = runTest {
        val port = GuardPort()
        val vm = ConversationViewModel(port, backgroundScope, "s-p20")
        vm.stageGoalForChoice("sheet goal objective")
        vm.cancelGoalForChoice()
        vm.send(heldQueueDisposition = "keepQueueAndSend", expectedHeldQueueItemIds = listOf("q-1"))
        runCurrent()
        assertTrue(port.goalObjectives.isEmpty(), "the dismissed goal never sends")
    }

    // ------------------------------------------------------------------ N-BQ-1 (VM consumer guard)

    @Test
    fun `N-BQ-1 - the STALE effort switch renders the honest try-again warning (the previously-dead branch)`() = runTest {
        val port = object : Phase7FakePort() {
            override suspend fun setThoughtLevel(taskId: String, thought: String) =
                ConversationPort.SendOutcome.STALE
        }
        val vm = ConversationViewModel(port, backgroundScope, "s-bq")
        vm.setThoughtLevel("high")
        runCurrent()
        val notice = assertNotNull(vm.state.value.sendNotice)
        assertEquals(SendNotice.Key.SESSION_CHANGED, notice.key)
        assertEquals(SendNotice.Kind.WARNING, notice.kind)
        assertTrue(notice.text.contains("try", ignoreCase = true), "the honest retry-anticipating copy")
    }
}
