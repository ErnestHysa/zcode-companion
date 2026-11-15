package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass-30 rig self-tests — the Part-AG attachment-transaction arms:
 *
 * 1. MOCK_ATTACHMENT_FAIL=<begin|chunk|commit>: the named phase's fault code is
 *    served as an RPC ERROR while CONNECTED, so the app's transport throws
 *    TransportFailure → the port's GENERIC Failed path (chip failed with the
 *    payload RETAINED — Retry stays live). Healthy (null) serves nothing.
 * 2. MOCK_ATTACHMENT_CHUNK_DELAY_MS: the delay flag is settable and defaults to 0
 *    (no delay — the historical instant-commit behavior is unchanged for old rigs).
 *
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass30RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 30 }), expectedSid = "d_p30")
        m.activeSessionForTest("sess-p30")
        block?.let { m.script.it() }
        return m
    }

    // ---- the fail-phase faults ----

    @Test
    fun `fail phase serves the named fault on each transaction phase`() {
        val m = mock { attachmentFailPhase = "begin" }
        assertEquals("fault.attachment.beginFail", m.attachmentFailFault())
        m.script.attachmentFailPhase = "chunk"
        assertEquals("fault.attachment.chunkFail", m.attachmentFailFault())
        m.script.attachmentFailPhase = "commit"
        assertEquals("fault.attachment.commitFail", m.attachmentFailFault())
    }

    @Test
    fun `healthy script serves no attachment fault`() {
        val m = mock()
        assertNull(m.attachmentFailFault())
        m.script.attachmentFailPhase = ""
        assertNull(m.attachmentFailFault(), "empty string arms nothing (env-absent parity)")
    }

    @Test
    fun `unknown fail phase arms nothing`() {
        val m = mock { attachmentFailPhase = "thumbnail" }
        assertNull(m.attachmentFailFault(), "only begin/chunk/commit are valid phases")
    }

    @Test
    fun `rejectAttachments still takes precedence over the fail phase (verified faults stand)`() {
        val m = mock {
            rejectAttachments = true
            attachmentFailPhase = "commit"
        }
        assertEquals("fault.attachment.rejected", m.attachmentBeginGuardFault(), "the pass-4 verified begin guard outranks the pass-30 arm")
    }

    // ---- the chunk delay ----

    @Test
    fun `chunk delay defaults to zero and is settable`() {
        val m = mock()
        assertEquals(0L, m.script.attachmentChunkDelayMs)
        m.script.attachmentChunkDelayMs = 1200L
        assertEquals(1200L, m.script.attachmentChunkDelayMs)
    }

    @Test
    fun `fail phase mutates no attachment store state by itself`() {
        val m = mock { attachmentFailPhase = "commit" }
        assertTrue(m.attachmentStoreForTest().isEmpty(), "the arm is a response shape, not a store mutation")
    }
}
