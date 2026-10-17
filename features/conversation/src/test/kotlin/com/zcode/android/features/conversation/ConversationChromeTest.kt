package com.zcode.android.features.conversation

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import java.io.File
import com.zcode.android.features.conversation.ui.StripBudget
import com.zcode.android.features.conversation.ui.UnreadBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B2.9+B2.12 (gaps-and-polish Phase 3C): the conversation's unread marker was a bare
 * 16dp dot with no count (B2.9) and the pinned-strip stack above the timeline was
 * unbounded — stale/attention/queue/history/load-older strips together can squeeze the
 * timeline to zero (B2.12). Numbered badges + a collapse budget with "+N more"
 * (blocking interaction cards are NEVER budgeted — approval first, §18.2).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConversationChromeTest {

    @get:Rule
    val rule = createComposeRule()

    // ------------------------------------------------------------------ B2.9 numbered badge

    @Test
    fun `the unread badge carries the COUNT`() {
        rule.setContent { ZCodeTheme { UnreadBadge(unread = 3) } }
        rule.waitForIdle()
        rule.onNodeWithTag("conversation_unread_badge").assertIsDisplayed()
        rule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `no unread - no badge`() {
        rule.setContent { ZCodeTheme { UnreadBadge(unread = 0) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("conversation_unread_badge").fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------------------------ B2.12 strip budget

    private fun strip(i: Int): @Composable () -> Unit = {
        Text("strip $i", modifier = Modifier.testTag("strip_$i"))
    }

    @Test
    fun `the strip stack collapses to the budget with a -N more toggle`() {
        rule.setContent { ZCodeTheme { StripBudget(strips = listOf(strip(1), strip(2), strip(3), strip(4), strip(5))) } }
        rule.waitForIdle()
        rule.onNodeWithTag("strip_1").assertIsDisplayed()
        rule.onNodeWithTag("strip_2").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("strip_3").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("strip_budget_more").assertIsDisplayed()
        rule.onNodeWithText("3", substring = true).assertIsDisplayed()
    }

    @Test
    fun `expanding the budget reveals every strip`() {
        rule.setContent { ZCodeTheme { StripBudget(strips = listOf(strip(1), strip(2), strip(3), strip(4), strip(5))) } }
        rule.waitForIdle()
        rule.onNodeWithTag("strip_budget_more").performClick()
        rule.waitForIdle()
        for (i in 1..5) rule.onNodeWithTag("strip_$i").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("strip_budget_more").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a stack under the budget renders everything with no toggle`() {
        rule.setContent { ZCodeTheme { StripBudget(strips = listOf(strip(1), strip(2))) } }
        rule.waitForIdle()
        for (i in 1..2) rule.onNodeWithTag("strip_$i").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("strip_budget_more").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the conversation screen keeps interaction cards OUTSIDE the budget`() {
        val src = File(repoRoot(), "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/ConversationScreen.kt").readText()
        val budgetCall = src.substringAfter("StripBudget(").substringBefore(")\n")
        assertEquals(false, "pendingPermissions" in budgetCall)
        assertEquals(false, "pendingUserInputs" in budgetCall)
    }

    private fun repoRoot(): File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir)
    }
}
