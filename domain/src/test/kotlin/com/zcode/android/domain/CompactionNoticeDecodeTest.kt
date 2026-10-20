package com.zcode.android.domain

import com.zcode.android.domain.model.CompactionNotice
import com.zcode.android.domain.model.ConversationRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * I2.4 (Phase 6A): the desktop's `chat.contextCompaction.*` lifecycle is carried by a
 * `timelineMarker` row whose `marker.type == "compact"` (host projectMarker:
 * `{type:"compact", origin, status, tokensBefore?, tokensAfter?}`). The renderer maps
 * status → phase: running→started, noop→skipped, cancelled→interrupted, failed→failed,
 * origin=auto→completedAuto, else completed. The phone derives the same six phases.
 */
class CompactionNoticeDecodeTest {

    private fun marker(status: String, origin: String? = "manual", extra: String = ""): ConversationRow.TimelineMarker {
        val json = """{"rowId":41,"turnId":"t1","kind":"timelineMarker","entityId":"e41","createdAt":1.0,"createdAtSeq":41,
            "lane":"turnTailBoundary",
            "marker":{"type":"compact","status":"$status"${if (origin != null) ",\"origin\":\"$origin\"" else ""}$extra}}"""
        return ConversationRow.Decoder.decode(Json.parseToJsonElement(json).jsonObject) as ConversationRow.TimelineMarker
    }

    @Test
    fun `status maps to the six desktop phases`() {
        assertEquals(CompactionNotice.Phase.STARTED, CompactionNotice.from(marker("running"))!!.phase)
        assertEquals(CompactionNotice.Phase.SKIPPED, CompactionNotice.from(marker("noop"))!!.phase)
        assertEquals(CompactionNotice.Phase.INTERRUPTED, CompactionNotice.from(marker("cancelled"))!!.phase)
        assertEquals(CompactionNotice.Phase.FAILED, CompactionNotice.from(marker("failed"))!!.phase)
        assertEquals(CompactionNotice.Phase.COMPLETED, CompactionNotice.from(marker("completed", origin = "manual"))!!.phase)
        assertEquals(CompactionNotice.Phase.COMPLETED_AUTO, CompactionNotice.from(marker("completed", origin = "auto"))!!.phase)
    }

    @Test
    fun `auto origin only promotes the terminal success - a running auto compaction is still started`() {
        assertEquals(CompactionNotice.Phase.STARTED, CompactionNotice.from(marker("running", origin = "auto"))!!.phase)
        assertEquals(CompactionNotice.Phase.FAILED, CompactionNotice.from(marker("failed", origin = "auto"))!!.phase)
    }

    @Test
    fun `token counts ride along when the host sends them`() {
        val n = CompactionNotice.from(marker("completed", extra = ",\"tokensBefore\":180000,\"tokensAfter\":42000"))!!
        assertEquals(180_000L, n.tokensBefore)
        assertEquals(42_000L, n.tokensAfter)
        assertNull(CompactionNotice.from(marker("running"))!!.tokensBefore)
    }

    @Test
    fun `non-compact markers derive nothing - the generic quiet row keeps them`() {
        val json = """{"rowId":42,"turnId":"t1","kind":"timelineMarker","entityId":"e42","createdAt":1.0,"createdAtSeq":42,
            "marker":{"type":"modelChange","toProvider":"zai","toModel":"glm-5.1"}}"""
        val row = ConversationRow.Decoder.decode(Json.parseToJsonElement(json).jsonObject) as ConversationRow.TimelineMarker
        assertNull(CompactionNotice.from(row))
    }

    @Test
    fun `an unknown status is tolerated as a plain completed notice - never a crash`() {
        assertEquals(CompactionNotice.Phase.COMPLETED, CompactionNotice.from(marker("someFutureState"))!!.phase)
        assertEquals(CompactionNotice.Phase.COMPLETED, CompactionNotice.from(marker("", origin = null))!!.phase)
    }
}
