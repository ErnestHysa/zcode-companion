package com.zcode.android.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.zcode.android.domain.model.TrajectoryRecord

/**
 * N-BK-1(b) (vc52 Phase 12) pin: the trajectory record's `response.usage` decodes
 * (the desktop `bce` response schema — every field optional); absent usage → null
 * (the rows render nothing, never a 0).
 */
class TrajectoryUsageDecodeTest {

    @Test
    fun `the usage object decodes all six columns`() {
        val rec = TrajectoryRecord.decode(
            Json.parseToJsonElement("""
                {"requestId":"r1","startedAt":"t","response":{
                   "finishReason":"stop",
                   "usage":{"inputTokens":151,"outputTokens":21,"totalTokens":184,
                            "cacheReadTokens":48,"cacheWriteTokens":2,"reasoningTokens":6}
                }}
            """.trimIndent()).jsonObject,
        )!!
        val u = assertNotNull(rec.usage)
        assertEquals(151L, u.inputTokens)
        assertEquals(21L, u.outputTokens)
        assertEquals(184L, u.totalTokens)
        assertEquals(48L, u.cacheReadTokens)
        assertEquals(2L, u.cacheWriteTokens)
        assertEquals(6L, u.reasoningTokens)
    }

    @Test
    fun `absent usage stays null (the row renders nothing)`() {
        val rec = TrajectoryRecord.decode(
            Json.parseToJsonElement("""{"requestId":"r2","startedAt":"t","response":{"finishReason":"stop"}}""").jsonObject,
        )!!
        assertNull(rec.usage)
    }

    @Test
    fun `a partial usage object keeps only the carried fields`() {
        val rec = TrajectoryRecord.decode(
            Json.parseToJsonElement("""
                {"requestId":"r3","startedAt":"t","response":{"usage":{"totalTokens":99}}}
            """.trimIndent()).jsonObject,
        )!!
        val u = assertNotNull(rec.usage)
        assertEquals(99L, u.totalTokens)
        assertNull(u.inputTokens)
        assertNull(u.reasoningTokens)
    }
}
