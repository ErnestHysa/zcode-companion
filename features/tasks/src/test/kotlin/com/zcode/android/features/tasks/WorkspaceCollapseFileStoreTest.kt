package com.zcode.android.features.tasks

import com.zcode.android.features.tasks.ui.WorkspaceCollapseFileStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GAP-14: the disk-backed collapse store round-trips workspace keys and degrades
 * honestly (missing/corrupt file → empty set, never a crash).
 */
class WorkspaceCollapseFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveThenLoad_roundTripsKeys() {
        val file = File(tmp.root, "collapse.txt")
        WorkspaceCollapseFileStore(file).save(setOf("/ws/alpha", "/ws/beta"))
        assertEquals(setOf("/ws/alpha", "/ws/beta"), WorkspaceCollapseFileStore(file).load())
    }

    @Test
    fun saveEmpty_clearsPersistedKeys() {
        val file = File(tmp.root, "collapse.txt")
        val store = WorkspaceCollapseFileStore(file)
        store.save(setOf("/ws/alpha"))
        store.save(emptySet())
        assertEquals(emptySet<String>(), store.load())
    }

    @Test
    fun missingFile_loadsEmpty() {
        assertEquals(emptySet<String>(), WorkspaceCollapseFileStore(File(tmp.root, "nope.txt")).load())
    }

    @Test
    fun corruptFile_loadsEmpty_withoutCrashing() {
        val file = File(tmp.root, "collapse.txt")
        file.writeText("definitely-not-a-valid-payload\u0000\u0007")
        val loaded = WorkspaceCollapseFileStore(file).load()
        assertTrue(loaded.isEmpty())
    }
}
