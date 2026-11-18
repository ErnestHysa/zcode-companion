package com.zcode.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.TaskRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-P38-A (pass-38 P3): the "All" view enumerates EVERY workspace key ever synced
 * (endpoint-scoped observation) while the full-snapshot replace + the TTL/cap sweep
 * are KEY-scoped — a workspace whose key stops being served (renamed/moved/
 * spelling-drifted) left a GHOST SECTION with its last-synced rows presented as
 * live, across relay swaps, app restarts, and a FULL emulator reboot (the
 * `synthetic-ws-one` ghost vs the live `/synthetic/ws-one`; 7 ledger stamps). Its
 * rows were even OPENABLE into a live conversation.
 *
 * The pin (the row's named harness): replaceAll(keyA) then serve only keyB →
 * pruneWorkspacesExcept ages keyA's rows AND meta out while keyB stays intact.
 * The RED at the pre-fix commit is compile-shape (the prune seam did not exist —
 * the store had NO mechanism to age a dead key out).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class Pass0Phase3GhostKeyPruneTest {

    private lateinit var db: ZcodeDatabase
    private lateinit var tasks: TaskRepositoryImpl

    private val endpoint = EndpointId("ep-1")
    private val keyA = WorkspaceIdentityKey("synthetic-ws-one") // the ghost spelling
    private val keyB = WorkspaceIdentityKey("/synthetic/ws-one") // the live key

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ZcodeDatabase::class.java,
        ).allowMainThreadQueries().build()
        tasks = TaskRepositoryImpl(db)
    }

    @After
    fun tearDown() = db.close()

    private fun task(id: String, pinned: Boolean = false) = Task(
        taskId = id,
        title = "Task $id",
        status = TaskStatus.IDLE,
        workspace = keyA,
        createdAtMs = 1,
        updatedAtMs = System.currentTimeMillis(),
        pinned = pinned,
    )

    @Test
    fun `the ghost key's rows and section age out when the snapshot stops serving it`() = runTest {
        tasks.replaceAll(endpoint, keyA, listOf(task("g1"), task("g2", pinned = true)), etag = null, atMs = 1)
        tasks.replaceAll(endpoint, keyB, listOf(task("l1").copy(workspace = keyB)), etag = null, atMs = 2)
        assertEquals(3, tasks.observeAllTasks(endpoint).first().size)

        // The connect snapshot serves ONLY keyB now (the desktop renamed the key).
        val pruned = tasks.pruneWorkspacesExcept(endpoint, keepKeys = setOf(keyB.raw))
        assertEquals(2, pruned) // both ghost rows (pinned included — the key itself is dead)
        val remaining = tasks.observeAllTasks(endpoint).first()
        assertEquals(listOf("l1"), remaining.map { it.taskId })
        assertTrue(remaining.all { it.workspace.raw == keyB.raw })
        // The dead key's ETag meta goes with it.
        assertNull(tasks.cachedEtag(endpoint, keyA))
    }

    @Test
    fun `the kept keys survive untouched and the meta stays`() = runTest {
        tasks.replaceAll(endpoint, keyB, listOf(task("l1").copy(workspace = keyB)), etag = com.zcode.android.domain.model.SnapshotEtag("e1"), atMs = 1)
        tasks.replaceAll(endpoint, keyA, listOf(task("g1")), etag = null, atMs = 1)
        tasks.pruneWorkspacesExcept(endpoint, keepKeys = setOf(keyA.raw, keyB.raw))
        assertEquals(2, tasks.observeAllTasks(endpoint).first().size)
        assertEquals("e1", tasks.cachedEtag(endpoint, keyB)?.raw)
    }

    @Test
    fun `an empty keep set prunes nothing (the restarting-desktop guard owns the empty view)`() = runTest {
        tasks.replaceAll(endpoint, keyA, listOf(task("g1")), etag = null, atMs = 1)
        tasks.pruneWorkspacesExcept(endpoint, keepKeys = emptySet())
        assertEquals(1, tasks.observeAllTasks(endpoint).first().size)
    }
}
