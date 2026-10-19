package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A3.4 (gaps-and-polish Phase 4C): the reasoning-effort picker — `setThoughtLevel`
 * on the shipped facade (zcodeAgent.ts:705, `{sessionId, thoughtLevel,
 * expectedRevision?, persistAsWorkspaceLastUsed?}`) was never called app-side; the
 * effort level was only reachable as a side step of the model picker. The picker is
 * WIRE-DRIVEN: the offered levels are the CURRENT MODEL's `config.thoughtLevels`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThoughtLevelRoutingTest {

    private class ThoughtPort : Phase7FakePort() {
        val calls = mutableListOf<String>()
        var outcome = ConversationPort.SendOutcome.ACCEPTED
        override suspend fun setThoughtLevel(taskId: String, thought: String): ConversationPort.SendOutcome {
            calls += thought; return outcome
        }
    }

    @Test
    fun `the picked level rides the port`() = runTest {
        val port = ThoughtPort()
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.setThoughtLevel("high")
        runCurrent()
        assertEquals(listOf("high"), port.calls)
    }

    @Test
    fun `offline effort switch is blocked with a notice`() = runTest {
        val port = ThoughtPort().apply { outcome = ConversationPort.SendOutcome.BLOCKED_OFFLINE }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.setThoughtLevel("low")
        runCurrent()
        val notice = assertNotNull(vm.state.value.sendNotice)
        assertTrue(notice.kind == SendNotice.Kind.WARNING)
    }

    @Test
    fun `refused effort switch says nothing changed`() = runTest {
        val port = ThoughtPort().apply { outcome = ConversationPort.SendOutcome.FAILED }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.setThoughtLevel("medium")
        runCurrent()
        assertEquals(SendNotice.Key.EFFORT_REFUSED, vm.state.value.sendNotice?.key)
    }
}
