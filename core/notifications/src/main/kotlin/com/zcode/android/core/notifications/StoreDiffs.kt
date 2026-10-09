package com.zcode.android.core.notifications

import com.zcode.android.domain.model.Task
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.repo.InteractionRepository

/**
 * Pure diff of repository emissions → broker events (the app wiring collects
 * Room/protocol flows; these helpers decide WHICH emissions are notification-worthy
 * without any state of their own — the wiring keeps the previous snapshot).
 *
 * First-emission seeding: a cold app start re-emits the CACHED projection; the
 * first list ALWAYS seeds the baseline and never notifies (a fresh process must not
 * spam "task completed" for terminal states that were terminal yesterday). Open
 * interactions are different: they re-post through [NotificationBroker.onBackgroundEntry]
 * (§15-S13), not through this diff.
 */
object StoreDiffs {

    /** New REQUESTED interactions (by id) → their broker kind. */
    fun newOpenInteractions(
        previousIds: Set<String>,
        current: List<InteractionRepository.InteractionRecord>,
    ): List<Pair<InteractionRepository.InteractionRecord, NotificationRecord.Kind>> =
        current.mapNotNull { record ->
            val isNew = record.interactionId.raw !in previousIds
            val kind = when {
                !isNew -> null
                record.kind == InteractionRepository.InteractionRecord.Kind.PERMISSION -> NotificationRecord.Kind.APPROVAL_NEEDED
                else -> NotificationRecord.Kind.USER_INPUT_NEEDED
            }
            kind?.let { record to it }
        }

    /** Task status transitions INTO a terminal state → TASK_FINISHED / TASK_FAILED. */
    fun taskTerminalTransitions(
        previous: Map<String, TaskStatus>,
        current: List<Task>,
    ): List<Pair<Task, NotificationRecord.Kind>> = current.mapNotNull { task ->
        val before = previous[task.taskId]
        val kind = when (task.status) {
            TaskStatus.COMPLETED ->
                if (before != null && before != TaskStatus.COMPLETED) NotificationRecord.Kind.TASK_FINISHED else null
            TaskStatus.ERROR ->
                if (before != null && before != TaskStatus.ERROR) NotificationRecord.Kind.TASK_FAILED else null
            else -> null
        }
        kind?.let { task to it }
    }

    /** Status index of a task list (the wiring's previous-snapshot shape). */
    fun statusIndex(tasks: List<Task>): Map<String, TaskStatus> =
        tasks.associate { it.taskId to it.status }

    /** Interaction ids of an open list (the wiring's previous-snapshot shape). */
    fun interactionIds(records: List<InteractionRepository.InteractionRecord>): Set<String> =
        records.map { it.interactionId.raw }.toSet()

    /**
     * FIELD (2026-10-06, vc35 user report — "answered approvals re-post on every
     * background; the badge stays 4"): CLOSED interactions (present before, absent
     * or resolved now) must DISMISS their broker records. The old wiring only ever
     * looked for NEW open interactions — a resolved interaction's tray record stayed
     * unseen forever and §15-S13 re-posted it on every background entry.
     */
    fun closedInteractions(previousIds: Set<String>, current: List<InteractionRepository.InteractionRecord>): Set<String> {
        val openNow = interactionIds(current)
        return previousIds - openNow
    }
}
