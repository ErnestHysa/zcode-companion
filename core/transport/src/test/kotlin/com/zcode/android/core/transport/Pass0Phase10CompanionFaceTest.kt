package com.zcode.android.core.transport

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.RecoveryAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * pass-0 Phase 10 — G97's conflict-face pin. The relay takeover (KICKED, §15.3)
 * is the ONE phone-visible signal of the desktop's web-remote companion plane:
 * a browser companion page (or another remote page) taking the connection. The
 * typed face names the takeover AND carries the remedy the desktop's own
 * `singlePageNote` gives ("This relay pairing supports one phone page at a
 * time. If it is already open elsewhere, close the old page first." — asar
 * `webRemoteControl.singlePageNote`, 3.18.1 `ec475e6e…`). The bare "Another
 * ZCode Remote page took over this connection" copy (no remedy, no companion
 * naming) was the G97 face gap.
 */
class Pass0Phase10CompanionFaceTest {

    @Test
    fun `the KICKED takeover face names the companion and carries the single-page remedy`() {
        val err = ErrorMapping.relayError("KICKED", "kicked by takeover")
        assertEquals(ErrorCategory.SESSION, err.category)
        assertTrue(
            err.userMessage.contains("Remote page") || err.userMessage.contains("remote page"),
            "the face names the takeover source (browser companion / another page): " + err.userMessage,
        )
        assertTrue(
            err.userMessage.contains("close", ignoreCase = true),
            "the face carries the close-the-other-page remedy: " + err.userMessage,
        )
    }

    @Test
    fun `the takeover stays a USER-retryable SESSION error with reconnect recovery`() {
        val err = ErrorMapping.relayError("KICKED", "kicked by takeover")
        assertEquals(AppError.Retryable.USER, err.retryable)
        assertEquals(RecoveryAction.RECONNECT, err.autoRecovery)
    }
}
