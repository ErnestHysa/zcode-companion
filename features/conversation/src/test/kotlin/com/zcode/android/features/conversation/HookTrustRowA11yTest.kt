package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceHookReview
import com.zcode.android.features.conversation.ui.HookTrustReviewCard
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * N-BF-4 + N-BH-3 (pass 36 a11y cluster; carries pass-21's "already trusted" nicety):
 * (1) a hook-review ROW tap did not toggle — only the checkbox did, and Trust stayed
 * disabled at "Trust 0 selected hook(s)" (the desktop's row IS the toggle); (2) a
 * trusted_persistent item's disabled checkbox looked IDENTICAL to an actionable one,
 * carried no content description and had no "Trusted" label (two taps produced no
 * effect and no feedback).
 *
 * Pins: a row tap toggles selection (Trust N enables); a trusted item renders the
 * "Trusted" state chip INSTEAD of a checkbox, and the merged row still announces it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class HookTrustRowA11yTest {

    @get:Rule
    val rule = createComposeRule()

    private fun hook() = WorkspaceHookReview(
        reviewFlowId = "flow-1", generation = 1, interactionId = "hook-1",
        sessionId = "s1", taskId = "t1", runId = "r1",
        workspaceIdentity = "/repo", workspaceLabel = "repo",
        bundleDigest = "d", createdAtMs = 1L, deadlineAtMs = 2L,
        workspacePath = "/repo", remoteSessionId = null,
        summary = WorkspaceHookReview.Summary(eventCount = 2, hookCount = 2, pendingCount = 1),
        items = listOf(
            WorkspaceHookReview.Item(
                reviewItemId = "h-pending", event = "PreToolUse", matcher = null, type = "command",
                displayName = "New hook", displayCommand = "bash new.sh", sourcePath = "/repo/.zcode/hooks.json",
                executionMode = "foreground", configuredEnabled = true, editable = false,
                trustState = "pending_trust",
            ),
            WorkspaceHookReview.Item(
                reviewItemId = "h-trusted", event = "PreToolUse", matcher = null, type = "command",
                displayName = "Already trusted", displayCommand = "bash old.sh", sourcePath = "/repo/.zcode/hooks.json",
                executionMode = "foreground", configuredEnabled = true, editable = false,
                trustState = "trusted_persistent",
            ),
        ),
    )

    private fun setContent(onTrust: (List<String>) -> Unit = {}) {
        rule.setContent {
            ZCodeTheme {
                HookTrustReviewCard(
                    hook = hook(),
                    busy = false,
                    onTrustSelected = { onTrust(it) },
                    onRevoke = {},
                    onToggleItem = { _, _ -> },
                    onGrantPersistent = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `N-BF-4 - a row tap toggles selection (Trust N enables and fires)`() {
        var trusted: List<String>? = null
        setContent { trusted = it }
        // The actionable row starts unchecked → Trust disabled.
        rule.onNodeWithTag("hook_review_trust_selected").assertIsDisplayed()
        assertTrue(
            "precondition: Trust must start disabled at 0 selected",
            androidx.compose.ui.semantics.SemanticsProperties.Disabled in rule.onNodeWithTag("hook_review_trust_selected").fetchSemanticsNode().config,
        )
        // THE BUG: tapping the ROW did nothing (only the checkbox toggled).
        rule.onNodeWithTag("hook_review_item_h-pending").performClick()
        rule.waitForIdle()
        assertTrue(
            "row tap did not enable Trust (the row is not the toggle)",
            androidx.compose.ui.semantics.SemanticsProperties.Disabled !in rule.onNodeWithTag("hook_review_trust_selected").fetchSemanticsNode().config,
        )
        // The toggle fires the trust action through the pinned row (with its confirm).
        rule.onNodeWithTag("hook_review_trust_selected").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("hook_review_confirm_yes").performClick()
        rule.waitForIdle()
        assertEquals(listOf("h-pending"), trusted)
    }

    @Test
    fun `N-BH-3 - a trusted item shows the Trusted state chip instead of a checkbox`() {
        setContent()
        // (The chip and checkbox are merged INTO the toggleable row — the tags live
        // in the UNMERGED tree, the row's merged text carries "Trusted".)
        rule.onAllNodesWithTag("hook_review_state_h-trusted", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText("Trusted").assertIsDisplayed()
        // No checkbox node for the trusted item (the lookalike is gone).
        rule.onAllNodesWithTag("hook_review_check_h-trusted", useUnmergedTree = true).assertCountEquals(0)
        // The actionable item keeps its checkbox.
        rule.onAllNodesWithTag("hook_review_check_h-pending", useUnmergedTree = true).assertCountEquals(1)
    }
}
