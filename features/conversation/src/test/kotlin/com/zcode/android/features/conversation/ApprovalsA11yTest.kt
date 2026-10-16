package com.zcode.android.features.conversation

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.ui.ApprovalsScreen
import com.zcode.android.features.conversation.port.ConversationPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Phase 11 §30 items on the APPROVAL surfaces (the safety-critical path):
 *  §30.2 approval actions ≥ 56 dp; §30.1 labels on every interactive;
 *  §30.4 traversal order (scripted leg); §30.6 deny/allow never color-only
 *  (textual option labels — asserted with zh-CN resources in the qualifier class).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ApprovalsA11yTest {

    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun interaction(id: String) = ConversationPort.InteractionSummary(
        interactionId = id,
        sessionId = "sess-demo-1",
        kind = "PERMISSION",
        lifecycle = "REQUESTED",
        toolName = "Bash",
        createdAtMs = System.currentTimeMillis() - 65_000,
    )

    private fun setContent(interactions: List<ConversationPort.InteractionSummary>, onAnswer: (String, String, String?) -> Unit = { _, _, _ -> }) {
        val wrapped: (String, String, String?, (ConversationPort.AnswerOutcome) -> Unit) -> Unit =
            { a, b, c, onOutcome -> onAnswer(a, b, c); onOutcome(ConversationPort.AnswerOutcome.SUBMITTED) }
        rule.setContent {
            ZCodeTheme {
                ApprovalsScreen(
                    interactions = interactions,
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onOpenTask = {},
                    onAnswer = wrapped,
                    scope = scope,
                )
            }
        }
    }

    private fun allNodes(): List<androidx.compose.ui.semantics.SemanticsNode> {
        val root = rule.onRoot().fetchSemanticsNode()
        val out = ArrayList<androidx.compose.ui.semantics.SemanticsNode>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            out += n
            n.children.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    /** §30.2 — approval actions render ≥ 56 dp tall. */
    @Test
    fun approvalActions_meet56dp() {
        setContent(listOf(interaction("i-1")))
        val density = rule.density.density
        val buttons = allNodes().filter {
            it.config.getOrNull(SemanticsActions.OnClick) != null &&
                (it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t ->
                    t.text.contains("Allow once") || t.text == "Deny"
                })
        }
        assertTrue("expected the two approval actions", buttons.size >= 2)
        buttons.forEach {
            assertTrue("approval action below 56dp: ${it.boundsInRoot}", it.boundsInRoot.height / density >= 56f)
        }
    }

    /** §30.1 + §30.4 — labels present; traversal order matches visual order. */
    @Test
    fun labelsAndTraversalOrder() {
        setContent(listOf(interaction("i-1")))
        val clickables = allNodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(clickables.size >= 4)
        // §30.1 under M3 Scaffold — TalkBack order = traversal groups (bar chrome
        // first via ZCodeTopBar's group/index-0, body index-1); the tree order the
        // rule enumerates is body-first. Pin the body's visual order + chrome-on-top.
        val headerBottom = 48f * rule.density.density * 2
        val (chrome, body) = clickables.map { it.boundsInRoot.center.y }.partition { it <= headerBottom }
        assertTrue("chrome above every body row: $chrome vs $body", chrome.all { c -> body.all { c <= it } })
        assertEquals("body clickables must be in visual order", body.sorted(), body)
        val unlabelled = clickables.count {
            it.config.getOrNull(SemanticsProperties.Text)?.isEmpty() != false &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.isEmpty() != false
        }
        assertEquals("every clickable carries a label", 0, unlabelled)
    }

    /** §20.2 deny flow keeps its confirm (never a one-tap deny at inbox either). */
    @Test
    fun denyRequiresConfirm_beforeAnswer() {
        var answered: Triple<String, String, String?>? = null
        setContent(listOf(interaction("i-1")), onAnswer = { a, b, c -> answered = Triple(a, b, c) })
        rule.onNodeWithTag("approvals_deny_i-1").performClick()
        rule.runOnIdle { assertEquals(null, answered) } // confirm dialog first
        rule.onNodeWithTag("approvals_deny_confirm").performClick()
        rule.runOnIdle { assertEquals("deny", answered?.second) }
    }
}

/** §31: the approval option labels resolve under zh-CN resources. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "zh-rCN")
class ApprovalsZhCnTest {

    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun actionLabels_renderInChinese() {
        val record = ConversationPort.InteractionSummary(
            interactionId = "i-9",
            sessionId = "sess-1",
            kind = "PERMISSION",
            lifecycle = "REQUESTED",
            toolName = "Bash",
            createdAtMs = System.currentTimeMillis() - 65_000,
        )
        rule.setContent {
            ZCodeTheme {
                ApprovalsScreen(
                    interactions = listOf(record),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {}, onOpenTask = {}, onAnswer = { _, _, _, _ -> }, scope = scope,
                )
            }
        }
        rule.onNodeWithText("审批").fetchSemanticsNode() // exists
        rule.onNodeWithText("仅允许一次").assertExists()
        rule.onNodeWithText("拒绝").assertExists()
    }
}
