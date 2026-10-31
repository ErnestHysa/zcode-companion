package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.SessionProjectionState
import com.zcode.android.domain.model.WorkspaceHookReview
import com.zcode.android.features.conversation.ui.HookTrustReviewCard
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GAP-A1 / G1 (vc51 roadmap, sub-phase 1C): the hook-trust review card — ADR-024's
 * missing half. Pins the ADR-024 UX contract: per-item opt-in checkboxes DEFAULT OFF,
 * "Trust N selected hooks" disabled at N=0, the CONFIRM SHEET gating trust (firing
 * the exact checked ids + the persistent grant — the grant's only caller), the
 * stale_digest marker, and the revoke affordance for trusted items. Plus the uiState
 * selector that routes WORKSPACE_HOOK_REVIEW interactions into the pinned-card plane.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class HookTrustReviewCardTest {

    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun item(
        id: String,
        trustState: String = "pending_trust",
        editable: Boolean = false,
        enabled: Boolean = true,
    ) = WorkspaceHookReview.Item(
        reviewItemId = id,
        event = "pre_tool_use",
        matcher = "Bash",
        type = "command",
        displayName = "Hook $id",
        displayCommand = "./hooks/$id.sh",
        sourcePath = "/ws/.zcode/hooks.json",
        executionMode = "foreground",
        configuredEnabled = enabled,
        editable = editable,
        trustState = trustState,
    )

    private fun hook(vararg items: WorkspaceHookReview.Item, generation: Long = 1) = WorkspaceHookReview(
        reviewFlowId = "flow-1",
        generation = generation,
        interactionId = "hook-int-1",
        sessionId = "synth-task-1",
        taskId = "synth-task-1",
        runId = "run-1",
        workspaceIdentity = "/synthetic/ws-one",
        workspaceLabel = "Synthetic One",
        bundleDigest = "digest-abc",
        createdAtMs = 1_700_000_000_000,
        deadlineAtMs = 1_700_000_600_000,
        workspacePath = "/synthetic/ws-one",
        remoteSessionId = null,
        summary = WorkspaceHookReview.Summary(eventCount = 3, hookCount = items.size, pendingCount = items.count { it.actionable }),
        items = items.toList(),
    )

    private fun setContent(h: WorkspaceHookReview) {
        calls.clear()
        rule.setContent {
            ZCodeTheme {
                HookTrustReviewCard(
                    hook = h,
                    busy = false,
                    onTrustSelected = { ids -> calls += "trust:$ids" },
                    onRevoke = { ids -> calls += "revoke:$ids" },
                    onToggleItem = { id, enabled -> calls += "toggle:$id:$enabled" },
                    onGrantPersistent = { calls += "grant" },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `A1 - checkboxes default OFF and trust is disabled at zero selected`() {
        setContent(hook(item("a"), item("b")))
        rule.onNodeWithTag("hook_review_card_hook-int-1").assertIsDisplayed()
        rule.onNodeWithTag("hook_review_trust_selected").assertIsNotEnabled()
        // ADR-024: opting IN is explicit — checking one item enables trust for exactly N=1.
        rule.onNodeWithTag("hook_review_check_a").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("hook_review_trust_selected").assertIsEnabled()
        rule.onNodeWithText("Trust 1 selected hook(s)").assertIsDisplayed()
    }

    @Test
    fun `A1 - the confirm sheet fires the exact checked ids plus the persistent grant`() {
        setContent(hook(item("a"), item("b")))
        rule.onNodeWithTag("hook_review_check_a").performClick()
        rule.onNodeWithTag("hook_review_check_b").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("hook_review_trust_selected").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("hook_review_confirm_yes").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertTrue("trust must ride the confirm sheet", "trust:[a, b]" in calls)
        assertTrue(
            "the confirmed path is the persistent grant's ONLY caller",
            "grant" in calls,
        )
        assertEquals(listOf("trust:[a, b]", "grant"), calls)
    }

    @Test
    fun `A1 - dismissing the confirm sheet fires nothing`() {
        setContent(hook(item("a")))
        rule.onNodeWithTag("hook_review_check_a").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("hook_review_trust_selected").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("hook_review_confirm_no").performClick()
        rule.waitForIdle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `A1 - stale_digest items carry the stale marker`() {
        setContent(hook(item("s", trustState = "stale_digest"), item("t")))
        rule.onNodeWithTag("hook_review_stale_s").assertIsDisplayed()
        rule.onAllNodesWithTag("hook_review_stale_t").assertCountEquals(0)
    }

    @Test
    fun `A1 - trusted items expose revoke behind a confirm sheet`() {
        setContent(hook(item("trusted", trustState = "trusted_persistent")))
        rule.onNodeWithTag("hook_review_revoke_trusted").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("hook_review_revoke_yes").performClick()
        rule.waitForIdle()
        assertEquals(listOf("revoke:[trusted]"), calls)
    }

    @Test
    fun `A1 - editable items expose the enable-disable toggle`() {
        setContent(hook(item("ed", editable = true, enabled = true)))
        rule.onNodeWithTag("hook_review_toggle_ed").performClick()
        rule.waitForIdle()
        assertEquals(listOf("toggle:ed:false"), calls)
    }

    @Test
    fun `A1 - the uiState selector routes workspaceHookReview into the pinned-card plane`() {
        val payload = buildJsonObject {
            put("interactionId", "hook-int-9")
            put("kind", "workspaceHookReview")
            put("anchorRowId", JsonNull)
            put("createdAt", 1_700_000_000_000.0)
            putJsonObject("payload") {
                put("kind", "workspaceHookReview")
                put("reviewFlowId", "flow-9")
                put("generation", 1)
                put("interactionId", "hook-int-9")
                put("sessionId", "synth-task-1")
                put("taskId", "synth-task-1")
                put("runId", "run-1")
                put("workspaceIdentity", "/synthetic/ws-one")
                put("workspaceLabel", "Synthetic One")
                put("bundleDigest", "digest-abc")
                put("createdAt", 1_700_000_000_000.0)
                put("deadlineAt", 1_700_000_600_000.0)
                putJsonObject("summary") { put("eventCount", 1); put("hookCount", 1); put("pendingCount", 1) }
                putJsonArray("items") {
                    add(
                        buildJsonObject {
                            put("reviewItemId", "a")
                            put("event", "pre_tool_use")
                            put("type", "command")
                            put("displayName", "Hook a")
                            put("displayCommand", "./hooks/a.sh")
                            put("sourcePath", "/ws/.zcode/hooks.json")
                            put("executionMode", "foreground")
                            put("configuredEnabled", true)
                            put("editable", false)
                            put("trustState", "pending_trust")
                        },
                    )
                }
            }
        }
        val state = SessionProjectionState.fromSnapshot(
            buildJsonObject {
                putJsonArray("pendingInteractions") { add(payload) }
            },
        )
        val ui = ConversationUiState(sessionState = state)
        assertEquals(1, ui.pendingHookReviews.size)
        assertEquals("hook-int-9", ui.pendingHookReviews.single().interactionId)
        assertEquals(1, ui.pendingHookReviews.single().hookReview?.items?.size)
    }
}
