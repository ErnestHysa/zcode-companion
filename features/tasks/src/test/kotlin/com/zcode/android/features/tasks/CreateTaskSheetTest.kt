package com.zcode.android.features.tasks

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * B1 (gaps-and-polish Phase 3A): "create-task stops stacking dialogs". The composer
 * chip used to open the model picker ON TOP of the `CreateTaskDialog` AlertDialog —
 * two overlays, double scrim, the live "grey skeleton" (E2/F3). Create-task is now ONE
 * bottom sheet with two pages: the form, and the shared picker body rendered INLINE
 * when the model chip is tapped. Nothing stacks.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class CreateTaskSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private val workspaces = listOf(WorkspaceOption("/ws/a", "Alpha", "/ws/a"))

    private fun setSheet(onCreate: (String, String, String?, String?) -> Unit = { _, _, _, _ -> }) {
        rule.setContent {
            ZCodeTheme {
                CreateTaskSheet(
                    workspaces = workspaces,
                    creating = false,
                    onDismiss = {},
                    onCreate = onCreate,
                    modelPicker = { _, onPick, onBack ->
                        // A stand-in for the shared ModelPickerContent body.
                        Text("picker page", modifier = Modifier.testTag("fake_picker_page"))
                        TextButton(onClick = { onPick("zai", "glm-5.1") }, modifier = Modifier.testTag("fake_pick")) { Text("pick") }
                        TextButton(onClick = onBack, modifier = Modifier.testTag("fake_back")) { Text("back") }
                    },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `tapping the model chip swaps the sheet page - the picker renders INLINE, the form leaves`() {
        setSheet()
        rule.onNodeWithTag("create_task_confirm").assertIsDisplayed()
        rule.onNodeWithTag("create_model_chip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("fake_picker_page").assertIsDisplayed()
        // Same sheet, second page: the form's confirm button is NOT also on screen.
        assertTrue(
            "the form must not stay stacked under the picker",
            rule.onAllNodesWithTag("create_task_confirm").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `picking a model returns to the form with the chip updated`() {
        var created: List<String?>? = null
        setSheet { k, t, p, m -> created = listOf(k, t, p, m) }
        rule.onNodeWithTag("create_task_text").performTextInput("do the thing")
        rule.onNodeWithTag("create_model_chip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("fake_pick").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("zai/glm-5.1").assertIsDisplayed()
        rule.onNodeWithTag("create_task_confirm").performClick()
        rule.waitForIdle()
        assertEquals(listOf("/ws/a", "do the thing", "zai", "glm-5.1"), created)
    }

    @Test
    fun `backing out of the picker page returns to the form unchanged`() {
        setSheet()
        rule.onNodeWithTag("create_model_chip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("fake_back").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("create_task_confirm").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("fake_picker_page").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `create-task is a sheet - no AlertDialog in the create flow and the app injects the picker BODY`() {
        val root = repoRoot()
        val tasks = File(root, "features/tasks/src/main/kotlin/com/zcode/android/features/tasks/ui/TaskListScreen.kt").readText()
        val create = tasks.substringAfter("fun CreateTaskSheet(").substringBefore("private fun ApprovalsBellAction(")
        assertTrue("create-task must be a ZCodeModalBottomSheet", "ZCodeModalBottomSheet(" in create)
        assertFalse("create-task must not host an AlertDialog", "AlertDialog(" in create)
        val app = File(root, "app/src/main/kotlin/com/zcode/android/app/nav/ZCodeApp.kt").readText()
        assertTrue("the app injects ModelPickerContent (the body), not a second sheet", "ModelPickerContent(" in app)
        assertFalse("the composer sheet must not be stacked inside create-task", "ComposerModelPickerDialog(" in app)
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir)
    }
}
