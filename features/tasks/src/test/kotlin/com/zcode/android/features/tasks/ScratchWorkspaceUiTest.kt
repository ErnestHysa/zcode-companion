package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceOption
import com.zcode.android.features.tasks.ui.CreateTaskSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * C7 (gaps-and-polish Phase 4C): the create-task sheet offers the desktop's SCRATCH
 * workspace — `createScratchWorkspace` on the shipped facade (`{name}` → `{path}`);
 * the phone could never start one. Selecting "New scratch workspace" reveals a name
 * field; creating flows the returned path through the SAME create callback (the
 * caller creates the workspace then the task against it).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ScratchWorkspaceUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val created = mutableListOf<Triple<String, String, Pair<String, String>?>>()
    private var scratchPath: String? = "/synthetic/scratch-demo"

    private val workspaces = listOf(WorkspaceOption("/synthetic/ws", "Synthetic WS", "/synthetic/ws"))

    private fun setContent() {
        created.clear()
        rule.setContent {
            ZCodeTheme {
                CreateTaskSheet(
                    workspaces = workspaces,
                    creating = false,
                    onDismiss = {},
                    onCreate = { key, text, provider, model ->
                        val path = scratchPath
                        if (path != null) created += Triple(key, text, provider?.let { it to (model ?: "") })
                    },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the workspace menu offers New scratch workspace and reveals a name field`() {
        setContent()
        rule.onNodeWithTag("create_workspace_picker").performClick(); rule.waitForIdle()
        rule.onNodeWithText("New scratch workspace").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("create_scratch_name").assertIsDisplayed()
    }

    @Test
    fun `creating with scratch selected passes the scratch name and text to the create callback`() {
        setContent()
        rule.onNodeWithTag("create_workspace_picker").performClick(); rule.waitForIdle()
        rule.onNodeWithText("New scratch workspace").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("create_scratch_name").performTextInput("demo")
        rule.onNodeWithTag("create_task_text").performTextInput("Say hello")
        rule.onNodeWithTag("create_task_confirm").performClick(); rule.waitForIdle()
        assertEquals(1, created.size)
        assertEquals("the scratch sentinel name rides the create callback", "scratch:demo", created.first().first)
        assertEquals("Say hello", created.first().second)
    }

    @Test
    fun `an unnamed scratch is refused`() {
        setContent()
        rule.onNodeWithTag("create_workspace_picker").performClick(); rule.waitForIdle()
        rule.onNodeWithText("New scratch workspace").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("create_task_text").performTextInput("Say hello")
        rule.onNodeWithTag("create_task_confirm").performClick(); rule.waitForIdle()
        assertTrue(created.isEmpty(), "no scratch name → nothing created")
    }

    @Test
    fun `the regular workspace flow is unchanged`() {
        setContent()
        rule.onNodeWithTag("create_task_text").performTextInput("Regular task")
        rule.onNodeWithTag("create_task_confirm").performClick(); rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag("create_scratch_name").fetchSemanticsNodes().size)
        assertEquals(1, created.size)
        assertEquals("/synthetic/ws", created.first().first)
    }
}
