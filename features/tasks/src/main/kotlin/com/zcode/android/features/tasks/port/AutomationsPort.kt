package com.zcode.android.features.tasks.port

import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.OffPeakTaskRow

/**
 * GAP-6 consumer-side port (INV-2/INV-6): the Tasks-tab read-only automations +
 * off-peak lists. The write half (create/edit/trigger/delete/enable — the
 * roadmap's Deferred scope) exists NOWHERE in this port or its implementation.
 */
interface AutomationsPort {

    /**
     * @param automations null = unavailable (§13.3 hidden fallback); empty = the
     *   workspace genuinely has none (empty state).
     * @param offPeak same semantics, workspace-filtered by the implementation.
     */
    data class Lists(
        val automations: List<AutomationRow>?,
        val offPeak: List<OffPeakTaskRow>?,
        val error: String?,
    )

    /** FIELD (2026-10-12): schedule spec — the three carriers automation-types.ts
     *  supports, surfaced as one sealed choice for the form. */
    data class ScheduleSpec(
        /** Every N minutes (relativeDelayMinutes carrier; one-shot). */
        val everyMinutes: Int? = null,
        /** Daily cron at hour:minute (24h, local; recurring). */
        val dailyHour: Int? = null,
        val dailyMinute: Int? = null,
        /** Raw 5-field cron (advanced, recurring). */
        val cronExpr: String? = null,
    ) {
        /** The cronExpr wire value (service normalizes carriers to scheduleRule). */
        fun cronExprOrPlaceholder(): String = when {
            cronExpr != null -> cronExpr
            dailyHour != null && dailyMinute != null -> "$dailyMinute $dailyHour * * *"
            else -> "* * * * *"
        }

        fun isRelativeDelay(): Boolean = everyMinutes != null

        fun isRecurring(): Boolean = everyMinutes == null

        companion object {
            fun everyNMinutes(n: Int) = ScheduleSpec(everyMinutes = n)
            fun dailyAt(hour: Int, minute: Int) = ScheduleSpec(dailyHour = hour, dailyMinute = minute)
            fun cron(expr: String) = ScheduleSpec(cronExpr = expr)

            /**
             * B2.1: the phone-side cron syntax gate — a 5-field cron in the standard
             * charset (digits, `*`, `,`, `-`, `/`, name/step tokens like MON/1-5/5L).
             * Deliberately PERMISSIVE: exact semantics stay the desktop write-path's
             * concern; the gate only refuses garbage that would silently corrupt the
             * schedule (or the editor's old blank-to-"0 9 * * *" default).
             */
            fun isWellFormedCron(expr: String): Boolean {
                val fields = expr.trim().split(Regex("\\s+"))
                if (fields.size != 5) return false
                val field = Regex("[0-9A-Za-z*,\\-/#+?]+")
                return fields.all { it.isNotEmpty() && field.matches(it) }
            }
        }
    }

    /** Wire request for create/update (the common fields). */
    data class AutomationWrite(
        val title: String,
        val prompt: String,
        val schedule: ScheduleSpec,
        val modelProvider: String? = null,
        val modelId: String? = null,
        val mode: String? = null,
    )

    suspend fun create(workspaceKey: String, write: AutomationWrite): Boolean

    suspend fun update(automationId: String, write: AutomationWrite): Boolean

    suspend fun delete(automationId: String): Boolean

    suspend fun setEnabled(automationId: String, enabled: Boolean): Boolean

    suspend fun runNow(automationId: String): Boolean

    suspend fun lists(): Lists
}
