package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.ErrorRecoveryCard
import com.zcode.android.features.conversation.ui.ToolErrorActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G66 (vc52 Phase 13): the typed error card with recovery actions. The desktop's
 * chat error card carries Switch model / Retry / Copy TraceID / Copy details /
 * Report issue (provider sign-in and quota/captcha stay desktop); the phone's
 * tool/turn failures rendered a raw `ERROR — code: message` block with NO typed
 * recovery. Pins: a scripted error renders the mobile-reachable actions; each
 * fires; Retry is gated by canRetry (the wire's actions.canRetry + an entityId);
 * Report issue hides when no callback is wired.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ErrorRecoveryCardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun card(
        canRetry: Boolean = true,
        onReportIssue: (() -> Unit)? = {},
        retry: () -> Unit = {},
        switchModel: () -> Unit = {},
    ) {
        rule.setContent {
            ZCodeTheme {
                ErrorRecoveryCard(
                    code = "fault.rate_limit_exceeded",
                    message = "The desktop refused the request (429)",
                    actions = ToolErrorActions(
                        canRetry = canRetry,
                        onRetry = retry,
                        onSwitchModel = switchModel,
                        onReportIssue = onReportIssue?.let { cb -> { cb() } },
                    ),
                )
            }
        }
    }

    @Test
    fun `a scripted error renders the five mobile-reachable actions`() {
        card()
        rule.onNodeWithTag("error_recovery_card").assertExists()
        rule.onNodeWithTag("error_action_retry").assertExists()
        rule.onNodeWithTag("error_action_switch_model").assertExists()
        rule.onNodeWithTag("error_action_report").assertExists()
        rule.onNodeWithTag("error_copy_trace").assertExists()
        rule.onNodeWithTag("error_copy_details").assertExists()
    }

    @Test
    fun `Retry fires`() {
        var retried = false
        card(retry = { retried = true })
        rule.onNodeWithTag("error_action_retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun `Switch model fires`() {
        var switched = false
        card(switchModel = { switched = true })
        rule.onNodeWithTag("error_action_switch_model").performClick()
        assertTrue(switched)
    }

    @Test
    fun `Report issue fires with the card open`() {
        var reported = false
        card(onReportIssue = { reported = true })
        rule.onNodeWithTag("error_action_report").performClick()
        assertTrue(reported)
    }

    @Test
    fun `canRetry=false hides Retry (the wire withheld the capability)`() {
        card(canRetry = false)
        rule.onNodeWithTag("error_recovery_card").assertExists()
        rule.onAllNodesWithTag("error_action_retry").apply {
            assertEquals(0, fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `a null report callback hides Report issue (desktop-author plane)`() {
        card(onReportIssue = null)
        rule.onAllNodesWithTag("error_action_report").apply {
            assertEquals(0, fetchSemanticsNodes().size)
        }
    }

    /** The copy buttons put the trace id / full details on the clipboard (§27 user-initiated). */
    @Test
    fun `Copy TraceID and Copy details land the right clipboard payloads`() {
        card()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        rule.onNodeWithTag("error_copy_trace").performClick()
        assertEquals("fault.rate_limit_exceeded", cm.primaryClip?.getItemAt(0)?.text?.toString())
        rule.onNodeWithTag("error_copy_details").performClick()
        assertEquals(
            "fault.rate_limit_exceeded: The desktop refused the request (429)",
            cm.primaryClip?.getItemAt(0)?.text?.toString(),
        )
    }
}
