package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.domain.model.WorkflowRunSummary
import com.zcode.android.features.conversation.ui.WorkflowRunSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G3 (vc51 roadmap Phase 2): workflow-run Resume + run-settings amend are MODELED, NEVER
 * SENT (`resumeWorkflowRun` / `amendWorkflowRunSettings` — desktop `workflowResumeGate.ts` +
 * `workflow-run-settings-command.ts`; the phone sheet renders the "Resumable" tag as
 * passive text and has NO actions). Pins: a resumable run's sheet offers Resume and the tap
 * dispatches the run; a non-resumable run offers none; the settings action opens the amend
 * sheet and dispatches the typed fields; cancel dispatches nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class WorkflowRunSheetActionsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun run(id: String, status: WorkflowRunStatus, resumable: Boolean) = WorkflowRunSummary(
        runId = id, label = id, status = status, statusRaw = status.wire.ifEmpty { "weird" },
        stopReason = if (resumable) "interrupted" else null, toolCallId = null, updatedAtMs = 1,
        resumedFrom = null, supersededBy = null, failureCode = null, failureMessage = null, resumable = resumable,
    )

    private fun setContent(
        resumable: Boolean,
        onResume: ((WorkflowRunSummary) -> Unit)? = {},
        onAmendSettings: ((WorkflowRunSummary, String?, Int?) -> Unit)? = { _, _, _ -> },
    ) {
        rule.setContent {
            ZCodeTheme {
                WorkflowRunSheet(
                    open = ConversationViewModel.OpenWorkflowRun(
                        run("r1", if (resumable) WorkflowRunStatus.STOPPED else WorkflowRunStatus.RUNNING, resumable),
                        emptyList(), hasMore = false, loading = false, unavailable = false,
                    ),
                    onDismiss = {},
                    onLoadMore = {},
                    onResume = onResume,
                    onAmendSettings = onAmendSettings,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `resumable run shows Resume and the tap dispatches the run`() {
        val resumed = mutableListOf<String>()
        setContent(resumable = true, onResume = { resumed += it.runId })
        rule.onNodeWithTag("workflow_sheet_resume").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(listOf("r1"), resumed)
    }

    @Test
    fun `non-resumable run offers no Resume action`() {
        setContent(resumable = false)
        rule.onAllNodesWithTag("workflow_sheet_resume").assertCountEquals(0)
    }

    @Test
    fun `settings action opens the amend sheet and dispatches the typed fields`() {
        val amended = mutableListOf<Triple<String, String?, Int?>>()
        setContent(resumable = false, onAmendSettings = { r, m, c -> amended += Triple(r.runId, m, c) })
        rule.onNodeWithTag("workflow_sheet_settings").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workflow_amend_sheet").assertIsDisplayed()
        rule.onNodeWithTag("workflow_amend_concurrency").performClick()
        rule.onNodeWithTag("workflow_amend_concurrency").performTextInput("3")
        rule.onNodeWithTag("workflow_amend_apply").performClick()
        rule.waitForIdle()
        assertEquals(listOf(Triple("r1", null, 3)), amended)
    }

    @Test
    fun `amend cancel dispatches nothing`() {
        val amended = mutableListOf<Triple<String, String?, Int?>>()
        setContent(resumable = false, onAmendSettings = { r, m, c -> amended += Triple(r.runId, m, c) })
        rule.onNodeWithTag("workflow_sheet_settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workflow_amend_cancel").performClick()
        rule.waitForIdle()
        assertEquals(emptyList<Triple<String, String?, Int?>>(), amended)
    }
}
