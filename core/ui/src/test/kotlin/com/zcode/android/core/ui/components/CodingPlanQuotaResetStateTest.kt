package com.zcode.android.core.ui.components

import com.zcode.android.domain.model.CodingPlanResetStatus
import com.zcode.android.domain.model.CodingPlanResetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GAP-4 (the project roadmap; docs/protocol/v4-wire.md §13.2; ADR-020) — the
 * desktop `codingPlanQuotaResetUi.ts` state machine, ported VERBATIM. Every pin
 * below cites the desktop comment it preserves:
 *
 * - opportunities are valid only when `expireAt > now` (sorted, earliest first);
 * - completion requires the SERVER's usedAt; the shared `hasUnreadHistory`
 *   cursor is owned by the NEWEST of the two reset histories (equal → current
 *   type owns it) — "否则一次周重置就会把过期的五小时历史误判为刚完成";
 * - processing is STICKY — "手动 use 期间 status 轮询可能仍读到消费前快照…
 *   不能被旧 opportunity 回退成可再次点击";
 * - failure restores the pre-click opportunity count/expiry and REUSES the
 *   idempotency key — "失败恢复必须保留点击前的机会数量和过期时间";
 * - a NEW valid opportunity supersedes a sticky completion — "有效机会到来即视为
 *   进入新一轮周期，回到 AVAILABLE";
 * - the optimistic window is 5h for FIVE_HOUR, 7d for WEEK.
 */
class CodingPlanQuotaResetStateTest {

    private val now = 1791933600000L
    private val expires1h = now + 3_600_000
    private val expires2h = now + 7_200_000
    private val usedAt3hAgo = now - 10_800_000

    private fun status(
        fiveHour: List<Long> = emptyList(),
        week: List<Long> = emptyList(),
        latestFiveHourUsedAt: Long? = null,
        latestWeekUsedAt: Long? = null,
        hasUnread: Boolean = false,
    ) = CodingPlanResetStatus(
        fiveHourExpires = fiveHour,
        weekExpires = week,
        latestFiveHourUsedAt = latestFiveHourUsedAt,
        latestWeekUsedAt = latestWeekUsedAt,
        hasUnreadHistory = hasUnread,
    )

    @Test
    fun validOpportunities_becomeAvailable_sortedEarliestFirst() {
        val entry = CodingPlanQuotaResetState.applyResetStatus(
            previous = null,
            status = status(fiveHour = listOf(expires2h, expires1h)),
            now = now,
        )
        assertEquals(ResetUiStatus.AVAILABLE, entry!!.status)
        assertEquals(2, entry.opportunityCount)
        assertEquals(expires1h, entry.opportunityExpiresAt)
        assertNull(entry.idempotencyKey)
    }

    @Test
    fun expiredOpportunities_filtered_noEntry() {
        val expired = now - 1
        assertNull(
            CodingPlanQuotaResetState.applyResetStatus(
                null, status(fiveHour = listOf(expired)), now,
            ),
        )
    }

    @Test
    fun unreadCompletion_enteredFromAvailable_withOptimisticWindow() {
        val available = CodingPlanQuotaResetState.applyResetStatus(
            null, status(fiveHour = listOf(expires1h)), now,
        )!!
        val completed = CodingPlanQuotaResetState.applyResetStatus(
            previous = available,
            status = status(latestFiveHourUsedAt = usedAt3hAgo, hasUnread = true),
            now = now,
            resetType = CodingPlanResetType.FIVE_HOUR,
        )!!
        assertEquals(ResetUiStatus.COMPLETED, completed.status)
        assertEquals(usedAt3hAgo, completed.completedAt)
        assertEquals(now, completed.observedAt)
        // 乐观改写：FIVE_HOUR 完成后下一次重置 = usedAt + 5h
        assertEquals(usedAt3hAgo + CodingPlanQuotaResetState.FIVE_HOURS_MS, completed.nextResetAt)
        assertEquals(true, completed.quotaOverridePending)
        assertEquals(0, completed.opportunityCount)
    }

    @Test
    fun weekCompletion_usesSevenDayWindow() {
        val completed = CodingPlanQuotaResetState.applyResetStatus(
            previous = null,
            status = status(latestWeekUsedAt = usedAt3hAgo, hasUnread = true),
            now = now,
            resetType = CodingPlanResetType.WEEK,
        )!!
        assertEquals(ResetUiStatus.COMPLETED, completed.status)
        assertEquals(usedAt3hAgo + CodingPlanQuotaResetState.WEEK_MS, completed.nextResetAt)
    }

    @Test
    fun sharedUnreadCursor_ownedByNewestHistory_only() {
        // week usedAt NEWER than five-hour → week owns the unread flag:
        // the five-hour type must NOT enter completed from stale history
        val weekNewer = usedAt3hAgo + 1
        val fiveHourEntry = CodingPlanQuotaResetState.applyResetStatus(
            null, status(latestFiveHourUsedAt = usedAt3hAgo, latestWeekUsedAt = weekNewer, hasUnread = true),
            now, resetType = CodingPlanResetType.FIVE_HOUR,
        )
        assertNull(fiveHourEntry)
        val weekEntry = CodingPlanQuotaResetState.applyResetStatus(
            null, status(latestFiveHourUsedAt = usedAt3hAgo, latestWeekUsedAt = weekNewer, hasUnread = true),
            now, resetType = CodingPlanResetType.WEEK,
        )
        assertEquals(ResetUiStatus.COMPLETED, weekEntry!!.status)
        // equal usedAt → the CURRENT type owns it (至少有一类能进入完成态)
        val equal = CodingPlanQuotaResetState.applyResetStatus(
            null, status(latestFiveHourUsedAt = usedAt3hAgo, latestWeekUsedAt = usedAt3hAgo, hasUnread = true),
            now, resetType = CodingPlanResetType.WEEK,
        )
        assertEquals(ResetUiStatus.COMPLETED, equal!!.status)
    }

    @Test
    fun oldHistoryWithoutUnreadFlag_neverCompletesOnMount() {
        // "旧历史且 has_unread_history=false 不能在首次挂载时进入 completed"
        assertNull(
            CodingPlanQuotaResetState.applyResetStatus(
                null, status(latestFiveHourUsedAt = usedAt3hAgo, hasUnread = false), now,
            ),
        )
    }

    @Test
    fun processing_isSticky_againstPreConsumeSnapshots() {
        val processing = CodingPlanQuotaResetState.startManualUse(
            CodingPlanQuotaResetState.applyResetStatus(null, status(fiveHour = listOf(expires1h)), now)!!,
            "key-1", now,
        )!!
        assertEquals(ResetUiStatus.PROCESSING, processing.status)
        // a status poll that still reads the pre-consume snapshot (opportunity
        // present, no new usedAt) must NOT flip processing back to available
        val still = CodingPlanQuotaResetState.applyResetStatus(
            processing, status(fiveHour = listOf(expires1h)), now,
        )
        assertEquals(ResetUiStatus.PROCESSING, still!!.status)
        assertEquals("key-1", still.idempotencyKey)
    }

    @Test
    fun processing_withServerUsedAt_confirmsCompleted() {
        val processing = CodingPlanQuotaResetState.startManualUse(
            CodingPlanQuotaResetState.applyResetStatus(null, status(fiveHour = listOf(expires1h)), now)!!,
            "key-1", now,
        )!!
        val confirmed = CodingPlanQuotaResetState.applyResetStatus(
            processing,
            status(latestFiveHourUsedAt = usedAt3hAgo, hasUnread = false),
            now + 900,
        )!!
        assertEquals(ResetUiStatus.COMPLETED, confirmed.status)
        // 手动轨迹归因：startedAt carries the manual click time through completion
        assertEquals(now, confirmed.startedAt)
        // key cleared on successful reconciliation ("成功对账后清空")
        assertNull(confirmed.idempotencyKey)
    }

    @Test
    fun startManualUse_noOpFromNonAvailable_andReusesFailedAttemptKey() {
        val available = CodingPlanQuotaResetState.applyResetStatus(
            null, status(fiveHour = listOf(expires1h)), now,
        )!!
        val first = CodingPlanQuotaResetState.startManualUse(available, "key-a", now)!!
        assertEquals("key-a", first.idempotencyKey)
        // a retry attempt generates a fresh key candidate, but the FAILED
        // attempt's sticky key wins ("同一次失败重试必须复用的幂等键")
        val failed = CodingPlanQuotaResetState.failManualUse(first, "net lost")!!
        val retried = CodingPlanQuotaResetState.startManualUse(failed, "key-b", now + 1)!!
        assertEquals("key-a", retried.idempotencyKey)
        // startManualUse from a NON-available entry is a no-op (desktop guard)
        val completed = CodingPlanQuotaResetState.applyResetStatus(
            available, status(latestFiveHourUsedAt = usedAt3hAgo, hasUnread = true), now,
        )!!
        assertEquals(
            completed,
            CodingPlanQuotaResetState.startManualUse(completed, "x", now),
        )
        assertEquals(
            first,
            CodingPlanQuotaResetState.startManualUse(first, "x", now),
        )
    }

    @Test
    fun failManualUse_restoresOpportunity_andKeepsIdempotencyKey() {
        val available = CodingPlanQuotaResetState.applyResetStatus(
            null, status(fiveHour = listOf(expires2h, expires1h)), now,
        )!!
        val processing = CodingPlanQuotaResetState.startManualUse(available, "key-a", now)!!
        val failed = CodingPlanQuotaResetState.failManualUse(processing, "network lost")!!
        assertEquals(ResetUiStatus.AVAILABLE, failed.status)
        // "失败恢复必须保留点击前的机会数量和过期时间，否则入口会消失"
        assertEquals(2, failed.opportunityCount)
        assertEquals(expires1h, failed.opportunityExpiresAt)
        assertEquals("key-a", failed.idempotencyKey)
        assertEquals("network lost", failed.error)
        assertNull(failed.startedAt)
        // failManualUse from a non-processing entry is a no-op
        assertEquals(available, CodingPlanQuotaResetState.failManualUse(available, "x"))
    }

    @Test
    fun newValidOpportunity_supersedesStickyCompletion() {
        val completed = CodingPlanQuotaResetState.applyResetStatus(
            null, status(latestFiveHourUsedAt = usedAt3hAgo, hasUnread = true), now,
        )!!
        // same usedAt (周期未变) + a NEW opportunity granted → back to AVAILABLE
        // ("后端可能在同一重置周期内再次发放机会；若完成态继续优先，新机会会被永久吞掉")
        val next = CodingPlanQuotaResetState.applyResetStatus(
            completed, status(fiveHour = listOf(expires1h), latestFiveHourUsedAt = usedAt3hAgo), now,
        )!!
        assertEquals(ResetUiStatus.AVAILABLE, next.status)
        assertEquals(1, next.opportunityCount)
    }

    @Test
    fun doneDisplayWindow_matchesDesktopConstant() {
        assertEquals(2_600L, CodingPlanQuotaResetState.DONE_DISPLAY_MS)
        assertTrue(CodingPlanQuotaResetState.FIVE_HOURS_MS == 5 * 3_600_000L)
        assertTrue(CodingPlanQuotaResetState.WEEK_MS == 7 * 24 * 3_600_000L)
    }
}
