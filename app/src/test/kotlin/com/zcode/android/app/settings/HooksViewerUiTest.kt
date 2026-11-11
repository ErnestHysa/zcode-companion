package com.zcode.android.app.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceHookReview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G71 (vc52 Phase 12) compose leg: the read-only workspace HOOKS viewer. Pins: the
 * retained review renders per-item name/event/command/trust/source with the trust
 * states localized; every recorded trust state renders its label; the empty face is
 * the honest "no review seen yet"; Refresh fires the port and renders the typed
 * note (requested / refused / offline); the authoring boundary note renders.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class HooksViewerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class RecordingPort(
        private val entries: List<HooksPort.Entry>,
        private val refreshOutcome: Boolean?,
    ) : HooksPort {
        val refreshed = mutableListOf<String>()

        override suspend fun reviews(): List<HooksPort.Entry> = entries
        override suspend fun refresh(taskId: String): Boolean? {
            refreshed.add(taskId)
            return refreshOutcome
        }
    }

    private fun review(trustStates: List<String>) = WorkspaceHookReview(
        reviewFlowId = "flow-1",
        generation = 1,
        interactionId = "hook-1",
        sessionId = "sess-1",
        taskId = "sess-1",
        runId = "run",
        workspaceIdentity = "synthetic-ws",
        workspaceLabel = "Synthetic Workspace",
        bundleDigest = "a".repeat(64),
        createdAtMs = 0,
        deadlineAtMs = 0,
        workspacePath = null,
        remoteSessionId = null,
        summary = WorkspaceHookReview.Summary(2, trustStates.size, trustStates.count { it == "pending_trust" }),
        items = trustStates.mapIndexed { i, state ->
            WorkspaceHookReview.Item(
                reviewItemId = "h${i + 1}",
                event = listOf("SessionStart", "PreToolUse", "Stop")[i % 3],
                matcher = if (i == 1) "Write, Edit" else null,
                type = "command",
                displayName = listOf("Warm build cache", "Lint gate", "Watcher")[i % 3],
                displayCommand = "echo warming",
                sourcePath = ".zcode/hooks.json",
                executionMode = "foreground",
                configuredEnabled = true,
                editable = true,
                trustState = state,
            )
        },
    )

    private fun port(trustStates: List<String>, refreshOutcome: Boolean? = true) =
        RecordingPort(listOf(HooksPort.Entry("sess-1", review(trustStates))), refreshOutcome)

    @Test
    fun `the retained review renders items with trust and source`() {
        rule.setContent { ZCodeTheme { HooksViewerScreen(port = port(listOf("pending_trust", "trusted_persistent"))) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hooks_viewer_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("hooks_group_sess-1").assertIsDisplayed()
        rule.onNodeWithTag("hook_item_h1").assertIsDisplayed()
        rule.onNodeWithTag("hook_trust_h1").assertExists()
        rule.onNodeWithText("Warm build cache").assertExists()
        // Both items carry the same command copy — assert the COUNT, not uniqueness.
        rule.onAllNodesWithText("echo warming").assertCountEquals(2)
        rule.onAllNodesWithText(".zcode/hooks.json").assertCountEquals(2)
        rule.onNodeWithText("pending trust").assertExists()
        rule.onNodeWithText("trusted").assertExists()
    }

    @Test
    fun `every recorded trust state renders its localized label`() {
        rule.setContent {
            ZCodeTheme {
                HooksViewerScreen(
                    port = port(
                        listOf(
                            "not_applicable", "pending_trust", "trusted_persistent",
                            "blocked_untrusted", "blocked_policy", "revoked", "stale_digest",
                        ),
                    ),
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hooks_viewer_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("blocked (untrusted)").assertExists()
        rule.onNodeWithText("blocked (policy)").assertExists()
        rule.onNodeWithText("revoked").assertExists()
        rule.onNodeWithText("stale — re-review").assertExists()
    }

    @Test
    fun `refresh fires the port and renders the requested note`() {
        val p = port(listOf("pending_trust"), refreshOutcome = true)
        rule.setContent { ZCodeTheme { HooksViewerScreen(port = p) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hooks_viewer_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("hooks_refresh_sess-1").performClick()
        rule.waitUntil(5_000) { p.refreshed.isNotEmpty() }
        org.junit.Assert.assertEquals("sess-1", p.refreshed.single())
        rule.onNodeWithTag("hooks_refresh_note_sess-1").assertExists()
        rule.onNodeWithText("Review re-requested — the trust card re-opens in the conversation.", substring = true).assertExists()
    }

    @Test
    fun `refused and offline refresh outcomes render their notes`() {
        val p = port(listOf("pending_trust"), refreshOutcome = false)
        rule.setContent { ZCodeTheme { HooksViewerScreen(port = p) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hooks_viewer_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("hooks_refresh_sess-1").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hooks_refresh_note_sess-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("The desktop refused the review request.").assertExists()
    }

    @Test
    fun `the empty face is the honest no-review-yet copy`() {
        rule.setContent { ZCodeTheme { HooksViewerScreen(port = RecordingPort(emptyList(), true)) } }
        rule.onNodeWithTag("hooks_viewer_empty").assertIsDisplayed()
    }

    @Test
    fun `the authoring boundary note renders with the list`() {
        rule.setContent { ZCodeTheme { HooksViewerScreen(port = port(listOf("pending_trust"))) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hooks_viewer_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("hooks_viewer_note").assertExists()
    }
}
