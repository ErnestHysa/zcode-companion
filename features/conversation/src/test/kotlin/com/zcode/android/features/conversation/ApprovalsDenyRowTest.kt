package com.zcode.android.features.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
 * B2.3 (gaps-and-polish Phase 1C, P0): the Approve/Deny row is an UNWEIGHTED `Row`
 * with a dead `Spacer(0.dp)` and a constructed-then-discarded `PaddingValues(4.dp)`
 * — the two action buttons sit FLUSH (no spacing) and OVERFLOW narrow screens
 * (360dp-class phones / split-screen): the Deny button is pushed out of the card.
 * Fix shape: weighted row + real spacing — both buttons fully visible at 320dp
 * with a gap between them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ApprovalsDenyRowTest {

    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun record(id: String) = ConversationPort.InteractionSummary(
        interactionId = id,
        sessionId = "sess-1",
        kind = "PERMISSION",
        lifecycle = "DISPLAYED",
        toolName = "Bash",
        createdAtMs = System.currentTimeMillis(),
    )

    @Test
    fun `approve and deny sit inside 320dp with real spacing`() {
        val id = "i-flush-row"
        rule.setContent {
            ZCodeTheme {
                Box(androidx.compose.ui.Modifier.requiredWidth(320.dp)) {
                    ApprovalsScreen(
                        interactions = listOf(record(id)),
                        phase = ConnectionPhase.CONNECTED,
                        onBack = {},
                        onOpenTask = {},
                        onAnswer = { _, _, _, _ -> },
                        scope = scope,
                    )
                }
            }
        }
        rule.waitForIdle()

        val density = rule.density.density
        val allow = rule.onAllNodesWithTag("approvals_allow_once_${id.take(12)}")
            .fetchSemanticsNode().let { it.positionInRoot.x + it.size.width }
        val denyStart = rule.onNodeWithTag("approvals_deny_${id.take(12)}")
            .fetchSemanticsNode().positionInRoot.x
        val denyEnd = rule.onNodeWithTag("approvals_deny_${id.take(12)}")
            .fetchSemanticsNode().let { it.positionInRoot.x + it.size.width }

        assertTrue(
            "the buttons must not sit flush (dead Spacer(0) / discarded PaddingValues)",
            denyStart - allow >= 8f * density,
        )
        assertTrue(
            "the Deny button must fit inside a 320dp card (end=$denyEnd)",
            denyEnd <= 320f * density + 1f,
        )
    }
}
