package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.data.repo.TaskRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * N-BD-5 (vc52 Phase 13): the task-row provider badge's STORE half — the
 * bootstrap-decoded `provider` rides the real Room projection (entity column +
 * both mappings). The REAL DAO, not a fake: the GAP-A2 lesson (recording fakes
 * cannot see column clobbering). Pins: provider round-trips through
 * replaceAll → observeAllTasks; a provider-less task stays null; a FULL-row
 * upsert that omits provider (the historical shape) clears the stale value —
 * the badge is bootstrap-projection truth, never a residue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TaskProviderBadgeStoreTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var repo: TaskRepositoryImpl

    private val endpoint = EndpointId("ep-1")
    private val workspace = WorkspaceIdentityKey("/ws/a")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = TaskRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun task(id: String, provider: String? = null) = Task(
        taskId = id,
        title = "Task $id",
        status = TaskStatus.RUNNING,
        workspace = workspace,
        createdAtMs = 1_000,
        updatedAtMs = 2_000,
        provider = provider,
    )

    /**
     * Room's flow emits the CURRENT snapshot on collect — awaiting PRESENCE can
     * return the stale pre-invalidation value. Await the CONDITION (bounded).
     */
    private suspend fun awaitRow(taskId: String, predicate: (Task) -> Boolean): Task =
        kotlinx.coroutines.withTimeout(5_000) {
            repo.observeAllTasks(endpoint)
                .first { rows -> rows.firstOrNull { it.taskId == taskId }?.let(predicate) == true }
                .first { it.taskId == taskId }
        }

    @Test
    fun `provider round-trips the real projection`() = runBlocking {
        repo.replaceAll(endpoint, workspace, listOf(task("t-1", provider = "zai-individual-coding-plan")), etag = null, atMs = 1L)
        assertEquals("zai-individual-coding-plan", awaitRow("t-1") { it.provider != null }.provider)
    }

    @Test
    fun `a provider-less task stays null (the historical shape)`() = runBlocking {
        repo.replaceAll(endpoint, workspace, listOf(task("t-2")), etag = null, atMs = 1L)
        assertNull("the historical shape stays provider-less", awaitRow("t-2") { it.provider == null }.provider)
    }

    @Test
    fun `a projection replace that omits provider CLEARS the stale badge (full-row truth)`() = runBlocking {
        repo.replaceAll(endpoint, workspace, listOf(task("t-3", provider = "old-provider")), etag = null, atMs = 1L)
        awaitRow("t-3") { it.provider != null } // the seeded emission lands first
        repo.replaceAll(endpoint, workspace, listOf(task("t-3")), etag = null, atMs = 2L)
        assertNull("the badge is projection truth, not residue", awaitRow("t-3") { it.provider == null }.provider)
    }
}
