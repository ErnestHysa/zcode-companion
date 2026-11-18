package com.zcode.android.app.nav

import com.zcode.android.domain.model.AppPreferences
import com.zcode.android.domain.repo.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * F-P40-B (pass-40 P2, pass-41 revision: rename-only-dead): the three workspace-
 * manager pref writes (select/reorder/rename) ran on TasksRoute's
 * `rememberCoroutineScope()` — a COMPOSITION-OWNED scope. A write in flight dies
 * SILENTLY the moment the composition leaves while the write is still suspended (the
 * rig receipt: preferences.pb field 17 frozen through Save, ×4 across two processes,
 * no exception; AR.C's frame-wedge window is the prime suspect) — while SELECT and
 * REORDER through the same shape happened to land (their windows never overlapped a
 * disposal). The fix shape from the roadmap: move all three writes off the
 * composition-captured scope onto the process-lifetime [AppStores.appScope].
 *
 * The bodies are the TasksRoute lambdas verbatim (ZCodeApp.kt:741-755).
 */
class WorkspacePreferenceWriter(
    private val repository: PreferencesRepository,
    private val scope: CoroutineScope,
) {
    /** Field 18 `selected_workspace_key` (null = All). */
    fun select(key: String?) {
        scope.launch { repository.update { p -> p.copy(selectedWorkspaceKey = key) } }
    }

    /** Field 16 `workspace_order` (the display order; unlisted keys keep wire order). */
    fun reorder(keysInOrder: List<String>) {
        scope.launch { repository.update { p -> p.copy(workspaceOrder = keysInOrder) } }
    }

    /**
     * Field 17 `workspace_aliases` (rename-class): non-null alias = upsert; null =
     * clear (the wire label falls back).
     */
    fun rename(key: String, alias: String?) {
        scope.launch {
            repository.update { p ->
                val next = p.workspaceAliases.toMutableMap()
                if (alias == null) next.remove(key) else next[key] = alias
                p.copy(workspaceAliases = next)
            }
        }
    }
}
