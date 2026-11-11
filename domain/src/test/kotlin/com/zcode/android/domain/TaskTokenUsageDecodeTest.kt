package com.zcode.android.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.zcode.android.domain.model.TaskTokenUsage

/**
 * G60 (vc52 Phase 12) pin: the per-task token usage decode (`getTaskTokenUsage`,
 * the v4/conversation/usage response `U2`, .strict(), fresh asar 4a6080ed…) —
 * tolerant on missing numeric keys (0), fail-closed on a missing sessionId.
 */
class TaskTokenUsageDecodeTest {

    private fun json() = """
        {"sessionId":"sess-1","totalTokens":184320,"inputTokens":151000,
         "outputTokens":21000,"reasoningTokens":6400,"cacheCreationTokens":2800,
         "cacheReadTokens":48600,"modelRequestCount":37,"modelErrorCount":1}
    """.trimIndent()

    @Test
    fun `the full payload decodes every field`() {
        val u = TaskTokenUsage.decode(Json.parseToJsonElement(json()).jsonObject)!!
        assertEquals("sess-1", u.sessionId)
        assertEquals(184_320L, u.totalTokens)
        assertEquals(151_000L, u.inputTokens)
        assertEquals(21_000L, u.outputTokens)
        assertEquals(6_400L, u.reasoningTokens)
        assertEquals(2_800L, u.cacheCreationTokens)
        assertEquals(48_600L, u.cacheReadTokens)
        assertEquals(37L, u.modelRequestCount)
        assertEquals(1L, u.modelErrorCount)
    }

    @Test
    fun `a missing numeric key decodes 0 (tolerant, never a wrong total)`() {
        val u = TaskTokenUsage.decode(
            Json.parseToJsonElement("""{"sessionId":"s","totalTokens":5}""").jsonObject,
        )!!
        assertEquals(5L, u.totalTokens)
        assertEquals(0L, u.inputTokens)
        assertEquals(0L, u.modelErrorCount)
    }

    @Test
    fun `a missing sessionId fails closed (null)`() {
        assertNull(TaskTokenUsage.decode(Json.parseToJsonElement("""{"totalTokens":5}""").jsonObject))
    }
}
