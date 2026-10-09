package com.zcode.android.core.ui.components

import com.zcode.android.domain.model.UsageQuotaLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * GAP-3 (the project roadmap; docs/protocol/v4-wire.md §13.1) — the desktop
 * `codingPlanQuotaPresentation.ts` decision table, pinned VERBATIM:
 *
 * - TOKENS_LIMIT ≡ CREDIT_LIMIT are the SAME category (zai team backends emit
 *   CREDIT_LIMIT with identical unit/number semantics — lib lines 16-33);
 *   TIME_LIMIT is the tool category; MCP_USAGE_LIMIT matches NEITHER.
 * - Meter lookups are EXACT: 5h = (token category, unit 3, number 5),
 *   weekly = (token category, unit 6, no number), tool-calls =
 *   (tool category, unit 5, number 1). A unit-9 limit matches nothing.
 * - `percentage` is the USED share: remaining = clamp(100 − percentage, 0, 100),
 *   null when absent/non-finite; full ⇔ remaining = 100.
 * - Value label: "--" when remaining is null; max 0 fraction digits ≥10,
 *   1 fraction digit <10 (Intl.NumberFormat parity).
 * - Reset-time labels: five-hour = HH:mm (h23) always
 *   (formatContextFiveHourResetTime); weekly/tools/MCP = "MMM d" (date format).
 */
class CodingPlanQuotaPresentationTest {

    private val utc = ZoneOffset.UTC
    private val us = Locale.US

    private fun limit(
        type: String,
        unit: Double? = null,
        number: Double? = null,
        percentage: Double? = null,
        nextResetTime: Long? = null,
    ) = UsageQuotaLimit(
        type = type,
        unit = unit,
        number = number,
        usage = null,
        remaining = null,
        percentage = percentage,
        nextResetTime = nextResetTime,
        usageDetails = emptyList(),
    )

    // ---- category equivalence + exact lookups --------------------------------

    @Test
    fun fiveHourLookup_matchesTokenAndCredit_categoryWithExactUnitAndNumber() {
        val limits = listOf(
            limit("TOKENS_LIMIT", unit = 3.0, number = 5.0, percentage = 36.0),
            limit("CREDIT_LIMIT", unit = 3.0, number = 5.0, percentage = 20.0),
            limit("TOKENS_LIMIT", unit = 3.0, percentage = 99.0), // number missing
            limit("MCP_USAGE_LIMIT", unit = 3.0, number = 5.0, percentage = 1.0),
        )
        // a TOKENS_LIMIT query hits both token-type faces (number 5 = five-hour window)
        val found = CodingPlanQuotaPresentation.findQuotaLimit(
            limits, CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 3.0, 5.0,
        )
        assertEquals(36.0, found!!.percentage)
        // ...and the CREDIT_LIMIT face is reachable through the SAME query (zai parity)
        val credit = CodingPlanQuotaPresentation.findQuotaLimit(
            listOf(limits[1]), CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 3.0, 5.0,
        )
        assertEquals(20.0, credit!!.percentage)
        // MCP_USAGE_LIMIT never matches a token query (the MCP_USAGE_QUOTA_LIMIT_TYPE
        // comment: findCodingPlanQuotaLimit must NOT hit the MCP aggregate)
        assertNull(
            CodingPlanQuotaPresentation.findQuotaLimit(
                listOf(limits[3]), CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 3.0, 5.0,
            ),
        )
        // missing `number` on the limit → no match when the query carries one
        assertNull(
            CodingPlanQuotaPresentation.findQuotaLimit(
                listOf(limits[2]), CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 3.0, 5.0,
            ),
        )
    }

    @Test
    fun weeklyAndToolLookups_exactSemantics() {
        val limits = listOf(
            limit("TOKENS_LIMIT", unit = 6.0, percentage = 51.0),
            limit("TIME_LIMIT", unit = 5.0, number = 1.0, percentage = 12.0),
            limit("TIME_LIMIT", unit = 5.0, number = 2.0, percentage = 50.0),
            limit("CREDIT_LIMIT", unit = 6.0, percentage = 7.0),
            limit("TOKENS_LIMIT", unit = 9.0, percentage = 1.0),
        )
        assertEquals(
            51.0,
            CodingPlanQuotaPresentation.findQuotaLimit(
                limits, CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 6.0, null,
            )!!.percentage,
        )
        // FIRST match wins (desktop Array.find) — the credit twin (index 3, also
        // token-category unit 6) must NOT shadow the token face
        assertEquals(
            "TOKENS_LIMIT",
            CodingPlanQuotaPresentation.findQuotaLimit(
                limits, CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 6.0, null,
            )!!.type,
        )
        assertEquals(
            12.0,
            CodingPlanQuotaPresentation.findQuotaLimit(
                limits, CodingPlanQuotaPresentation.TOOL_LIMIT_TYPES, 5.0, 1.0,
            )!!.percentage,
        )
        // number 2 exists on the wire but the desktop queries number 1 — never matched
        assertNull(
            CodingPlanQuotaPresentation.findQuotaLimit(
                limits, CodingPlanQuotaPresentation.TOOL_LIMIT_TYPES, 5.0, 7.0,
            ),
        )
        // The DESKTOP's three fixed meter queries (the only consumers) never
        // return the unit-9 limit — it renders no meter (exact lookups).
        val desktopQueries = listOf(
            Triple(CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 3.0, 5.0),
            Triple(CodingPlanQuotaPresentation.TOKEN_LIMIT_TYPES, 6.0, null),
            Triple(CodingPlanQuotaPresentation.TOOL_LIMIT_TYPES, 5.0, 1.0),
        )
        desktopQueries.forEach { (types, unit, number) ->
            val found = CodingPlanQuotaPresentation.findQuotaLimit(limits, types, unit, number)
            assertTrue(
                "query $unit/$number must never return the unit-9 limit (got $found)",
                found == null || found.unit != 9.0,
            )
        }
    }

    // ---- remaining-percentage gate -------------------------------------------

    @Test
    fun remainingPercentage_invertsUsedShare_andClamps() {
        assertEquals(
            64.0,
            CodingPlanQuotaPresentation.quotaRemainingPercentage(limit("TOKENS_LIMIT", percentage = 36.0)),
        )
        assertEquals(
            100.0,
            CodingPlanQuotaPresentation.quotaRemainingPercentage(limit("TOKENS_LIMIT", percentage = 0.0)),
        )
        assertEquals(
            0.0,
            CodingPlanQuotaPresentation.quotaRemainingPercentage(limit("TOKENS_LIMIT", percentage = 100.0)),
        )
        // desktop Math.max(0, Math.min(100, 100 - pct)) — out-of-range server values clamp
        assertEquals(
            100.0,
            CodingPlanQuotaPresentation.quotaRemainingPercentage(limit("TOKENS_LIMIT", percentage = -5.0)),
        )
        assertEquals(
            0.0,
            CodingPlanQuotaPresentation.quotaRemainingPercentage(limit("TOKENS_LIMIT", percentage = 144.0)),
        )
        // absent / non-finite → null (the "never render a fake meter" gate)
        assertNull(CodingPlanQuotaPresentation.quotaRemainingPercentage(limit("TOKENS_LIMIT")))
        assertNull(
            CodingPlanQuotaPresentation.quotaRemainingPercentage(limit("TOKENS_LIMIT", percentage = Double.NaN)),
        )
        assertNull(CodingPlanQuotaPresentation.quotaRemainingPercentage(null))
    }

    @Test
    fun quotaFull_onlyWhenRemainingIsExactly100() {
        assertTrue(CodingPlanQuotaPresentation.isQuotaLimitFull(limit("TOKENS_LIMIT", percentage = 0.0)))
        assertFalse(CodingPlanQuotaPresentation.isQuotaLimitFull(limit("TOKENS_LIMIT", percentage = 0.5)))
        // percentage ABSENT → remaining null → NOT full (reset affordance not suppressed)
        assertFalse(CodingPlanQuotaPresentation.isQuotaLimitFull(limit("TOKENS_LIMIT")))
        assertFalse(CodingPlanQuotaPresentation.isQuotaLimitFull(null))
    }

    // ---- value + reset-time labels --------------------------------------------

    @Test
    fun valueLabel_matchesDesktopFractionDigitRule() {
        assertEquals("--", CodingPlanQuotaPresentation.formatQuotaRemainingPercentage(null, us))
        assertEquals("64%", CodingPlanQuotaPresentation.formatQuotaRemainingPercentage(64.0, us))
        assertEquals("100%", CodingPlanQuotaPresentation.formatQuotaRemainingPercentage(100.0, us))
        // ≥10 → max 0 fraction digits; <10 → max 1
        assertEquals("12%", CodingPlanQuotaPresentation.formatQuotaRemainingPercentage(12.36, us))
        assertEquals("7.5%", CodingPlanQuotaPresentation.formatQuotaRemainingPercentage(7.5, us))
    }

    @Test
    fun fiveHourResetLabel_isHHmmAlways() {
        // fixture instant 1791951600000 = 2026-10-14T04:20Z (a DIFFERENT UTC day
        // than the base now) — the five-hour face shows the time of day regardless
        assertEquals(
            "04:20",
            CodingPlanQuotaPresentation.formatFiveHourResetTime(1791951600000L, 1791933600000L, utc, us),
        )
        assertNull(CodingPlanQuotaPresentation.formatFiveHourResetTime(null, 1791933600000L, utc, us))
    }

    @Test
    fun dateResetLabel_isMmmD() {
        assertEquals(
            "Oct 20",
            CodingPlanQuotaPresentation.formatQuotaResetTime(1792538400000L, 1791933600000L, utc, us),
        )
        assertEquals(
            "Nov 12",
            CodingPlanQuotaPresentation.formatQuotaResetTime(1794525600000L, 1791933600000L, utc, us),
        )
        assertNull(CodingPlanQuotaPresentation.formatQuotaResetTime(null, 1791933600000L, utc, us))
    }

    @Test
    fun instantMath_isSane() {
        // guard the fixture instants the labels above pin (UTC reads)
        assertEquals(
            "2026-10-14T04:20Z",
            Instant.ofEpochMilli(1791951600000L).toString().let { it.substring(0, 16) + "Z" },
        )
    }
}
