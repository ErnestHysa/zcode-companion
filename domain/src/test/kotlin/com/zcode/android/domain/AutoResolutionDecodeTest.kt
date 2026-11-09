package com.zcode.android.domain

import com.zcode.android.domain.model.AutoResolution
import com.zcode.android.domain.model.PendingInteraction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G77 (vc52 Phase 7): the wire-decode pin for the desktop's AskUserQuestion
 * auto-resolution fuse — the byte-verified b$ discriminatedUnion("state") from the
 * SHIPPED asar (`{state: hiddenGrace|visibleCountdown, startedAt, visibleAt,
 * deadlineAt}` | `{state: snoozed, startedAt, snoozedAt}`, epoch-ms numbers; a
 * superRefine forbids the fuse only on workspaceHookReview). Tolerant decode
 * (INV-8) everywhere: unknown states retained as UNKNOWN, absent keys null.
 */
class AutoResolutionDecodeTest {

    private fun entry(auto: String?): PendingInteraction {
        val json = """
        {
          "interactionId": "i-fuse",
          "kind": "permission",
          "anchorRowId": null,
          "createdAt": 1700000000000.0,
          ${if (auto != null) "\"autoResolution\": $auto," else ""}
          "payload": {"kind":"permission","toolCallId":"tc-1","toolName":"Bash",
                      "options":[{"optionId":"allowOnce","label":"Allow once","kind":"allowOnce"}]}
        }
        """.trimIndent()
        return assertNotNull(PendingInteraction.decode(Json.parseToJsonElement(json).jsonObject))
    }

    @Test
    fun `visibleCountdown decodes with all three timestamps`() {
        val interaction = entry("""{"state":"visibleCountdown","startedAt":1700000000000.0,"visibleAt":1700000000000.0,"deadlineAt":1700000300000.0}""")
        val auto = assertNotNull(interaction.autoResolution)
        assertEquals(AutoResolution.State.VISIBLE_COUNTDOWN, auto.state)
        assertEquals(1700000000000L, auto.startedAtMs)
        assertEquals(1700000000000L, auto.visibleAtMs)
        assertEquals(1700000300000L, auto.deadlineAtMs)
        assertEquals(1700000300000L, auto.deadlineMs, "the fuse's fire time is the deadline")
    }

    @Test
    fun `hiddenGrace decodes and carries NO countdown deadline`() {
        val interaction = entry("""{"state":"hiddenGrace","startedAt":1700000000000.0,"visibleAt":1700000060000.0,"deadlineAt":1700000300000.0}""")
        val auto = assertNotNull(interaction.autoResolution)
        assertEquals(AutoResolution.State.HIDDEN_GRACE, auto.state)
        assertNull(auto.deadlineMs, "grace hides the countdown — the phone shows no ticking fuse yet")
    }

    @Test
    fun `snoozed decodes with the snoozed timestamp`() {
        val interaction = entry("""{"state":"snoozed","startedAt":1700000000000.0,"snoozedAt":1700000100000.0}""")
        val auto = assertNotNull(interaction.autoResolution)
        assertEquals(AutoResolution.State.SNOOZED, auto.state)
        assertEquals(1700000100000L, auto.snoozedAtMs)
        assertNull(auto.deadlineMs)
    }

    @Test
    fun `an unknown state is retained as UNKNOWN - never guessed (INV-8)`() {
        val interaction = entry("""{"state":"someFutureState","startedAt":1700000000000.0,"deadlineAt":1700000300000.0}""")
        val auto = assertNotNull(interaction.autoResolution)
        assertEquals(AutoResolution.State.UNKNOWN, auto.state)
        assertNull(auto.deadlineMs, "an unknown state never produces a countdown")
        assertEquals(1700000300000L, auto.deadlineAtMs, "the raw numbers are still retained")
    }

    @Test
    fun `a missing autoResolution key decodes to null`() {
        val interaction = entry(null)
        assertNull(interaction.autoResolution)
    }

    @Test
    fun `expiredAt - a live fuse whose deadline passed is expired`() {
        val auto = AutoResolution.decode(
            Json.parseToJsonElement("""{"state":"visibleCountdown","startedAt":1000.0,"visibleAt":1000.0,"deadlineAt":2000.0}""").jsonObject,
        )
        assertTrue(auto.expiredAt(nowMs = 2000L), "at the deadline the fuse has fired")
        assertTrue(auto.expiredAt(nowMs = 3000L), "past the deadline: the already-expired face")
        assertFalse(auto.expiredAt(nowMs = 1500L), "before the deadline: the countdown is live")
    }

    @Test
    fun `the hook plane tolerates an entry WITHOUT the fuse (the desktop forbids it there)`() {
        val json = """
        {
          "interactionId": "hook-rig",
          "kind": "workspaceHookReview",
          "anchorRowId": null,
          "createdAt": 1700000000000.0,
          "payload": {"kind":"workspaceHookReview","interactionId":"hook-rig","items":[]}
        }
        """.trimIndent()
        val interaction = assertNotNull(PendingInteraction.decode(Json.parseToJsonElement(json).jsonObject))
        assertNull(interaction.autoResolution)
    }
}
