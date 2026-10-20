package com.zcode.android.app.settings

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * H1.6 (Phase 6B) pins: the feedback form — the four desktop ticket types, a required
 * description (empty → submit disabled), submit rides the sender with the wire's
 * enum value and the trimmed contact (blank → null), the ticket notice renders the
 * desktop's ticket id, and a refused submit shows the honest failure copy.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class FeedbackFormUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `empty description disables submit - typing enables and the submit rides the sender`() {
        val calls = mutableListOf<Triple<String, String, String?>>()
        rule.setContent {
            ZCodeTheme {
                FeedbackFormDialog(
                    onDismiss = {},
                    sender = { type, description, contact ->
                        calls += Triple(type, description, contact)
                        delay(10)
                        FeedbackResult.Ticket("mock-ticket-7", "open")
                    },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("feedback_submit").assertIsNotEnabled()
        rule.onNodeWithTag("feedback_type_feature").performClick()
        rule.onNodeWithTag("feedback_description").performTextInput("The automations editor reset my schedule")
        rule.onNodeWithTag("feedback_contact").performTextInput("me@example.test")
        rule.onNodeWithTag("feedback_submit").assertIsEnabled()
        rule.onNodeWithTag("feedback_submit").performClick()
        rule.waitForIdle()
        // The delay makes the busy gate observable; settle and assert the send.
        rule.waitUntil(timeoutMillis = 5_000) { calls.isNotEmpty() }
        assertEquals(listOf(Triple("feature", "The automations editor reset my schedule", "me@example.test")), calls)
        rule.onNodeWithTag("feedback_result").assertExists()
        rule.onNodeWithText("Ticket mock-ticket-7 created — thank you!").assertExists()
    }

    @Test
    fun `a refused submit shows the honest failure copy`() {
        rule.setContent {
            ZCodeTheme {
                FeedbackFormDialog(
                    onDismiss = {},
                    sender = { _, _, _ -> FeedbackResult.Failed("not connected") },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("feedback_description").performTextInput("something broke")
        rule.onNodeWithTag("feedback_submit").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("feedback_result").assertExists()
        rule.onNodeWithText("The feedback could not be sent — check the connection and try again.").assertExists()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExists(): androidx.compose.ui.test.SemanticsNodeInteraction {
    assertExists()
    return this
}
