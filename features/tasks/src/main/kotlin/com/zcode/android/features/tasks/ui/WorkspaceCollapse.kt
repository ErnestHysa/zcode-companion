package com.zcode.android.features.tasks.ui

import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * GAP-14 (the project roadmap): per-workspace COLLAPSE state for the task list,
 * ported from the desktop's WorkspaceGroupedTasksSection collapsedGroupIds set
 * (WorkspaceGroupedTasksSection.tsx:922-939 — toggle membership in a Set of group
 * ids). Persisted per workspace key so it survives app restart.
 */

/** Persistence seam — the app module supplies the disk-backed store. */
interface WorkspaceCollapseStore {
    fun load(): Set<String>
    fun save(collapsed: Set<String>)
}

/** UI state holder: toggle membership in the collapsed set, persisting on each change. */
class WorkspaceCollapseState(private val store: WorkspaceCollapseStore?) {

    // Snapshot state (not StateFlow): composition must OBSERVE the toggle — the list
    // hides/shows on the same frame as the header tap.
    var collapsedKeys: Set<String> by mutableStateOf(store?.load() ?: emptySet())
        private set

    fun isCollapsed(workspaceKey: String): Boolean = workspaceKey in collapsedKeys

    fun toggle(workspaceKey: String) {
        val next = collapsedKeys.toMutableSet()
        if (!next.add(workspaceKey)) next.remove(workspaceKey)
        collapsedKeys = next
        store?.save(next)
    }
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
