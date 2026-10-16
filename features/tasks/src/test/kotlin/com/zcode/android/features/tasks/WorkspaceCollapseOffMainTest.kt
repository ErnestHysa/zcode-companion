package com.zcode.android.features.tasks

import com.zcode.android.features.tasks.ui.WorkspaceCollapseState
import com.zcode.android.features.tasks.ui.WorkspaceCollapseStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B2.17 (gaps-and-polish Phase 1E, P0): workspace collapse did SYNC DISK I/O on
 * the UI thread per tap AND on the first frame (`WorkspaceCollapse.kt:26, :35`).
 * The load/save now route through a persist executor — the pins prove both
 * operations are DISPATCHED (never inline on the constructing/calling thread) and
 * that a threaded executor keeps them off main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkspaceCollapseOffMainTest {

    /** Records invocations; runs work only when drained. */
    private class QueuingExecutor : java.util.concurrent.Executor {
        val pending = mutableListOf<Runnable>()
        val executed get() = pending.size
        override fun execute(command: Runnable) {
            pending += command
        }

        fun drain() = pending.toList().also { pending.clear() }.forEach { it.run() }
    }

    /** Runs work on a REAL background thread, recording which thread it ran on. */
    private class ThreadRecordingExecutor : java.util.concurrent.Executor {
        val threads = mutableListOf<Long>()
        override fun execute(command: Runnable) {
            val latch = CountDownLatch(1)
            var ranOn = 0L
            Thread {
                ranOn = Thread.currentThread().id
                command.run()
                latch.countDown()
            }.start()
            latch.await(5, TimeUnit.SECONDS)
            threads += ranOn
        }
    }

    private class RecordingStore : WorkspaceCollapseStore {
        var loaded = false
        var persisted: Set<String>? = null
        val loadThreadIds = mutableListOf<Long>()
        val saveThreadIds = mutableListOf<Long>()

        override fun load(): Set<String> {
            loaded = true
            loadThreadIds += Thread.currentThread().id
            return setOf("/ws/pre-collapsed")
        }

        override fun save(collapsed: Set<String>) {
            persisted = collapsed
            saveThreadIds += Thread.currentThread().id
        }
    }

    @Test
    fun `the initial load is DISPATCHED - never inline on the constructing thread`() {
        val store = RecordingStore()
        val executor = QueuingExecutor()

        val state = WorkspaceCollapseState(store, executor)
        assertFalse(
            "RED (B2.17): the first-frame load ran INLINE on the constructing thread",
            store.loaded,
        )

        executor.drain()
        assertTrue(store.loaded)
        assertEquals(setOf("/ws/pre-collapsed"), state.collapsedKeys)
        assertEquals(1, store.loadThreadIds.size)
    }

    @Test
    fun `toggle persists through the executor - off the caller thread`() {
        val store = RecordingStore()
        val executor = ThreadRecordingExecutor()
        val callerThread = Thread.currentThread().id

        val state = WorkspaceCollapseState(store, executor)
        // The background load applies via a main-looper post — idle it (what the
        // production main looper does between frames).
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(setOf("/ws/pre-collapsed"), state.collapsedKeys)

        state.toggle("/ws/other")
        assertEquals(setOf("/ws/pre-collapsed", "/ws/other"), state.collapsedKeys)
        assertTrue(
            "the save must be persisted through the executor",
            store.persisted == setOf("/ws/pre-collapsed", "/ws/other"),
        )
        assertEquals(1, store.saveThreadIds.size)
        assertNotEquals(
            "the save must NOT run on the caller (UI) thread — RED (B2.17): it was sync",
            callerThread,
            store.saveThreadIds.single(),
        )
        assertNotEquals(callerThread, store.loadThreadIds.single())
    }

    @Test
    fun `toggle-off removes the key and persists the removal`() {
        val store = RecordingStore()
        val executor = QueuingExecutor()

        val state = WorkspaceCollapseState(store, executor)
        executor.drain()
        assertTrue(state.isCollapsed("/ws/pre-collapsed"))

        state.toggle("/ws/pre-collapsed")
        assertFalse(state.isCollapsed("/ws/pre-collapsed"))
        executor.drain()
        assertEquals(emptySet<String>(), store.persisted)
    }
}
