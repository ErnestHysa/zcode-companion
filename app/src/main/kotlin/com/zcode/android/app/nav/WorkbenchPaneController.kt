package com.zcode.android.app.nav

/**
 * G61 (vc52 Phase 13): the workbench detail pane's VM lifecycle controller. The
 * campaign's F-33-A lesson: a retained conversation VM bound to a destroyed
 * container is the zombie-screen generator — the workbench holds EXACTLY ONE
 * live conversation VM. `acquire` is idempotent per task (recompositions never
 * re-create), a different task tears the old VM down BEFORE creating the next,
 * and [close] tears down. The compose helper swaps during composition — the
 * swap is idempotent per taskId, so recomposition with the same selection is a
 * no-op and configuration changes ride the surviving ViewModel holder.
 */
class WorkbenchPaneController<T>(
    private val teardown: (T) -> Unit,
) {

    private var current: Pair<String, T>? = null

    fun acquire(taskId: String, create: (String) -> T): T {
        current?.let { (existingId, existing) ->
            if (existingId == taskId) return existing
            teardown(existing)
        }
        val created = create(taskId)
        current = taskId to created
        return created
    }

    fun close() {
        current?.let { teardown(it.second) }
        current = null
    }

    val activeTaskId: String? get() = current?.first
}
