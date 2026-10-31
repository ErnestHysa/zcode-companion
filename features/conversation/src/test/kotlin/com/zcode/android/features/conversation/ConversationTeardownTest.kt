package com.zcode.android.features.conversation

import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.StreamingBuffer
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import org.junit.Test

/**
 * GAP-A3/G14 (vc51) — the conversation-teardown pin: leaving a conversation (the
 * holder's onCleared on entry pop = a task switch or screen exit) releases the
 * session's wire subscription through [ConversationPort.close]. The recorded
 * defect: zero unsubscribe call sites anywhere — every visited session stayed
 * subscribed forever.
 */
class ConversationTeardownTest {

    private class CloseRecordingPort : Phase7FakePort() {
        val closed = mutableListOf<String>()
        override fun close(taskId: String) {
            closed.add(taskId)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `onTeardown releases the session through the port close`() = runTest {
        val port = CloseRecordingPort()
        val vm = ConversationViewModel(port, backgroundScope, "sess-exit-1")
        advanceUntilIdle()

        vm.onTeardown()

        assertTrue(
            "sess-exit-1" in port.closed,
            "the conversation teardown must unsubscribe the session (GAP-A3: the wire receipt on task switch/exit)",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `teardown is safe when the port close throws - the exit path never crashes`() = runTest {
        val port = object : Phase7FakePort() {
            override fun close(taskId: String) {
                throw IllegalStateException("scope already cancelled")
            }
        }
        val vm = ConversationViewModel(port, backgroundScope, "sess-exit-2")
        advanceUntilIdle()

        vm.onTeardown()

        // No exception escaped — the runTest body completing IS the assertion.
    }
}
