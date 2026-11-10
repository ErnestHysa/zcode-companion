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
 *    approval") — never tool names, never paths — at BOTH content levels (the sink posts
 *    it as the notification's PUBLIC version).
 *  - F-33-H: under CONTENT ("Task titles") the PRIVATE (unlocked) version carries the
 *    subject — the tool + command/path, or the question — and only then may Approve/Deny
 *    ride the shade ([Decision.shadeActions]). Under MINIMAL nothing may name the subject
 *    (Android shows the private version on the lock screen unless the user hides
 *    sensitive content), so the approval is answered IN-APP: an action the user cannot
 *    read is never offered.
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
        NotificationRecord.Kind.CUA_PERMISSION,
        -> NotificationChannels.TASK_STATUS
    }

    data class Event(
        val kind: NotificationRecord.Kind,
        val taskTitle: String? = null,
        /**
         * F-33-H: what an approval/question is ABOUT — "Bash: <command>", "Edit: <path>", or
         * the question — already bounded and secret-scrubbed upstream (InteractionPreview).
         */
        val subject: String? = null,
        /** G64: the CUA observation's missing-permission face ("Accessibility: Missing", …). */
        val cuaMissing: String? = null,
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
        /** F-33-H: Approve/Deny may ride the shade — only when [detailText] shows the subject. */
        val shadeActions: Boolean = false,
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
        /** G64: the CUA permission card — the missing permission named. */
        fun cuaPermissionPublic(missing: String): String
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
        override fun cuaPermissionPublic(missing: String) =
            "Computer Use on your desktop needs macOS permissions ($missing) — grant them on the Mac"
    }

    fun decide(event: Event, policy: Policy, copy: NotificationCopy = DefaultNotificationCopy): Decision {
        val channel = channelIdFor(event.kind)
        // §9.9 + §24.4: while foregrounded the in-app surfaces own attention; the
        // broker never posts (eligibility step of §24.1).
        if (policy.appForeground) return Decision(false, channel, "", null)
        val full = policy.contentLevel == AppPreferences.NotificationContentLevel.CONTENT
        // F-33-H: the subject rides the PRIVATE version only under CONTENT.
        val subject = event.subject?.takeIf { full && it.isNotBlank() }
        return when (event.kind) {
            NotificationRecord.Kind.APPROVAL_NEEDED ->
                Decision(true, channel, copy.approvalPublic(), subject, shadeActions = subject != null)
            NotificationRecord.Kind.USER_INPUT_NEEDED ->
                Decision(true, channel, copy.questionPublic(), subject)
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
            NotificationRecord.Kind.CUA_PERMISSION ->
                Decision(true, channel, copy.cuaPermissionPublic(event.cuaMissing ?: "macOS permissions"), event.cuaMissing)
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
            NotificationRecord.Kind.CUA_PERMISSION ->
                "cua-permission:" + (taskId?.takeIf { it.isNotBlank() } ?: "unknown")
        }
}
