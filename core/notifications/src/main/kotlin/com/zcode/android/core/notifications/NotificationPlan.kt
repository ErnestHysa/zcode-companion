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

    fun decide(event: Event, policy: Policy): Decision {
        val channel = channelIdFor(event.kind)
        // §9.9 + §24.4: while foregrounded the in-app surfaces own attention; the
        // broker never posts (eligibility step of §24.1).
        if (policy.appForeground) return Decision(false, channel, "", null)
        val full = policy.contentLevel == AppPreferences.NotificationContentLevel.CONTENT
        return when (event.kind) {
            NotificationRecord.Kind.APPROVAL_NEEDED ->
                Decision(true, channel, "ZCode needs your approval", null)
            NotificationRecord.Kind.USER_INPUT_NEEDED ->
                Decision(true, channel, "ZCode has a question for you", null)
            NotificationRecord.Kind.TASK_FINISHED ->
                if (full && !event.taskTitle.isNullOrBlank()) {
                    Decision(true, channel, "Task completed", "Task completed: ${event.taskTitle}")
                } else {
                    Decision(true, channel, "Task completed", null)
                }
            NotificationRecord.Kind.TASK_FAILED ->
                if (full && !event.taskTitle.isNullOrBlank()) {
                    Decision(true, channel, "Task failed", "Task failed: ${event.taskTitle}")
                } else {
                    Decision(true, channel, "Task failed", null)
                }
            NotificationRecord.Kind.CONNECTION_LOST ->
                Decision(true, channel, "Connection lost", null)
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
