package com.zcode.android.features.git

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.components.LocalNotifier
import com.zcode.android.core.ui.components.Notifier
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitFileDiff
import com.zcode.android.domain.model.GitIdentity
import com.zcode.android.domain.model.GitSummary
import com.zcode.android.features.git.port.GitPort
import com.zcode.android.features.git.ui.GitScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P10-A (pass-0 Phase 4): the Commit sheet's Commit action SILENTLY no-oped when
 * the hub's active-workspace resolution was null — dialog closed, zero wire verbs,
 * zero logs, zero user face (`writeOp`'s `?: return null` was the silent exit; the
 * onCommit `if (hash != null)` branch had no else). The null-target path must render
 * a TYPED refusal — never a silent close.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase4CommitNullTargetTest {

    @get:Rule
    val rule = createComposeRule()

    private val notices = mutableListOf<String>()

    /** The typed unavailable copy the null-target face renders (F-P10-A's string). */
    private val unavailableCopy = "The git channel is not connected — no active workspace."

    /** The write target is GONE: commit() resolves to null (the live silent face). */
    private open class NullTargetPort : GitPort {
        override suspend fun summary(): GitSummary? = GitSummary(
            repoRoot = "/ws", branchName = "main", trackingBranchName = "origin/main",
            headRefType = "branch", ahead = 0, behind = 0, isDirty = false,
            isGitAvailable = true, isRepository = true,
        )

        override suspend fun changes(sourceId: String): List<GitChangeEntry> = emptyList()

        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null

        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage =
            GitPort.CommitsPage(emptyList<GitCommit>(), false)

        override fun workspacePath(): String? = null

        override suspend fun identity(): GitIdentity? = GitIdentity("Ernest", "ernest@example.test")

        override suspend fun commit(message: String, stagedOnly: Boolean): com.zcode.android.domain.model.GitCommitResult? = null

        /** The DefaultGitPort shape post-fix: the null target is a TYPED refusal. */
        override suspend fun commitOutcome(message: String, stagedOnly: Boolean): GitPort.CommitOutcome =
            GitPort.CommitOutcome(
                result = null,
                refusal = GitPort.GitWriteRefusal(noTarget = true, message = null),
            )
    }

    @Test
    fun `the null-target commit renders a typed refusal - F-P10-A`() {
        val port = NullTargetPort()
        rule.setContent {
            ZCodeTheme {
                CompositionLocalProvider(
                    LocalNotifier provides Notifier { message, _ -> notices.add(message) },
                ) {
                    GitScreen(port = port)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("git_commit_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("commit_message_field").performTextInput("rig message")
        rule.onNodeWithTag("commit_send").performClick()
        rule.waitForIdle()
        assertTrue(
            "the null-target commit must render a typed refusal (RED pre-fix: the silent exit " +
                "closed the dialog with zero wire verbs and zero face); notices=$notices",
            notices.any { it.contains(unavailableCopy) },
        )
    }
}
