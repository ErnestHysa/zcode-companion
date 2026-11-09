package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RIG-P1 (vc52 Phase 7, G77) mock self-tests: the MOCK_AUTO_RESOLUTION arm serves
 * the byte-verified desktop `autoResolution` discriminatedUnion (shipped asar b$
 * schema) on every pending permission/userInput entry, the live "countdown" fuse
 * DROPS the entry once it fires (the auto-continue — the phone's authoritative
 * disappearance rule resolves it), "expired" serves the already-expired face, and
 * `snoozeInteractionAutoResolution` flips the live fuse to the snoozed state.
 * Synthetic content only (INV-15/16).
 */
class MockZCodeAutoResolutionSelfTest {

    private fun mock(): MockZCode {
        val passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val m = MockZCode(passHash = passHash, expectedSid = "d_auto")
        m.script.emitStateKeys = true
        return m
    }

    private fun pendingList(m: MockZCode): JsonArray =
        m.stateBlockForTest()!!["pendingInteractions"] as JsonArray

    private fun autoResOf(entries: JsonArray, id: String): kotlinx.serialization.json.JsonObject? =
        entries.firstOrNull { (it as? kotlinx.serialization.json.JsonObject)?.get("interactionId")?.toString()?.contains(id) == true }
            ?.let { it.jsonObject["autoResolution"]?.jsonObject }

    @Test
    fun `unset arm - no autoResolution key (the pre-RIG-P1 shape)`() {
        val m = mock()
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "mock-approval-1")
        val entries = pendingList(m)
        assertEquals(1, entries.size)
        assertNull(entries[0].jsonObject["autoResolution"], "no arm → no key (the rig cannot test G77 without arming)")
    }

    @Test
    fun `countdown arm - visibleCountdown with startedAt, visibleAt, deadlineAt numbers`() {
        val m = mock()
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "mock-approval-1")
        val started = System.currentTimeMillis()
        m.armAutoResolutionForTest("countdown", fuseMs = 120_000, startedAtMs = started)
        val auto = autoResOf(pendingList(m), "mock-approval-1")
        assertNotNull(auto, "the armed entry carries autoResolution")
        assertEquals("visibleCountdown", (auto!!["state"] as JsonPrimitive).content)
        assertEquals(started.toDouble(), (auto["startedAt"] as JsonPrimitive).content.toDouble(), "startedAt is the fuse start")
        assertEquals((started + 120_000).toDouble(), (auto["deadlineAt"] as JsonPrimitive).content.toDouble(), "deadlineAt = startedAt + fuse")
    }

    @Test
    fun `grace arm - hiddenGrace hides the countdown phase from the phone`() {
        val m = mock()
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "mock-approval-1")
        m.armAutoResolutionForTest("grace", fuseMs = 300_000, startedAtMs = System.currentTimeMillis())
        val auto = autoResOf(pendingList(m), "mock-approval-1")
        assertEquals("hiddenGrace", (auto!!["state"] as JsonPrimitive).content)
    }

    @Test
    fun `expired arm - the deadline sits in the past (the non-actionable face)`() {
        val m = mock()
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "mock-approval-1")
        m.armAutoResolutionForTest("expired", fuseMs = 300_000, startedAtMs = System.currentTimeMillis())
        val auto = autoResOf(pendingList(m), "mock-approval-1")
        assertEquals("visibleCountdown", (auto!!["state"] as JsonPrimitive).content)
        val deadline = (auto["deadlineAt"] as JsonPrimitive).content.toDouble().toLong()
        assertTrue(deadline < System.currentTimeMillis(), "the deadline is already past")
    }

    @Test
    fun `a fired countdown fuse drops the entry - the desktop auto-continued`() {
        val m = mock()
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "mock-approval-1")
        // The fuse started 10 minutes ago with a 1-minute fuse → long fired.
        m.armAutoResolutionForTest("countdown", fuseMs = 60_000, startedAtMs = System.currentTimeMillis() - 600_000)
        val entries = pendingList(m)
        assertEquals(
            0,
            entries.size,
            "the fuse fired: the interaction LEAVES the authoritative list (auto-continue)",
        )
    }

    @Test
    fun `an expired arm does NOT drop the entry - the phone must render the expired face`() {
        val m = mock()
        m.script.pendingPermission = MockZCode.PendingPermission(interactionId = "mock-approval-1")
        m.armAutoResolutionForTest("expired", fuseMs = 300_000, startedAtMs = System.currentTimeMillis())
        assertEquals(1, pendingList(m).size, "expired serves the face, it never silently drops")
    }

    @Test
    fun `the arm covers the user-input plane too`() {
        val m = mock()
        m.script.pendingUserInput = MockZCode.PendingUserInput(interactionId = "q-rig-1", options = listOf("yes"), freeText = false)
        m.armAutoResolutionForTest("countdown", fuseMs = 120_000, startedAtMs = System.currentTimeMillis())
        val auto = autoResOf(pendingList(m), "q-rig-1")
        assertNotNull(auto, "questions carry the fuse (the desktop superRefine forbids it ONLY on workspaceHookReview)")
        assertEquals("visibleCountdown", (auto!!["state"] as JsonPrimitive).content)
    }
}
