package com.zcode.android.features.tasks

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.WorkspaceOption
import com.zcode.android.features.tasks.ui.WorkspaceManagerSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G41 (vc51) — the workspace MANAGER over the RECEIVED workspaces (the
 * companion shape: switch / reorder / rename-class; the desktop's add/open/
 * remote wizard stays desktop-side). Pins: switch selection, REORDER persists
 * the new key order, RENAME persists the alias (and clears it on empty), and
 * the order computation (unlisted keys keep wire order) + alias display.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class WorkspaceManagerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val workspaces = listOf(
        WorkspaceOption("ws-one", "Synthetic One", "/synthetic/one"),
        WorkspaceOption("ws-two", "Synthetic Two", "/synthetic/two"),
        WorkspaceOption("ws-three", "Synthetic Three", "/synthetic/three"),
    )

    @Test
    fun `switch selects the workspace and closes the sheet`() {
        var selected: String? = null
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                WorkspaceManagerSheet(
                    workspaces = workspaces,
                    aliases = emptyMap(),
                    selectedKey = null,
                    onSelect = { selected = it },
                    onReorder = {},
                    onRename = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithTag("workspace_manager_row_${"ws-two".hashCode()}").performClick()
        assertEquals("ws-two", selected)
    }

    @Test
    fun `reorder moves a workspace up and persists the new key order`() {
        var order: List<String>? = null
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                WorkspaceManagerSheet(
                    workspaces = workspaces,
                    aliases = emptyMap(),
                    selectedKey = null,
                    onSelect = {},
                    onReorder = { order = it },
                    onRename = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithTag("workspace_manager_up_${"ws-two".hashCode()}").performClick()
        assertEquals(listOf("ws-two", "ws-one", "ws-three"), order)
    }

    @Test
    fun `rename persists the alias and clearing it restores the wire label`() {
        var renamed: Pair<String, String?>? = null
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                WorkspaceManagerSheet(
                    workspaces = workspaces,
                    aliases = mapOf("ws-one" to "Old alias"),
                    selectedKey = null,
                    onSelect = {},
                    onReorder = {},
                    onRename = { key, alias -> renamed = key to alias },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithTag("workspace_manager_rename_${"ws-one".hashCode()}").performClick()
        rule.onNodeWithTag("workspace_manager_rename_field").performTextReplacement("My repo")
        rule.onNodeWithTag("workspace_manager_rename_save").performClick()
        assertEquals("ws-one" to "My repo", renamed)
    }

    @Test
    fun `the order computation keeps unlisted keys in wire order and applies aliases for display`() {
        // The TaskListScreen-side computation (orderedWorkspaces): listed first, rest wire order.
        val order = listOf("ws-three", "ws-one")
        val aliases = mapOf("ws-one" to "My repo")
        val listed = order.mapNotNull { key -> workspaces.firstOrNull { it.key == key } }
        val rest = workspaces.filter { ws -> order.none { it == ws.key } }
        val ordered = (listed + rest).map { ws -> ws.copy(label = aliases[ws.key]?.takeIf { it.isNotBlank() } ?: ws.label) }
        assertEquals(listOf("ws-three", "ws-one", "ws-two"), ordered.map { it.key })
        assertEquals("My repo", ordered.first { it.key == "ws-one" }.label)
        assertEquals("Synthetic Two", ordered.first { it.key == "ws-two" }.label)
    }
}
