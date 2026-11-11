package com.zcode.android.features.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.features.conversation.ui.TimelineEntry
import com.zcode.android.features.conversation.ui.groupTimeline

/**
 * N-BK-1(d) (vc52 Phase 12) pin: the task timeline's DAY grouping — a header
 * between calendar days (Today / Yesterday / the date), keyed by epochDay so
 * LazyColumn identity is stable; rows without createdAt never break the chain;
 * the tool grouping behavior is unchanged.
 */
class TimelineDayGroupingTest {

    private fun row(id: Long, createdAt: Double) = ConversationRow.Decoder.decode(
        Json.parseToJsonElement("""
            {"rowId":$id,"turnId":"t1","kind":"assistantText","entityId":"e$id",
             "createdAt":$createdAt,"createdAtSeq":$id,"text":"row $id","state":"complete"}
        """.trimIndent()).jsonObject,
    )

    private val day0 = 1_700_000_000_000.0 // 2023-11-14T22:13Z
    private val day1 = day0 + 86_400_000.0
    private val day2 = day0 + 2 * 86_400_000.0

    @Test
    fun `a day boundary emits exactly one header per calendar day`() {
        val rows = listOf(row(1, day0), row(2, day0 + 1000), row(3, day1), row(4, day2))
        val entries = groupTimeline(rows, nowMs = day2.toLong())
        val headers = entries.filterIsInstance<TimelineEntry.DayHeader>()
        assertEquals(3, headers.size, "one header per distinct day")
        assertEquals(listOf(day0.toLong() / 86_400_000L, day1.toLong() / 86_400_000L, day2.toLong() / 86_400_000L), headers.map { it.epochDay })
        // The LAST day is "Today" (nowMs = day2).
        assertEquals("Today", headers.last().label)
    }

    @Test
    fun `yesterday and older render their labels`() {
        val entries = groupTimeline(listOf(row(1, day0), row(2, day1)), nowMs = day1.toLong())
        val headers = entries.filterIsInstance<TimelineEntry.DayHeader>()
        assertEquals("Yesterday", headers[0].label)
        assertEquals("Today", headers[1].label)
    }

    @Test
    fun `same-day rows emit exactly one header (no boundary churn)`() {
        val entries = groupTimeline(listOf(row(5, day0 + 1), row(6, day0 + 2), row(7, day0 + 3)), nowMs = day0.toLong())
        val headers = entries.filterIsInstance<TimelineEntry.DayHeader>()
        assertEquals(1, headers.size, "same calendar day = ONE header")
        assertEquals(3, entries.filterIsInstance<TimelineEntry.Single>().size, "every row still renders")
    }

    @Test
    fun `day keys are distinct from row keys (LazyColumn identity)`() {
        val entries = groupTimeline(listOf(row(1, day0)), nowMs = day0.toLong())
        val header = entries.filterIsInstance<TimelineEntry.DayHeader>().single()
        assertTrue(header.key != entries.filterIsInstance<TimelineEntry.Single>().single().key)
    }
}
