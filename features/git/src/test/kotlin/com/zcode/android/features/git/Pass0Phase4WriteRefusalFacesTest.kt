package com.zcode.android.features.git

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.components.LocalNotifier
import com.zcode.android.core.ui.components.Notifier
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.GitChangeEntry
import com.zcode.android.domain.model.GitCommit
import com.zcode.android.domain.model.GitCommitResult
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
 * pass-0 Phase 4: the write tier's refusal faces.
 *
 * F-P41-A: "Commit & push" silently half-completed on a refused push — the PushConfirm
 * closed BEFORE the async result, the success-only notice branch had NO else, and the
 * port mapped the refused call to null. The refused reason must reach the notice plane.
 *
 * F-P10-A rides the same notice plane (the commit null-target typed refusal) — its pin
 * lands with its own commits.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase4WriteRefusalFacesTest {

    @get:Rule
    val rule = createComposeRule()

    private val notices = mutableListOf<String>()

    /** The live refusal text the desktop serves on a refused push (the mock's
     * desktop-faithful body — receipted verbatim in pass 41). */
    private val pushRefusal = "Cannot push while HEAD is detached."

    /** The commit lands ok (so the andPush handoff arms the PushConfirm); the push
     * itself is REFUSED — the port's null-swallow boundary is the live face pre-fix. */
    private open class PushRefusedPort : GitPort {
        override suspend fun summary(): GitSummary? = GitSummary(
            repoRoot = "/ws", branchName = "main", trackingBranchName = "origin/main",
            headRefType = "branch", ahead = 1, behind = 0, isDirty = false,
            isGitAvailable = true, isRepository = true,
        )

        override suspend fun changes(sourceId: String): List<GitChangeEntry> = emptyList()

        override suspend fun diff(path: String, staged: Boolean?): GitFileDiff? = null

        override suspend fun commits(skip: Int, maxCount: Int): GitPort.CommitsPage =
            GitPort.CommitsPage(emptyList<GitCommit>(), false)

        override fun workspacePath(): String? = "/ws"

        override suspend fun identity(): GitIdentity? = GitIdentity("Ernest", "ernest@example.test")

        override suspend fun commit(message: String, stagedOnly: Boolean): GitCommitResult =
            GitCommitResult("deadbeef1234567")

        override suspend fun push(): com.zcode.android.domain.model.GitPushResult? = null
    }

    private fun setContent(port: GitPort) {
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
    }

    private fun driveToPushConfirm() {
        rule.onNodeWithTag("git_commit_open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("commit_message_field").performTextInput("rig message")
        rule.onNodeWithTag("commit_and_push").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("commit_send").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("push_confirm").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.onAllNodesWithTag(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true)

    @Test
    fun `a refused push renders the refused reason - F-P41-A`() {
        val port = PushRefusedPort()
        setContent(port)
        driveToPushConfirm()
        rule.onNodeWithTag("push_confirm").assertIsDisplayed()
        rule.onNodeWithTag("push_confirm").performClick()
        rule.waitForIdle()
        assertTrue(
            "the refused push must render its reason on the notice plane (RED pre-fix: " +
                "the success-only branch had no else and the port mapped the refusal to null); " +
                "notices=$notices",
            notices.any { it.contains(pushRefusal) },
        )
    }
}
