package com.zcode.android.tools.mockzcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pass-22 rig self-tests — the two Part-X coverage-honesty arms:
 *
 * 1. MOCK_SET_THOUGHT_LEVEL_STALE — the effort switch rides the PROTOCOL method
 *    (v4Call setThoughtLevel); the host layer CAS-checks via FQ and answers a
 *    JSON-RPC ERROR (Dl -32009, "Session state revision mismatch",
 *    {actualRevision, expectedRevision}) — never a {status:"stale"} ack — and
 *    mutates nothing. The command-typed MOCK_CAS_STALE_TYPES arm cannot reach it.
 * 2. MOCK_COMPACT_COMMAND — the host's command-plane compact handler (vMc):
 *    under enqueue/guide/choice the compact SELF-QUEUES (a kind:"compact" queue
 *    item + queue patch + revision bump, NO rows) and answers accepted; a second
 *    compact while one is queued answers the typed rejected ack
 *    compactOperationLock ("Compact is already running or queued").
 *
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass22RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 22 }), expectedSid = "d_p22")
        m.activeSessionForTest("sess-p22")
        block?.let { m.script.it() }
        return m
    }

    // ---- the effort stale arm: the host-faithful Dl error body ----

    @Test
    fun `setThoughtLevel stale body matches the host FQ serialization`() {
        val m = mock { setThoughtLevelStale = true }
        val before = m.revisionForTest()
        val body = m.setThoughtLevelStaleErrorBody("7")
        assertEquals("Session state revision mismatch", body["message"]?.toString()?.trim('"'))
        assertEquals(-32009, body["code"]?.toString()?.toIntOrNull())
        assertTrue(body.toString().contains("\"actualRevision\""))
        assertTrue(body.toString().contains("\"expectedRevision\":7"))
        assertEquals(before, m.revisionForTest(), "the stale error mutates no revision")
    }

    @Test
    fun `setThoughtLevel stale arm un-armed keeps the echo shape (no behavior change)`() {
        val m = mock()
        assertTrue(!m.script.setThoughtLevelStale, "default off — old rigs keep the echo handler")
    }

    // ---- the compact arm: vMc mirror ----

    @Test
    fun `compact under choice self-queues a kind-compact item and bumps the revision`() {
        val m = mock {
            compactCommandArm = true
            inputRoutingMode = "choice"
        }
        val before = m.revisionForTest()
        val decision = m.compactCommandDecision()
        val queued = assertIs<MockZCode.CompactDecision.Queued>(decision)
        assertEquals("choice", queued.routing)
        assertEquals(before + 1, m.revisionForTest())
        val items = m.script.queueItems.orEmpty()
        assertEquals(1, items.size)
        assertEquals("compact", items.single().kind)
        assertEquals("/compact", items.single().text)
    }

    @Test
    fun `second compact while queued answers compactOperationLock and mutates nothing`() {
        val m = mock {
            compactCommandArm = true
            inputRoutingMode = "choice"
        }
        m.compactCommandDecision() // the first compact queues
        val before = m.revisionForTest()
        val decision = m.compactCommandDecision()
        val rejected = assertIs<MockZCode.CompactDecision.Rejected>(decision)
        assertEquals("compactOperationLock", rejected.reasonCode)
        assertEquals("Compact is already running or queued", rejected.message)
        assertEquals(before, m.revisionForTest(), "the rejection mutates nothing")
        assertEquals(1, m.script.queueItems.orEmpty().size, "the queue is unchanged")
    }

    @Test
    fun `compact under startNow passes through (no scripted turn)`() {
        val m = mock {
            compactCommandArm = true
            inputRoutingMode = "startNow"
        }
        assertIs<MockZCode.CompactDecision.Passthrough>(m.compactCommandDecision())
        assertEquals(0, m.script.queueItems.orEmpty().size)
    }

    @Test
    fun `compact arm un-armed is inert (old rigs keep the unscripted fault)`() {
        val m = mock { inputRoutingMode = "choice" }
        assertTrue(!m.script.compactCommandArm, "default off")
    }

    @Test
    fun `stale arm still intercepts the CAS-guarded queue verbs (pass-20 contract intact)`() {
        val m = mock { casStaleTypes = setOf("reorderQueueItem", "deleteQueueItem", "sendQueuedNow") }
        for (type in listOf("reorderQueueItem", "deleteQueueItem", "sendQueuedNow")) {
            val ack = m.casStaleAck(type, null)
            assertEquals("stale", ack?.get("status")?.toString()?.trim('"'), type)
            assertEquals("proto.staleRevision", ack?.get("reasonCode")?.toString()?.trim('"'), type)
        }
    }
}
