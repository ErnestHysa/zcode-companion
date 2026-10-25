package com.zcode.android.domain

import com.zcode.android.domain.model.SavedWorkflow
import com.zcode.android.domain.model.SavedWorkflowList
import com.zcode.android.domain.model.SavedWorkflowRun
import com.zcode.android.domain.model.StartSavedWorkflowErrors
import com.zcode.android.domain.model.WorkflowRunEventsPage
import com.zcode.android.domain.model.WorkflowRunStatus
import com.zcode.android.domain.model.WorkflowRunSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3.3 + L6.4 (Phase 5A) decode pins over the bundle-extracted shapes: the run list item
 * (`Br`), the run events page (`Rl`), the saved-workflow hub list (`BJ`/`nz` with typed
 * args), the saved runs (`cz`), and the start error vocabulary (`Bu`). Unknown statuses
 * classify UNKNOWN with the raw retained; a run without a runId is dropped.
 */
class WorkflowModelsDecodeTest {

    @Test
    fun `run summaries decode the Br shape and classify live states`() {
        val runs = WorkflowRunSummary.decodeList(
            Json.parseToJsonElement(
                """{"runs":[
                  {"runId":"run-1","label":"Nightly review","status":"running","updatedAt":1700000000000,"resumable":false,"toolCallId":"tc1"},
                  {"runId":"run-2","status":"errored","failureCode":"compile_failed","failureMessage":"syntax","resumable":true},
                  {"runId":"run-3","status":"paused-ish","resumable":false},
                  {"status":"running"}
                ]}""",
            ),
        )
        assertEquals(3, runs.size)
        assertEquals(WorkflowRunStatus.RUNNING, runs[0].status)
        assertTrue(runs[0].status.isLive)
        assertEquals("Nightly review", runs[0].label)
        assertEquals(1700000000000, runs[0].updatedAtMs)
        assertEquals(WorkflowRunStatus.ERRORED, runs[1].status)
        assertEquals("compile_failed", runs[1].failureCode)
        assertTrue(runs[1].resumable)
        assertEquals(WorkflowRunStatus.UNKNOWN, runs[2].status)
        assertEquals("paused-ish", runs[2].statusRaw, "unknown statuses keep the raw")
        assertFalse(runs[2].status.isLive)
    }

    @Test
    fun `run events page decodes sequence type payload and hasMore`() {
        val page = WorkflowRunEventsPage.decode(
            Json.parseToJsonElement(
                """{"events":[
                  {"sequence":1,"type":"node.dispatched","payload":{"siteId":"s1","ordinal":0,"message":"dispatch worker"}},
                  {"sequence":2,"type":"run.completed","payload":{},"truncated":true},
                  {"type":"no-seq"}
                ],"hasMore":true}""",
            ).jsonObject,
        )
        assertEquals(2, page.events.size)
        assertEquals("dispatch worker", page.events[0].headline)
        assertEquals("run.completed", page.events[1].headline, "a payload without text falls back to the type")
        assertTrue(page.events[1].truncated)
        assertTrue(page.hasMore)
    }

    @Test
    fun `saved workflow list decodes typed args required flags invalid files and dir`() {
        val list = SavedWorkflowList.decode(
            Json.parseToJsonElement(
                """{"workflows":[
                  {"name":"release-notes","description":"Draft notes","whenToUse":"before a tag","scope":"project","path":"/ws/.zcode/workflows/release-notes.ts",
                   "args":{"version":{"type":"string","required":true,"description":"semver"},"dryRun":{"type":"boolean","default":false}}},
                  {"name":"lint-all","description":"","scope":"global","path":"/g/lint-all.ts","argNames":["target"]}
                ],"invalid":[{"path":"/ws/.zcode/workflows/broken.ts","reason":"parse error"}],"dir":"/ws/.zcode/workflows"}""",
            ).jsonObject,
        )
        assertEquals(2, list.workflows.size)
        val rn = list.workflows[0]
        assertEquals(listOf("version"), rn.requiredArgs.map { it.name })
        assertEquals("boolean", rn.args.first { it.name == "dryRun" }.type)
        assertEquals("false", rn.args.first { it.name == "dryRun" }.default.toString())
        assertEquals("global", list.workflows[1].scope)
        assertEquals(listOf("target"), list.workflows[1].args.map { it.name }, "argNames fallback (the saved_workflow_list tool shape)")
        assertEquals(listOf("/ws/.zcode/workflows/broken.ts" to "parse error"), list.invalid)
        assertEquals("/ws/.zcode/workflows", list.dir)
        assertNull(SavedWorkflow.decode(Json.parseToJsonElement("""{"description":"no name"}""").jsonObject))
    }

    @Test
    fun `saved runs decode cz with status stopReason tokens and parent session`() {
        val runs = SavedWorkflowRun.decodeList(
            Json.parseToJsonElement(
                """{"runs":[{"runId":"r1","name":"release-notes","status":"stopped","stopReason":"user","createdAt":1,"updatedAt":2,"spentTokens":1234,"parentSessionId":"sess-1","cwd":"/ws"}],"truncated":true}""",
            ).jsonObject,
        )
        val r = runs.single()
        assertEquals(WorkflowRunStatus.STOPPED, r.status)
        assertEquals("user", r.stopReason)
        assertEquals(1234, r.spentTokens)
        assertEquals("sess-1", r.parentSessionId)
    }

    @Test
    fun `start error vocabulary is the desktop's six codes`() {
        assertEquals(setOf("invalid_name", "not_found", "invalid_args", "compile_failed", "session_busy", "start_failed"), StartSavedWorkflowErrors.ALL)
    }
}
