package com.zcode.android.core.ui.components

import com.zcode.android.domain.model.CodingPlanResetStatus

/**
 * GAP-4 (the project roadmap; docs/protocol/v4-wire.md §13.2; ADR-020) — the
 * desktop `packages/ui/src/lib/codingPlanQuotaResetUi.ts` reset state machine,
 * ported as pure functions. Entry states: available (server-granted reset
 * opportunities) → processing (manual use awaiting server reconciliation) →
 * completed (the server's usedAt) | failed (back to available, key retained).
 * The desktop's celebration garnish (confetti, cross-surface auto-play, the
 * 2.6 s auto-collapse) is NOT ported — the completion note persists until the
 * next status refresh.
 */
object CodingPlanQuotaResetState {

    /** 完成后“额度已重置”提示的停留时长（desktop constant; display garnish). */
    const val DONE_DISPLAY_MS: Long = 2_600L

    const val FIVE_HOURS_MS: Long = 5 * 60 * 60 * 1_000L
    const val WEEK_MS: Long = 7 * 24 * 60 * 60 * 1_000L

    /**
     * One reset-type's UI state. [opportunityCount]/[opportunityExpiresAt] are
     * valid in AVAILABLE (and preserved through PROCESSING so failure can
     * restore them); [completedAt] is the SERVER's usedAt; [idempotencyKey] is
     * retained across retries of the same attempt and cleared on success.
     */
    data class Entry(
        val status: ResetUiStatus,
        val opportunityCount: Int,
        val opportunityExpiresAt: Long?,
        val startedAt: Long?,
        val completedAt: Long?,
        val observedAt: Long?,
        val quotaOverridePending: Boolean,
        val nextResetAt: Long?,
        val idempotencyKey: String?,
        val error: String?,
    )

    private fun resetWindowMs(resetType: String): Long =
        if (resetType == "WEEK") WEEK_MS else FIVE_HOURS_MS

    /**
     * Desktop `applyCodingPlanQuotaResetStatus` — fold one status snapshot into
     * the UI state. [manualStartedAt] carries a manual use's click time;
     * [resetType] selects which history/opportunity fields this entry reads.
     */
    fun applyResetStatus(
        previous: Entry?,
        status: CodingPlanResetStatus,
        now: Long,
        manualStartedAt: Long? = null,
        resetType: String = "FIVE_HOUR",
    ): Entry? {
        val availableResets = if (resetType == "WEEK") status.weekExpires else status.fiveHourExpires
        val latestUsedAt =
            (if (resetType == "WEEK") status.latestWeekUsedAt else status.latestFiveHourUsedAt)
        val otherUsedAt =
            (if (resetType == "WEEK") status.latestFiveHourUsedAt else status.latestWeekUsedAt)
        // 共享 hasUnreadHistory 只归属 usedAt 最新的一类；相等时按当前类型归属。
        val ownsUnread = status.hasUnreadHistory &&
            latestUsedAt != null &&
            (otherUsedAt == null || latestUsedAt >= otherUsedAt)
        val validOpportunities = availableResets
            .filter { it > now }
            .sorted()
        // Sticky completion only yields when no NEW opportunity arrived —
        // "有效机会到来即视为进入新一轮周期，回到 AVAILABLE".
        val hasValidOpportunity = validOpportunities.isNotEmpty()
        val shouldComplete = latestUsedAt != null && (
            ownsUnread ||
                previous?.status == ResetUiStatus.PROCESSING ||
                (
                    (manualStartedAt != null ||
                        (previous?.status == ResetUiStatus.COMPLETED && previous.completedAt == latestUsedAt)) &&
                        !hasValidOpportunity
                    )
            )
        if (shouldComplete && latestUsedAt != null) {
            return createCompletedEntry(previous, latestUsedAt, now, manualStartedAt, resetWindowMs(resetType))
        }
        // "手动 use 期间 status 轮询可能仍读到消费前快照。此时保持 processing"
        if (previous?.status == ResetUiStatus.PROCESSING) {
            return previous
        }
        val earliest = validOpportunities.firstOrNull()
        if (earliest != null) {
            return Entry(
                status = ResetUiStatus.AVAILABLE,
                opportunityCount = validOpportunities.size,
                opportunityExpiresAt = earliest,
                startedAt = null,
                completedAt = null,
                observedAt = null,
                quotaOverridePending = false,
                nextResetAt = null,
                idempotencyKey = if (previous?.status == ResetUiStatus.AVAILABLE) previous.idempotencyKey else null,
                error = null,
            )
        }
        return null
    }

    private fun createCompletedEntry(
        previous: Entry?,
        completedAt: Long,
        observedAt: Long,
        manualStartedAt: Long?,
        nextResetMs: Long,
    ): Entry {
        val isSameCompletion = previous?.status == ResetUiStatus.COMPLETED && previous.completedAt == completedAt
        return Entry(
            status = ResetUiStatus.COMPLETED,
            opportunityCount = 0,
            opportunityExpiresAt = null,
            // 手动点击和完成历史可能由不同入口观察——保留原分类，不退化为自动完成。
            startedAt = when {
                isSameCompletion -> previous.startedAt
                previous?.status == ResetUiStatus.PROCESSING -> previous.startedAt
                else -> manualStartedAt
            },
            completedAt = completedAt,
            observedAt = if (isSameCompletion) (previous.observedAt ?: observedAt) else observedAt,
            quotaOverridePending = if (isSameCompletion) previous.quotaOverridePending else true,
            nextResetAt = completedAt + nextResetMs,
            idempotencyKey = null,
            error = null,
        )
    }

    /**
     * Desktop `startCodingPlanQuotaResetManualUse`: available → processing,
     * preserving the opportunity snapshot for lossless failure recovery and
     * REUSING the entry's own idempotency key when one is retained.
     */
    fun startManualUse(entry: Entry?, idempotencyKey: String, now: Long): Entry? {
        if (entry == null || entry.status != ResetUiStatus.AVAILABLE) {
            return entry
        }
        return entry.copy(
            status = ResetUiStatus.PROCESSING,
            opportunityCount = entry.opportunityCount,
            opportunityExpiresAt = entry.opportunityExpiresAt,
            startedAt = now,
            completedAt = null,
            observedAt = null,
            quotaOverridePending = false,
            nextResetAt = null,
            idempotencyKey = entry.idempotencyKey ?: idempotencyKey,
            error = null,
        )
    }

    /** Desktop `failCodingPlanQuotaResetManualUse`: processing → available with
     *  the pre-click opportunity snapshot and the SAME idempotency key. */
    fun failManualUse(entry: Entry?, error: String): Entry? {
        if (entry == null || entry.status != ResetUiStatus.PROCESSING) {
            return entry
        }
        return entry.copy(
            status = ResetUiStatus.AVAILABLE,
            opportunityCount = entry.opportunityCount,
            opportunityExpiresAt = entry.opportunityExpiresAt,
            startedAt = null,
            completedAt = null,
            observedAt = null,
            quotaOverridePending = false,
            nextResetAt = null,
            error = error,
        )
    }
}

/** Desktop `CodingPlanQuotaResetUiStatus` (lib lines 17-21): available →
 *  processing (manual use awaiting server reconciliation) → completed (the
 *  server's usedAt). */
enum class ResetUiStatus { AVAILABLE, PROCESSING, COMPLETED }
