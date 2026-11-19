package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.TurnDiffDialog
import com.zcode.android.features.conversation.ui.sendNoticeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BO-1 (pass-0 Phase 7) — the conversation-module surfaces that interpolated RAW
 * `fault.*` codes into user copy. The G66/K9 fault→human mapping
 * (core.ui ErrorCopy.faultMessageRes) never reached these Phase-9 faces:
 *
 *  1. the per-turn changes viewer's error face (`changes_error` %s ← raw loader error);
 *  2. the attachment FAILED banner (`notice_attachment_failed` %1$s ← the raw
 *     `TransportFailure` diagnostic via `ErrorMapping.taskRejected`).
 *
 * (The quota-reset face and the changes-viewer app-side census ride the app module's
 * QuotaResetRawFaultCopyTest; the resurrected-chip banner shares surface 2.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class RawFaultCodeUserCopyTest {

    @get:Rule
    val rule = createComposeRule()

    // ------------------------------------------------------------------ surface 1

    @Test
    fun `the changes viewer error face renders human copy - never a raw fault code`() {
        rule.setContent {
            ZCodeTheme {
                TurnDiffDialog(
                    turnLabel = "Turn 3",
                    rowId = 41L,
                    entityId = "ent-41",
                    provider = { _, _ -> ConversationPort.TurnChangesResult(null, "fault.file.notFound") },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        val raw = rule.onAllNodesWithText("fault.", substring = true).fetchSemanticsNodes()
        assertEquals(
            "the changes error face interpolated the RAW fault code (fault.file.notFound)",
            0,
            raw.size,
        )
        val human = rule.onAllNodesWithText("available on the connected desktop", substring = true)
            .fetchSemanticsNodes()
        assertTrue(
            "the fault→human mapping copy must render instead (${human.size} nodes)",
            human.isNotEmpty(),
        )
    }

    // ------------------------------------------------------------------ surface 2

    @Test
    fun `the attachment FAILED banner maps fault args to human copy`() {
        lateinit var text: String
        rule.setContent {
            ZCodeTheme {
                text = sendNoticeText(
                    SendNotice(
                        "Attachment failed to upload (fault.attachment.commitFail) — not sent.",
                        SendNotice.Kind.ERROR,
                        SendNotice.Key.ATTACHMENT_FAILED,
                        listOf("fault.attachment.commitFail"),
                    ),
                )
            }
        }
        rule.waitForIdle()
        assertFalse(
            "the FAILED banner interpolated the raw fault arg: $text",
            text.contains("fault."),
        )
        assertTrue("the human copy must render instead: $text", text.contains("upload", ignoreCase = true))
    }

    @Test
    fun `a non-fault FAILED arg passes through unchanged (connection-lost family)`() {
        lateinit var text: String
        rule.setContent {
            ZCodeTheme {
                text = sendNoticeText(
                    SendNotice(
                        "Attachment failed to upload (connection lost) — not sent.",
                        SendNotice.Kind.ERROR,
                        SendNotice.Key.ATTACHMENT_FAILED,
                        listOf(""),
                    ),
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            "the blank arg keeps the connection-lost fallback: $text",
            text.contains("connection", ignoreCase = true),
        )
        assertFalse("no raw fault in the fallback face", text.contains("fault."))
    }
}
