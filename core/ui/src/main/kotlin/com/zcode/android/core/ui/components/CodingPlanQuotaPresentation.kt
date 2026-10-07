package com.zcode.android.core.ui.components

import com.zcode.android.domain.model.UsageQuotaLimit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GAP-3 (android-native-gaps.md; docs/protocol/v4-wire.md §13.1) — the desktop
 * `packages/ui/src/lib/codingPlanQuotaPresentation.ts` decision table, ported
 * verbatim. Every constant and branch below cites its desktop line.
 */
object CodingPlanQuotaPresentation {

    /**
     * Token/Credit quota categories (lib lines 16-33): zai's Team-Plan backend
     * emits `CREDIT_LIMIT`, bigmodel's `TOKENS_LIMIT` — identical unit/number
     * semantics (unit=3,number=5 → five-hour window; unit=6 → weekly), so both
     * type faces answer the same query.
     */
    val TOKEN_LIMIT_TYPES: Set<String> = setOf("TOKENS_LIMIT", "CREDIT_LIMIT")

    /** Tool quota category (lib lines 34-36): TIME_LIMIT = monthly tool calls. */
    val TOOL_LIMIT_TYPES: Set<String> = setOf("TIME_LIMIT")

    /** Desktop `isSameLimitCategory` (lib lines 38-53) — set membership per category. */
    private fun isSameLimitCategory(limitType: String, queryTypes: Set<String>): Boolean =
        if (queryTypes.contains("TIME_LIMIT")) {
            TOOL_LIMIT_TYPES.contains(limitType)
        } else {
            TOKEN_LIMIT_TYPES.contains(limitType)
        }

    /**
     * Desktop `findCodingPlanQuotaLimit` (lib lines 55-65): first limit matching
     * category + exact `unit` (+ exact `number` when the query carries one), else
     * null. Note `MCP_USAGE_LIMIT` matches NO query category (its type lives in
     * neither set — the MCP aggregate is a separate entitlement field).
     */
    fun findQuotaLimit(
        limits: List<UsageQuotaLimit>?,
        queryTypes: Set<String>,
        unit: Double,
        number: Double?,
    ): UsageQuotaLimit? =
        limits?.firstOrNull { limit ->
            isSameLimitCategory(limit.type, queryTypes) &&
                limit.unit == unit &&
                (number == null || limit.number == number)
        }

    /**
     * Desktop `getQuotaRemainingPercentage` (lib lines 78-89): the wire's
     * `percentage` is the USED share; the UI expresses "how much is left" —
     * invert + clamp to 0..100; null when absent/non-finite (never render a
     * fake meter).
     */
    fun quotaRemainingPercentage(limit: UsageQuotaLimit?): Double? {
        val pct = limit?.percentage ?: return null
        if (!pct.isFinite()) return null
        return (100.0 - pct).coerceIn(0.0, 100.0)
    }

    /**
     * Desktop `isCodingPlanQuotaLimitFull` (lib lines 92-99): remaining exactly
     * 100 (nothing consumed) → reset has no value → the affordance hides.
     */
    fun isQuotaLimitFull(limit: UsageQuotaLimit?): Boolean =
        quotaRemainingPercentage(limit) == 100.0

    /**
     * Desktop `formatQuotaRemainingPercentage` (lib lines 101-113): "--" when
     * remaining is null; Intl.NumberFormat(maximumFractionDigits = remaining >=
     * 10 ? 0 : 1) + "%".
     */
    fun formatQuotaRemainingPercentage(remaining: Double?, locale: Locale): String {
        if (remaining == null) return "--"
        val format = java.text.NumberFormat.getNumberInstance(locale)
        format.maximumFractionDigits = if (remaining >= 10.0) 0 else 1
        format.minimumFractionDigits = 0
        return format.format(remaining) + "%"
    }

    /**
     * Desktop `formatContextFiveHourResetTime` (CodingPlanContextUsage.tsx:69-84):
     * HH:mm (h23) ALWAYS — the five-hour window can roll to another day and the
     * time of day is still the actionable part. (A Long epoch is always a valid
     * instant — the desktop's NaN guard has no Kotlin failure mode; null/0 → null.)
     */
    fun formatFiveHourResetTime(value: Long?, nowMs: Long, zone: ZoneId, locale: Locale): String? {
        if (value == null || value == 0L) return null
        val resetAt = Instant.ofEpochMilli(value).atZone(zone)
        return DateTimeFormatter.ofPattern("HH:mm", locale).format(resetAt)
    }

    /**
     * Desktop `formatQuotaResetTime` format "date" (lib lines 117-131): "MMM d".
     * (The desktop's `adaptive` face — today → HH:mm, else date — serves Start
     * Plan buckets; the composer quota meters use "date" for weekly/tools/MCP.)
     */
    fun formatQuotaResetTime(value: Long?, nowMs: Long, zone: ZoneId, locale: Locale): String? {
        if (value == null || value == 0L) return null
        val resetAt = Instant.ofEpochMilli(value).atZone(zone)
        return DateTimeFormatter.ofPattern("MMM d", locale).format(resetAt)
    }
}
