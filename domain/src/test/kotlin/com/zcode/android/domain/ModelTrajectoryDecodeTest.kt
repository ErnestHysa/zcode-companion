package com.zcode.android.domain

import com.zcode.android.domain.model.TrajectoryResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A3.13 (Phase 5B) decode pins over the mapRecord shape: kind/model/duration/finish/error
 * + the tool-name list; records without requestId/startedAt drop; the result carries
 * sourceFiles + truncated.
 */
class ModelTrajectoryDecodeTest {

    @Test
    fun `trajectory records decode and drop incomplete entries`() {
        val result = TrajectoryResult.decode(
            Json.parseToJsonElement(
                """{"taskId":"t1","available":true,"truncated":true,
                  "sourceFiles":["model-io-t1.jsonl"],
                  "records":[
                    {"requestId":"r1","attempt":2,"startedAt":"2026-10-20T10:00:00Z","durationMs":4200,
                     "callSource":{"kind":"subagent"},"model":{"modelId":"glm-5.3","providerId":"zai"},
                     "response":{"finishReason":"tool_calls","text":"Checking state first","toolCalls":[{"name":"bash"},{"name":"read"}]}},
                    {"requestId":"r2","startedAt":"2026-10-20T10:01:00Z",
                     "error":{"name":"RateLimitError","message":"429"}},
                    {"attempt":1}
                  ]}""",
            ).jsonObject,
        )
        assertTrue(result.truncated)
        assertEquals(listOf("model-io-t1.jsonl"), result.sourceFiles)
        assertEquals(2, result.records.size)
        val r1 = result.records[0]
        assertEquals("r1", r1.requestId)
        assertEquals(2, r1.attempt)
        assertEquals("subagent", r1.callKind)
        assertEquals("glm-5.3", r1.modelId)
        assertEquals("tool_calls", r1.finishReason)
        assertEquals("Checking state first", r1.textHead)
        assertEquals(listOf("bash", "read"), r1.toolNames)
        assertEquals(null, r1.error)
        val r2 = result.records[1]
        assertEquals("RateLimitError: 429", r2.error)
        assertTrue(r2.toolNames.isEmpty())
    }
}
