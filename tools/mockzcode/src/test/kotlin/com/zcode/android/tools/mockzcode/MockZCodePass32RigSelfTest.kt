package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pass-32 rig self-tests — the READY-resurrection arm (Part AH.E's named residual):
 *
 * MOCK_ATTACHMENT_KEEP_AFTER_ABORT=1: attachmentAbortV4 KEEPS the staging entry, so
 * post-abort chunkV4s find their record (the chunk handler's store lookup) and
 * commit succeeds — the AH.D "server that blindly accepted post-abort chunks
 * shape that resurrects the user-removed chip as ✓ READY (F-P31-A's second
 * variant). Default (unarmed) stays server-faithful: the abort removes the entry
 * and the next chunk errors fault.attachment.unknownUpload (the AH-B shape).
 *
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass32RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 32 }), expectedSid = "d_p32")
        m.activeSessionForTest("sess-p32")
        block?.let { m.script.it() }
        return m
    }

    @Test
    fun `keep arm defaults to false and is settable`() {
        val m = mock()
        assertFalse(m.script.attachmentKeepAfterAbort, "unarmed default is the server-faithful shape")
        m.script.attachmentKeepAfterAbort = true
        assertTrue(m.script.attachmentKeepAfterAbort)
    }

    @Test
    fun `abort removes the staging entry by default (the AH-B shape)`() {
        val m = mock()
        m.attachmentSeedForTest("up-default")
        assertTrue(m.attachmentAbortMutate("up-default"), "a present entry is removed")
        assertTrue(m.attachmentStoreForTest().isEmpty(), "the store is empty after the default abort")
    }

    @Test
    fun `keep arm keeps the entry so post-abort chunks find their record`() {
        val m = mock { attachmentKeepAfterAbort = true }
        m.attachmentSeedForTest("up-keep")
        assertFalse(m.attachmentAbortMutate("up-keep"), "the armed abort keeps the entry")
        assertEquals(1, m.attachmentStoreForTest().size, "the staging entry survives the armed abort — the chunk handler's store lookup succeeds and commit can land")
    }

    @Test
    fun `abort of an absent entry reports not-removed in both modes`() {
        val unarmed = mock()
        assertFalse(unarmed.attachmentAbortMutate("up-absent"), "no entry to remove")
        val armed = mock { attachmentKeepAfterAbort = true }
        assertFalse(armed.attachmentAbortMutate("up-absent"), "kept-or-absent both report false")
    }
}
