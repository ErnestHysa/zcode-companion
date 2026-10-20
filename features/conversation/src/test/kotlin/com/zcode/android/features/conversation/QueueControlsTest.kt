package com.zcode.android.features.conversation

import com.zcode.android.domain.model.QueueState
import com.zcode.android.domain.model.SessionConfigState
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I1 (Phase 5A) ViewModel legs: moveQueueItem computes the desktop's `beforeQueueItemId`
 * from the CURRENT projection order (up = the item above; down = the item after the
 * displaced one; last = null end); out-of-range moves send nothing; setAutoDrain and
 * setFollowupMode ride the port; a STALE echo surfaces the honest re-try notice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueControlsTest {

    companion object {
        fun projection(ids: List<String>, autoDrain: Boolean? = true, followup: String = "queue") = SessionProjectionState(
        revision = 1,
        queue = QueueState(
            items = ids.map { QueueState.QueueItem(it, "text $it", kind = null, dispatchState = "queued") },
            autoDrain = autoDrain,
            pauseReason = null,
        ),
        config = SessionConfigState(
            provider = "zai", model = "glm-5.3", thought = "standard", thoughtLevels = listOf("standard"),
            followupMode = followup, mode = "build", planEnabled = null, modelSelectionProviderId = null, modelSelectionModelId = null,
        ),
    )
    }

    private class QueuePort(ids: List<String> = emptyList()) : Phase7FakePort() {
        val calls = mutableListOf<String>()
        var outcome = ConversationPort.SendOutcome.ACCEPTED
        val sessionState = MutableStateFlow(projection(ids))
        override fun observeSessionState(taskId: String) = sessionState
        override suspend fun reorderQueueItem(taskId: String, queueItemId: String, beforeQueueItemId: String?): ConversationPort.SendOutcome {
            calls += "reorder:$queueItemId:${beforeQueueItemId ?: "end"}"; return outcome
        }
        override suspend fun setAutoDrain(taskId: String, autoDrain: Boolean): ConversationPort.SendOutcome { calls += "drain:$autoDrain"; return outcome }
        override suspend fun setFollowupMode(taskId: String, mode: String): ConversationPort.SendOutcome { calls += "followup:$mode"; return outcome }
    }

    @Test
    fun `up targets the item above and down-to-last sends null`() = runTest {
        val port = QueuePort(listOf("a", "b", "c"))
        val vm = ConversationViewModel(port, backgroundScope, "s")
        runCurrent()
        vm.moveQueueItem("b", -1); runCurrent()
        vm.moveQueueItem("a", +1); runCurrent()
        vm.moveQueueItem("c", +1); runCurrent()
        assertEquals(listOf("reorder:b:a", "reorder:a:c", "reorder:c:end"), port.calls)
    }

    @Test
    fun `out-of-range moves send nothing`() = runTest {
        val port = QueuePort(listOf("a", "b"))
        val vm = ConversationViewModel(port, backgroundScope, "s")
        runCurrent()
        vm.moveQueueItem("a", -1); runCurrent()
        vm.moveQueueItem("b", +1); runCurrent()
        vm.moveQueueItem("zz", +1); runCurrent()
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `drain and followup ride the port and stale echoes the retry notice`() = runTest {
        val port = QueuePort(listOf("a"))
        val vm = ConversationViewModel(port, backgroundScope, "s")
        runCurrent()
        vm.setAutoDrain(false); runCurrent()
        vm.setFollowupMode("guide"); runCurrent()
        assertEquals(listOf("drain:false", "followup:guide"), port.calls)
        port.outcome = ConversationPort.SendOutcome.STALE
        vm.setAutoDrain(true); runCurrent()
        assertEquals(SendNotice.Kind.WARNING, vm.state.value.sendNotice?.kind)
    }
}
