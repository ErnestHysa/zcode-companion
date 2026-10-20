package com.zcode.android.features.conversation

import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.port.ConversationPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * GAP-12 (docs/protocol/v4-wire.md §11.3) — the composer send path routes through
 * the desktop's slash grammar: /compact and /goal hit the VERIFIED v4 commands;
 * unsupported goal actions are refused with a notice (never a silent prompt);
 * custom/unknown slash text goes out as plain sendText (desktop parity — the CLI
 * consumes it); /plan switches the mode and sends the task text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlashSendRoutingTest {

    private class RoutingPort : Phase7FakePort() {
        val calls = mutableListOf<String>()
        val promptTexts = mutableListOf<String>()
        var outcome = ConversationPort.SendOutcome.ACCEPTED

        override suspend fun compact(taskId: String): ConversationPort.SendOutcome {
            calls += "compact"
            return outcome
        }

        override suspend fun sendGoal(taskId: String, objective: String, displayText: String): ConversationPort.SendOutcome {
            calls += "sendGoal:$objective"
            return outcome
        }

        override suspend fun resumeGoal(taskId: String): ConversationPort.SendOutcome {
            calls += "resumeGoal"
            return outcome
        }

        override suspend fun pauseGoal(taskId: String): ConversationPort.SendOutcome {
            calls += "pauseGoal"
            return outcome
        }

        override suspend fun switchMode(taskId: String, mode: String): ConversationPort.SendOutcome {
            calls += "switchMode:$mode"
            return outcome
        }

        override suspend fun sendPrompt(taskId: String, text: String, heldQueueDisposition: String?): ConversationPort.SendOutcome {
            promptTexts += text
            calls += "sendPrompt"
            return outcome
        }
    }

    private fun vmWith(port: RoutingPort, scope: kotlinx.coroutines.test.TestScope): ConversationViewModel {
        val vm = ConversationViewModel(port, scope.backgroundScope, "s-slash")
        return vm
    }

    @Test
    fun `slash dispatch - compact`() = runTest {
        val port = RoutingPort()
        val vm = vmWith(port, this)
        vm.onDraftChange("/compact")
        vm.send()
        runCurrent()
        assertEquals(listOf("compact"), port.calls)
        assertEquals("", vm.state.value.draft, "accepted dispatch clears the draft")
    }

    @Test
    fun `slash dispatch - goal objective and resume`() = runTest {
        val port = RoutingPort()
        val vm = vmWith(port, this)
        vm.onDraftChange("/goal ship the release")
        vm.send()
        runCurrent()
        assertEquals(listOf("sendGoal:ship the release"), port.calls)
        vm.onDraftChange("/goal resume")
        vm.send()
        runCurrent()
        assertEquals(listOf("sendGoal:ship the release", "resumeGoal"), port.calls)
    }

    @Test
    fun `slash dispatch - goal pause rides the CAS-guarded pauseGoal word (I1-goal unlock)`() = runTest {
        val port = RoutingPort()
        val vm = vmWith(port, this)
        vm.onDraftChange("/goal pause")
        vm.send()
        runCurrent()
        assertEquals(listOf("pauseGoal"), port.calls)
        assertEquals("", vm.state.value.draft, "accepted dispatch clears the draft")
    }

    @Test
    fun `unsupported goal actions still refused - draft preserved, honest notice`() = runTest {
        val port = RoutingPort()
        val vm = vmWith(port, this)
        vm.onDraftChange("/goal clear")
        vm.send()
        runCurrent()
        assertTrue(port.calls.isEmpty(), "nothing may go on the wire for /goal clear|show")
        assertEquals("/goal clear", vm.state.value.draft, "draft preserved — the user's text is never eaten")
        assertTrue(vm.state.value.sendNotice != null, "honest notice shown")
    }

    @Test
    fun `custom slash text rides plain sendText`() = runTest {
        val port = RoutingPort()
        val vm = vmWith(port, this)
        vm.onDraftChange("/deploy prod")
        vm.send()
        runCurrent()
        assertEquals(listOf("sendPrompt"), port.calls)
        assertEquals("/deploy prod", port.promptTexts.single(), "RAW slash text — the CLI consumes custom commands")
    }

    @Test
    fun `plan shortcut switches mode then sends the task text`() = runTest {
        val port = RoutingPort()
        val vm = vmWith(port, this)
        vm.onDraftChange("/plan migrate the db")
        vm.send()
        runCurrent()
        assertEquals(listOf("switchMode:plan", "sendPrompt"), port.calls)
        assertEquals("migrate the db", port.promptTexts.single(), "the TASK is the body, never the /plan prefix")
    }

    @Test
    fun `plain prompts bypass the slash plane entirely`() = runTest {
        val port = RoutingPort()
        val vm = vmWith(port, this)
        vm.onDraftChange("just fix the bug")
        vm.send()
        runCurrent()
        assertEquals(listOf("sendPrompt"), port.calls)
        assertEquals("just fix the bug", port.promptTexts.single())
    }
}
