package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.ApprovalsScreen
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Approvals inbox UI (§18.3, Phase 7): empty state, open-interaction rows with the
 * one-tap allowOnce + deny-confirm actions, and the honest offline note (§17.6 —
 * nothing queues).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ApprovalsScreenUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun record(id: String, kind: String = "PERMISSION", tool: String? = "Bash") =
        ConversationPort.InteractionSummary(
            interactionId = id,
            sessionId = "sess-1",
            kind = kind,
            lifecycle = "DISPLAYED",
            toolName = tool,
            createdAtMs = System.currentTimeMillis(),
        )

    private fun setContent(
        interactions: List<ConversationPort.InteractionSummary>,
        phase: ConnectionPhase = ConnectionPhase.CONNECTED,
        onAnswer: (String, String, String?, (ConversationPort.AnswerOutcome) -> Unit) -> Unit = { _, _, _, _ -> },
    ) {
        rule.setContent {
            ZCodeTheme {
                ApprovalsScreen(
                    interactions = interactions,
                    phase = phase,
                    onBack = {},
                    onOpenTask = {},
                    onAnswer = onAnswer,
                    scope = scope,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun empty_state_rendersNothingNeedsYou() {
        setContent(emptyList())
        rule.onNodeWithTag("approvals_empty").assertIsDisplayed()
    }

    @Test
    fun offline_showsHonestNote() {
        setContent(listOf(record("i-1")), phase = ConnectionPhase.RECONNECTING)
        rule.onNodeWithTag("approvals_offline_note").assertIsDisplayed()
    }

    @Test
    fun permissionRow_allowOnce_answersImmediately() {
        var answered: Pair<String, String>? = null
        setContent(listOf(record("i-allow"))) { iid, opt, _, _ -> answered = iid to opt }
        rule.onNodeWithTag("approvals_allow_once_i-allow").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("i-allow" to "allowOnce", answered)
    }

    @Test
    fun permissionRow_denyRequiresConfirm() {
        var answered: Pair<String, String>? = null
        setContent(listOf(record("i-deny"))) { iid, opt, _, _ -> answered = iid to opt }
        rule.onNodeWithTag("approvals_deny_i-deny").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertNull(answered) // §20.2: deny ALWAYS confirms
        rule.onNodeWithText("Deny this request?").assertIsDisplayed()
        rule.onNodeWithTag("approvals_deny_confirm").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("i-deny" to "deny", answered)
    }

    @Test
    fun userInputRow_routesToTheTaskForFullContext() {
        var opened: String? = null
        val interactions = listOf(record("i-q", kind = "USER_INPUT", tool = null))
        rule.setContent {
            ZCodeTheme {
                ApprovalsScreen(
                    interactions = interactions,
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onOpenTask = { opened = it },
                    onAnswer = { _, _, _, _ -> },
                    scope = scope,
                )
            }
        }
        rule.waitForIdle()
        // The question row does NOT offer a blind answer — full context lives on the card.
        org.junit.Assert.assertEquals(0, rule.onAllNodesWithTag("approvals_allow_once_i-q").fetchSemanticsNodes().size)
        rule.onNodeWithText("Open task to answer").performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("sess-1", opened)
    }
}
