package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * pass-0 Phase 7 composer-leg pins, ViewModel level:
 *
 *  - **F-P31-A**: the in-flight upload transaction is CANCELLABLE — a mid-upload
 *    Remove cancels the running coroutine (the desktop's AbortController parity,
 *    pass-32 AI.D) instead of leaving a detached job that resurrects the chip.
 *  - **N-BR-1**: the stale attachment banner's lying windows — a Committed retry and
 *    an explicit Remove both clear the keyed notice (never leave "not sent" beside
 *    a ✓ chip / a removed chip).
 *  - **N-BS-1**: the mixed-state send (✓ ready + ✗ failed) carries the per-send
 *    exclusion signal instead of silently dropping the failed chip.
 *  - **N-BT-1**: a stale row action renders the ROW-ACTION stale copy — never the
 *    switch-specific banner (the shared-key renderer resolved BY KEY and discarded
 *    the per-path message).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Pass0Phase7ComposerUploadVmTest {

    /** Upload transaction that suspends until cancelled — the in-flight window. */
    private class HangingUploadPort : Phase7FakePort() {
        val uploadStarted = CompletableDeferred<String>()
        val uploadCancelled = CompletableDeferred<Unit>()
        val retryStarted = CompletableDeferred<String>()
        val retryCancelled = CompletableDeferred<Unit>()
        val removals = mutableListOf<String>()

        override suspend fun uploadAttachment(
            taskId: String, fileName: String, mime: String, bytes: ByteArray, uploadId: String,
            onProgress: (Int, Int) -> Unit,
        ): ConversationPort.UploadResult {
            uploadStarted.complete(uploadId)
            try {
                kotlinx.coroutines.awaitCancellation()
            } catch (e: kotlinx.coroutines.CancellationException) {
                uploadCancelled.complete(Unit)
                throw e
            }
        }

        override suspend fun retryUpload(taskId: String, uploadId: String): ConversationPort.UploadResult {
            retryStarted.complete(uploadId)
            try {
                kotlinx.coroutines.awaitCancellation()
            } catch (e: kotlinx.coroutines.CancellationException) {
                retryCancelled.complete(Unit)
                throw e
            }
        }

        override suspend fun removeUpload(uploadId: String) {
            removals.add(uploadId)
        }
    }

    @Test
    fun `F-P31-A - removing a mid-upload retry cancels the live transaction`() = runTest {
        val port = HangingUploadPort()
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        val job = launch { vm.retryUpload("up-hang") }
        runCurrent()
        assertEquals("up-hang", port.retryStarted.await())
        vm.removeUpload("up-hang")
        runCurrent()
        port.retryCancelled.await() // the detached-job shape never completes this
        assertEquals(listOf("up-hang"), port.removals, "the port abort must still fire")
        job.cancel()
    }

    @Test
    fun `F-P31-A - removing a mid-upload first attempt cancels the running upload`() = runTest {
        val port = HangingUploadPort()
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        val job = launch { vm.uploadAttachment("shot.png", "image/png", ByteArray(8)) }
        runCurrent()
        val uploadId = port.uploadStarted.await()
        vm.removeUpload(uploadId)
        runCurrent()
        port.uploadCancelled.await() // the detached-job shape never completes this
        assertEquals(listOf(uploadId), port.removals, "the port abort must still fire")
        job.cancel()
    }

    /** A port that fails the first attempt, then commits the retry. */
    private class RetryCommittedPort(
        private val attachments: Flow<List<ConversationPort.ComposerAttachment>> = MutableStateFlow(emptyList()),
    ) : Phase7FakePort() {
        var firstResult: ConversationPort.UploadResult =
            ConversationPort.UploadResult.Failed("fault.attachment.commitFail")
        var retryResult: ConversationPort.UploadResult = ConversationPort.UploadResult.Committed("ref-1", 8L)

        override fun observeComposerAttachments(taskId: String): Flow<List<ConversationPort.ComposerAttachment>> = attachments

        override suspend fun uploadAttachment(
            taskId: String, fileName: String, mime: String, bytes: ByteArray, uploadId: String,
            onProgress: (Int, Int) -> Unit,
        ): ConversationPort.UploadResult = firstResult

        override suspend fun retryUpload(taskId: String, uploadId: String): ConversationPort.UploadResult = retryResult
    }

    @Test
    fun `N-BR-1 - a committed retry clears the stale attachment banner`() = runTest {
        val port = RetryCommittedPort()
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        vm.uploadAttachment("shot.png", "image/png", ByteArray(8))
        runCurrent()
        assertEquals(
            SendNotice.Key.ATTACHMENT_FAILED,
            vm.state.value.sendNotice?.key,
            "the failed upload must arm the banner first",
        )
        vm.retryUpload("up-x")
        runCurrent()
        kotlinx.coroutines.delay(1)
        runCurrent()
        assertNull(
            vm.state.value.sendNotice,
            "the retry COMMITTED — the 'not sent' banner's claim is false, it must clear",
        )
    }

    @Test
    fun `N-BR-1 - an explicit chip remove clears the stale attachment banner`() = runTest {
        val port = RetryCommittedPort()
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        vm.uploadAttachment("shot.png", "image/png", ByteArray(8))
        runCurrent()
        assertEquals(SendNotice.Key.ATTACHMENT_FAILED, vm.state.value.sendNotice?.key)
        vm.removeUpload("up-x")
        runCurrent()
        kotlinx.coroutines.delay(1)
        runCurrent()
        assertNull(
            vm.state.value.sendNotice,
            "the chip is GONE — the banner must not keep claiming it was not sent",
        )
    }

    /** The mixed-state strip: one ✓ ready chip + one ✗ failed chip. */
    private fun mixedStrip() = listOf(
        ConversationPort.ComposerAttachment("up-ready", "good.png", "image/png", 8L, "ready", 1f, "ref-good"),
        ConversationPort.ComposerAttachment("up-fail", "rig-p30-mid.bin", "application/octet-stream", 16L, "failed", 0f, null),
    )

    @Test
    fun `N-BS-1 - a mixed-state send surfaces the excluded chip`() = runTest {
        val strip = MutableStateFlow(mixedStrip())
        val port = RetryCommittedPort(attachments = strip)
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        runCurrent()
        vm.onDraftChange("mirobexed")
        vm.send()
        runCurrent()
        kotlinx.coroutines.delay(1)
        runCurrent()
        val notice = vm.state.value.sendNotice
        assertTrue(
            notice != null && "Sent without" in notice.text,
            "the failed chip was silently EXCLUDED from the payload with no signal (notice=$notice)",
        )
        assertEquals("rig-p30-mid.bin", notice?.args?.firstOrNull(), "the notice names the excluded chip")
        assertNotEquals(
            SendNotice.Key.SESSION_CHANGED,
            notice?.key,
            "the exclusion is its own keyed notice, never a session-changed face",
        )
    }

    @Test
    fun `N-BS-1 - a fully-ready send stays silent (no exclusion notice)`() = runTest {
        val strip = MutableStateFlow(
            listOf(
                ConversationPort.ComposerAttachment("up-ready", "good.png", "image/png", 8L, "ready", 1f, "ref-good"),
            ),
        )
        val port = RetryCommittedPort(attachments = strip)
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        runCurrent()
        vm.onDraftChange("clean send")
        vm.send()
        runCurrent()
        kotlinx.coroutines.delay(1)
        runCurrent()
        assertNull(vm.state.value.sendNotice, "a complete send must not raise the exclusion face")
    }

    /** A stale row action on the feedback path (pass-37's live shape). */
    private class StaleFeedbackPort : Phase7FakePort() {
        override suspend fun setAssistantFeedback(taskId: String, rowId: Long, entityId: String, feedback: String?): ConversationPort.SendOutcome =
            ConversationPort.SendOutcome.STALE
    }

    @Test
    fun `N-BT-1 - a stale feedback action never renders the switch banner`() = runTest {
        val port = StaleFeedbackPort()
        val vm = ConversationViewModel(port, backgroundScope, "s1")
        vm.setAssistantFeedback(41L, "ent-41", "like")
        runCurrent()
        val notice = vm.state.value.sendNotice
        assertTrue(notice != null, "the stale verdict must surface a notice")
        assertNotEquals(
            SendNotice.Key.SESSION_CHANGED,
            notice?.key,
            "the shared SESSION_CHANGED key renders the switch copy BY KEY — a stale row action must carry its own key",
        )
        assertTrue(
            "switch" !in notice!!.text.lowercase(),
            "the row-action stale copy must not name the switch: ${notice.text}",
        )
        assertTrue("try again" in notice.text.lowercase(), "the honest stale copy invites a retry: ${notice.text}")
    }
}
