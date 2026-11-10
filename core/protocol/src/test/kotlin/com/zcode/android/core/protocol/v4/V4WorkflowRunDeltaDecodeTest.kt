package com.zcode.android.core.protocol.v4

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * G51 (vc52 Phase 11) op-decode pins: every new op of the declared workflow-run
 * delta family decodes typed — `workflowRun.updated` (run patch + cleared +
 * removedActors/removedNodes + actor/node step graph, the byte-verified shipped
 * asar `12a2fc93…` discriminatedUnion) and `workflowRun.removed` — and NEVER lands
 * as Unknown (INV-8: unknown ops are counted, known ops are typed). The step
 * objects stay raw at the protocol layer (the domain types the faces).
 */
class V4WorkflowRunDeltaDecodeTest {

    @Test
    fun `workflowRun updated decodes runId revision run cleared removals and step arrays`() {
        val raw = """
            {"op":"workflowRun.updated","runId":"wf-run-0","revision":7,
             "run":{"runId":"wf-run-0","status":"completed","lastEventSequence":4},
             "cleared":["stopReason","failureCode"],
             "removedActors":[{"siteId":"a-old","ordinal":2}],
             "removedNodes":[{"siteId":"n-old","ordinal":1}],
             "actors":[{"siteId":"a1","ordinal":0,"name":"lint","status":"completed"}],
             "nodes":[{"siteId":"n1","ordinal":0,"phase":"settled","outcome":"ok"}]}
        """.trimIndent()
        val d = ConversationDelta.decode(Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject)
        val updated = assertIs<ConversationDelta.WorkflowRunUpdated>(d)
        assertEquals("wf-run-0", updated.runId)
        assertEquals(7L, updated.revision)
        assertEquals("completed", (updated.run?.get("status") as JsonPrimitive).content)
        assertEquals(listOf("stopReason", "failureCode"), updated.cleared)
        assertEquals(1, updated.removedActors.size)
        assertEquals("a-old", updated.removedActors[0].siteId)
        assertEquals(2L, updated.removedActors[0].ordinal)
        assertEquals(1, updated.removedNodes.size)
        assertEquals(1, updated.actors?.size)
        assertEquals(1, updated.nodes?.size)
    }

    @Test
    fun `workflowRun removed decodes runId and revision`() {
        val raw = """{"op":"workflowRun.removed","runId":"wf-run-9","revision":3}"""
        val d = ConversationDelta.decode(Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject)
        val removed = assertIs<ConversationDelta.WorkflowRunRemoved>(d)
        assertEquals("wf-run-9", removed.runId)
        assertEquals(3L, removed.revision)
    }

    @Test
    fun `optional faces are tolerated absent`() {
        val raw = """{"op":"workflowRun.updated","runId":"wf-x"}"""
        val d = ConversationDelta.decode(Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject)
        val updated = assertIs<ConversationDelta.WorkflowRunUpdated>(d)
        assertNull(updated.revision)
        assertNull(updated.run)
        assertEquals(emptyList(), updated.cleared)
        assertEquals(emptyList(), updated.removedActors)
        assertNull(updated.actors)
        assertNull(updated.nodes)
    }

    @Test
    fun `a missing runId fails the op decode (fail closed, never a guessed identity)`() {
        val raw = """{"op":"workflowRun.updated","revision":1}"""
        val threw = runCatching {
            ConversationDelta.decode(Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject)
        }
        assertTrue(threw.isFailure, "runId is required by the shipped schema (strict)")
    }

    @Test
    fun `unknown ops still land as Unknown and are counted (INV-8 intact)`() {
        val before = ConversationDelta.unknownOpCount
        val raw = """{"op":"pluginUi.resourceUpdated","pluginId":"p","serverName":"s","uri":"u","subscribers":[]}"""
        val d = ConversationDelta.decode(Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject)
        val unknown = assertIs<ConversationDelta.Unknown>(d)
        assertEquals("pluginUi.resourceUpdated", unknown.op)
        assertEquals(before + 1, ConversationDelta.unknownOpCount)
    }

    @Test
    fun `the verified five-op base set still decodes (no regression)`() {
        val row = """{"rowId":1,"kind":"userInput","createdAt":1.0,"createdAtSeq":1}"""
        val cases = listOf(
            """{"op":"row.appended","row":$row}""",
            """{"op":"row.upserted","row":$row}""",
            """{"op":"row.removed","fromRowId":5}""",
            """{"op":"row.delta","rowId":5,"path":"text","append":"x"}""",
            """{"op":"state.updated","patch":{"revision":2}}""",
        )
        val kinds = cases.map { ConversationDelta.decode(Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject)::class.simpleName }
        assertEquals(
            listOf("RowAppended", "RowUpserted", "RowRemoved", "RowDelta", "StateUpdated"),
            kinds,
        )
    }
}
