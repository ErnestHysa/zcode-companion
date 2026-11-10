package com.zcode.android.features.conversation

import com.zcode.android.domain.model.SessionProjectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * G52 (vc52 Phase 11) ViewModel leg: a seeded `modelTransition` patch renders a
 * typed INFO notice (the desktop's toast face) — ONCE per transition eventId, and
 * the admission count rides the session state for the context sheet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelTransitionNoticeVmTest {

    private fun transitionPatch(eventId: String) = MutableStateFlow(
        SessionProjectionState().mergedWith(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"modelTransition":{"eventId":"$eventId","origin":"registryFallback",
                    "from":{"provider":"zai","model":"glm-5.1"},"to":{"provider":"zai","model":"glm-4.7-air"}}}""",
            ) as kotlinx.serialization.json.JsonObject,
        ),
    )

    private class StatePort(var flow: MutableStateFlow<SessionProjectionState>) : Phase7FakePort() {
        override fun observeSessionState(taskId: String) = flow
    }

    @Test
    fun `a seeded transition renders one typed INFO notice`() = runTest {
        val flow = transitionPatch("evt-1")
        val vm = ConversationViewModel(StatePort(flow), backgroundScope, "s")
        runCurrent()
        val notice = vm.state.value.sendNotice
        assertEquals(SendNotice.Key.MODEL_TRANSITION, notice?.key)
        assertEquals(SendNotice.Kind.INFO, notice?.kind)
        val text = notice?.text.orEmpty()
        kotlin.test.assertTrue("glm-5.1" in text && "glm-4.7-air" in text, "both models named: $text")
        kotlin.test.assertTrue("registry fallback" in text)
    }

    @Test
    fun `the same eventId never re-announces, a new event does`() = runTest {
        val flow = transitionPatch("evt-1")
        val vm = ConversationViewModel(StatePort(flow), backgroundScope, "s")
        runCurrent()
        vm.dismissSendNotice()
        runCurrent()
        assertNull(vm.state.value.sendNotice)
        // The SAME transition re-delivered (a snapshot replay) — no new notice.
        vm.state.value.sessionState?.let { flow.value = it }
        runCurrent()
        assertNull(vm.state.value.sendNotice, "one notice per eventId")
        // A NEW event id announces again.
        flow.value = transitionPatch("evt-2").value
        runCurrent()
        assertEquals(SendNotice.Key.MODEL_TRANSITION, vm.state.value.sendNotice?.key)
    }
}
