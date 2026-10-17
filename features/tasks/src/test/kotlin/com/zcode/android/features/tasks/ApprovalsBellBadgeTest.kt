package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.tasks.ui.ApprovalsBellAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B2.9 (gaps-and-polish Phase 3C): the task list's approval count badge was silently
 * CAPPED at "9" (`openApprovals.coerceAtMost(9)`) — 12 pending approvals read as 9.
 * The badge now shows the true count.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ApprovalsBellBadgeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the badge shows the TRUE count past nine`() {
        rule.setContent { ZCodeTheme { ApprovalsBellAction(openApprovals = 12, onOpenApprovals = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_approvals_badge", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun `small counts render as-is`() {
        rule.setContent { ZCodeTheme { ApprovalsBellAction(openApprovals = 2, onOpenApprovals = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun `zero renders no badge`() {
        rule.setContent { ZCodeTheme { ApprovalsBellAction(openApprovals = 0, onOpenApprovals = {}) } }
        rule.waitForIdle()
        org.junit.Assert.assertTrue(
            rule.onAllNodesWithTag("tasks_approvals_badge", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }
}
