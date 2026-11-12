package com.zcode.android.features.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.ui.TaskListScreen
import com.zcode.android.features.tasks.ui.keepAwakeHintVisibleFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G62 (vc52 Phase 13): the desktop's keep-awake setting is invisible to the phone —
 * the Mac may sleep mid-task and the offline/degraded state is indistinguishable
 * from a network fault. The honest slice: in the OFFLINE/DEGRADED state the task
 * list carries a hint ("Is your desktop asleep? …Keep awake while running…"); a
 * connected session never shows it (a connected desktop is not asleep).
 * Copy exists in BOTH shipped locales (en + zh-CN).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class OfflineKeepAwakeHintTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setState(phase: ConnectionPhase) {
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = TasksUiState(
                        phase = phase,
                        loadState = TasksUiState.Load.READY,
                        endpointName = "TestBook",
                        groups = emptyList(),
                    ),
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                )
            }
        }
    }

    @Test
    fun `degraded state renders the keep-awake hint`() {
        setState(ConnectionPhase.DEGRADED)
        rule.onNodeWithTag("tasks_keep_awake_hint").assertExists()
        rule.onNodeWithText("asleep", substring = true).assertExists()
    }

    @Test
    fun `failed state renders the keep-awake hint`() {
        setState(ConnectionPhase.FAILED)
        rule.onNodeWithTag("tasks_keep_awake_hint").assertExists()
    }

    @Test
    fun `reconnecting state renders the keep-awake hint`() {
        setState(ConnectionPhase.RECONNECTING)
        rule.onNodeWithTag("tasks_keep_awake_hint").assertExists()
    }

    @Test
    fun `connected state does not render the hint`() {
        setState(ConnectionPhase.CONNECTED)
        // 0 matches — the hint is offline/degraded-only.
        assertEquals(0, rule.onAllNodesWithTag("tasks_keep_awake_hint").fetchSemanticsNodes().size)
    }

    /** The pure gate the strip uses — pinned directly so the UI and the gate cannot drift. */
    @Test
    fun `visibility gate matches the stale phases exactly`() {
        assertTrue(keepAwakeHintVisibleFor(ConnectionPhase.DEGRADED))
        assertTrue(keepAwakeHintVisibleFor(ConnectionPhase.FAILED))
        assertTrue(keepAwakeHintVisibleFor(ConnectionPhase.RECONNECTING))
        assertFalse(keepAwakeHintVisibleFor(ConnectionPhase.CONNECTED))
        assertFalse(keepAwakeHintVisibleFor(ConnectionPhase.UNPAIRED))
        assertFalse(keepAwakeHintVisibleFor(ConnectionPhase.CONNECTING))
    }

    /** G62 copy pin: the hint string resolves non-blank in en AND zh-CN. */
    @Test
    fun `copy resolves in en and zh-CN`() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val en = context.createConfigurationContext(
            android.content.res.Configuration().apply { setLocale(java.util.Locale.ENGLISH) },
        ).getString(R.string.tasks_keep_awake_hint)
        val zh = context.createConfigurationContext(
            android.content.res.Configuration().apply {
                setLocale(java.util.Locale.forLanguageTag("zh-CN"))
            },
        ).getString(R.string.tasks_keep_awake_hint)
        assertTrue(en.contains("asleep"))
        assertTrue(en.contains("Keep awake"))
        assertTrue(zh.isNotBlank() && zh != en)
    }
}
