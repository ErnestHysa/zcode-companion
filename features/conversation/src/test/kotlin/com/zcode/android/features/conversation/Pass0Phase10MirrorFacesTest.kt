package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceHookReview
import com.zcode.android.features.conversation.ui.ArtifactPreviewCard
import com.zcode.android.features.conversation.ui.HookTrustReviewCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * pass-0 Phase 10 — the typed-face pins on planes the phone previously rendered
 * BLIND (G89: the hook review-reason enum; G81: the gen-ui desktopOnly face).
 *
 *  - G89: a `stale_digest` review item renders the DESKTOP's typed reason copy
 *    ("Hook configuration changed — review required" — the asar i18n
 *    `settings.hooks.review.reason.bundle_changed` EN value, 3.18.1
 *    `ec475e6e…`), not the bare "Stale" chip; a flow past its `deadlineAt` with
 *    pending items renders the typed "Review timed out" line
 *    (`interaction_timeout`). saveHooks authoring stays desktop-only (the dated
 *    no-action decision rides the ledger).
 *  - G81: an HTML artifact card (the gen-ui carrier kind — the desktop renders
 *    generated interactive UI in its plugin sandbox; the phone accepts zero
 *    files) carries the honest "Generated UI — open on your desktop" face.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase10MirrorFacesTest {

    @get:Rule
    val rule = createComposeRule()

    private fun item(
        id: String,
        trustState: String = "pending_trust",
    ) = WorkspaceHookReview.Item(
        reviewItemId = id,
        event = "PreToolUse",
        matcher = "Bash",
        type = "command",
        displayName = "Hook $id",
        displayCommand = "./hooks/$id.sh",
        sourcePath = "/ws/.zcode/hooks.json",
        executionMode = "foreground",
        configuredEnabled = true,
        editable = false,
        trustState = trustState,
    )

    private fun hook(
        vararg items: WorkspaceHookReview.Item,
        deadlineInMs: Long = 600_000,
        nowBaseMs: Long = 1_700_000_000_000,
    ) = WorkspaceHookReview(
        reviewFlowId = "flow-1",
        generation = 1,
        interactionId = "hook-int-1",
        sessionId = "synth-task-1",
        taskId = "synth-task-1",
        runId = "run-1",
        workspaceIdentity = "/synthetic/ws-one",
        workspaceLabel = "Synthetic One",
        bundleDigest = "digest-abc",
        createdAtMs = nowBaseMs,
        deadlineAtMs = nowBaseMs + deadlineInMs,
        workspacePath = "/synthetic/ws-one",
        remoteSessionId = null,
        summary = WorkspaceHookReview.Summary(
            eventCount = 1,
            hookCount = items.size,
            pendingCount = items.count { it.actionable },
        ),
        items = items.toList(),
    )

    private fun setContentCard(h: WorkspaceHookReview) {
        rule.setContent {
            ZCodeTheme {
                HookTrustReviewCard(
                    hook = h,
                    busy = false,
                    onTrustSelected = { _ -> },
                    onRevoke = {},
                    onToggleItem = { _, _ -> },
                    onGrantPersistent = {},
                )
            }
        }
    }

    @Test
    fun `G89 stale item renders the desktop's typed reason copy`() {
        setContentCard(hook(item("h1", trustState = "stale_digest")))
        rule.onNodeWithTag("hook_review_item_h1").assertExists()
        // The typed reason (asar settings.hooks.review.reason.bundle_changed, EN)
        // — the bare "Stale" chip alone is the G89 defect.
        rule.onAllNodesWithText("Hook configuration changed — review required")
            .assertCountEquals(1)
    }

    @Test
    fun `G89 flow past deadline with pending items renders the typed timeout line`() {
        setContentCard(hook(item("h1"), deadlineInMs = -1_000))
        // interaction_timeout: "Review timed out" (asar EN value), flow-level.
        rule.onNodeWithTag("hook_review_flow_reason").assertExists()
        rule.onAllNodesWithText("Review timed out").assertCountEquals(1)
    }

    @Test
    fun `G89 flow within deadline renders no timeout line`() {
        setContentCard(hook(item("h1"), deadlineInMs = 600_000))
        rule.onAllNodesWithTag("hook_review_flow_reason").assertCountEquals(0)
    }

    @Test
    fun `G89 pending item within deadline stays silent - no invented reason`() {
        setContentCard(hook(item("h1")))
        rule.onAllNodesWithText("Review timed out").assertCountEquals(0)
        rule.onAllNodesWithText("Hook configuration changed — review required")
            .assertCountEquals(0)
    }

    @Test
    fun `G81 the HTML artifact card carries the gen-ui desktopOnly face`() {
        rule.setContent {
            ZCodeTheme {
                ArtifactPreviewCard(
                    rowId = 41,
                    name = "gen-ui-panel.html",
                    kind = GeneratedArtifactPreview.Kind.HTML,
                    ref = "/ws/.zcode/gen-ui/gen-ui-panel.html",
                    mime = "text/html",
                )
            }
        }
        rule.onNodeWithTag("preview_card_41").assertExists()
        rule.onAllNodesWithText("Generated UI — open on your desktop", substring = true)
            .assertCountEquals(1)
    }
}
