package com.zcode.android.features.tasks.ui

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

/** UI state holder: toggle membership in the collapsed set, persisting on each change. */
class WorkspaceCollapseState(private val store: WorkspaceCollapseStore?) {

    private val _collapsed = MutableStateFlow(store?.load() ?: emptySet())

    val collapsed: StateFlow<Set<String>> get() = _collapsed

    fun isCollapsed(workspaceKey: String): Boolean {
        // Implemented in fix(GAP-14).
        return false
    }

    fun toggle(workspaceKey: String) {
        // Implemented in fix(GAP-14).
    }
}

/**
 * Disk-backed store: one workspace key per line (keys are desktop paths / identity
 * strings — no separators ambiguity beyond newlines, which keys never contain).
 * A missing or corrupt file degrades to an empty set, never a crash (INV-8 spirit).
 */
class WorkspaceCollapseFileStore(private val file: File) : WorkspaceCollapseStore {

    override fun load(): Set<String> {
        // Implemented in fix(GAP-14).
        return emptySet()
    }

    override fun save(collapsed: Set<String>) {
        // Implemented in fix(GAP-14).
    }
}
