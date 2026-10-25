package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.ApprovalsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * K4 + L3 (gaps-and-polish Phase 3B, P1): the approvals card — the app's highest-stakes
 * surface — identified the task by a raw session-id prefix or a literal "?" (live
 * "task ? · 4 min ago", K4/L3) and offered Allow/Deny with NO command preview (approve-
 * blind, L3) although the payload was retained. The card now names the task (resolved
 * from the task store by sessionId; `?` only as the last resort) and renders one
 * bounded, redacted preview line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ApprovalsCardContextTest {

    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun record(
        id: String,
        sessionId: String? = "sess-1234567890abcdef-tail",
        taskTitle: String? = null,
        preview: String? = null,
        kind: String = "PERMISSION",
        tool: String? = "Bash",
    ) = ConversationPort.InteractionSummary(
        interactionId = id,
        sessionId = sessionId,
        kind = kind,
        lifecycle = "DISPLAYED",
        toolName = tool,
        createdAtMs = System.currentTimeMillis(),
        taskTitle = taskTitle,
        preview = preview,
    )

    private fun setContent(interactions: List<ConversationPort.InteractionSummary>) {
        rule.setContent {
            ZCodeTheme {
                ApprovalsScreen(
                    interactions = interactions,
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onOpenTask = {},
                    onAnswer = { _, _, _, _ -> },
                    scope = scope,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `K4 - the card names the TASK, not the session id`() {
        setContent(listOf(record("int-1", taskTitle = "Fix the login redirect")))
        rule.onNodeWithTag("approvals_task_ref_int-1", useUnmergedTree = true)
            .assertTextContains("Fix the login redirect", substring = true)
        // The 16-char session prefix is gone from the card.
        rule.onAllNodesWithText("sess-1234567890a", substring = true).assertCountEquals(0)
    }

    @Test
    fun `K4 - unresolved title falls back to the session prefix, and no session falls back to ?`() {
        setContent(listOf(record("int-a", taskTitle = null), record("int-b", sessionId = null, taskTitle = null)))
        rule.onNodeWithTag("approvals_task_ref_int-a", useUnmergedTree = true)
            .assertTextContains("sess-1234567890a", substring = true)
        rule.onNodeWithTag("approvals_task_ref_int-b", useUnmergedTree = true)
            .assertTextContains("?", substring = true)
    }

    @Test
    fun `L3 - a pending Bash approval renders a one-line command preview`() {
        setContent(listOf(record("int-1", taskTitle = "Fix login", preview = "rm -rf build && ./gradlew assembleDebug")))
        rule.onNodeWithTag("approvals_preview_int-1", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("rm -rf build && ./gradlew assembleDebug").assertIsDisplayed()
    }

    @Test
    fun `L3 - no preview means no preview row (never an empty monospace line)`() {
        setContent(listOf(record("int-1", preview = null)))
        assertTrue(rule.onAllNodesWithTag("approvals_preview_int-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `L3 - the deny confirmation quotes the preview so the user denies the right command`() {
        setContent(listOf(record("int-1", preview = "git push --force origin main")))
        rule.onNodeWithTag("approvals_deny_int-1").assertIsDisplayed()
        rule.onNodeWithTag("approvals_deny_int-1").performClick()
        rule.waitForIdle()
        // Both the card and the confirmation carry the request text — the dialog's is tagged.
        rule.onNodeWithTag("approvals_deny_preview", useUnmergedTree = true)
            .assertTextContains("git push --force origin main", substring = true)
        rule.onAllNodesWithText("git push --force origin main", substring = true).assertCountEquals(2)
    }
}
