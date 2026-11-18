package com.zcode.android.features.tasks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.tasks.ui.TaskListScreen
import com.zcode.android.features.tasks.port.TaskSearchHit
import com.zcode.android.features.tasks.port.TaskSearchResult
import com.zcode.android.features.tasks.port.TasksPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-P43-A (pass-43 P3): the tasks-SEARCH results pane SILENTLY TRUNCATED at the
 * port's limit:20 — the wire carried the desktop-faithful pagination answer
 * (`27 hits (limit=20 served=20 hasMore=true)`) but the port dropped total/hasMore
 * and the pane rendered the bare list with NO count and NO show-more affordance;
 * matches 21–27 were UNREACHABLE through the search UI while the desktop NEVER
 * silently truncates (useGlobalTaskList keeps {items,total,hasMore} and renders
 * taskList.showMore).
 *
 * The pinned shape (the row's Accept): a stubbed port 20 hits / total 27 /
 * hasMore true → the count face ("Showing 20 of 27 matches") + the affordance
 * render; tapping re-queries at the RAISED limit (the desktop's expanded shape —
 * everything) and renders the remainder. RED at the pre-fix commit is
 * compile-shape (the port dropped total/hasMore; the state had no fields).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class Pass0Phase3SearchPageTest {

    @get:Rule
    val rule = createComposeRule()

    private fun hits(count: Int) = (0 until count).map {
        TaskSearchHit("t$it", "Synthetic task $it", "snippet $it")
    }

    @Test
    fun `the count face and the show-more affordance render when the answer is truncated`() {
        val state = TasksUiState(
            phase = com.zcode.android.domain.state.ConnectionPhase.CONNECTED,
            loadState = TasksUiState.Load.READY,
            searchQuery = "synthetic",
            searchResults = hits(20),
            searchTotal = 27,
            searchHasMore = true,
        )
        var showMore = 0
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TaskListScreen(
                    state = state,
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    onSearch = {}, onSearchShowMore = { showMore++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Showing 20 of 27 matches").assertExists()
        rule.onNodeWithTag("tasks_search_count").assertExists()
        rule.onNodeWithTag("tasks_search_show_more").performScrollTo().performClick()
        assertEquals(1, showMore)
    }

    @Test
    fun `a complete answer renders no count face and no affordance`() {
        val state = TasksUiState(
            phase = com.zcode.android.domain.state.ConnectionPhase.CONNECTED,
            loadState = TasksUiState.Load.READY,
            searchQuery = "synthetic",
            searchResults = hits(27),
            searchTotal = 27,
            searchHasMore = false,
        )
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                TaskListScreen(
                    state = state,
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tasks_search_hit_t26").assertExists() // the remainder reachable
        rule.onNodeWithTag("tasks_search_count").assertDoesNotExist()
        rule.onNodeWithTag("tasks_search_show_more").assertDoesNotExist()
    }
}

/** The VM half: the raised-limit re-query (the desktop's expanded shape). */
@OptIn(ExperimentalCoroutinesApi::class)
class Pass0Phase3SearchPageVmTest {

    private class SearchPort : TasksPort {
        val limits = mutableListOf<Int>()
        override val tasks = MutableStateFlow(emptyList<com.zcode.android.domain.model.Task>())
        override val phase = MutableStateFlow(com.zcode.android.domain.state.ConnectionPhase.CONNECTED)
        override val taskLoadError = MutableStateFlow<com.zcode.android.core.common.error.AppError?>(null)
        override val lastRefreshAtMs = MutableStateFlow<Long?>(null)
        override val endpointName = MutableStateFlow<String?>("TestBook")
        override val suggestRepair = MutableStateFlow(false)
        override suspend fun refresh() = true
        override fun retryConnection() {}
        override fun repairPairing() {}
        override fun observeWorkspaces() = MutableStateFlow(emptyList<com.zcode.android.domain.model.WorkspaceOption>())
        override suspend fun createTask(workspaceKey: String, firstText: String, provider: String?, model: String?) = true

        override suspend fun searchTasks(query: String, limit: Int): TaskSearchResult {
            limits += limit
            val total = 27
            val served = minOf(limit, total)
            return TaskSearchResult(
                hits = (0 until served).map { TaskSearchHit("t$it", "Synthetic task $it", null) },
                total = total,
                hasMore = served < total,
            )
        }
    }

    @Test
    fun `search queries at the initial limit and show-more re-queries at the total`() = runTest {
        val port = SearchPort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.search("synthetic")
        runCurrent()
        assertEquals(listOf(20), port.limits)
        assertEquals(20, vm.state.value.searchResults.size)
        assertTrue(vm.state.value.searchHasMore)
        assertEquals(27, vm.state.value.searchTotal)

        vm.searchShowMore()
        runCurrent()
        assertEquals(listOf(20, 27), port.limits)
        assertEquals(27, vm.state.value.searchResults.size)
        assertEquals(false, vm.state.value.searchHasMore)
    }

    @Test
    fun `a blank query clears and show-more is a no-op without a query`() = runTest {
        val port = SearchPort()
        val vm = TasksViewModel(port, backgroundScope)
        vm.searchShowMore()
        runCurrent()
        assertTrue(port.limits.isEmpty())
        vm.search("")
        runCurrent()
        assertTrue(port.limits.isEmpty())
        assertTrue(vm.state.value.searchResults.isEmpty())
    }
}
