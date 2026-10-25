package com.zcode.android.core.notifications

import com.zcode.android.domain.model.AppPreferences
import com.zcode.android.domain.model.NotificationRecord

/**
 * NotificationPlan — the PURE decision core of the §24.1 broker (§24.1 "the broker
 * is transport-independent and the sole notification decision point: incoming event
 * → eligibility → deduplication → privacy policy → delivery"). Everything here is a
 * function of (event, policy) — no Android types, no I/O — so the §24.1 mapping,
 * §24.3 dedupe/collapse shape, foreground suppression (§9.9) and the §24.2 privacy
 * levels are unit-testable without a device.
 *
 * Privacy posture (T8, §24.1 table — normative defaults):
 *  - APPROVAL/USER-INPUT: lock-screen text is CATEGORY ONLY ("ZCode needs your
 *    approval") — never tool names, never paths — at BOTH content levels.
 *  - TASK_FINISHED/TASK_FAILED: MINIMAL = category only; CONTENT = "…: <title>"
 *    (title only; toggleable — the §24.1 "toggleable" cell is the content level).
 *  - CONNECTION_LOST: category only (no content exists to show).
 */
object NotificationPlan {

    /** §24.1 event → (channel, importance) mapping. */
    fun channelIdFor(kind: NotificationRecord.Kind): String = when (kind) {
        NotificationRecord.Kind.APPROVAL_NEEDED,
        NotificationRecord.Kind.USER_INPUT_NEEDED,
        -> NotificationChannels.PERMISSIONS
        NotificationRecord.Kind.TASK_FINISHED,
        NotificationRecord.Kind.TASK_FAILED,
        NotificationRecord.Kind.CONNECTION_LOST,
        -> NotificationChannels.TASK_STATUS
    }

    data class Event(
        val kind: NotificationRecord.Kind,
        val taskTitle: String? = null,
    )

    data class Policy(
        /** §9.9: suppression while the app is foregrounded (in-app surfaces own it). */
        val appForeground: Boolean,
        /** §24.2 lock-screen visibility user setting (default MINIMAL). */
        val contentLevel: AppPreferences.NotificationContentLevel,
    )

    data class Decision(
        val show: Boolean,
        val channelId: String,
        /** Lock-screen-safe text (public version). Category-only under MINIMAL. */
        val publicText: String,
        /** Content text shown when unlocked; null = same as public (§24.1 "no content"). */
        val detailText: String?,
    )

    /**
     * §31: the user-visible copy is injected so the pure plan stays localizable.
     * Production binds [ResourceNotificationCopy]; the default is the English
     * copy (every existing pure-JVM test asserts it).
     */
    interface NotificationCopy {
        fun approvalPublic(): String
        fun questionPublic(): String
        fun taskFinishedPublic(): String
        fun taskFinishedDetail(taskTitle: String): String
        fun taskFailedPublic(): String
        fun taskFailedDetail(taskTitle: String): String
        fun connectionLostPublic(): String
        /** C3: the shade action labels (defaults for the pure tests). */
        fun approveAction(): String = "Approve"
        fun denyAction(): String = "Deny"
    }

    /** English copy (the §24.1 table verbatim) — the default for pure tests. */
    object DefaultNotificationCopy : NotificationCopy {
        override fun approvalPublic() = "ZCode needs your approval"
        override fun questionPublic() = "ZCode has a question for you"
        override fun taskFinishedPublic() = "Task completed"
        override fun taskFinishedDetail(taskTitle: String) = "Task completed: $taskTitle"
        override fun taskFailedPublic() = "Task failed"
        override fun taskFailedDetail(taskTitle: String) = "Task failed: $taskTitle"
        override fun connectionLostPublic() = "Connection lost"
    }

    fun decide(event: Event, policy: Policy, copy: NotificationCopy = DefaultNotificationCopy): Decision {
        val channel = channelIdFor(event.kind)
        // §9.9 + §24.4: while foregrounded the in-app surfaces own attention; the
        // broker never posts (eligibility step of §24.1).
        if (policy.appForeground) return Decision(false, channel, "", null)
        val full = policy.contentLevel == AppPreferences.NotificationContentLevel.CONTENT
        return when (event.kind) {
            NotificationRecord.Kind.APPROVAL_NEEDED ->
                Decision(true, channel, copy.approvalPublic(), null)
            NotificationRecord.Kind.USER_INPUT_NEEDED ->
                Decision(true, channel, copy.questionPublic(), null)
            NotificationRecord.Kind.TASK_FINISHED ->
                if (full && !event.taskTitle.isNullOrBlank()) {
                    Decision(true, channel, copy.taskFinishedPublic(), copy.taskFinishedDetail(event.taskTitle))
                } else {
                    Decision(true, channel, copy.taskFinishedPublic(), null)
                }
            NotificationRecord.Kind.TASK_FAILED ->
                if (full && !event.taskTitle.isNullOrBlank()) {
                    Decision(true, channel, copy.taskFailedPublic(), copy.taskFailedDetail(event.taskTitle))
                } else {
                    Decision(true, channel, copy.taskFailedPublic(), null)
                }
            NotificationRecord.Kind.CONNECTION_LOST ->
                Decision(true, channel, copy.connectionLostPublic(), null)
        }
    }

    /**
     * §24.3 dedupe identity: ONE notification per open interaction (updates replace,
     * never stack); terminal outcomes collapse per task; connection-lost is a single
     * global slot. Returns null for events that cannot produce a stable key.
     */
    fun notificationKey(kind: NotificationRecord.Kind, taskId: String?, interactionId: String?): String? =
        when (kind) {
            NotificationRecord.Kind.APPROVAL_NEEDED,
            NotificationRecord.Kind.USER_INPUT_NEEDED,
            -> interactionId?.takeIf { it.isNotBlank() }?.let { "interaction:$it" }
            NotificationRecord.Kind.TASK_FINISHED,
            NotificationRecord.Kind.TASK_FAILED,
            -> taskId?.takeIf { it.isNotBlank() }?.let { "task:$it:$kind" }
            NotificationRecord.Kind.CONNECTION_LOST -> "connection-lost"
        }
}
