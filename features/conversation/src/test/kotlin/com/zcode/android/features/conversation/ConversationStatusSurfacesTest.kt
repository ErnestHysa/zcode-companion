package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.domain.model.TurnFileChanges
import com.zcode.android.features.conversation.ui.SessionStatusStrip
import com.zcode.android.features.conversation.ui.TurnSeparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * I2.1+I2.2 (gaps-and-polish Phase 3C): the phone's git data never reached its
 * conversation screen, and the per-turn change summary was a bare separator line. The
 * session status strip (branch · clean/changes · agents — desktop `chat.statusPanel.*`)
 * and the per-turn change summary card (desktop `chat.changeSummary.*`) render inside
 * the conversation; both derive from data the phone already receives (the git summary
 * channel + the turnHeader fileChanges).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConversationStatusSurfacesTest {

    @get:Rule
    val rule = createComposeRule()

    private fun summary(dirty: Boolean = true) = GitSummary(
        repoRoot = "/repo",
        branchName = "feat/picker",
        trackingBranchName = "origin/feat/picker",
        headRefType = "branch",
        ahead = 2,
        behind = 0,
        isDirty = dirty,
        isGitAvailable = true,
        isRepository = true,
    )

    // ------------------------------------------------------------------ I2.2 status strip

    @Test
    fun `the strip shows the branch, the working-tree state and the agent count`() {
        rule.setContent { ZCodeTheme { SessionStatusStrip(summary = summary(dirty = true), runningAgents = 2) } }
        rule.waitForIdle()
        rule.onNodeWithTag("session_status_strip").assertIsDisplayed()
        rule.onNodeWithText("feat/picker", substring = true).assertIsDisplayed()
        rule.onNodeWithText("2", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a clean tree says so`() {
        rule.setContent { ZCodeTheme { SessionStatusStrip(summary = summary(dirty = false), runningAgents = 0) } }
        rule.waitForIdle()
        rule.onNodeWithTag("session_status_clean", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("session_status_dirty", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `no summary - no strip (honest absence, never a guessed branch)`() {
        rule.setContent { ZCodeTheme { SessionStatusStrip(summary = null, runningAgents = 0) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("session_status_strip").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a non-repository workspace renders no strip`() {
        val notRepo = summary().copy(isRepository = false)
        rule.setContent { ZCodeTheme { SessionStatusStrip(summary = notRepo, runningAgents = 0) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("session_status_strip").fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------------------------ I2.1 change summary card

    private fun turnRow(files: Long = 3) = ConversationRow.TurnHeader(
        rowId = 5,
        turnId = "t5",
        base = ConversationRow.Base(createdAt = 1.0, createdAtSeq = 5),
        origin = "user",
        executionKind = null,
        state = ConversationRow.TurnState.COMPLETED_SUCCESS,
        startedAt = 0.0,
        endedAt = 1000.0,
        activeMs = 1000,
        sourceCommandId = null,
        historyRoundCount = null,
        fileChanges = TurnFileChanges(files = files.toInt(), additions = 12, deletions = 4, state = "active", items = emptyList()),
        raw = buildJsonObject { },
    )

    @Test
    fun `a turn with file changes renders the summary card`() {
        rule.setContent { ZCodeTheme { TurnSeparator(row = turnRow()) } }
        rule.waitForIdle()
        rule.onNodeWithTag("row_turn_card").assertIsDisplayed()
        rule.onNodeWithText("3", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("row_turn_changes").assertIsDisplayed()
    }

    @Test
    fun `a turn without changes stays the quiet separator`() {
        val row = turnRow(files = 0)
        rule.setContent { ZCodeTheme { TurnSeparator(row = row) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("row_turn_card").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("row_turn").assertIsDisplayed()
    }

    @Test
    fun `tapping review on the card opens the diff viewer callback`() {
        var opened = false
        rule.setContent { ZCodeTheme { TurnSeparator(row = turnRow(), onOpenChanges = { opened = true }) } }
        rule.waitForIdle()
        rule.onNodeWithTag("row_turn_changes").performClick()
        rule.waitForIdle()
        assertEquals(true, opened)
    }
}
