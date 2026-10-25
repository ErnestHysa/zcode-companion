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
 * E3.2+I1+N5 (gaps-and-polish Phase 4A) — the ViewModel half of the four row
 * actions: each rides its own port method with the row target; outcomes surface
 * as honest notices (desktop: `[v4-pane] retry 被拒绝`/`fork 被拒绝` warnings);
 * an accepted fork exposes the CHILD session for the same navigation the desktop
 * does (`onSessionCreated(ack.result.sessionId)`, SessionPane.tsx:3039-3042).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RowActionsRoutingTest {

    private class RowPort : Phase7FakePort() {
        val calls = mutableListOf<String>()
        var outcome = ConversationPort.SendOutcome.ACCEPTED
        var forkChild: String? = "sess-child-1"

        override suspend fun retryTurn(taskId: String, rowId: Long, entityId: String): ConversationPort.SendOutcome {
            calls.add("retry:$rowId:$entityId"); return outcome
        }
        override suspend fun forkAssistant(taskId: String, rowId: Long, entityId: String): ConversationPort.ForkOutcome {
            calls.add("fork:$rowId:$entityId")
            return ConversationPort.ForkOutcome(outcome, if (outcome == ConversationPort.SendOutcome.ACCEPTED) forkChild else null)
        }
        override suspend fun applyFileRewind(taskId: String, rowId: Long, entityId: String): ConversationPort.SendOutcome {
            calls.add("rewind:$rowId:$entityId"); return outcome
        }
        override suspend fun setAssistantFeedback(taskId: String, rowId: Long, entityId: String, feedback: String?): ConversationPort.SendOutcome {
            calls.add("feedback:$rowId:$entityId:$feedback"); return outcome
        }
    }

    @Test
    fun `retry rides the port with the row target`() = runTest {
        val port = RowPort()
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.retryTurn(rowId = 8, entityId = "ent-8")
        runCurrent()
        assertEquals(listOf("retry:8:ent-8"), port.calls)
        assertNull(vm.state.value.sendNotice, "an accepted retry is silent — the re-asked turn is the feedback")
    }

    @Test
    fun `accepted fork exposes the child session for navigation`() = runTest {
        val port = RowPort()
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.forkAssistant(rowId = 2, entityId = "ent-2")
        runCurrent()
        assertEquals(listOf("fork:2:ent-2"), port.calls)
        assertEquals("sess-child-1", vm.forkedSession.value)
        vm.consumeForkedSession()
        assertNull(vm.forkedSession.value, "one-shot: consumed once the screen navigated")
    }

    @Test
    fun `refused fork surfaces a notice and no navigation`() = runTest {
        val port = RowPort().apply { outcome = ConversationPort.SendOutcome.FAILED }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.forkAssistant(rowId = 2, entityId = "ent-2")
        runCurrent()
        assertNull(vm.forkedSession.value)
        assertEquals(SendNotice.Kind.ERROR, vm.state.value.sendNotice?.kind)
    }

    @Test
    fun `stale retry preserves the honest session-moved notice`() = runTest {
        val port = RowPort().apply { outcome = ConversationPort.SendOutcome.STALE }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.retryTurn(1, "e")
        runCurrent()
        assertEquals(SendNotice.Key.SESSION_CHANGED, vm.state.value.sendNotice?.key)
    }

    @Test
    fun `rewind files rides the port and an accepted rewind is announced`() = runTest {
        val port = RowPort()
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.applyFileRewind(rowId = 5, entityId = "ent-turn-5")
        runCurrent()
        assertEquals(listOf("rewind:5:ent-turn-5"), port.calls)
        assertEquals(SendNotice.Kind.INFO, vm.state.value.sendNotice?.kind)
    }

    @Test
    fun `feedback toggles like dislike and clear through the port`() = runTest {
        val port = RowPort()
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.setAssistantFeedback(3, "ent-3", "like")
        vm.setAssistantFeedback(3, "ent-3", "dislike")
        vm.setAssistantFeedback(3, "ent-3", null)
        runCurrent()
        assertEquals(listOf("feedback:3:ent-3:like", "feedback:3:ent-3:dislike", "feedback:3:ent-3:null"), port.calls)
        assertNull(vm.state.value.sendNotice, "accepted feedback is silent — the row's feedback echo renders it")
    }

    @Test
    fun `offline row action is blocked with the offline notice`() = runTest {
        val port = RowPort().apply { outcome = ConversationPort.SendOutcome.BLOCKED_OFFLINE }
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.setAssistantFeedback(3, "ent-3", "like")
        runCurrent()
        assertNotNull(vm.state.value.sendNotice)
        assertTrue(vm.state.value.sendNotice!!.kind == SendNotice.Kind.WARNING)
    }
}
