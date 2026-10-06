package com.zcode.android.core.ui.components

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GAP-13 shape fidelity: the label decision table is the desktop's
 * formatMessageTimeLabel (packages/ui/src/v4/messageTimeLabel.ts @ 29628c9) —
 * same-day → time-only, yesterday → "<word> <time>", same-year → "<Mon d> <time>",
 * prior-year → "<yyyy Mon d> <time>", invalid → null. Pinned with a fixed zone and
 * locale so the table cannot drift with the machine.
 */
class MessageTimeLabelTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val locale: Locale = Locale.US

    /** 2026-10-06 14:05 in America/New_York. */
    private val now: Long =
        ZonedDateTime.of(2026, 10, 6, 14, 5, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Double =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli().toDouble()

    @Test
    fun sameDay_rendersTimeOnly() {
        val label = messageTimeLabel(at(2026, 10, 6, 13, 5), now, zone, locale)
        assertEquals("1:05 PM", label)
    }

    @Test
    fun yesterday_rendersWordThenTime() {
        val label = messageTimeLabel(at(2026, 10, 5, 14, 5), now, zone, locale)
        assertEquals("Yesterday 2:05 PM", label)
    }

    @Test
    fun yesterday_resourceWordIsPrepended_verbatim() {
        // The word is resolved upstream from resources (§31); the core only composes
        // "<word> <time>" — pinned with a non-dictionary word so no CLDR drift.
        val label = messageTimeLabel(at(2026, 10, 5, 14, 5), now, zone, locale, yesterdayWord = "昨天")
        assertEquals("昨天 2:05 PM", label)
    }

    @Test
    fun sameYear_rendersMonthDayTime() {
        val label = messageTimeLabel(at(2026, 3, 3, 9, 8), now, zone, locale)
        assertEquals("Mar 3 9:08 AM", label)
    }

    @Test
    fun priorYear_rendersYearMonthDayTime() {
        val label = messageTimeLabel(at(2024, 12, 31, 23, 59), now, zone, locale)
        assertEquals("2024 Dec 31 11:59 PM", label)
    }

    @Test
    fun justBeforeMidnight_yesterdayWord() {
        val nowLate = ZonedDateTime.of(2026, 10, 6, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        val label = messageTimeLabel(at(2026, 10, 5, 23, 45), nowLate, zone, locale)
        assertEquals("Yesterday 11:45 PM", label)
    }

    @Test
    fun invalidTimestamps_renderNull() {
        assertNull(messageTimeLabel(0.0, now, zone, locale))
        assertNull(messageTimeLabel(-42.0, now, zone, locale))
        assertNull(messageTimeLabel(Double.NaN, now, zone, locale))
        assertNull(messageTimeLabel(Double.POSITIVE_INFINITY, now, zone, locale))
    }
}
