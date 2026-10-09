package com.zcode.android.domain.recovery

import com.zcode.android.domain.model.NotificationRecord

/**
 * Notification reconciliation on recovery — §15-S12 ("reconcile notifications (mark
 * seen if terminal consumed)") and §24.3 ("on recovery/reconnect, reconciliation marks
 * notifications for resolved interactions as dismissed").
 *
 * Pure decision core (the Phase 8 broker owns delivery); the reconciler NEVER creates
 * notifications — it only dismisses records whose authoritative state consumed them.
 */
object NotificationReconciler {

    /**
     * Given the live notification records and the authoritative terminal/interaction
     * evidence observed at recovery, return the notificationKeys to dismiss.
     *
     * @param records currently-visible records (dedupe keys).
     * @param consumedTaskIds tasks whose authoritative projection reached a terminal
     *   outcome (completed/failed/cancelled/archived/deleted) — their TASK_FINISHED /
     *   TASK_FAILED records collapse per §24.3.
     * @param resolvedInteractionIds interactions authoritatively resolved elsewhere —
     *   APPROVAL_NEEDED / USER_INPUT_NEEDED records are dismissed (S15/S13 cleanup).
     */
    fun dismissals(
        records: List<NotificationRecord>,
        consumedTaskIds: Set<String>,
        resolvedInteractionIds: Set<String>,
    ): List<String> = records.mapNotNull { record ->
        val consumed = when (record.kind) {
            NotificationRecord.Kind.TASK_FINISHED,
            NotificationRecord.Kind.TASK_FAILED,
            -> record.taskId != null && record.taskId in consumedTaskIds
            NotificationRecord.Kind.APPROVAL_NEEDED,
            NotificationRecord.Kind.USER_INPUT_NEEDED,
            -> record.interactionId != null && record.interactionId in resolvedInteractionIds
            NotificationRecord.Kind.CONNECTION_LOST -> false // cleared by the connection machine, not here
        }
        if (consumed) record.notificationKey else null
    }
}
