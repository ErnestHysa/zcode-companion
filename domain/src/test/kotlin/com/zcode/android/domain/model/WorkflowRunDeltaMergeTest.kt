package com.zcode.android.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * G51 (vc52 Phase 11) consumer-half pins: the step-graph fold (WorkflowRunGraph.
 * applyDelta over upserts + removals), the plane fold (deltasSeen/count/tail), the
 * summary merge (the shared faces mapped, summary-only faces kept, `cleared`
 * un-sets) and the step decode over BOTH desktop schema generations (the pass-26
 * census shape `99e280fb` and the current `12a2fc93` shape — INV-8 tolerance).
 */
class WorkflowRunDeltaMergeTest {

    private fun site(s: String, o: Long) = WorkflowRunDelta.RunSite(s, o)

    private fun step(siteId: String, ordinal: Long, mutate: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit = {}): WorkflowRunStep {
        val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "siteId" to kotlinx.serialization.json.JsonPrimitive(siteId),
            "ordinal" to kotlinx.serialization.json.JsonPrimitive(ordinal),
        )
        mutate(map)
        return WorkflowRunStep.decode(JsonObject(map))!!
    }

    private fun updated(
        runId: String,
        run: JsonObject? = null,
        cleared: List<String> = emptyList(),
        removedActors: List<WorkflowRunDelta.RunSite> = emptyList(),
        removedNodes: List<WorkflowRunDelta.RunSite> = emptyList(),
        actors: List<WorkflowRunStep> = emptyList(),
        nodes: List<WorkflowRunStep> = emptyList(),
    ) = WorkflowRunDelta.Updated(runId, 1, run, cleared, removedActors, removedNodes, actors, nodes, JsonObject(emptyMap()))

    private fun summary(
        runId: String,
        status: WorkflowRunStatus,
        stopReason: String? = null,
        failureMessage: String? = null,
        label: String? = runId,
        updatedAtMs: Long? = 1,
    ) = WorkflowRunSummary(
        runId = runId, label = label, status = status, statusRaw = status.wire, stopReason = stopReason,
        toolCallId = null, updatedAtMs = updatedAtMs, resumedFrom = null, supersededBy = null,
        failureCode = null, failureMessage = failureMessage, resumable = false,
    )

    // ---------------------------------------------------------------- step decode: both generations

    @Test
    fun `the current-desktop step shape decodes (12a2fc93 node faces)`() {
        val raw = Json.parseToJsonElement(
            """{"siteId":"n1","ordinal":0,"kind":"world-read","phase":"settled","outcome":"ok",
                "cached":true,"actorSiteId":"a1","actorOrdinal":0,"phaseName":"lint-phase",
                "instructionsHead":"run the linter","turn":2,"toolCalls":3,
                "lastTool":{"name":"bash","target":"gradle test"}}""",
        ) as JsonObject
        val s = WorkflowRunStep.decode(raw)!!
        assertEquals("world-read", s.kind)
        assertEquals("settled", s.phase)
        assertEquals("ok", s.outcome)
        assertEquals(true, s.cached)
        assertEquals("a1", s.actorSiteId)
        assertEquals(0L, s.actorOrdinal)
        assertEquals("run the linter", s.instructionsHead)
        assertEquals(2L, s.turn)
        assertEquals(3L, s.toolCalls)
        assertEquals("bash", s.lastToolName)
        assertEquals("gradle test", s.lastToolTarget)
        assertEquals("bash → gradle test", s.headline)
        assertEquals("settled", s.stateLabel)
    }

    @Test
    fun `the pass-26 census step shape decodes (99e280fb actor faces)`() {
        val raw = Json.parseToJsonElement(
            """{"siteId":"a-old","ordinal":1,"kind":"world-run","op":"bash.run","args":[1,"two"],
                "status":"failed","summary":{"resultBytes":120,"resultCount":2,"exitCode":1,"stdoutBytes":80,"stderrBytes":40},
                "error":{"code":"exit_code","message":"non-zero"},"createdAt":1000.0,"updatedAt":2000.0}""",
        ) as JsonObject
        val s = WorkflowRunStep.decode(raw)!!
        assertEquals("world-run", s.kind)
        assertEquals("bash.run", s.op)
        assertEquals(2, s.args.size)
        assertEquals("failed", s.status)
        assertEquals(120L, s.resultBytes)
        assertEquals(2L, s.resultCount)
        assertEquals(1L, s.exitCode)
        assertEquals(80L, s.stdoutBytes)
        assertEquals(40L, s.stderrBytes)
        assertEquals("non-zero", s.errorText)
        assertEquals(1000L, s.createdAtMs)
        assertEquals(2000L, s.updatedAtMs)
        assertEquals("bash.run", s.headline)
        assertEquals("failed", s.stateLabel)
    }

    @Test
    fun `a step without siteId or ordinal is skipped (never a guessed identity)`() {
        assertNull(WorkflowRunStep.decode(JsonObject(emptyMap())))
    }

    // ---------------------------------------------------------------- graph fold

    @Test
    fun `applyDelta upserts steps removes by site key and rides the run patch`() {
        val g0 = WorkflowRunGraph(runId = "r", revision = null)
        val g1 = WorkflowRunGraph.applyDelta(
            g0,
            updated(
                "r",
                run = JsonObject(mapOf("lastEventSequence" to kotlinx.serialization.json.JsonPrimitive(9))),
                actors = listOf(step("a1", 0) { it["status"] = kotlinx.serialization.json.JsonPrimitive("running") }),
                nodes = listOf(step("n1", 0)),
            ),
        )
        assertEquals(1, g1.actors.size)
        assertEquals(1, g1.nodes.size)
        assertEquals(9L, g1.lastEventSequence)
        val g2 = WorkflowRunGraph.applyDelta(
            g1,
            updated(
                "r",
                run = JsonObject(mapOf("truncated" to kotlinx.serialization.json.JsonPrimitive(true))),
                removedActors = listOf(site("a1", 0)),
                removedNodes = listOf(site("n1", 0)),
                actors = listOf(step("a2", 1)),
            ),
        )
        assertEquals(1, g2.actors.size)
        assertEquals("a2", g2.actors.values.first().siteId)
        assertEquals(0, g2.nodes.size)
        assertTrue(g2.truncated)
        assertEquals(9L, g2.lastEventSequence, "an absent face keeps the prior value")
    }

    @Test
    fun `the same site re-upserts in place (keyed identity, never duplicated)`() {
        var g = WorkflowRunGraph(runId = "r")
        g = WorkflowRunGraph.applyDelta(g, updated("r", actors = listOf(step("a1", 0))))
        g = WorkflowRunGraph.applyDelta(g, updated("r", actors = listOf(step("a1", 0) { it["status"] = kotlinx.serialization.json.JsonPrimitive("completed") })))
        assertEquals(1, g.actors.size)
        assertEquals("completed", g.actors["a1#0"]?.status)
    }

    @Test
    fun `steps order actors then nodes by ordinal`() {
        val g = WorkflowRunGraph.applyDelta(
            WorkflowRunGraph(runId = "r"),
            updated("r", actors = listOf(step("a2", 2), step("a1", 0)), nodes = listOf(step("n1", 1), step("n0", 0))),
        )
        assertEquals(listOf("a1", "a2", "n0", "n1"), g.steps.map { it.siteId }, "actors (by ordinal) then nodes (by ordinal)")
    }

    // ---------------------------------------------------------------- plane fold

    @Test
    fun `the plane folds deltas marks deltasSeen counts and bounds the tail`() {
        var plane = WorkflowRunPlane.EMPTY
        for (i in 0 until (WorkflowRunPlane.RECENT_TAIL + 5)) {
            plane = WorkflowRunPlane.apply(plane, updated("r$i"))
        }
        assertEquals((WorkflowRunPlane.RECENT_TAIL + 5).toLong(), plane.deltaCount)
        assertTrue(plane.deltasSeen)
        assertEquals(WorkflowRunPlane.RECENT_TAIL, plane.recentDeltas.size, "the tail is bounded")
        assertEquals("r${WorkflowRunPlane.RECENT_TAIL + 4}", plane.recentDeltas.last().let { (it as WorkflowRunDelta.Updated).runId }, "the tail keeps the NEWEST ops")
        assertEquals((WorkflowRunPlane.RECENT_TAIL + 5), plane.graphs.size, "graphs accumulate beyond the tail")
        val planeAfterRemove = WorkflowRunPlane.apply(
            plane,
            WorkflowRunDelta.Removed("r0", 1, JsonObject(emptyMap())),
        )
        assertNull(planeAfterRemove.graphs["r0"])
    }

    // ---------------------------------------------------------------- summary merge

    @Test
    fun `the merge maps the shared faces and keeps summary-only faces`() {
        val runs = listOf(
            summary("r1", WorkflowRunStatus.RUNNING, stopReason = null, failureMessage = null, label = "lint-all", updatedAtMs = 42),
            summary("r2", WorkflowRunStatus.COMPLETED),
        )
        val patch = JsonObject(mapOf(
            "runId" to kotlinx.serialization.json.JsonPrimitive("r1"),
            "status" to kotlinx.serialization.json.JsonPrimitive("errored"),
            "stopReason" to kotlinx.serialization.json.JsonPrimitive("provider"),
            "error" to kotlinx.serialization.json.JsonPrimitive("boom"),
            "resumable" to kotlinx.serialization.json.JsonPrimitive(true),
        ))
        val merged = applyWorkflowRunDeltaToSummaries(runs, updated("r1", run = patch))!!
        val r1 = merged.first { it.runId == "r1" }
        assertEquals(WorkflowRunStatus.ERRORED, r1.status)
        assertEquals("errored", r1.statusRaw)
        assertEquals("provider", r1.stopReason)
        assertEquals("boom", r1.failureMessage, "the run-patch error maps to failureMessage")
        assertTrue(r1.resumable)
        assertEquals("lint-all", r1.label, "summary-only label kept")
        assertEquals(42L, r1.updatedAtMs, "summary-only updatedAt kept")
        assertEquals(2, merged.size, "r2 untouched")
        assertEquals(WorkflowRunStatus.COMPLETED, merged.first { it.runId == "r2" }.status)
    }

    @Test
    fun `an unknown runId appends a minimal summary (the delta can pre-seed a run)`() {
        val patch = JsonObject(mapOf(
            "status" to kotlinx.serialization.json.JsonPrimitive("running"),
        ))
        val merged = applyWorkflowRunDeltaToSummaries(emptyList(), updated("new-run", run = patch))!!
        assertEquals(1, merged.size)
        val added = merged.first()
        assertEquals("new-run", added.runId)
        assertEquals(WorkflowRunStatus.RUNNING, added.status)
        assertNull(added.label)
    }

    @Test
    fun `cleared un-sets the mapped faces`() {
        val runs = listOf(summary("r1", WorkflowRunStatus.STOPPED, stopReason = "user", failureMessage = "x"))
        val merged = applyWorkflowRunDeltaToSummaries(
            runs,
            updated("r1", run = JsonObject(emptyMap()), cleared = listOf("stopReason", "error")),
        )!!
        val r1 = merged.first { it.runId == "r1" }
        assertNull(r1.stopReason, "cleared stopReason un-set")
        assertNull(r1.failureMessage, "cleared error un-sets failureMessage")
    }

    @Test
    fun `removed drops the run`() {
        val runs = listOf(summary("r1", WorkflowRunStatus.RUNNING), summary("r2", WorkflowRunStatus.PENDING))
        val merged = applyWorkflowRunDeltaToSummaries(runs, WorkflowRunDelta.Removed("r1", null, JsonObject(emptyMap())))!!
        assertEquals(listOf("r2"), merged.map { it.runId })
    }

    @Test
    fun `a null run list stays null (the panel-hides face)`() {
        assertNull(applyWorkflowRunDeltaToSummaries(null, updated("r1")))
    }
}
