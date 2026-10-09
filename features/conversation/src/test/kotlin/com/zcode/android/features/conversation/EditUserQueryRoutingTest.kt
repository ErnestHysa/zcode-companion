package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GAP-10 — the ViewModel edit path: the confirmed edit rides the port's
 * editUserQuery (coordinator CAS plane); a blank replacement is refused
 * client-side (desktop parity — "edit 跳过：行内编辑内容为空"); outcomes surface
 * as honest notices (blocked/stale/failed), never optimistic row edits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditUserQueryRoutingTest {

    private class EditPort : Phase7FakePort() {
        val edits = mutableListOf<Triple<Long, String, String>>()
        var outcome = ConversationPort.SendOutcome.ACCEPTED

        override suspend fun editUserQuery(taskId: String, rowId: Long, entityId: String, newText: String): ConversationPort.SendOutcome {
            edits.add(Triple(rowId, entityId, newText))
            return outcome
        }
    }

    @Test
    fun `confirmed edit rides the port with rowId entityId and text`() = runTest {
        val port = EditPort()
        val vm = ConversationViewModel(port, backgroundScope, "s-edit")
        vm.editUserQuery(rowId = 42, entityId = "ent-42", newText = "edited question")
        runCurrent()
        assertEquals(listOf(Triple(42L, "ent-42", "edited question")), port.edits)
    }

    @Test
    fun `blank edit text is refused client-side`() = runTest {
        val port = EditPort()
        val vm = ConversationViewModel(port, backgroundScope, "s-edit")
        vm.editUserQuery(rowId = 42, entityId = "ent-42", newText = "   ")
        runCurrent()
        assertTrue(port.edits.isEmpty(), "nothing on the wire for a blank replacement")
        assertTrue(vm.state.value.sendNotice != null, "honest notice instead")
    }

    @Test
    fun `blocked edit preserves the notice path`() = runTest {
        val port = EditPort().apply { outcome = ConversationPort.SendOutcome.STALE }
        val vm = ConversationViewModel(port, backgroundScope, "s-edit")
        vm.editUserQuery(rowId = 1, entityId = "e", newText = "x")
        runCurrent()
        assertTrue(vm.state.value.sendNotice != null, "stale → conversation-moved notice")
    }
}
