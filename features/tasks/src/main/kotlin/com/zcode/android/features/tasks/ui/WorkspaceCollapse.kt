package com.zcode.android.features.tasks.ui

import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * GAP-14 (android-native-gaps.md): per-workspace COLLAPSE state for the task list,
 * ported from the desktop's WorkspaceGroupedTasksSection collapsedGroupIds set
 * (WorkspaceGroupedTasksSection.tsx:922-939 — toggle membership in a Set of group
 * ids). Persisted per workspace key so it survives app restart.
 */

/** Persistence seam — the app module supplies the disk-backed store. */
interface WorkspaceCollapseStore {
    fun load(): Set<String>
    fun save(collapsed: Set<String>)
}

/** UI state holder: toggle membership in the collapsed set, persisting on each change.
 *
 * B2.17 RED STATE: [persistExecutor] is the seam — the DEFAULT runs work inline on
 * the calling (UI) thread, exactly like the original code (a sync `store.load()`
 * in the delegate initializer = first-frame disk I/O, and a sync `store.save()` on
 * every tap). The fix swaps the production default to a background executor.
 */
class WorkspaceCollapseState(
    private val store: WorkspaceCollapseStore?,
    private val persistExecutor: java.util.concurrent.Executor = WorkspaceCollapseStateBackgroundExecutor(),
) {

    // Snapshot state (not StateFlow): composition must OBSERVE the toggle — the list
    // hides/shows on the same frame as the header tap.
    var collapsedKeys: Set<String> by mutableStateOf(emptySet())
        private set

    init {
        store?.let { s ->
            persistExecutor.execute {
                val loaded = s.load()
                applyOnMain { collapsedKeys = loaded }
            }
        }
    }

    fun isCollapsed(workspaceKey: String): Boolean = workspaceKey in collapsedKeys

    fun toggle(workspaceKey: String) {
        val next = collapsedKeys.toMutableSet()
        if (!next.add(workspaceKey)) next.remove(workspaceKey)
        collapsedKeys = next
        store?.let { s -> persistExecutor.execute { s.save(next) } }
    }

    private companion object {
        fun applyOnMain(apply: () -> Unit) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) apply()
            else android.os.Handler(android.os.Looper.getMainLooper()).post(apply)
        }
    }
}

/**
 * B2.17: the production persist executor — one shared daemon thread; collapse
 * loads/saves never run on the UI thread. Tests inject a direct executor.
 */
fun WorkspaceCollapseStateBackgroundExecutor(): java.util.concurrent.Executor =
    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "workspace-collapse-persist").apply { isDaemon = true }
    }

/**
 * Disk-backed store: a header-marked file with one workspace key per line. The
 * header guards against foreign/garbage payloads (degrade to an empty set, never a
 * crash — INV-8 spirit); keys never contain newlines or control characters.
 */
class WorkspaceCollapseFileStore(private val file: File) : WorkspaceCollapseStore {

    override fun load(): Set<String> {
        if (!file.isFile) return emptySet()
        return runCatching {
            val lines = file.readLines()
            if (lines.firstOrNull() != HEADER) {
                emptySet()
            } else {
                lines.drop(1).filter { it.isNotBlank() }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    override fun save(collapsed: Set<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText((listOf(HEADER) + collapsed.sorted()).joinToString("\n"))
        }
    }

    private companion object {
        const val HEADER = "zcode-workspace-collapse-1"
    }
}
