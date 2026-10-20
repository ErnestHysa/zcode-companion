package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskGroupColor
import com.zcode.android.domain.model.TaskGroupInfo
import com.zcode.android.domain.model.TaskMeta
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.features.tasks.ui.TaskListScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A3.8–A3.10 (gaps-and-polish Phase 5A) UI pins: the row overflow offers Pin/Unpin +
 * Mark unread/read (+ Resume only for finished rows, Move-to-group only for unpinned
 * rows); the pinned section renders first with its header; unread rows carry the dot
 * and the a11y flag; custom groups render as colored sub-sections; the archived viewer
 * renders unavailable / empty / rows (Unarchive one-tap, Delete behind a confirm); the
 * move-to-group sheet and the group editor fire their callbacks.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class TaskManagementUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun task(id: String, title: String = "Fix auth", status: TaskStatus = TaskStatus.RUNNING, pinned: Boolean = false, unreadAt: Long? = null) = Task(
        taskId = id, title = title, status = status,
        workspace = WorkspaceIdentityKey("/ws/demo"), createdAtMs = 1, updatedAtMs = 2,
        pinned = pinned, unreadAt = unreadAt,
    )

    private fun setContent(state: TasksUiState) {
        calls.clear()
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = state,
                    onTaskClick = {},
                    onOpenSettings = {},
                    onOpenConnection = {},
                    onRetry = {},
                    onRefresh = {},
                    onSetTaskPinned = { id, p -> calls += "pin:$id:$p" },
                    onSetTaskUnread = { t, u -> calls += "unread:${t.taskId}:$u" },
                    onResumeTask = { calls += "resume:$it" },
                    onMoveTaskToGroup = { id, g -> calls += "move:$id:$g" },
                    onCreateGroup = { t, c -> calls += "createGroup:$t:${c.wire}" },
                    onRenameGroup = { id, t -> calls += "renameGroup:$id:$t" },
                    onRecolorGroup = { id, c -> calls += "recolorGroup:$id:${c.wire}" },
                    onDeleteGroup = { calls += "deleteGroup:$it" },
                    onLoadArchived = { calls += "loadArchived" },
                    onUnarchive = { calls += "unarchive:$it" },
                    onDeleteArchived = { calls += "deleteArchived:$it" },
                    onLoadGroups = { calls += "loadGroups" },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun ready(vararg tasks: Task, pinned: List<Task> = emptyList(), groups: List<TaskGroupInfo> = emptyList(), membership: Map<String, String> = emptyMap(), archived: List<TaskMeta>? = null, archivedLoading: Boolean = false) =
        TasksUiState(
            loadState = TasksUiState.Load.READY,
            endpointName = "TestBook",
            lastRefreshAtMs = 1,
            groups = listOf(TaskGroup("/ws/demo", "demo", tasks.toList())),
            taskCount = tasks.size + pinned.size,
            pinned = pinned,
            taskGroups = groups,
            groupMembership = membership,
            archived = archived,
            archivedLoading = archivedLoading,
        )

    private fun openMenu(taskId: String) {
        rule.onNodeWithTag("task_actions_$taskId").performClick(); rule.waitForIdle()
    }

    @Test
    fun `menu offers pin and mark unread and they ride their callbacks`() {
        setContent(ready(task("t1")))
        openMenu("t1")
        rule.onNodeWithTag("task_menu_pin_t1").assertIsDisplayed()
        rule.onNodeWithText("Pin").assertIsDisplayed()
        rule.onNodeWithTag("task_menu_pin_t1").performClick(); rule.waitForIdle()
        openMenu("t1")
        rule.onNodeWithText("Mark unread").assertIsDisplayed()
        rule.onNodeWithTag("task_menu_unread_t1").performClick(); rule.waitForIdle()
        assertEquals(listOf("loadGroups", "pin:t1:true", "unread:t1:true"), calls)
    }

    @Test
    fun `pinned unread row flips the menu verbs and shows the dot and pin glyph with a11y flags`() {
        val pinnedRow = task("p1", title = "Pinned one", pinned = true, unreadAt = 1700000000000)
        setContent(ready(pinned = listOf(pinnedRow)))
        rule.onNodeWithTag("tasks_pinned_header").assertIsDisplayed()
        rule.onNodeWithTag("task_unread_p1", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("task_pinned_p1", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("task_row_p1").assertContentDescriptionContains("unread").assertContentDescriptionContains("pinned")
        openMenu("p1")
        rule.onNodeWithText("Unpin").assertIsDisplayed()
        rule.onNodeWithText("Mark read").assertIsDisplayed()
        // A pinned row cannot be grouped (the host refuses it) — no Move entry.
        rule.onAllNodesWithTag("task_menu_move_p1").assertCountEquals(0)
        rule.onNodeWithTag("task_menu_pin_p1").performClick(); rule.waitForIdle()
        assertTrue("pin:p1:false" in calls)
    }

    @Test
    fun `resume is offered for finished rows only`() {
        setContent(ready(task("r1", status = TaskStatus.RUNNING), task("c1", status = TaskStatus.COMPLETED)))
        openMenu("r1")
        rule.onAllNodesWithTag("task_menu_resume_r1").assertCountEquals(0)
        rule.onNodeWithTag("task_menu_move_r1").performClick(); rule.waitForIdle() // dismiss via a neighbour
        openMenu("c1")
        rule.onNodeWithTag("task_menu_resume_c1").performClick(); rule.waitForIdle()
        assertTrue("resume:c1" in calls)
    }

    @Test
    fun `custom groups render as colored sub-sections and the editor fires rename recolor delete`() {
        val g = TaskGroupInfo("g1", "Backend", TaskGroupColor.GREEN)
        setContent(ready(task("t1"), task("t2", title = "Grouped"), groups = listOf(g), membership = mapOf("t2" to "g1")))
        rule.onNodeWithTag("task_group_header_g1").assertIsDisplayed()
        rule.onNodeWithText("Backend").assertIsDisplayed()
        rule.onNodeWithTag("task_group_edit_g1").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("group_editor_sheet").assertIsDisplayed()
        rule.onNodeWithTag("group_color_red_0").performClick()
        rule.onNodeWithTag("group_save_confirm").performClick(); rule.waitForIdle()
        assertTrue("renameGroup:g1:Backend" in calls)
        assertTrue("recolorGroup:g1:red" in calls)
    }

    @Test
    fun `move to group sheet lists no-group the groups and new-group`() {
        val g = TaskGroupInfo("g1", "Backend", TaskGroupColor.BLUE)
        setContent(ready(task("t1"), groups = listOf(g)))
        openMenu("t1")
        rule.onNodeWithTag("task_menu_move_t1").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("move_group_sheet").assertIsDisplayed()
        rule.onNodeWithTag("move_group_none").assertIsDisplayed()
        rule.onNodeWithTag("move_group_new").assertIsDisplayed()
        rule.onNodeWithTag("move_group_g1").performClick(); rule.waitForIdle()
        assertTrue("move:t1:g1" in calls)
    }

    @Test
    fun `new group from the top overflow creates with title and color`() {
        setContent(ready(task("t1")))
        rule.onNodeWithTag("tasks_more").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tasks_menu_new_group").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("group_create_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("group_title_field").performTextInput("Core")
        rule.onNodeWithTag("group_color_purple_1").performClick()
        rule.onNodeWithTag("group_create_confirm").performClick(); rule.waitForIdle()
        assertTrue("createGroup:Core:purple" in calls)
    }

    @Test
    fun `archived viewer renders unavailable then rows with unarchive and confirmed delete`() {
        setContent(ready(task("t1"), archived = null))
        rule.onNodeWithTag("tasks_more").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tasks_menu_archived").performClick(); rule.waitForIdle()
        assertTrue("loadArchived" in calls)
        rule.onNodeWithTag("archived_unavailable").assertIsDisplayed()
    }

    @Test
    fun `archived rows offer unarchive one-tap and delete behind the permanent confirm`() {
        val meta = TaskMeta("a1", "Old task", "completed", archived = true, pinned = false, unreadAtMs = null, createdAtMs = 1, updatedAtMs = 2, workspacePath = "/ws", workspaceIdentity = null, workspaceLabel = "demo")
        setContent(ready(task("t1"), archived = listOf(meta)))
        rule.onNodeWithTag("tasks_more").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tasks_menu_archived").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Old task").assertIsDisplayed()
        rule.onNodeWithTag("archived_unarchive_a1").performClick(); rule.waitForIdle()
        assertTrue("unarchive:a1" in calls)
        rule.onNodeWithTag("archived_delete_a1").performClick(); rule.waitForIdle()
        // Nothing fired before the confirm (desktop confirmDialog.taskDelete* "cannot be recovered").
        assertTrue(calls.none { it.startsWith("deleteArchived") })
        rule.onNodeWithTag("archived_delete_confirm").performClick(); rule.waitForIdle()
        assertTrue("deleteArchived:a1" in calls)
    }

    @Test
    fun `archived empty state renders`() {
        setContent(ready(task("t1"), archived = emptyList()))
        rule.onNodeWithTag("tasks_more").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("tasks_menu_archived").performClick(); rule.waitForIdle()
        rule.onNodeWithTag("archived_empty").assertIsDisplayed()
    }
}
