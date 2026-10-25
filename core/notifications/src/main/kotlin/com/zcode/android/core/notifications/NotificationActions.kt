package com.zcode.android.core.notifications

/**
 * C3 (Phase 4E): the shade-action contract shared by the sink (core) and the
 * receiver (app). The sink targets the ACTION string — the app's manifest-registered
 * receiver resolves it; core never imports the app class.
 */
object NotificationActions {
    const val ACTION = "com.zcode.android.action.NOTIFICATION_DECISION"

    const val EXTRA_INTERACTION_ID = "interactionId"
    const val EXTRA_OPTION_ID = "optionId"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_NOTIFICATION_KEY = "notificationKey"

    /** Semantic ids the sink labels (the desktop's allowOnce/deny vocabulary). */
    const val ID_APPROVE = "approve"
    const val ID_DENY = "deny"
}
