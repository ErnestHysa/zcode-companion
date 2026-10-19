package com.zcode.android.domain

import com.zcode.android.domain.model.GoalState
import com.zcode.android.domain.model.PlanState
import com.zcode.android.domain.model.SessionProjectionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D2 (gaps-and-polish Phase 4C): three snapshot keys the phone dropped as "not
 * consumed" now decode (snapshot.ts, verbatim):
 *  - `goal` (goalStateSchema.nullable()) — the /goal plane WITH the verification
 *    timeline the desktop renders and the phone never showed;
 *  - `plan` (planStateSchema.nullable()) — the todo projection (TodoWrite restore);
 *  - turn KIND comes from the already-decoded `control.activeWorks[].kind`
 *    (activeWorkSummarySchema enum) — the label helper folds it.
 * Key-level whole replacement + malformed-key drop + count (the §9 policy).
 */
class GoalPlanDecodeTest {

    private val goalJson = """
      {"targetId":"g1","objective":"Ship the release","summaryTitle":"Ship it","timeUsedSeconds":120,
       "activeRunStartedAtMs":1760000000000,"status":"verifying","iteration":3,
       "verifications":[
         {"iteration":1,"outcome":"pass","at":1760000001000,"anchorRowId":12},
         {"iteration":2,"outcome":"notSatisfied","at":1760000002000,"anchorRowId":20,"reason":"2 criteria unmet","nextAction":"continue"},
         {"iteration":3,"outcome":"failed","at":1760000003000,"anchorRowId":30,"reason":"verifier crashed"}
       ],
       "iterations":[]}
    """.trimIndent()

    private val planJson = """
      {"items":[
         {"id":"t1","content":"Write the changelog","status":"completed"},
         {"id":"t2","content":"Bump the version","status":"inProgress"},
         {"id":"t3","content":"Tag the release","status":"pending"}
       ],"updatedAt":1760000040000}
    """.trimIndent()

    @Test
    fun `goal decodes with the verification timeline`() {
        val state = SessionProjectionState.fromSnapshot(Json.parseToJsonElement("""{"goal":$goalJson}""").jsonObject)
        val goal = state.goal!!
        assertEquals("Ship the release", goal.objective)
        assertEquals("verifying", goal.status)
        assertEquals(3, goal.iteration)
        assertEquals(listOf("pass", "notSatisfied", "failed"), goal.verifications.map { it.outcome })
        assertEquals("2 criteria unmet", goal.verifications[1].reason)
        assertEquals(12L, goal.verifications[0].anchorRowId)
    }

    @Test
    fun `goal null is a PRESENT key with no goal (the wire's explicit nullable)`() {
        val state = SessionProjectionState.fromSnapshot(Json.parseToJsonElement("""{"goal":null}""").jsonObject)
        assertEquals(true, state.hasDecodedStateKeys)
        assertNull(state.goal)
    }

    @Test
    fun `plan decodes with statuses raw`() {
        val state = SessionProjectionState.fromSnapshot(Json.parseToJsonElement("""{"plan":$planJson}""").jsonObject)
        val plan = state.plan!!
        assertEquals(listOf("completed", "inProgress", "pending"), plan.items.map { it.status })
        assertEquals("Bump the version", plan.items[1].content)
        assertEquals(1760000040000L, plan.updatedAtMs)
    }

    @Test
    fun `a patch merges goal and plan key-level`() {
        val base = SessionProjectionState.fromSnapshot(Json.parseToJsonElement("""{"goal":$goalJson}""").jsonObject)
        val next = base.mergedWith(Json.parseToJsonElement("""{"plan":$planJson}""").jsonObject)
        assertEquals("Ship the release", next.goal?.objective, "goal preserved")
        assertEquals(3, next.plan?.items?.size, "plan replaced in")
    }

    @Test
    fun `a malformed goal drops THAT key and counts`() {
        val before = SessionProjectionState.malformedKeyDrops
        val state = SessionProjectionState.fromSnapshot(Json.parseToJsonElement("""{"goal":{"status":"active","iteration":1,"verifications":[]}}""").jsonObject)
        assertNull(state.goal)
        assertTrue(SessionProjectionState.malformedKeyDrops > before, "counted, never a crash")
    }

    @Test
    fun `unknown goal status is retained raw (INV-8)`() {
        val state = SessionProjectionState.fromSnapshot(
            Json.parseToJsonElement("""{"goal":{"objective":"x","status":"suspended","iteration":1,"verifications":[]}}""").jsonObject,
        )
        assertEquals("suspended", state.goal?.status)
    }

    // ---------------------------------------------------------------- turn kind (from activeWorks)

    @Test
    fun `the turn-kind label folds the activeWorks kinds`() {
        assertEquals("generating", turnKindLabel("primaryTurn"))
        assertEquals("running subagents", turnKindLabel("foregroundSubagent"))
        assertEquals("compacting", turnKindLabel("compact"))
        assertEquals("verifying goal", turnKindLabel("goalVerifier"))
        assertEquals("continuing goal", turnKindLabel("goalContinuation"))
        assertEquals("steering", turnKindLabel("turnSteer"))
        assertEquals(null, turnKindLabel("something-new"))
        assertEquals(null, turnKindLabel(null))
    }

    private fun turnKindLabel(kind: String?): String? = GoalState.turnKindLabel(kind)
}
