package com.zcode.android.app.nav

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.data.proto.AppPreferencesProto
import com.zcode.android.data.store.AppPreferencesSerializer
import com.zcode.android.data.store.PreferencesRepositoryImpl
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.AppPreferences
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.model.WorkspaceOption
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.TaskGroup
import com.zcode.android.features.tasks.TasksUiState
import com.zcode.android.features.tasks.ui.TaskListScreen
import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-P40-B (pass-40 P2, pass-41 revision: rename-only-dead) — the workspace-manager
 * RENAME-ALIAS write never reached `preferences.pb` (×4 across two processes: the pb
 * mtime frozen through Save, field 17 ABSENT, zero UI echo) while the SELECT (field
 * 18) and REORDER (field 16) writes are receipted ALIVE through the SAME shape.
 *
 * The pinned mechanism class: TasksRoute wires the three writes on
 * `rememberCoroutineScope()` (ZCodeApp.kt:677 → :741-755) — a COMPOSITION-OWNED scope.
 * A write launched there dies SILENTLY the moment the composition leaves (dialog
 * churn, the ANR-thrash frame wedges of AR.C) — no exception, frozen mtime, absent
 * field: the exact receipt. The pin drives the VERBATIM wiring (the composition-scope
 * host below) and disposes the composition between the Save click and the awaited
 * read: the alias must SURVIVE the composition's death.
 *
 * The pre-fix RED demonstration: with the wiring on the composition scope the
 * disposal test loses the alias (field 17 ABSENT after the disposal). The fix moves
 * the three writes onto the process-lifetime stores scope (the roadmap's fix shape:
 * "move the three ZCodeApp.kt:742-755 writes off the composition-captured scope").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class Pass0Phase3WorkspaceRenameTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var repo: PreferencesRepositoryImpl

    private val workspaces = listOf(
        WorkspaceOption("/synthetic/ws-one", "Synthetic One", "/synthetic/ws-one"),
        WorkspaceOption("/synthetic/ws-two", "Synthetic Two", "/synthetic/ws-two"),
    )

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "p40b-${System.nanoTime()}")
        dir.mkdirs()
        file = File(dir, "preferences.pb")
        repo = PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { file }),
        )
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun task(id: String) = Task(
        taskId = id,
        title = "Task $id",
        status = TaskStatus.IDLE,
        workspace = WorkspaceIdentityKey("/synthetic/ws-one"),
        createdAtMs = 1,
        updatedAtMs = System.currentTimeMillis(),
    )

    /**
     * TasksRoute's workspace-manager wiring VERBATIM (ZCodeApp.kt:673-757): the prefs
     * mirror observed live, the callbacks launched on `rememberCoroutineScope()` — the
     * composition-owned scope the pre-fix code used. Every body is a copy of the
     * production lambda (the pin anchors ZCodeApp.kt:741-755).
     */
    @androidx.compose.runtime.Composable
    private fun CompositionScopedWorkspaceHost() {
        val prefs by repo.observe().collectAsState(AppPreferences())
        val startScope = rememberCoroutineScope()
        TaskListScreen(
            state = TasksUiState(
                phase = ConnectionPhase.CONNECTED,
                loadState = TasksUiState.Load.READY,
                groups = listOf(
                    TaskGroup("/synthetic/ws-one", "Synthetic One", listOf(task("t1"))),
                ),
                taskCount = 1,
            ),
            onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
            workspaces = workspaces,
            selectedWorkspaceKeyExternal = prefs.selectedWorkspaceKey,
            onWorkspaceSelected = { key ->
                startScope.launch { repo.update { p -> p.copy(selectedWorkspaceKey = key) } }
            },
            onWorkspacesReordered = { keys ->
                startScope.launch { repo.update { p -> p.copy(workspaceOrder = keys) } }
            },
            onWorkspaceRenamed = { key, alias ->
                startScope.launch {
                    repo.update { p ->
                        val next = p.workspaceAliases.toMutableMap()
                        if (alias == null) next.remove(key) else next[key] = alias
                        p.copy(workspaceAliases = next)
                    }
                }
            },
            workspaceOrder = prefs.workspaceOrder,
            workspaceAliases = prefs.workspaceAliases,
        )
    }

    private fun setHost() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                CompositionScopedWorkspaceHost()
            }
        }
        rule.waitForIdle()
    }

    /** The full user flow: Manage chip → sheet → Rename on ws-one → type → Save. */
    private fun driveRename(alias: String) {
        rule.onNodeWithTag("tasks_ws_manage").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workspace_manager_rename_${"/synthetic/ws-one".hashCode()}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workspace_manager_rename_field").performTextReplacement(alias)
        rule.onNodeWithTag("workspace_manager_rename_save").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `rename lands on preferences pb field 17 and the chip echoes the alias while the composition lives`() {
        setHost()
        driveRename("RigAlias")
        // Await the async write through the repository (the composition never left).
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.current().workspaceAliases["/synthetic/ws-one"] == "RigAlias" }
        }
        val parsed = AppPreferencesProto.parseFrom(file.readBytes())
        assertEquals("RigAlias", parsed.workspaceAliasesMap["/synthetic/ws-one"])
        // The recomposition echo: the chip row renders the alias over the wire label.
        rule.onNodeWithText("RigAlias").assertExists()
    }

    @Test
    fun `the alias survives the composition's death between the click and the write`() {
        setHost()
        driveRename("RigAlias")
        // THE DISPOSAL: the composition leaves right after the Save click (the
        // dialog churn / frame-wedge window the rig receipted). Pre-fix the write
        // died with the composition-owned scope — silently.
        rule.setContent { }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.current().workspaceAliases["/synthetic/ws-one"] == "RigAlias" }
        }
        val parsed = AppPreferencesProto.parseFrom(file.readBytes())
        assertTrue(
            "field 17 must carry the alias after the disposal",
            parsed.workspaceAliasesMap["/synthetic/ws-one"] == "RigAlias",
        )
    }

    @Test
    fun `clearing the field removes the alias and the chip falls back to the wire label`() {
        setHost()
        driveRename("RigAlias")
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.current().workspaceAliases["/synthetic/ws-one"] == "RigAlias" }
        }
        // The clear path: empty field → null alias → the map entry removed.
        rule.onNodeWithTag("tasks_ws_manage").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workspace_manager_rename_${"/synthetic/ws-one".hashCode()}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("workspace_manager_rename_field").performTextReplacement("")
        rule.onNodeWithTag("workspace_manager_rename_save").performClick()
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.current().workspaceAliases["/synthetic/ws-one"] == null }
        }
        val parsed = AppPreferencesProto.parseFrom(file.readBytes())
        assertTrue(
            "field 17 must not carry the cleared alias",
            parsed.workspaceAliasesMap["/synthetic/ws-one"] == null,
        )
        // The wire label falls back.
        rule.onNodeWithText("Synthetic One").assertExists()
    }

    @Test
    fun `the alias round-trips a fresh repository instance over the same file (the restart arbiter)`() {
        setHost()
        driveRename("RigAlias")
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.current().workspaceAliases["/synthetic/ws-one"] == "RigAlias" }
        }
        // A cold process reads the same file: the alias is there (the Accept's
        // "survives an app restart" — the file IS the restart arbiter).
        val coldRepo = PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { file }),
        )
        assertEquals("RigAlias", runBlocking { coldRepo.current().workspaceAliases["/synthetic/ws-one"] })
    }
}
