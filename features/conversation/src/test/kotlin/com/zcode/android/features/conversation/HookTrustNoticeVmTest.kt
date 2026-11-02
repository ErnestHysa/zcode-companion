package com.zcode.android.features.conversation

import com.zcode.android.domain.model.WorkspaceHookReview
import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GAP-A15 (pass 13, 2026-11-02, live-found on the rig): a SUCCESSFUL hook trust rode
 * `SendNotice.Key.ROW_ACTION_OFFLINE` (the "honest generic info key" comment) — but the
 * renderer maps that key to the FIXED offline copy ("Offline — the action was not sent.
 * Nothing changed."), so the wire said `grantWorkspaceHookTrust ok` while the strip
 * claimed the action never landed. Pinned: an accepted trust raises the dedicated
 * HOOK_TRUST_APPLIED key carrying the trusted count (the renderer's locale copy derives
 * from it — HookTrustNoticeCopyTest pins the string).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HookTrustNoticeVmTest {

    private class HookPort(var outcome: ConversationPort.SendOutcome) : Phase7FakePort() {
        val calls = mutableListOf<String>()
        override suspend fun respondHookReview(
            taskId: String,
            hook: WorkspaceHookReview,
            reviewItemIds: List<String>,
        ): ConversationPort.SendOutcome {
            calls += "respond:${reviewItemIds.size}"
            return outcome
        }
    }

    private fun hook() = WorkspaceHookReview(
        reviewFlowId = "flow-1",
        generation = 1,
        interactionId = "hook-int-1",
        sessionId = "s",
        taskId = "s",
        runId = "run-1",
        workspaceIdentity = "/synthetic/ws-one",
        workspaceLabel = "Synthetic One",
        bundleDigest = "digest-abc",
        createdAtMs = 1_700_000_000_000,
        deadlineAtMs = 1_700_000_600_000,
        workspacePath = "/synthetic/ws-one",
        remoteSessionId = null,
        summary = WorkspaceHookReview.Summary(eventCount = 2, hookCount = 2, pendingCount = 2),
        items = emptyList(),
    )

    @Test
    fun `A15 - an accepted trust raises the dedicated applied key with the count`() = runTest {
        val port = HookPort(ConversationPort.SendOutcome.ACCEPTED)
        val vm = ConversationViewModel(port, backgroundScope, "s")
        vm.trustHooks(hook(), listOf("a", "b"))
        runCurrent()
        assertEquals(listOf("respond:2"), port.calls)
        val notice = vm.state.value.sendNotice
        assertEquals(SendNotice.Key.HOOK_TRUST_APPLIED, notice?.key)
        assertEquals(SendNotice.Kind.INFO, notice?.kind)
        assertEquals(listOf("2"), notice?.args)
    }
}
