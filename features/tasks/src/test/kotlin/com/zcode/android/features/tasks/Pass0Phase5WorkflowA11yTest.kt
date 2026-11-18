package com.zcode.android.features.tasks

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assert
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.SavedWorkflow
import com.zcode.android.domain.model.SavedWorkflowArg
import com.zcode.android.domain.model.SavedWorkflowList
import com.zcode.android.features.tasks.port.AutomationsPort
import com.zcode.android.features.tasks.ui.WorkflowsHub
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * pass-0 Phase 5 — N-BL-1: the Workflows hub's per-row Run/Manage buttons were the
 * ONLY subtree-unlabeled clickables in the a11y sweep (pass-5 H.A2 — TalkBack
 * silence). The pinned contract: BOTH per-row buttons carry a NAME-BEARING
 * contentDescription ("Run workflow <name>" / "Manage workflow <name>") — a generic
 * label (or none) fails the pin, because per-row identity is what TalkBack must
 * announce on a multi-row hub.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase5WorkflowA11yTest {

    @get:Rule
    val rule = createComposeRule()

    private val wfName = "nightly-sync"

    private fun wf(name: String) = SavedWorkflow(
        name = name, description = "Does $name", whenToUse = null, scope = "project", path = "/ws/$name.ts",
        args = listOf(SavedWorkflowArg("target", "string", null, required = false, default = null)),
    )

    private fun port(list: SavedWorkflowList?) = object : AutomationsPort {
        override suspend fun lists() = AutomationsPort.Lists(emptyList<AutomationRow>(), emptyList(), null)
        override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite) = false
        override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite) = false
        override suspend fun delete(automationId: String) = false
        override suspend fun setEnabled(automationId: String, enabled: Boolean) = false
        override suspend fun runNow(automationId: String) = false
        override suspend fun savedWorkflows() = list
        override suspend fun savedWorkflowRuns(name: String?, limit: Int) = null
    }

    private fun setContent(list: SavedWorkflowList?) {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                WorkflowsHub(
                    port = port(list),
                    tasks = emptyList(),
                    onStartWorkflow = { _, _, _, _ -> },
                    connected = true,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the Run button carries the workflow's name as its content description`() {
        setContent(SavedWorkflowList(listOf(wf(wfName)), emptyList(), "/ws"))
        rule.onNodeWithTag("workflow_run_$wfName")
            .assert(hasContentDescription("Run workflow $wfName"))
    }

    @Test
    fun `the Manage button carries the workflow's name as its content description`() {
        setContent(SavedWorkflowList(listOf(wf(wfName)), emptyList(), "/ws"))
        rule.onNodeWithTag("workflow_manage_$wfName")
            .assert(hasContentDescription("Manage workflow $wfName"))
    }
}
