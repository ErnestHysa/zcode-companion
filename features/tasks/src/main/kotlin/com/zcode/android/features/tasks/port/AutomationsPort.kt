package com.zcode.android.features.tasks.port

import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.OffPeakTaskRow

/**
 * GAP-6 consumer-side port (INV-2/INV-6): the Tasks-tab automations + off-peak
 * lists with FULL CRUD. (A1.3, 2026-10-14: this doc previously claimed the write
 * half "exists NOWHERE" — that was stale; the create/update/delete/setEnabled/
 * runNow half SHIPPED 2026-10-12, desktop parity zcodeAgent.ts:677-688, with the
 * AutomationFormDialog editor. Read-only INV-9 applies to TASKS/GIT/FILES, not to
 * this port — automations CRUD is the desktop's own sanctioned write surface.)
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
        // ---- Phase 4B (J2-target): the desktop's full write shape (automation-types.ts) ----
        /** The authoritative rule for CUSTOM repeats (every N × unit); null = plain cron. */
        val scheduleRule: com.zcode.android.domain.model.AutomationRow.ScheduleRule? = null,
        /** true = unbounded loop; false = finite (maxRuns). Edits keep the row's own value. */
        val recurring: Boolean = true,
        val maxRuns: Int? = null,
        /** Custom-repeat end bound (local day-end ms); null = never. */
        val endAt: Long? = null,
        /** Update only: the user replaced a carried rule with a plain cron → `scheduleRule: null`. */
        val clearScheduleRule: Boolean = false,
    )

    suspend fun create(workspaceKey: String, write: AutomationWrite): Boolean

    suspend fun update(automationId: String, write: AutomationWrite): Boolean

    suspend fun delete(automationId: String): Boolean

    suspend fun setEnabled(automationId: String, enabled: Boolean): Boolean

    suspend fun runNow(automationId: String): Boolean

    suspend fun lists(): Lists

    // ---------------------------------------------------------------- Phase 4B typed outcomes + run history

    /** automation-types.ts AUTOMATION_CREATE_LIMIT (20) → `AUTOMATION_CREATE_LIMIT_REACHED`. */
    enum class CreateOutcome { OK, LIMIT_REACHED, FAILED }

    /** Typed create: the limit error is a distinct, copy-worthy outcome (desktop automations.limit.*). */
    suspend fun createTyped(workspaceKey: String, write: AutomationWrite): CreateOutcome =
        if (create(workspaceKey, write)) CreateOutcome.OK else CreateOutcome.FAILED

    /** ZCodeAgentRunAutomationNowResult.status: queued | duplicate (an in-flight run already exists). */
    enum class RunNowOutcome { QUEUED, DUPLICATE, FAILED }

    /** B2.11: Run Now lands its result instead of being fire-and-forgotten. */
    suspend fun runNowTyped(automationId: String): RunNowOutcome =
        if (runNow(automationId)) RunNowOutcome.QUEUED else RunNowOutcome.FAILED

    data class RunsResult(val runs: List<com.zcode.android.domain.model.AutomationRunRow>?, val error: String?)

    /** A3.2: `listAutomationRuns` — the run history (null = unavailable, typed error). */
    suspend fun runs(automationId: String): RunsResult = RunsResult(null, "run history unavailable")

    /** `deleteAutomationRun`. */
    suspend fun deleteRun(runId: String): Boolean = false

    /** `restartAutomation` — re-arms a completed/failed automation (desktop automations.restart). */
    suspend fun restart(automationId: String): Boolean = false
}
