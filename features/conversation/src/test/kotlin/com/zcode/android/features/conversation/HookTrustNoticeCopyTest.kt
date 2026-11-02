package com.zcode.android.features.conversation

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.features.conversation.ui.sendNoticeText
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-A15 renderer half: the HOOK_TRUST_APPLIED key resolves to the success copy per
 * locale — and never to the fixed offline string the old ROW_ACTION_OFFLINE key mapped to.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class HookTrustNoticeCopyTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `A15 - the applied key renders the trusted copy, not the offline banner`() {
        var resolved = ""
        rule.setContent {
            val notice = SendNotice(
                "Trusted 2 hook(s) — the session continues.",
                SendNotice.Kind.INFO,
                SendNotice.Key.HOOK_TRUST_APPLIED,
                listOf("2"),
            )
            resolved = sendNoticeText(notice)
            Text(resolved)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Trusted 2 hook(s) — the session continues.").assertIsDisplayed()
        assertFalse("the applied key must not render the offline banner: $resolved", resolved.contains("Offline"))
        assertFalse("the applied key must not render the not-sent copy: $resolved", resolved.contains("not sent"))
    }

    @Test
    fun `A15 - the offline key still renders the offline banner (unchanged plane)`() {
        var resolved = ""
        rule.setContent {
            val notice = SendNotice(
                "Not connected",
                SendNotice.Kind.WARNING,
                SendNotice.Key.ROW_ACTION_OFFLINE,
            )
            resolved = sendNoticeText(notice)
            Text(resolved)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Offline — the action was not sent. Nothing changed.").assertIsDisplayed()
    }
}
