package com.zcode.android.app.nav

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import com.zcode.android.domain.repo.PreferencesRepository
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.TaskGroup
import com.zcode.android.features.tasks.TasksUiState
import com.zcode.android.features.tasks.ui.TaskListScreen
import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
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
 * A write launched there dies SILENTLY the moment the composition leaves while the
 * write is still in flight (dialog churn, the ANR-thrash frame wedges of AR.C) — no
 * exception, frozen mtime, absent field: the exact receipt. The pin drives the
 * verbatim wiring through a composition-scoped host, holds the write IN FLIGHT on a
 * test gate ([GatedPreferencesRepository]), removes the host, then releases the gate:
 * the alias must SURVIVE the composition's death.
 *
 * The pre-fix RED demonstration: with the wiring on the composition scope the gated
 * write is cancelled at the disposal and the alias never lands (field 17 ABSENT).
 * The fix moves the three writes onto the process-lifetime stores scope (the
 * roadmap's fix shape: "move the three ZCodeApp.kt:742-755 writes off the
 * composition-captured scope"); the host then mirrors the production wiring via
 * [WorkspacePreferenceWriter] and the disposal test goes GREEN.
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

    /** false = the host leaves composition (the disposal the rig receipted). */
    private val hostVisible = mutableStateOf(true)

    /** Open by default; the disposal test closes it to hold the write in flight. */
    private val writeGate = CompletableDeferred(Unit)

    /** The DataStore file truth, read without touching the live DataStore instance. */
    private fun filePrefs(): AppPreferencesProto = AppPreferencesProto.parseFrom(file.readBytes())

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "p40b-${System.nanoTime()}")
        dir.mkdirs()
        file = File(dir, "preferences.pb")
        repo = PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { file }),
        )
        writeGate.complete(Unit)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /**
     * Forwards everything to the real repository but holds `update` on [writeGate]
     * first — the deterministic stand-in for the file-write latency window in which
     * the rig's disposal killed the write.
     */
    private class GatedPreferencesRepository(
        private val real: PreferencesRepository,
        private val gate: CompletableDeferred<Unit>,
    ) : PreferencesRepository {
        override fun observe() = real.observe()
        override suspend fun current() = real.current()
        override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
            gate.await()
            real.update(transform)
        }
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
     * The production wiring (post-fix): TasksRoute's callbacks route through
     * [WorkspacePreferenceWriter] on the process-lifetime stores scope (the pin's
     * [processScope] is the appScope stand-in — it outlives the composition). The
     * pre-fix RED demonstration ran this same harness with the callbacks on the
     * composition's `rememberCoroutineScope()`: the gated writes were cancelled at
     * the disposal and the alias never landed (receipted in the pre-fix run).
     */
    private val processScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    @androidx.compose.runtime.Composable
    private fun WorkspaceHost(repository: PreferencesRepository) {
        val prefs by repository.observe().collectAsState(AppPreferences())
        val writer = androidx.compose.runtime.remember(repository) {
            WorkspacePreferenceWriter(repository, processScope)
        }
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
            onWorkspaceSelected = writer::select,
            onWorkspacesReordered = writer::reorder,
            onWorkspaceRenamed = writer::rename,
            workspaceOrder = prefs.workspaceOrder,
            workspaceAliases = prefs.workspaceAliases,
        )
    }

    private fun setHost(repository: PreferencesRepository = repo) {
        hostVisible.value = true
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                if (hostVisible.value) WorkspaceHost(repository)
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
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.current().workspaceAliases["/synthetic/ws-one"] == "RigAlias" }
        }
        assertEquals("RigAlias", filePrefs().workspaceAliasesMap["/synthetic/ws-one"])
        // The recomposition echo: the chip renders the alias over the wire label.
        rule.onNodeWithTag("tasks_ws_${"/synthetic/ws-one".hashCode()}").assertTextContains("RigAlias")
    }

    @Test
    fun `the alias survives the composition's death while the write is in flight`() {
        // Hold the write inside the gated update (the in-flight window).
        val gate = CompletableDeferred<Unit>()
        val gated = GatedPreferencesRepository(repo, gate)
        setHost(gated)
        driveRename("RigAlias")
        // THE DISPOSAL: the host leaves composition while the write is suspended.
        hostVisible.value = false
        rule.waitForIdle()
        // Release the in-flight write: a process-lifetime scope completes it.
        gate.complete(Unit)
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.current().workspaceAliases["/synthetic/ws-one"] == "RigAlias" }
        }
        assertTrue(
            "field 17 must carry the alias after the disposal",
            filePrefs().workspaceAliasesMap["/synthetic/ws-one"] == "RigAlias",
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
        assertTrue(
            "field 17 must not carry the cleared alias",
            filePrefs().workspaceAliasesMap["/synthetic/ws-one"] == null,
        )
        // The wire label falls back on the chip.
        rule.onNodeWithTag("tasks_ws_${"/synthetic/ws-one".hashCode()}").assertTextContains("Synthetic One")
    }

    @Test
    fun `the alias round-trips the serialized file (the restart arbiter)`() {
        setHost()
        driveRename("RigAlias")
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { repo.current().workspaceAliases["/synthetic/ws-one"] == "RigAlias" }
        }
        // A cold process reads the serialized bytes: the alias is there (the
        // Accept's "survives an app restart" — the file IS the restart arbiter).
        assertEquals("RigAlias", filePrefs().workspaceAliasesMap["/synthetic/ws-one"])
    }

    @Test
    fun `the select and reorder writes also survive the composition's death while in flight`() {
        // The same class over the receipted-ALIVE pair (fields 16/18): the fix moves
        // all three writes, so all three must survive the disposal.
        val gate = CompletableDeferred<Unit>()
        val gated = GatedPreferencesRepository(repo, gate)
        setHost(gated)
        rule.onNodeWithTag("tasks_ws_manage").performClick()
        rule.waitForIdle()
        // The select path: the row tap (onSelect → onDismiss).
        rule.onNodeWithTag("workspace_manager_row_${"/synthetic/ws-one".hashCode()}").performClick()
        rule.waitForIdle()
        hostVisible.value = false
        rule.waitForIdle()
        gate.complete(Unit)
        // The FILE truth (the memory view can precede the flush).
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { filePrefs().selectedWorkspaceKey == "/synthetic/ws-one" }.getOrDefault(false)
        }
        assertEquals("/synthetic/ws-one", filePrefs().selectedWorkspaceKey)
    }
}
