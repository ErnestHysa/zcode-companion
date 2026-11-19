package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.sendNoticeText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BT-1 (pass-0 Phase 7) renderer mapping pin: the NEW row-action stale key
 * resolves to `notice_row_action_stale` ("…— try again.") — never the switch
 * copy. (Names the new enum, so it lands with the fix commit; the pre-fix RED
 * is RowActionStaleNoticeCopyTest's source pin + the ViewModel behavioral pin.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase7RowActionStaleRenderTest {

    @get:Rule
    val rule = createComposeRule()

    private fun rendered(notice: SendNotice): String {
        lateinit var text: String
        rule.setContent { ZCodeTheme { text = sendNoticeText(notice) } }
        rule.waitForIdle()
        return text
    }

    @Test
    fun `the ROW_ACTION_STALE key renders the try-again copy - never switch copy`() {
        val text = rendered(
            SendNotice(
                "The session changed on the desktop — try again.",
                SendNotice.Kind.WARNING,
                SendNotice.Key.ROW_ACTION_STALE,
            ),
        )
        assertFalse(
            "the row-action stale banner must not contain 'switch': $text",
            text.contains("switch", ignoreCase = true),
        )
        assertTrue("try again" in text.lowercase())
        assertTrue("session changed" in text.lowercase())
    }
}
